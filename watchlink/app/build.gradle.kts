import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // Kotlin itself comes from AGP's built-in Kotlin support (AGP 9), so
    // org.jetbrains.kotlin.android is deliberately not applied.
}

// Where the host tests write the protocol vectors (hex of Gadgetbridge's bytes TAB expected JSON) for an external
// Python test harness, taken from the committed golden file.
val vectorsFile = layout.buildDirectory.file("hostTest/gb-vectors.tsv").get().asFile

android {
    namespace = "org.circa.watchlink"
    // The app targets API 36 (Android 16) on the watch; see app/src/main/AndroidManifest.xml.
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.circa.watchlink"
        minSdk = 33
        targetSdk = 36
        versionCode = 9
        versionName = "0.9"
    }

    buildTypes {
        release {
            // Signing is done by build-all.sh with the AOSP testkey (not the platform key: WatchLink
            // parses input from the phone over Bluetooth and needs no platform powers), so Gradle leaves this
            // build unsigned.
            // R8 + resource shrinking: the unminified Kotlin build was 2.2 MB (the 0.4 javac/d8 APK was 50 KB);
            // proguard-rules.pro keeps the manifest components and documents why nothing else is kept.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// The app has no dependencies at all (not even androidx): it uses only the platform, so the APK stays small.
dependencies {}

// ---- host-JVM protocol tests ------------------------------------------------------------------
// src/hostTest/java holds the host-JVM protocol tests (HostTest.java and friends) and small org.json /
// android.util.Base64 shims. The golden source is src/hostTest/resources/gb-golden.tsv: the exact bytes Gadgetbridge's
// own Bangle.js encoder writes for every test case, committed as test data. They are compiled separately instead of
// living in AGP's unit-test source set because AGP compiles unit tests with the mockable android.jar patched into
// java.base, and javac then refuses any source in a package that jar also provides ("package exists in another
// module: java.base").
val kotlinClasses = layout.buildDirectory
    .dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")   // AGP 9 built-in-Kotlin output
val hostTestClasses = layout.buildDirectory.dir("hostTest/classes")

val compileHostTestJava by tasks.registering(JavaCompile::class) {
    description = "Compiles the host-JVM protocol tests"
    group = "verification"
    dependsOn("compileDebugKotlin")
    source = fileTree("src/hostTest/java")
    classpath = files(kotlinClasses, configurations.getByName("debugRuntimeClasspath"))
    destinationDirectory.set(hostTestClasses)
}

val hostTest by tasks.registering(JavaExec::class) {
    description = "Runs the host-JVM protocol tests and writes build/hostTest/gb-vectors.tsv"
    group = "verification"
    dependsOn(compileHostTestJava)
    classpath = files(hostTestClasses) + files(kotlinClasses) +
            configurations.getByName("debugRuntimeClasspath")
    mainClass.set("org.circa.watchlink.HostTest")
    args("--golden", file("src/hostTest/resources/gb-golden.tsv").absolutePath, "--vectors", vectorsFile.absolutePath)
    doFirst { vectorsFile.parentFile.mkdirs() }
}
