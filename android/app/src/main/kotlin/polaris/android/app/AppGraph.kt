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
) {
    /** One HTTP for place search: Polaris's User-Agent with the contact as it is at each request. */
    val http: HTTP by lazy { HTTP(transport, version) { settings.contact } }

    fun searchModel(): SearchModel = SearchModel.create(settings, usage, version, identity, transport, networkCache)

    fun placeSearch(scope: kotlinx.coroutines.CoroutineScope): PlaceSearch = PlaceSearch(http, scope)

    companion object {
        @Volatile private var loaded: AppGraph? = null

        /** The graph if this process has built it already. */
        fun cached(): AppGraph? = loaded

        /** Reads storage off the main thread the first time; later calls return the same graph. */
        suspend fun load(context: Context, transport: HttpTransport? = null): AppGraph {
            loaded?.let { return it }
            val app = context.applicationContext
            val (settings, usage) = Storage.settings(app)
            val preferences = Storage.preferences(app)
            return withContext(Dispatchers.IO) {
                AppGraph(
                    settings = settings,
                    usage = usage,
                    places = PlaceStore(preferences),
                    transport = transport ?: OkHttpTransport(),
                    archive = FileSearchArchive(File(app.noBackupFilesDir, "searches")),
                    locator = AndroidLocator(app),
                    identity = appIdentity(app),
                    version = BuildConfig.VERSION_NAME,
                )
            }.also { loaded = it }
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

    override fun hold(id: String, search: Snapshot.Search) {
        if (safe(id)) memory.save(id, search)
    }

    @Synchronized override fun save(id: String, search: Snapshot.Search) {
        if (!safe(id)) return
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
        memory.keepOnly(ids)
        dir.listFiles()?.forEach { if (it.nameWithoutExtension !in ids) it.delete() }
    }

    companion object {
        /** Names are ours (random UUIDs); anything else in a key is refused. */
        fun safe(id: String) = id.all { it.isLetterOrDigit() || it == '-' }
    }
}
