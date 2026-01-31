plugins {
    id("void.slate")
}

android {
    namespace = "app.voidapp.slate.core"
}

dependencies {
    // Core dependencies only - minimal footprint
    implementation(libs.coroutines.core)
    implementation(libs.koin.core)
    implementation(libs.lifecycle.viewmodel)

    // Compose for navigation types
    implementation(libs.navigation.compose)

    // QR code generation/scanning (shared utility)
    implementation("com.google.zxing:core:3.5.3")
}
