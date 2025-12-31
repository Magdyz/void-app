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

    // Supabase client for database access
    implementation(libs.bundles.supabase)

    // Kotlinx Serialization for JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // WorkManager for background sync
    implementation(libs.workmanager.runtime)

    // Koin for dependency injection
    implementation(libs.koin.android)

    // Testing
    testImplementation(libs.bundles.testing)
}
