package com.void.convention

import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin for VOID Slate (core infrastructure) modules.
 * 
 * Slate modules provide:
 * - Contracts and interfaces
 * - Shared infrastructure (crypto, storage)
 * - Design system
 */
class VoidSlatePlugin : Plugin<Project> {
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
                add("implementation", libs.findBundle("compose").get())
                add("implementation", libs.findLibrary("coroutines-core").get())
                add("implementation", libs.findLibrary("koin-core").get())
                add("testImplementation", libs.findBundle("testing").get())
            }
        }
    }
    
    private val Project.libs
        get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
            .named("libs")
}
