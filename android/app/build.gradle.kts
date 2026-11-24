import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Per-builder values, as Config/Secrets.xcconfig is on iOS: a Gradle property (-Ppolaris.googleKey=…)
// wins, then the git-ignored local.properties, then the neutral default. A stranger's build sets
// none of them and works: TfL and Transitous need no key, and Google is shown as needing one.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun setting(name: String, default: String = ""): String =
    (providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: default).trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "polaris.android"
    compileSdk = 36

    defaultConfig {
        applicationId = setting("polaris.applicationId", "com.example.polaris")
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GOOGLE_ROUTES_API_KEY", quoted(setting("polaris.googleKey")))
        buildConfigField("String", "TFL_APP_KEY", quoted(setting("polaris.tflKey")))
        // Who TfL and Transitous can reach about this build's requests: it goes in the User-Agent.
        buildConfigField("String", "POLARIS_CONTACT", quoted(setting("polaris.contact")))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
