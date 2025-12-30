plugins {
    id("void.block")
}

android {
    namespace = "app.voidapp.block.messaging"
}

// Block-specific dependencies
dependencies {
    // slate modules
    implementation(project(":slate:storage"))
    implementation(project(":slate:crypto"))
    implementation(project(":slate:network"))

    // WorkManager for background sync
    implementation(libs.workmanager.runtime)

    // Koin for dependency injection
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    // Blocks are isolated - no direct dependencies on other blocks
    // Cross-block communication via EventBus only
    // Dependencies injected at runtime via Koin in app module
}
