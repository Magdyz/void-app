plugins {
    id("void.slate")
}

android {
    namespace = "app.voidapp.slate.design"

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Slate core
    api(project(":slate:core"))

    // AndroidX Core for WindowCompat
    implementation("androidx.core:core-ktx:1.12.0")

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.compose.ui.tooling.preview)

    // Compose debugging
    debugImplementation(libs.compose.ui.tooling)
}
