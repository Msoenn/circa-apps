# R8 rules for the WatchLink release build (Kotlin, no androidx, no dependencies).
#
# What is kept and why:
#  - The framework instantiates the manifest components by name from AndroidManifest.xml. AGP also feeds its
#    aapt-generated rules for these, but they are spelled out here so the contract is explicit and survives
#    toolchain changes.
#  - HealthPermissionUsage is an <activity-alias> targeting .MainActivity, so there is no class of that name to
#    keep; keeping MainActivity covers it.
#  - Reflection: none. `grep -rn "Class.forName|getMethod|newInstance"` over src/main/kotlin is empty; the protocol
#    parsers (Proto, JsParser, JsonOut, Buckets, HrWindow, NotifyPolicy, HealthSnapshot, HrSampleSchedule,
#    LiveHrPolicy, NotifyThrottle) are called directly and may be shrunk/renamed. The host tests
#    (app/src/hostTest) run against the debug variant's unminified classes.
#
# Everything else the app references is reached from these entry points and is shrunk normally.

-keep class org.circa.watchlink.MainActivity { *; }
-keep class org.circa.watchlink.WatchLinkService { *; }
-keep class org.circa.watchlink.BootReceiver { *; }
-keep class org.circa.watchlink.HealthProvider { *; }
