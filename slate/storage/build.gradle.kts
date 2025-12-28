plugins {
    id("void.slate")
}

android {
    namespace = "app.voidapp.slate.storage"
}

dependencies {
    // Slate core for interfaces
    api(project(":slate:core"))

    // SQLCipher for encrypted database (16 KB page size compatible)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation("androidx.test:core:1.5.0")
}
