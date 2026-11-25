import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 keeps the sheet's page stack across process death by serialising its keys.
    alias(libs.plugins.kotlin.serialization)
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

// The app's JVM tests read the same recorded answers as the engine's.
android.sourceSets["test"].resources.srcDir(rootProject.layout.projectDirectory.dir("../PolarisTests/Fixtures"))
android.sourceSets["test"].resources.srcDir(rootProject.layout.projectDirectory.dir("engine/src/test/resources"))

// Debug builds only: the map check draws one recorded journey, and the demo answers (Variant.kt)
// replay recorded TfL and Transitous answers. Only these recordings are copied into debug assets.
abstract class DebugRecordings : DefaultTask() {
    @get:InputFiles abstract val recordings: ConfigurableFileCollection
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun copy() {
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.resolve("demo").mkdirs()
        recordings.files.forEach { file ->
            if (file.name == "transitous-plan.json") file.copyTo(dir.resolve(file.name))
            file.copyTo(dir.resolve("demo/${file.name}"))
        }
    }
}
val mapCheckAssets = tasks.register<DebugRecordings>("mapCheckAssets") {
    val fixtures = rootProject.layout.projectDirectory.dir("../PolarisTests/Fixtures")
    recordings.from(
        listOf("transitous-plan.json", "tfl-journeys.json", "transitous-departures.json", "transitous-nearby.json").map { fixtures.file(it) },
        listOf("transitous-geocode-kings-cross.json", "transitous-geocode-trafalgar-square.json").map { rootProject.layout.projectDirectory.file("engine/src/test/resources/$it") },
    )
}
// Every build: the third-party notices (android/THIRD_PARTY_NOTICES.md), shown in Settings' licences page.
abstract class NoticesAsset : DefaultTask() {
    @get:InputFile abstract val notices: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun copy() {
        val file = notices.get().asFile
        check(file.readLines())
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        file.copyTo(dir.resolve("THIRD_PARTY_NOTICES.md"))
    }

    /**
     * The same rules the app's licences page reads the file by (`noticeBlocks`): every repo-only
     * block, comment and fence closed, so no build, tested or not, ships a page that drops text
     * or shows nothing.
     */
    private fun check(lines: List<String>) {
        var repoOnly = false
        var comment = false
        var fence = false
        lines.forEachIndexed { i, raw ->
            val t = raw.trim()
            when {
                fence -> if (t.startsWith("```")) fence = false
                comment -> if (t.endsWith("-->")) comment = false
                t == "<!-- repo-only -->" -> { if (repoOnly) throw GradleException("THIRD_PARTY_NOTICES.md:${i + 1}: repo-only opened twice"); repoOnly = true }
                t == "<!-- /repo-only -->" -> { if (!repoOnly) throw GradleException("THIRD_PARTY_NOTICES.md:${i + 1}: /repo-only with none open"); repoOnly = false }
                repoOnly -> Unit
                t.startsWith("<!--") -> if (!t.endsWith("-->")) comment = true
                t.startsWith("```") -> fence = true
            }
        }
        if (repoOnly || comment || fence) throw GradleException("THIRD_PARTY_NOTICES.md: a repo-only block, comment or fence is never closed")
        if (lines.none { it.startsWith("### ") }) throw GradleException("THIRD_PARTY_NOTICES.md: no licence sections found")
    }
}
val noticesAsset = tasks.register<NoticesAsset>("noticesAsset") {
    notices.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
}
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(mapCheckAssets, DebugRecordings::output)
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(noticesAsset, NoticesAsset::output)
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
    // Window size classes and the fold's position, for the wide and foldable layouts.
    implementation(libs.compose.material3.adaptive)
    // The sheet's page stack, with system and predictive back, and a ViewModel per page.
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)
    // The same OkHttp the engine uses, given to MapLibre so tile requests carry the User-Agent.
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(kotlin("test"))
}
