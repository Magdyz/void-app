pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "void"

// ═══════════════════════════════════════════════════════════════════
// SLATE (Core Infrastructure) - The baseplate
// ═══════════════════════════════════════════════════════════════════
include(":slate:core")
include(":slate:crypto")
include(":slate:storage")
include(":slate:design")

// ═══════════════════════════════════════════════════════════════════
// BLOCKS (Features) - The lego pieces
// Comment out any block to remove it from the build
// ═══════════════════════════════════════════════════════════════════
include(":blocks:identity")      // 3-word identity system
include(":blocks:rhythm")        // Rhythm key authentication
include(":blocks:messaging")     // Core messaging
include(":blocks:contacts")      // Contact management
include(":blocks:decoy")         // Decoy mode for plausible deniability
include(":blocks:onboarding")    // Onboarding flow

// ═══════════════════════════════════════════════════════════════════
// APP (Shell) - Just wiring, no logic
// ═══════════════════════════════════════════════════════════════════
include(":app")
