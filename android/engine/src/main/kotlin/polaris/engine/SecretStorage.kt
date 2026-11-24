package polaris.engine

import java.util.Base64

/**
 * Seals and opens secrets. On Android it is an AES-GCM key held by the Android Keystore, which
 * never leaves the secure hardware; in tests a fake. Either call may throw: [KeyLost] only when
 * it is certain that what was sealed can never be opened again; anything else when it can't tell,
 * which keeps the value and waits.
 */
interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray

    /** Proven gone for good: no false alarms, because a value is deleted on this. */
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
 * Keys, the alerts server and the contact, as iOS keeps them in the Keychain: each value sealed
 * by [cipher] and only the sealed bytes stored. Deleting a good value on a false alarm is the
 * worst outcome, so:
 * - an empty value removes the entry, as iOS's `Keychain.set("")` does;
 * - a value that can't be sealed or written is not kept, and [write] says so; nothing is ever
 *   stored in the clear;
 * - a value that can't be opened reads as [SecretRead.Unreadable] and is kept: anything short of
 *   proof counts as this, and every later read tries again;
 * - only [SecretCipher.KeyLost] removes a value, and only if the stored bytes are still the ones
 *   that failed (a value saved meanwhile is never touched). Before removing, a notice naming the
 *   account (never the value) is written durably, so the person is told even if the app stops
 *   before a screen shows it; it stays until [acknowledgeLost];
 * - reads and writes of one account are serialised;
 * - nothing here logs, and errors never carry the value.
 */
class EncryptedSecretStore(private val cipher: SecretCipher, private val store: DurableStore) : SecretStore {
    private val locks = mutableMapOf<String, Any>()
    private fun lock(account: String) = synchronized(locks) { locks.getOrPut(account) { Any() } }

    override fun read(account: String): SecretRead = synchronized(lock(account)) {
        val sealed = store.read(key(account)) ?: return SecretRead.Absent
        val bytes = try {
            Base64.getDecoder().decode(sealed)
        } catch (e: IllegalArgumentException) {
            // Not something this store wrote; unknown, so kept.
            return SecretRead.Unreadable
        }
        try {
            SecretRead.Value(cipher.open(bytes).decodeToString())
        } catch (e: SecretCipher.KeyLost) {
            lose(account, sealed)
        } catch (e: Exception) {
            SecretRead.Unreadable
        }
    }

    /** Removes [sealed] only if it is still what is stored, after recording the notice. */
    private fun lose(account: String, sealed: String): SecretRead {
        if (store.read(key(account)) != sealed) return SecretRead.Unreadable
        if (!store.write(notice(account), "1")) return SecretRead.Unreadable
        store.write(key(account), null)
        return SecretRead.Lost
    }

    override fun write(value: String, account: String): Boolean = synchronized(lock(account)) {
        if (value.isEmpty()) return store.write(key(account), null)
        val sealed = try {
            Base64.getEncoder().encodeToString(cipher.seal(value.encodeToByteArray()))
        } catch (e: Exception) {
            return false
        }
        store.write(key(account), sealed)
    }

    override fun lostAccounts(): Set<String> = KNOWN.filter { store.read(notice(it)) != null }.toSet()

    override fun acknowledgeLost() {
        KNOWN.forEach { store.write(notice(it), null) }
    }

    private fun key(account: String) = "secret.$account"
    private fun notice(account: String) = "lost.$account"

    override fun toString() = "EncryptedSecretStore(redacted)"

    companion object {
        /** Every account Polaris keeps, so notices can be listed without reading any value. */
        val KNOWN = listOf(AppSettings.GOOGLE, AppSettings.TFL, AppSettings.CONTACT, AlertServer.ACCOUNT)
    }
}
