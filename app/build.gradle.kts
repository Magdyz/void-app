plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.voidapp.secure"
    compileSdk = 35

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
        }
    }
}

dependencies {
    // Slate modules - core infrastructure
    implementation(project(":slate:core"))
    implementation(project(":slate:crypto"))
    implementation(project(":slate:storage"))
    implementation(project(":slate:design"))

    // Blocks - all feature modules
    implementation(project(":blocks:identity"))
    // Phase 1B+ blocks (not implemented yet):
    // implementation(project(":blocks:rhythm"))
    // implementation(project(":blocks:messaging"))
    // implementation(project(":blocks:contacts"))
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

    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(libs.bundles.testing)
    debugImplementation(libs.compose.ui.tooling)
}
