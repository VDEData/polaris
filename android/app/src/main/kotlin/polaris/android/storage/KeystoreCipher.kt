package polaris.android.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import polaris.engine.SecretCipher
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in the Android Keystore and can't be read out of it (in the
 * secure hardware where the phone has it). The sealed form is the 12-byte IV followed by the
 * ciphertext and its tag. The key isn't backed up or moved to another phone.
 *
 * Failures come in two kinds, which `EncryptedSecretStore` treats differently:
 * - [SecretCipher.KeyLost] when what was sealed can never be opened: the key is missing, the
 *   Keystore says it is permanently invalid or unrecoverable, or the tag doesn't match (sealed by
 *   a key that has since been replaced). The value is dropped and the person told.
 * - anything else (the Keystore busy, a provider error) may pass, so the value is kept and read
 *   as unreadable meanwhile.
 *
 * A permanently invalid key is replaced the next time something is sealed: the new value is
 * sealed with a new key, and values sealed with the old one then read as lost.
 *
 * This is the platform's own API, used directly: Jetpack's security-crypto library, which wrapped
 * it, is deprecated.
 */
class KeystoreCipher(private val alias: String = ALIAS) : SecretCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, usableKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            cipher.init(Cipher.ENCRYPT_MODE, usableKey())
        }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw SecretCipher.KeyLost("too short to be sealed")
        val key = existingKey() ?: throw SecretCipher.KeyLost("no key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SecretCipher.KeyLost("key permanently invalid")
        }
        return try {
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw SecretCipher.KeyLost("sealed with another key")
        }
    }

    /** The key, or null when there is none. An entry that exists but can't be recovered is lost. */
    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return try {
            (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: UnrecoverableKeyException) {
            throw SecretCipher.KeyLost("key unrecoverable")
        }
    }

    /** The key to seal with: the existing one, or a new one when there is none or it is lost. */
    @Synchronized private fun usableKey(): SecretKey {
        val existing = try {
            existingKey()
        } catch (e: SecretCipher.KeyLost) {
            deleteKey()
            null
        }
        return existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    /** Forgets the key, as a reset or a restore onto another phone would. */
    fun deleteKey() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    companion object {
        const val ALIAS = "polaris.secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
