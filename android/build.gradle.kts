plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Build output stays in each module's build/ folder unless a builder points it elsewhere,
// for example to a bigger disk: ./gradlew -Ppolaris.buildRoot=/path/to/builds test
providers.gradleProperty("polaris.buildRoot").orNull?.let { root ->
    subprojects { layout.buildDirectory.set(file("$root/${project.name}")) }
}
