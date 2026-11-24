package polaris.android.storage

import polaris.engine.SecretCipher
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real cipher's decisions, against a fake Keystore holding real AES keys: only proof of loss
 * throws KeyLost (which deletes a value); every unclear answer keeps it. What only a device shows
 * (the Keystore's own answers) is in StorageDeviceTest.
 */
class KeystoreCipherTest {
    private class FakeKeystore : KeyAccess {
        var current: SecretKey? = null
        var created = 1_000L
        var generated = 0
        var deleted = 0
        // Failure switches.
        var keyThrows = false
        var keyNullButAliasExists = false
        var creationUnknown = false
        var invalidOnInit = false

        override fun key(): SecretKey? {
            if (keyThrows) throw UnrecoverableKeyException("Keystore busy")
            if (keyNullButAliasExists) return null
            return current
        }
        override fun containsAlias(): Boolean = keyNullButAliasExists || current != null
        override fun creationMillis(): Long? = if (creationUnknown || current == null) null else created
        override fun generate(): SecretKey {
            generated++
            created++
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { current = it }
        }
        override fun delete() { deleted++; current = null }
        override fun init(cipher: Cipher, mode: Int, key: SecretKey, spec: GCMParameterSpec?) {
            if (invalidOnInit) throw KeyAccess.PermanentlyInvalid()
            if (spec == null) cipher.init(mode, key) else cipher.init(mode, key, spec)
        }
    }

    private fun rig(): Pair<FakeKeystore, KeystoreCipher> = FakeKeystore().let { it to KeystoreCipher(it) }

    /** Opening fails, but not as lost: the value would be kept. */
    private fun keeps(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error != null, "expected a failure")
        assertFalse(error is SecretCipher.KeyLost, "a value would have been deleted: $error")
    }

    @Test fun sealsAndOpens() {
        val (keystore, cipher) = rig()
        val sealed = cipher.seal("a key".encodeToByteArray())
        assertEquals(1, keystore.generated)
        assertContentEquals("a key".encodeToByteArray(), cipher.open(sealed))
    }

    @Test fun aBusyKeystoreIsNotLoss() {
        val (keystore, cipher) = rig()
        val sealed = cipher.seal("a key".encodeToByteArray())
        keystore.keyThrows = true
        keeps { cipher.open(sealed) }
    }

    @Test fun noKeyIsLossOnlyWhenTheAliasIsAlsoAbsent() {
        val (keystore, cipher) = rig()
        val sealed = cipher.seal("a key".encodeToByteArray())
        keystore.keyNullButAliasExists = true
        keeps { cipher.open(sealed) }
        keystore.keyNullButAliasExists = false
        keystore.current = null
        assertFailsWith<SecretCipher.KeyLost> { cipher.open(sealed) }
    }

    @Test fun sealingNeverMakesAKeyOnAnUnclearAnswer() {
        val (keystore, cipher) = rig()
        cipher.seal("first".encodeToByteArray())
        keystore.keyThrows = true
        assertFailsWith<UnrecoverableKeyException> { cipher.seal("second".encodeToByteArray()) }
        keystore.keyThrows = false
        keystore.keyNullButAliasExists = true
        assertFailsWith<IllegalStateException> { cipher.seal("second".encodeToByteArray()) }
        assertEquals(1, keystore.generated, "no second key")
    }

    @Test fun aPermanentlyInvalidKeyIsLossAndIsReplacedOnTheNextSeal() {
        val (keystore, cipher) = rig()
        val old = cipher.seal("old".encodeToByteArray())
        keystore.invalidOnInit = true
        assertFailsWith<SecretCipher.KeyLost> { cipher.open(old) }
        // Sealing replaces it: the first init fails as invalid, the new key's works.
        var calls = 0
        val replacing = object : KeyAccess by keystore {
            override fun init(cipher: Cipher, mode: Int, key: SecretKey, spec: GCMParameterSpec?) {
                if (calls++ == 0) throw KeyAccess.PermanentlyInvalid()
                keystore.invalidOnInit = false
                keystore.init(cipher, mode, key, spec)
            }
        }
        val fresh = KeystoreCipher(replacing).seal("new".encodeToByteArray())
        assertEquals(1, keystore.deleted)
        assertEquals(2, keystore.generated)
        assertContentEquals("new".encodeToByteArray(), cipher.open(fresh))
        // The old value was sealed by the replaced key: provably lost.
        assertIs<SecretCipher.KeyLost>(runCatching { cipher.open(old) }.exceptionOrNull())
    }

    @Test fun aTagFailureIsLossOnlyWhenTheKeyIsProvenDifferent() {
        val (keystore, cipher) = rig()
        val sealed = cipher.seal("a key".encodeToByteArray())
        // Same key, damaged bytes: kept.
        val damaged = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        keeps { cipher.open(damaged) }
        // Different key, but the Keystore can't give its creation time: kept.
        keystore.delete()
        keystore.generate()
        keystore.creationUnknown = true
        keeps { cipher.open(sealed) }
        // Different key, creation times differ: lost.
        keystore.creationUnknown = false
        assertFailsWith<SecretCipher.KeyLost> { cipher.open(sealed) }
    }

    @Test fun bytesNotInThisFormatAreKept() {
        val (_, cipher) = rig()
        cipher.seal("make a key".encodeToByteArray())
        keeps { cipher.open(ByteArray(40) { 7 }) }
        keeps { cipher.open(byteArrayOf(1, 2, 3)) }
    }
}
