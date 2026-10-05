plugins {
    // AGP 9.4.x builds on JDK 17 and is the first AGP line that supports API level 37, which
    // androidx.wear.compose 1.7.0 requires (aar metadata: minCompileSdk=37, minAGP=9.1.0).
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin, KGP 2.2.10); only the Compose compiler
    // plugin is applied explicitly, and its version must match that KGP version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
