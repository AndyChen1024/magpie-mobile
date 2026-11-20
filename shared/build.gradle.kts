import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "com.andychen.magpie.shared"
        compileSdk = 37
        minSdk = 28
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withHostTestBuilder {}
    }

    val xcframework = XCFramework("MagpieShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "MagpieShared"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
