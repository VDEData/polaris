package polaris.engine

import java.util.Base64

/**
 * Seals and opens secrets. On Android it is an AES-GCM key held by the Android Keystore, which
 * never leaves the secure hardware; in tests a fake. Either call may throw: [KeyLost] when what
 * was sealed can never be opened again, anything else when it might work later.
 */
interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray

    /** The key that sealed this is gone or permanently unusable, or the bytes were sealed by another key. */
    class KeyLost(reason: String) : Exception(reason)
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
 * - a value that can't be opened now reads as [SecretRead.Unreadable] and is left in place, as
 *   iOS leaves a Keychain item it can't read yet: the failure may pass, and nothing else is used
 *   meanwhile;
 * - a value whose key is gone for good ([SecretCipher.KeyLost]: data restored onto another phone,
 *   the Keystore reset, a key invalidated) can never be opened: it is removed and read once as
 *   [SecretRead.Lost], so the settings screen can ask for it again; after that it is absent;
 * - nothing here logs, and errors never carry the value.
 */
class EncryptedSecretStore(private val cipher: SecretCipher, private val store: DurableStore) : SecretStore {
    override fun read(account: String): SecretRead {
        val sealed = store.read(key(account)) ?: return SecretRead.Absent
        val bytes = try {
            Base64.getDecoder().decode(sealed)
        } catch (e: IllegalArgumentException) {
            // Not something this store wrote: it can never be opened.
            return lose(account)
        }
        return try {
            SecretRead.Value(cipher.open(bytes).decodeToString())
        } catch (e: SecretCipher.KeyLost) {
            lose(account)
        } catch (e: Exception) {
            SecretRead.Unreadable
        }
    }

    private fun lose(account: String): SecretRead {
        store.write(key(account), null)
        return SecretRead.Lost
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
