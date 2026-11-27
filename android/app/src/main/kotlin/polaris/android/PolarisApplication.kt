package polaris.android

import android.app.Application
import polaris.android.alerts.FirebasePush

/**
 * The process's start. Firebase is set up here only on a phone that has already registered for
 * pushes (a build given a Firebase project, and an alert saved), so a notice arriving with the
 * app closed finds it ready. Before the first alert is saved no Firebase code runs.
 */
class PolarisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebasePush.setUpIfInUse(this)
    }
}
