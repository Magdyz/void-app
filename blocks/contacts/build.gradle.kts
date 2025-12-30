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

    // For QR code scanning (version will be added to catalog later)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
