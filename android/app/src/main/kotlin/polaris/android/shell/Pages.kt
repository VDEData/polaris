package polaris.android.shell

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import polaris.engine.JourneyQuery
import polaris.engine.Snapshot
import java.util.UUID

/**
 * The sheet's pages. Keys are small and serialisable, so Android can keep the stack across the
 * process being stopped: places and times, never journeys (those are kept by [polaris.android.app.SearchArchive]).
 */
@Serializable
data object HomePage : NavKey

/** The routes for one trip. [id] names this page's search, kept in the archive under it. */
@Serializable
data class ResultsPage(val query: String, val id: String = UUID.randomUUID().toString()) : NavKey {
    constructor(query: JourneyQuery) : this(Snapshot.encode(query))

    val journeyQuery: JourneyQuery? get() = Snapshot.decodeQuery(query)
}

/** One route from a results page. The journey page itself comes in the next update. */
@Serializable
data class JourneyPage(val searchId: String, val journeyId: String) : NavKey

/** What a place search is for, and so where its pick goes. Saved, so a search open during a rotation stays open. */
@Serializable
sealed interface SearchFor {
    val title: String
    val allowsCurrentLocation: Boolean get() = false

    @Serializable data object Destination : SearchFor { override val title get() = "Where to?" }
    /** The start for a trip whose start couldn't be found. */
    @Serializable data class Origin(val destination: String) : SearchFor { override val title get() = "Start from" }
    @Serializable data object Home : SearchFor { override val title get() = "Set Home" }
    @Serializable data object Work : SearchFor { override val title get() = "Set Work" }
    /** A results page's start or end. */
    /** [near] is the trip's other end as "lat,lon" when the search opened, to bias the answers. */
    @Serializable data class ResultsEnd(val page: String, val isOrigin: Boolean, val near: String? = null) : SearchFor {
        override val title get() = if (isOrigin) "From" else "To"
        override val allowsCurrentLocation get() = true
    }
}
