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
include(":blocks:rhythm")        // Rhythm key authentication - Phase 1B ✅
// include(":blocks:messaging")     // Core messaging - TODO: Phase 2A
// include(":blocks:contacts")      // Contact management - TODO: Phase 2A
// include(":blocks:decoy")         // Decoy mode for plausible deniability - TODO: Phase 3
// include(":blocks:onboarding")    // Onboarding flow - TODO: Phase 1C

// ═══════════════════════════════════════════════════════════════════
// APP (Shell) - Just wiring, no logic
// ═══════════════════════════════════════════════════════════════════
include(":app")
