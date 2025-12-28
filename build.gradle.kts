// Root build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Enforce no cross-block dependencies
subprojects {
    afterEvaluate {
        if (path.startsWith(":blocks:")) {
            configurations.all {
                dependencies.forEach { dep ->
                    if (dep is ProjectDependency && dep.dependencyProject.path.startsWith(":blocks:")) {
                        throw GradleException(
                            "Block ${project.path} cannot depend on another block ${dep.dependencyProject.path}. " +
                            "Use events for cross-block communication."
                        )
                    }
                }
            }
        }
    }
}
