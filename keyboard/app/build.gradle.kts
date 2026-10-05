import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Kotlin comes from AGP 9's built-in Kotlin support; org.jetbrains.kotlin.android is not applied.
    id("com.android.application")
}

android {
    namespace = "org.circa.keyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.circa.keyboard"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Signed by build-all.sh with the AOSP platform testkey; Gradle leaves it unsigned.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The pure layout/suggestion/editor code only reads android.text.InputType / EditorInfo
        // constants (inlined at compile time); anything else from android.jar returns defaults.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // No AndroidX at all: the IME is a single custom View on InputMethodService.
    testImplementation("junit:junit:4.13.2")
}
