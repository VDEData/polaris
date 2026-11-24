plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The engine is plain Kotlin with no Android imports, so its tests run on the JVM, offline.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// The iOS tests' recorded responses, read in place: one set of fixtures for both platforms.
sourceSets {
    test {
        resources.srcDir(rootProject.layout.projectDirectory.dir("../PolarisTests/Fixtures"))
    }
}

tasks.test {
    useJUnit()
}
