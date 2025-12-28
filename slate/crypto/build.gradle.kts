plugins {
    id("void.slate")
}

android {
    namespace = "app.voidapp.slate.crypto"
}

dependencies {
    // Slate core for interfaces
    api(project(":slate:core"))

    // Google Tink for crypto operations
    implementation(libs.tink)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.bundles.testing)
}
