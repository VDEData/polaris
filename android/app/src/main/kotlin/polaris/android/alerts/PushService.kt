package polaris.android.alerts

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import polaris.android.app.AppGraph

/**
 * Firebase Cloud Messaging's way in: a notice from the alerts server is shown (app open or
 * not: it is a data message, drawn here either way), and a changed push address is sent on to
 * the server the alerts are registered with.
 */
class PushService : FirebaseMessagingService() {
    /** Called on a worker thread, which Firebase holds open until this returns: the saved alerts are read on it. */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        // Nothing thrown from here: an exception would take the process down with a notice half handled.
        runCatching { kotlinx.coroutines.runBlocking { LineNotices.receive(applicationContext, data) } }
            .onFailure { android.util.Log.w("Polaris", "a line notice couldn't be shown: ${it.javaClass.simpleName}") }
    }

    /**
     * Firebase is set up before its own service code runs, whatever the start-up marker says (a
     * phone registered before the marker existed; a message already on its way when the last alert
     * was deleted). For this run only: the marker is set by registering, never from here.
     */
    override fun onCreate() {
        FirebasePush.setUp(applicationContext)
        super.onCreate()
    }

    /** This install's Firebase installation ID, given or changed: the server must have the new one. */
    override fun onRegistered(installationId: String) {
        val app = applicationContext
        // The store keeps to the main thread; with the app closed it is built first (storage, off the main thread).
        scope.launch { AppGraph.load(app).alerts.didRegister(installationId) }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
