plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services) apply false  // Applied conditionally per flavor
}

android {
    namespace = "app.voidapp.secure"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.voidapp.secure"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            // Play Store version with Firebase Cloud Messaging
        }
        create("foss") {
            dimension = "store"
            // F-Droid version with UnifiedPush (stub for now)
            applicationIdSuffix = ".foss"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

dependencies {
    // Slate modules - core infrastructure
    implementation(project(":slate:core"))
    implementation(project(":slate:crypto"))
    implementation(project(":slate:storage"))
    implementation(project(":slate:network"))
    implementation(project(":slate:design"))

    // Blocks - all feature modules
    implementation(project(":blocks:identity"))
    implementation(project(":blocks:rhythm"))  // Phase 1B: Rhythm authentication
    implementation(project(":blocks:messaging"))  // Phase 2: Messaging
    implementation(project(":blocks:contacts"))  // Phase 2: Contacts

    // Phase 3+ blocks (not implemented yet):
    // implementation(project(":blocks:decoy"))
    // implementation(project(":blocks:onboarding"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Koin for DI
    implementation(libs.bundles.koin)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // WorkManager for background sync
    implementation(libs.workmanager.runtime)

    // Push notifications - flavor specific
    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.messaging)
    "fossImplementation"(libs.unifiedpush)

    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(libs.bundles.testing)
    debugImplementation(libs.compose.ui.tooling)
}

// Apply Google Services plugin only for Play flavor
afterEvaluate {
    tasks.matching { it.name.contains("Process") && it.name.contains("PlayRelease") }.configureEach {
        // Note: google-services.json will be needed in app/ directory for Play flavor
    }
}
