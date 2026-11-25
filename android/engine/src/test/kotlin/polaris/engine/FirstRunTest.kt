package polaris.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The setup step: once, for new installs only, never for an install with data. Mirrors FirstRunTests.swift. */
class FirstRunTest {
    private val empty: (String) -> SecretRead = { SecretRead.Absent }

    @Test fun aNewInstallSeesItOnce() {
        val kv = InMemoryKeyValueStore()
        assertTrue(FirstRun.takeTurn(kv, empty))
        assertFalse(FirstRun.takeTurn(kv, empty), "never again, finished or skipped")
    }

    /** What each store writes when used counts as data: the real stores, not a copy of their keys. */
    @Test fun anInstallWithDataNeverSeesIt() {
        val uses: List<Pair<String, (KeyValueStore) -> Unit>> = listOf(
            "a saved place" to { kv -> PlaceStore(kv).home = Place(name = "King's Cross", coordinate = Coordinate(51.53, -0.12)) },
            "a recent place" to { kv -> PlaceStore(kv).remember(Place(name = "Bank", coordinate = Coordinate(51.51, -0.09))) },
            "a setting" to { kv -> AppSettings(preferences = kv).deepSearch = false },
            "a sort order" to { kv -> AppSettings(preferences = kv).sortOrder = SortOrder.fastest },
            "an appearance" to { kv -> AppSettings(preferences = kv).appearance = Appearance.dark },
            "Google's count" to { kv -> GoogleUsage(kv).spend(1) },
        )
        for ((what, use) in uses) {
            val kv = InMemoryKeyValueStore()
            use(kv)
            assertFalse(FirstRun.takeTurn(kv, empty), what)
            assertEquals("true", kv.getString(FirstRun.SEEN_KEY), "and it's decided for good: $what")
        }
    }

    @Test fun keysEnteredBeforeMeanNoSetup() {
        for (account in FirstRun.secretAccounts) {
            assertFalse(FirstRun.takeTurn(InMemoryKeyValueStore()) { if (it == account) SecretRead.Value("value") else SecretRead.Absent }, account)
        }
        assertTrue(FirstRun.takeTurn(InMemoryKeyValueStore()) { SecretRead.Value("") }, "empty values don't count")
    }

    @Test fun anUnreadableSecretDecidesNothing() {
        val kv = InMemoryKeyValueStore()
        assertFalse(FirstRun.takeTurn(kv) { SecretRead.Unreadable }, "never shown on a read that didn't happen")
        assertEquals(null, kv.getString(FirstRun.SEEN_KEY), "and nothing written: a later launch decides")
        assertTrue(FirstRun.takeTurn(kv, empty), "readable and new: shown")
    }

    @Test fun settingsTakeTheTurnFromTheirOwnStores() {
        val kv = InMemoryKeyValueStore()
        val secrets = InMemorySecretStore()
        assertTrue(AppSettings(secrets, preferences = kv).takeFirstRunTurn())
        assertFalse(AppSettings(secrets, preferences = kv).takeFirstRunTurn())
        val other = InMemorySecretStore().apply { write("a-key", AppSettings.TFL) }
        assertFalse(AppSettings(other, preferences = InMemoryKeyValueStore()).takeFirstRunTurn())
    }
}
