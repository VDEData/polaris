import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Per-builder values, as Config/Secrets.xcconfig is on iOS: a Gradle property (-Ppolaris.googleKey=…)
// wins, then the git-ignored local.properties, then the default. A stranger's build sets none of
// them and works: TfL and Transitous need no key, Google is shown as needing one, and the
// User-Agent names the project's page.
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
        // The project's page unless the builder sets their own, as POLARIS_CONTACT on iOS.
        buildConfigField("String", "POLARIS_CONTACT", quoted(setting("polaris.contact", "https://github.com/VDEData/polaris")))
        // An alerts server built in, as POLARIS_ALERTS_URL and _TOKEN on iOS. Alerts come in a later phase.
        buildConfigField("String", "POLARIS_ALERTS_URL", quoted(setting("polaris.alertsUrl")))
        buildConfigField("String", "POLARIS_ALERTS_TOKEN", quoted(setting("polaris.alertsToken")))
        // The map's vector tiles (a TileJSON address) and fonts: OpenFreeMap unless a builder sets their own.
        buildConfigField("String", "MAP_TILES", quoted(setting("polaris.mapTiles", "https://tiles.openfreemap.org/planet")))
        buildConfigField("String", "MAP_GLYPHS", quoted(setting("polaris.mapGlyphs", "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf")))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// The debug-only map check draws one recorded journey: copy just that recording into debug assets.
abstract class MapCheckAssets : DefaultTask() {
    @get:InputFile abstract val recording: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun copy() {
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        recording.get().asFile.copyTo(dir.resolve("transitous-plan.json"))
    }
}
val mapCheckAssets = tasks.register<MapCheckAssets>("mapCheckAssets") {
    recording.set(rootProject.layout.projectDirectory.file("../PolarisTests/Fixtures/transitous-plan.json"))
}
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(mapCheckAssets, MapCheckAssets::output)
    }
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
    implementation(libs.maplibre.android)
    // The same OkHttp the engine uses, given to MapLibre so tile requests carry the User-Agent.
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(kotlin("test"))
}
