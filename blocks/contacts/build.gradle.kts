plugins {
    id("void.block")
}

android {
    namespace = "app.voidapp.block.contacts"
}

// Block-specific dependencies
dependencies {
    // slate modules for storage
    implementation(project(":slate:storage"))
    implementation(project(":slate:crypto"))
    implementation(project(":slate:network"))

    // For QR code scanning
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // CameraX for modern camera API (replaces legacy DecoratedBarcodeView)
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
}
