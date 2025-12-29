plugins {
    id("void.block")
}

android {
    namespace = "app.voidapp.block.messaging"
}

// Block-specific dependencies
dependencies {
    // slate modules
    implementation(project(":slate:storage"))
    implementation(project(":slate:crypto"))

    // Contacts block for contact information (via EventBus only, no direct import)
    // We'll use EventBus to request contact info
}
