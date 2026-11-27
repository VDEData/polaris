package polaris.android.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import polaris.android.BuildConfig
import polaris.android.storage.Storage
import polaris.engine.AppIdentity
import polaris.engine.AppSettings
import polaris.engine.GoogleUsage
import polaris.engine.HTTP
import polaris.engine.HttpTransport
import polaris.engine.NetworkStatusCache
import polaris.engine.OkHttpTransport
import polaris.engine.PlaceSearch
import polaris.engine.PlaceStore
import polaris.engine.SearchModel
import polaris.engine.Snapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the screens use, built once per process from real storage. Plain objects, so the
 * screens' view models take them as constructor arguments and are tested on the JVM with fakes.
 */
class AppGraph(
    val settings: AppSettings,
    val usage: GoogleUsage,
    val places: PlaceStore,
    val transport: HttpTransport,
    val archive: SearchArchive,
    val locator: Locator,
    val identity: AppIdentity = AppIdentity(),
    val version: String = "0.1.0",
    /** TfL's status, kept 3 minutes for the whole app. */
    val networkCache: NetworkStatusCache = NetworkStatusCache.shared,
    /** First-run setup is to be shown: decided once, when the graph was built at launch ([polaris.engine.FirstRun]). */
    firstRun: Boolean = false,
    /** Work that must finish even if the screen that started it closes (a key typed just before back). */
    val background: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
    /** The time, for the departures store's minute and floor (a test's own clock). */
    val clock: () -> java.time.Instant = java.time.Instant::now,
    /** Where the live trip is kept for a relaunch (a file in the app; memory in tests). */
    val tripStore: polaris.engine.TripStore = polaris.engine.TripStore.Memory(),
    /** Fixes while a trip is live. */
    val tracker: polaris.android.trip.LocationTracking = polaris.android.trip.NoTracking,
    /** Whether the phone has a way to the internet (no re-plan is sent without one). */
    val online: () -> Boolean = { true },
    /** A trip found at launch still worth resuming ([polaris.engine.TripResume]). */
    pendingTrip: polaris.engine.SavedTrip? = null,
    /** A re-plan's search in place of the real one (a debug check's canned answer). */
    private val replanSearch: (suspend (polaris.engine.JourneyQuery, polaris.engine.Replan.Watch) -> polaris.engine.ReplanAnswer)? = null,
    /** The app, for the trip's notification and its service (none in JVM tests: the trip keeps to itself there). */
    private val appContext: Context? = null,
    /** Where the trip runs: the main thread in the app (it is confined to one thread). */
    tripScope: () -> kotlinx.coroutines.CoroutineScope = { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate) },
    /** Where this install's secret for each alerts server is sealed (the store the settings use). */
    private val secrets: polaris.engine.SecretStore = polaris.engine.InMemorySecretStore(),
    /** The saved alerts and what the server last heard (the app's preferences; memory in tests). */
    private val values: polaris.engine.KeyValueStore = polaris.engine.InMemoryKeyValueStore(),
    /** This phone's push address, for line alerts (Firebase in a build given a project; none otherwise). */
    val push: polaris.engine.PushRegistrar = polaris.engine.PushRegistrar.None,
    /** Where the alerts' requests run (a test's own dispatcher). */
    private val alertsIo: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val setupPending = java.util.concurrent.atomic.AtomicBoolean(firstRun)

    /** True once, for the first screen after a launch that decided to show setup. */
    fun takeSetup(): Boolean = setupPending.getAndSet(false)

    /** One HTTP for place search: Polaris's User-Agent with the contact as it is at each request. */
    val http: HTTP by lazy { HTTP(transport, version) { settings.contact } }

    fun searchModel(): SearchModel = SearchModel.create(settings, usage, version, identity, transport, networkCache)

    fun placeSearch(scope: kotlinx.coroutines.CoroutineScope): PlaceSearch = PlaceSearch(http, scope)

    /** Departure boards and what's leaving nearby, each kept a minute, for the whole app. */
    val departures: polaris.engine.DepartureStore by lazy { polaris.engine.DepartureStore(polaris.engine.DepartureProvider(http), clock) }

    /** The live trip, one for the whole app (Go). */
    val trips: polaris.android.trip.TripSession by lazy {
        polaris.android.trip.TripSession(
            scope = tripScope(), departures = departures, tracker = tracker, store = tripStore, clock = clock,
            search = replanSearch ?: polaris.engine.ReplanSearch.live(settings, usage, http, identity, networkCache),
            isOnline = online, pending = pendingTrip,
            alerts = notifier ?: polaris.engine.TripAlertScheduling.None,
            presenter = notifier ?: polaris.engine.TripPresenter.None,
            cutoffAlarm = { at -> appContext?.let { polaris.android.trip.TripEndAlarm.set(it, at, clock) } },
            // The Go page closes with the trip, so a short message says why.
            endedByItself = { arrived, destination ->
                appContext?.let { context ->
                    val words = if (arrived) context.getString(polaris.android.R.string.go_arrived, destination) else context.getString(polaris.android.R.string.go_ended_itself, destination)
                    android.widget.Toast.makeText(context, words, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    /** Go's ongoing notification, trip alerts and foreground service ([polaris.android.trip.TripNotifier]). */
    val notifier: polaris.android.trip.TripNotifier? by lazy {
        appContext?.let { polaris.android.trip.TripNotifier(it, { trips.trip.value }, clock) }
    }

    /** Alerts for your lines, one store for the whole app, on the main thread. */
    val alerts: polaris.engine.AlertStore by lazy {
        polaris.engine.AlertStore(
            settings, secrets, values, transport, { http.userAgent }, push,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate), clock = clock, io = alertsIo,
        )
    }

    /** TfL's lines and their stations, for choosing what to be alerted about (kept for the session). */
    val lines: polaris.engine.TfLLines by lazy { polaris.engine.TfLLines(http) }

    /** A line and its stations picked for the alert being edited, handed from the picker to its editor. */
    val segmentPicks = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, polaris.engine.RouteAlert.Segment>>(extraBufferCapacity = 4)

    /** The appearance chosen in Settings, which the activity follows at once. */
    val appearance: kotlinx.coroutines.flow.MutableStateFlow<polaris.engine.Appearance> by lazy { kotlinx.coroutines.flow.MutableStateFlow(settings.appearance) }

    companion object {
        @Volatile private var loaded: AppGraph? = null

        /** The graph if this process has built it already. */
        fun cached(): AppGraph? = loaded

        /** Reads storage off the main thread the first time; later calls return the same graph. */
        /** [secrets] replaces the Keystore store: debug checks only (`Variant.secrets`), null in release. */
        /** [clock] and [replanSearch] replace the real ones: debug checks only, null in release. */
        suspend fun load(
            context: Context,
            transport: HttpTransport? = null,
            secrets: polaris.engine.SecretStore? = null,
            clock: (() -> java.time.Instant)? = null,
            replanSearch: (suspend (polaris.engine.JourneyQuery, polaris.engine.Replan.Watch) -> polaris.engine.ReplanAnswer)? = null,
            /** [push] and [values] replace Firebase and the saved alerts: debug checks only, null in release. */
            push: polaris.engine.PushRegistrar? = null,
            values: polaris.engine.KeyValueStore? = null,
        ): AppGraph {
            // A debug run on recorded answers, or on the simulated store, never decides first-run setup (nor shows it).
            val decidesSetup = transport == null && secrets == null
            loaded?.let { return it }
            val app = context.applicationContext
            val (stored, usage) = Storage.settings(app)
            // The simulated store keeps its choices in memory too, so a check never writes the real preferences.
            val settings = secrets?.let { polaris.engine.AppSettings(it, Storage.buildDefaults, polaris.engine.InMemoryKeyValueStore()) } ?: stored
            val preferences = Storage.preferences(app)
            return withContext(Dispatchers.IO) {
                val trips = polaris.engine.FileTripStore(File(app.noBackupFilesDir, "trip.json"))
                // A trip the app was stopped during: picked up again, or cleared away, before any screen is up.
                val resumable = polaris.engine.TripResume.decide(trips, (clock ?: java.time.Instant::now)()) {
                    polaris.android.trip.TripNotifier.removeLeftovers(app)
                }
                AppGraph(
                    settings = settings,
                    usage = usage,
                    places = PlaceStore(preferences),
                    transport = transport ?: OkHttpTransport(),
                    archive = FileSearchArchive(File(app.noBackupFilesDir, "searches")),
                    locator = AndroidLocator(app),
                    identity = appIdentity(app),
                    version = BuildConfig.VERSION_NAME,
                    firstRun = decidesSetup && settings.takeFirstRunTurn(),
                    tripStore = trips,
                    tracker = polaris.android.trip.AndroidLocationTracker(app, clock ?: java.time.Instant::now) { cached()?.trips?.onFix() },
                    online = { isOnline(app) },
                    pendingTrip = resumable,
                    clock = clock ?: java.time.Instant::now,
                    replanSearch = replanSearch,
                    appContext = app,
                    secrets = secrets ?: Storage.secrets(app),
                    values = values ?: preferences,
                    push = push ?: polaris.android.alerts.FirebasePush(app),
                )
            }.also { loaded = it }
        }

        /** A network with the internet on it (underground there is none: Go sends no re-plan then). */
        private fun isOnline(context: Context): Boolean {
            val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
            val caps = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull() ?: return false
            return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        /**
         * This app's package name and signing certificate, which Google checks a key restricted to
         * an Android app against. Read from the running app, so it is right for whoever built it.
         */
        private fun appIdentity(context: Context): AppIdentity {
            val signers = runCatching {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            }.getOrNull()
            val certificate = signers?.firstOrNull()?.toByteArray()
            return AppIdentity(context.packageName, certificate?.let(AppIdentity::fingerprint)).also {
                Log.i("Polaris", "Google key restriction: package ${it.packageName}, certificate SHA-1 ${it.certificateSha1}")
            }
        }
    }
}

/**
 * The results pages' last searches, so a page Android recreated after stopping the app shows the
 * same routes without asking anyone again. Kept in memory for the journey page next to it, and in
 * a file per search for after the process has gone.
 */
interface SearchArchive {
    /** Keeps [search] in memory at once, for the page beside it; [save] writes it down. */
    fun hold(id: String, search: Snapshot.Search) {}
    fun save(id: String, search: Snapshot.Search)
    fun load(id: String): Snapshot.Search?
    /** Keeps only these searches' files (the pages still on the stack). */
    fun keepOnly(ids: Set<String>) {}
}

class MemorySearchArchive : SearchArchive {
    val saved = ConcurrentHashMap<String, Snapshot.Search>()
    override fun hold(id: String, search: Snapshot.Search) = save(id, search)
    override fun save(id: String, search: Snapshot.Search) {
        saved[id] = search
    }
    override fun load(id: String) = saved[id]
    override fun keepOnly(ids: Set<String>) {
        saved.keys.retainAll(ids)
    }
}

class FileSearchArchive(private val dir: File) : SearchArchive {
    private val memory = MemorySearchArchive()
    /** Pages closed by [keepOnly]: a save still queued for one of them is dropped. */
    private val closed = mutableSetOf<String>()

    override fun hold(id: String, search: Snapshot.Search) {
        if (safe(id)) memory.save(id, search)
    }

    @Synchronized override fun save(id: String, search: Snapshot.Search) {
        if (!safe(id) || id in closed) return
        memory.save(id, search)
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "$id.tmp")
            temp.writeText(Snapshot.encode(search))
            temp.renameTo(File(dir, "$id.json"))
        }
    }

    @Synchronized override fun load(id: String): Snapshot.Search? = if (!safe(id)) null else
        memory.load(id) ?: runCatching { File(dir, "$id.json").readText() }.getOrNull()?.let(Snapshot::decodeSearch)?.also { memory.save(id, it) }

    // One lock for writing, reading and pruning, so a prune never deletes a file being written.
    @Synchronized override fun keepOnly(ids: Set<String>) {
        closed += memory.saved.keys - ids
        dir.listFiles()?.forEach { if (it.nameWithoutExtension !in ids) closed += it.nameWithoutExtension }
        memory.keepOnly(ids)
        dir.listFiles()?.forEach { if (it.nameWithoutExtension !in ids) it.delete() }
    }

    companion object {
        /** Names are ours (random UUIDs); anything else in a key is refused. */
        fun safe(id: String) = id.all { it.isLetterOrDigit() || it == '-' }
    }
}
