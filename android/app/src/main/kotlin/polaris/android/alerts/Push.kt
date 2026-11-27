package polaris.android.alerts

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import polaris.android.BuildConfig
import polaris.engine.PushAvailability
import polaris.engine.PushRegistrar
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The build's Firebase project, from app/google-services.json at build time; all empty without one. */
data class FirebaseSettings(val projectId: String, val appId: String, val apiKey: String, val senderId: String) {
    val isSet: Boolean get() = projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank() && senderId.isNotBlank()

    /** The key never reaches a log through `toString`. */
    override fun toString() = "FirebaseSettings(projectId=$projectId, set=$isSet)"

    companion object {
        val build get() = FirebaseSettings(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_SENDER_ID)
    }
}

/**
 * This phone's push address from Firebase Cloud Messaging: its Firebase installation ID, which
 * the alerts server sends to. Asked for only when the alert store asks (an alert saved, with a
 * server to tell): registering with FCM is the first thing sent to Google.
 */
class FirebasePush(private val context: Context, private val settings: FirebaseSettings = FirebaseSettings.build) : PushRegistrar {
    override val availability: PushAvailability
        get() = when {
            !settings.isSet || !setUp(context, settings) -> PushAvailability.notSetUp
            runCatching { GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) }.getOrNull() != ConnectionResult.SUCCESS -> PushAvailability.unavailable
            else -> PushAvailability.ready
        }

    override suspend fun register(): String {
        check(setUp(context, settings)) { "no Firebase project in this build" }
        FirebaseMessaging.getInstance().register().await()
        return FirebaseInstallations.getInstance().id.await() ?: error("no installation ID")
    }

    companion object {
        /**
         * Sets Firebase up from the build's project, once a process; false in a build without one,
         * or if Firebase won't take it (a mistyped file). Nothing is sent by this.
         */
        fun setUp(context: Context, settings: FirebaseSettings = FirebaseSettings.build): Boolean {
            if (!settings.isSet) return false
            val app = context.applicationContext
            return runCatching {
                synchronized(this) {
                    if (FirebaseApp.getApps(app).isEmpty()) {
                        FirebaseApp.initializeApp(
                            app,
                            FirebaseOptions.Builder()
                                .setProjectId(settings.projectId)
                                .setApplicationId(settings.appId)
                                .setApiKey(settings.apiKey)
                                .setGcmSenderId(settings.senderId)
                                .build(),
                        )
                    }
                }
                true
            }.getOrElse {
                // Its kind only: the message could quote the file's values.
                Log.w("Polaris", "Firebase couldn't be set up: ${it.javaClass.simpleName}")
                false
            }
        }
    }
}

/** A Play services task, awaited (the app has no other use for a coroutines adapter library). */
private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
