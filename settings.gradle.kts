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
        maven { url = uri("https://jitpack.io") }  // Required for UnifiedPush
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
include(":slate:network")

// ═══════════════════════════════════════════════════════════════════
// BLOCKS (Features) - The lego pieces
// Comment out any block to remove it from the build
// ═══════════════════════════════════════════════════════════════════
include(":blocks:identity")      // 3-word identity system
include(":blocks:rhythm")        // Rhythm key authentication - Phase 1B ✅
include(":blocks:messaging")     // Core messaging - Phase 2 ✅
include(":blocks:contacts")      // Contact management - Phase 2 ✅
// include(":blocks:decoy")         // Decoy mode for plausible deniability - TODO: Phase 3
// include(":blocks:onboarding")    // Onboarding flow - TODO: Phase 1C

// ═══════════════════════════════════════════════════════════════════
// APP (Shell) - Just wiring, no logic
// ═══════════════════════════════════════════════════════════════════
include(":app")
