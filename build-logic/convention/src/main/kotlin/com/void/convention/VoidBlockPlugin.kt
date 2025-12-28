package com.void.convention

import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin for VOID Blocks (feature modules).
 * 
 * Enforces:
 * - Blocks can only depend on slate/core and slate/design
 * - Blocks cannot depend on other blocks
 * - Standard testing setup
 * - Compose configuration
 */
class VoidBlockPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
                apply("org.jetbrains.kotlin.plugin.compose")
            }
            
            extensions.configure<LibraryExtension> {
                compileSdk = 35

                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                buildFeatures {
                    compose = true
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            // Ensure Kotlin targets the same JVM version
            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
            
            dependencies {
                // Blocks can ONLY depend on slate modules
                add("implementation", project(":slate:core"))
                add("implementation", project(":slate:crypto"))
                add("implementation", project(":slate:storage"))
                add("implementation", project(":slate:design"))
                
                // Standard dependencies for all blocks
                add("implementation", libs.findBundle("compose").get())
                add("implementation", libs.findBundle("koin").get())
                add("implementation", libs.findLibrary("lifecycle-viewmodel-compose").get())
                add("implementation", libs.findLibrary("navigation-compose").get())
                add("implementation", libs.findLibrary("coroutines-core").get())
                
                // Testing
                add("testImplementation", libs.findBundle("testing").get())
            }
            
            // Task to verify no cross-block dependencies
            tasks.register("verifyBlockIsolation") {
                group = "verification"
                description = "Verifies this block doesn't depend on other blocks"
                
                doLast {
                    val blockDeps = configurations
                        .filter { it.isCanBeResolved }
                        .flatMap { it.dependencies }
                        .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                        .filter { it.dependencyProject.path.startsWith(":blocks:") }
                    
                    if (blockDeps.isNotEmpty()) {
                        throw org.gradle.api.GradleException(
                            "Block ${project.path} cannot depend on other blocks: " +
                            blockDeps.map { it.dependencyProject.path }
                        )
                    }
                }
            }
            
            tasks.named("check") {
                dependsOn("verifyBlockIsolation")
            }
        }
    }
    
    private val Project.libs
        get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
            .named("libs")
}
