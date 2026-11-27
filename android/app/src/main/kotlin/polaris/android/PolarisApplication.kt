package polaris.android

import android.app.Application
import polaris.android.alerts.FirebasePush

/**
 * The process's start. Firebase is set up here, and only in a build that was given a Firebase
 * project, so a notice arriving with the app closed finds it ready; setting it up sends nothing.
 */
class PolarisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebasePush.setUp(this)
    }
}
