plugins {
    id("void.block")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.voidapp.block.constellation"
}

// Block-specific dependencies
dependencies {
    // Slate dependencies (provided by void.block)
    implementation(project(":slate:crypto"))
    implementation(project(":slate:storage"))

    // Kotlinx Serialization for pattern serialization
    implementation(libs.kotlinx.serialization.json)

    // AndroidX Biometric for biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")
}
