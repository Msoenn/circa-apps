plugins {
    // Same toolchain as apps/settings and apps/launcher: AGP 9.4.1 on JDK 17, built-in Kotlin (KGP 2.2.10).
    // The keyboard is a plain View (no Compose), so the Compose compiler plugin is not applied.
    id("com.android.application") version "9.4.1" apply false
}
