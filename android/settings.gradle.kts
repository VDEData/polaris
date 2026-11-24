pluginManagement {
    // The Android Gradle plugin comes from Google's repository, Kotlin's plugins from Maven Central.
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Polaris"
include(":engine")

// The app needs the Android SDK; the engine needs only a JDK. Without an SDK the app is left
// out, so `./gradlew test` still builds and tests the engine on a plain JVM.
val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.trim().startsWith("sdk.dir=") }?.substringAfter("=")?.trim()
if (sdkDir != null && file(sdkDir).isDirectory) {
    include(":app")
} else {
    println("No Android SDK found (ANDROID_HOME or sdk.dir in local.properties): building the engine only.")
}
