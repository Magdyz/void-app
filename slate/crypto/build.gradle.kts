plugins {
    id("void.slate")
}

android {
    namespace = "app.voidapp.slate.crypto"

    // Configure test tasks to use JUnit 5 (Jupiter)
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    // Slate core for interfaces
    api(project(":slate:core"))

    // Google Tink for crypto operations
    implementation(libs.tink)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Testing - JUnit 5
    testImplementation(libs.bundles.testing)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.3")
}
