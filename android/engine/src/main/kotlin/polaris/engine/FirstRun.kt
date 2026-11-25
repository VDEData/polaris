package polaris.engine

/**
 * The setup step a new install sees once (ported from Polaris/Services/FirstRun.swift): what
 * works with nothing set, and the optional keys. Never for an install that has been used (keys,
 * saved places, settings or Google's count already stored), so an install updating from an
 * earlier build goes straight to the map.
 */
object FirstRun {
    const val SEEN_KEY = "setup.seen"

    /** What an install has stored already. */
    data class Facts(
        val seen: Boolean = false,
        /** A secret couldn't be read (the Keystore busy or locked): decide another launch. */
        val undecided: Boolean = false,
        /** Keys, an alerts server or a contact entered in Settings. */
        val hasEnteredValues: Boolean = false,
        /** Anything an earlier launch wrote: places, settings, Google's count. */
        val hasPriorData: Boolean = false,
    )

    fun shouldShow(facts: Facts): Boolean = !facts.seen && !facts.undecided && !facts.hasEnteredValues && !facts.hasPriorData

    /** Keys only an install that has been used has, under iOS's names. */
    val priorUseKeys = listOf(
        PlaceStore.HOME, PlaceStore.WORK, PlaceStore.RECENTS, "alerts", "alerts.registered", "alerts.token",
        GoogleUsage.KEY, AppSettings.DISABLED, AppSettings.DEEP, AppSettings.SORT, AppSettings.FILTER, AppSettings.APPEARANCE, "wholeMap",
    )

    val secretAccounts = listOf(AppSettings.GOOGLE, AppSettings.TFL, AppSettings.CONTACT, AlertServer.ACCOUNT)

    fun facts(preferences: KeyValueStore, secrets: (String) -> SecretRead): Facts {
        val reads = secretAccounts.map(secrets)
        return Facts(
            seen = preferences.getString(SEEN_KEY) == "true",
            undecided = reads.any { it == SecretRead.Unreadable },
            hasEnteredValues = reads.any { it is SecretRead.Value && it.text.isNotEmpty() },
            hasPriorData = priorUseKeys.any { preferences.getString(it) != null },
        )
    }

    /**
     * Decides at launch, once: shows it, or never will. Either way it isn't asked again, unless a
     * secret couldn't be read, when nothing is decided or written and a later launch asks.
     */
    fun takeTurn(preferences: KeyValueStore, secrets: (String) -> SecretRead): Boolean {
        val facts = facts(preferences, secrets)
        if (facts.undecided && !facts.seen && !facts.hasPriorData) return false
        preferences.putString(SEEN_KEY, "true")
        return shouldShow(facts)
    }
}
