package polaris.engine

import java.util.Base64

/**
 * Seals and opens secrets. On Android it is an AES-GCM key held by the Android Keystore, which
 * never leaves the secure hardware; in tests a fake. Either call may throw.
 */
interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * Small values written at once, saying whether they were kept (SharedPreferences' `commit` on
 * Android). Secrets need the answer: a key that wasn't saved mustn't be used.
 */
interface DurableStore {
    fun read(key: String): String?
    /** Null removes. False when it couldn't be written. */
    fun write(key: String, value: String?): Boolean
}

class InMemoryDurableStore : DurableStore {
    val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = synchronized(values) { values[key] }
    override fun write(key: String, value: String?): Boolean {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
        return true
    }
}

/**
 * Keys, the alerts server's token and the contact, as iOS keeps them in the Keychain: each value
 * sealed by [cipher] and only the sealed bytes stored. The rules:
 * - an empty value removes the entry, as iOS's `Keychain.set("")` does;
 * - a value that can't be sealed or written is not kept, and [write] says so; nothing is ever
 *   stored in the clear;
 * - a value that can't be opened reads as absent, so the build's value or none applies. It is
 *   left in place, as iOS leaves a Keychain item it can't read yet: the failure may pass (the
 *   Keystore busy or not yet unlocked), and if the key is truly gone (data restored onto another
 *   phone, the Keystore reset) the next value entered replaces it;
 * - nothing here logs, and errors never carry the value.
 */
class EncryptedSecretStore(private val cipher: SecretCipher, private val store: DurableStore) : SecretStore {
    override fun read(account: String): String? {
        val sealed = store.read(key(account)) ?: return null
        return try {
            cipher.open(Base64.getDecoder().decode(sealed)).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    override fun write(value: String, account: String): Boolean {
        if (value.isEmpty()) return store.write(key(account), null)
        val sealed = try {
            Base64.getEncoder().encodeToString(cipher.seal(value.encodeToByteArray()))
        } catch (e: Exception) {
            return false
        }
        return store.write(key(account), sealed)
    }

    private fun key(account: String) = "secret.$account"
}
