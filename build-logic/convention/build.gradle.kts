plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("voidSlate") {
            id = "void.slate"
            implementationClass = "com.void.convention.VoidSlatePlugin"
        }
        register("voidBlock") {
            id = "void.block"
            implementationClass = "com.void.convention.VoidBlockPlugin"
        }
        register("voidApp") {
            id = "void.app"
            implementationClass = "com.void.convention.VoidAppPlugin"
        }
    }
}
