plugins {
    id("void.slate")
}

android {
    namespace = "com.void.slate.design"

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Slate core
    api(project(":slate:core"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.compose.ui.tooling.preview)

    // Compose debugging
    debugImplementation(libs.compose.ui.tooling)
}
