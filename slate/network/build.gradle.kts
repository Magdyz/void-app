plugins {
    id("void.slate")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.voidapp.slate.network"
}

dependencies {
    // Slate core for interfaces
    api(project(":slate:core"))

    // Ktor for HTTP client + WebSockets
    implementation(libs.bundles.ktor)

    // Kotlinx Serialization for JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.bundles.testing)
}
