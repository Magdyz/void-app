plugins {
    id("void.slate")
}

android {
    namespace = "com.void.slate.storage"
}

dependencies {
    // Slate core for interfaces
    api(project(":slate:core"))

    // SQLCipher for encrypted database
    implementation(libs.sqlcipher)
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation("androidx.test:core:1.5.0")
}
