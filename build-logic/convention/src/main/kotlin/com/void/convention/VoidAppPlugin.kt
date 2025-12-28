package com.void.convention

import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin for the VOID App shell.
 * The app shell is minimal - just wiring blocks together.
 */
class VoidAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
                apply("org.jetbrains.kotlin.plugin.compose")
            }
            
            extensions.configure<BaseAppModuleExtension> {
                compileSdk = 35

                defaultConfig {
                    applicationId = "com.void.app"
                    minSdk = 26
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0.0"
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                buildFeatures {
                    compose = true
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                buildTypes {
                    release {
                        isMinifyEnabled = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                }
            }

            // Ensure Kotlin targets the same JVM version
            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
            
            dependencies {
                // All slate modules
                add("implementation", project(":slate:core"))
                add("implementation", project(":slate:crypto"))
                add("implementation", project(":slate:storage"))
                add("implementation", project(":slate:design"))
                
                // All blocks (comment out to remove a feature)
                add("implementation", project(":blocks:identity"))
                // add("implementation", project(":blocks:rhythm"))        // TODO: Phase 1B
                // add("implementation", project(":blocks:messaging"))     // TODO: Phase 2A
                // add("implementation", project(":blocks:contacts"))      // TODO: Phase 2A
                // add("implementation", project(":blocks:decoy"))         // TODO: Phase 3
                // add("implementation", project(":blocks:onboarding"))    // TODO: Phase 1C
                
                // Standard dependencies
                add("implementation", libs.findBundle("compose").get())
                add("implementation", libs.findBundle("koin").get())
                add("implementation", libs.findLibrary("navigation-compose").get())
                add("implementation", libs.findLibrary("lifecycle-runtime").get())
                add("implementation", libs.findLibrary("coroutines-android").get())
            }
        }
    }
    
    private val Project.libs
        get() = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
            .named("libs")
}
