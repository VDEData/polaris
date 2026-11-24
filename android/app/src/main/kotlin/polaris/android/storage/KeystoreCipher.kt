package polaris.android.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import polaris.engine.SecretCipher
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What [KeystoreCipher] needs from the Android Keystore, so its decisions can be tested on the
 * JVM with a fake. The Android behaviour each call relies on, from AOSP's
 * `keystore2/AndroidKeyStoreSpi.java`:
 * - `getKey` returns null only when Keystore answers KEY_NOT_FOUND; every other failure (busy,
 *   locked, a system error, and even a permanently invalidated key) comes out as
 *   `UnrecoverableKeyException`;
 * - `containsAlias` and `getCreationDate` go through `getKeyMetadata`, which returns null on any
 *   Keystore error, so `false` and `null` there are not proof of anything on their own.
 */
interface KeyAccess {
    /** The key; null only when the Keystore says it doesn't exist. Throws when it can't tell. */
    fun key(): SecretKey?

    /** Whether the alias exists. False also when the Keystore can't answer: never proof alone. */
    fun containsAlias(): Boolean

    /** When the key was created (its identity here), or null when the Keystore can't say. */
    fun creationMillis(): Long?

    fun generate(): SecretKey

    fun delete()

    /** `Cipher.init`, with `KeyPermanentlyInvalidatedException` turned into [PermanentlyInvalid]. */
    fun init(cipher: Cipher, mode: Int, key: SecretKey, spec: GCMParameterSpec?)

    class PermanentlyInvalid : Exception("key permanently invalid")
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and can't be read out of it. Sealed
 * form: "P2", the sealing key's creation time (8 bytes; also authenticated), the 12-byte IV, then
 * the ciphertext and tag.
 *
 * A value is deleted on [SecretCipher.KeyLost], so that is thrown only on proof:
 * - the Keystore says the key doesn't exist (`getKey` is null, which means KEY_NOT_FOUND, and
 *   `containsAlias` agrees);
 * - `Cipher.init` throws `KeyPermanentlyInvalidatedException`;
 * - the tag fails and the Keystore gives a creation time for the current key that differs from
 *   the one sealed with the value (it was sealed by an earlier key).
 *
 * Everything else, including an `UnrecoverableKeyException`, a tag failure under the same key or
 * with no creation time to compare, and bytes not in this format, is "can't tell": the value is
 * kept and read again later.
 *
 * Sealing never makes a new key on an unclear answer: only when the Keystore says there is none,
 * or when the existing one is proven permanently invalid. Values sealed by the old key then read
 * as lost (creation times differ) and the person is told.
 *
 * This is the platform's own API, used directly: Jetpack's security-crypto, which wrapped it, is
 * deprecated.
 */
class KeystoreCipher(private val access: KeyAccess) : SecretCipher {
    constructor(alias: String = ALIAS) : this(AndroidKeyAccess(alias))

    @Synchronized override fun seal(plain: ByteArray): ByteArray {
        var key = keyForSealing()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            access.init(cipher, Cipher.ENCRYPT_MODE, key, null)
        } catch (e: KeyAccess.PermanentlyInvalid) {
            // Proven unusable for good: replace it. What it sealed will read as lost.
            access.delete()
            key = access.generate()
            access.init(cipher, Cipher.ENCRYPT_MODE, key, null)
        }
        val created = access.creationMillis() ?: throw IllegalStateException("the key's creation time is unknown")
        val header = header(created)
        cipher.updateAAD(header)
        return header + cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < HEADER + IV_BYTES + TAG_BITS / 8 || sealed[0] != MAGIC[0] || sealed[1] != MAGIC[1]) {
            throw IllegalStateException("not in this format")
        }
        val sealedBy = ByteBuffer.wrap(sealed, 2, 8).long
        val key = access.key() ?: if (!access.containsAlias()) throw SecretCipher.KeyLost("no key") else throw IllegalStateException("Keystore unsure")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            access.init(cipher, Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, HEADER, IV_BYTES))
        } catch (e: KeyAccess.PermanentlyInvalid) {
            throw SecretCipher.KeyLost("key permanently invalid")
        }
        cipher.updateAAD(sealed, 0, HEADER)
        return try {
            cipher.doFinal(sealed, HEADER + IV_BYTES, sealed.size - HEADER - IV_BYTES)
        } catch (e: AEADBadTagException) {
            val current = access.creationMillis()
            if (current != null && current != sealedBy) throw SecretCipher.KeyLost("sealed by an earlier key")
            throw IllegalStateException("tag mismatch, key not proven different")
        }
    }

    /** The key to seal with; a new one only when the Keystore says there is none. */
    private fun keyForSealing(): SecretKey {
        access.key()?.let { return it }
        if (access.containsAlias()) throw IllegalStateException("Keystore unsure whether the key exists")
        return access.generate()
    }

    private fun header(created: Long): ByteArray = MAGIC + ByteBuffer.allocate(8).putLong(created).array()

    /** Forgets the key, as a reset or a restore onto another phone would. For tests. */
    fun deleteKey() = access.delete()

    companion object {
        const val ALIAS = "polaris.secrets"
        private val MAGIC = byteArrayOf('P'.code.toByte(), '2'.code.toByte())
        private const val HEADER = 10
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/** The real Keystore. */
class AndroidKeyAccess(private val alias: String) : KeyAccess {
    private fun store() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun key(): SecretKey? = store().getKey(alias, null) as SecretKey?

    override fun containsAlias(): Boolean = store().containsAlias(alias)

    override fun creationMillis(): Long? = store().getCreationDate(alias)?.time

    override fun generate(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    override fun delete() = store().deleteEntry(alias)

    override fun init(cipher: Cipher, mode: Int, key: SecretKey, spec: GCMParameterSpec?) {
        try {
            if (spec == null) cipher.init(mode, key) else cipher.init(mode, key, spec)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyAccess.PermanentlyInvalid()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}
