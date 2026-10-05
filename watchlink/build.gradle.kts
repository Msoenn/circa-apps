plugins {
    // Same AGP as apps/launcher (JDK 17, built-in Kotlin 2.2.10). WatchLink needs no other plugin:
    // no androidx, no Compose, only the Kotlin stdlib lands in the APK.
    id("com.android.application") version "9.4.1" apply false
}
