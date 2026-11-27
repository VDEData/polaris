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
        kotlinx.coroutines.runBlocking { LineNotices.receive(applicationContext, data) }
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
