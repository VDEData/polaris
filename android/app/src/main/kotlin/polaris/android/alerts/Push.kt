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
            // Whether it could be used, not using it: Firebase itself is first touched in register().
            !settings.isSet -> PushAvailability.notSetUp
            runCatching { GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) }.getOrNull() != ConnectionResult.SUCCESS -> PushAvailability.unavailable
            else -> PushAvailability.ready
        }

    override suspend fun register(): String {
        check(setUp(context, settings)) { "no Firebase project in this build" }
        // From now on Firebase is set up as the process starts, so a notice finds it ready.
        markInUse(context)
        FirebaseMessaging.getInstance().register().await()
        return FirebaseInstallations.getInstance().id.await() ?: error("no installation ID")
    }

    /** The installation is deleted (FCM stops delivering to its ID) and a new one registered. */
    override suspend fun reset(): String {
        check(setUp(context, settings)) { "no Firebase project in this build" }
        FirebaseInstallations.getInstance().delete().await()
        return register()
    }

    /** No alerts left and the server told: Firebase is no longer set up as the app starts, until an alert is saved again. */
    override fun release() {
        context.applicationContext.getSharedPreferences(IN_USE_FILE, Context.MODE_PRIVATE).edit().remove("registered").apply()
    }

    companion object {
        private const val IN_USE_FILE = "polaris-push"

        /** Pushes are in use on this phone (it has a push address saved, or a message came): set up now, and at each start from now on. */
        fun use(context: Context) {
            if (setUp(context)) markInUse(context)
        }

        private fun markInUse(context: Context) =
            context.applicationContext.getSharedPreferences(IN_USE_FILE, Context.MODE_PRIVATE).edit().putBoolean("registered", true).apply()

        /**
         * At process start: Firebase is set up only on a phone that has registered for pushes
         * before (an alert was saved with a server set). Until then none of Firebase's code runs
         * at all, so nothing of it can reach Google from an install that has no alerts.
         */
        fun setUpIfInUse(context: Context) {
            val used = context.applicationContext.getSharedPreferences(IN_USE_FILE, Context.MODE_PRIVATE).getBoolean("registered", false)
            if (used) setUp(context)
        }

        /**
         * Sets Firebase up from the build's project, once a process; false in a build without one,
         * or if Firebase won't take it (a mistyped file).
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
