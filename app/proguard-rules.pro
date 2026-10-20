# libtorrent4j: SWIG/JNI bindings are looked up by name from native code.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.kirtanya.reel.** { *** Companion; }
-keepclasseswithmembers class com.kirtanya.reel.** { kotlinx.serialization.KSerializer serializer(...); }
# QuickJS (plugin sandbox): native code calls back into these classes by name.
-keep class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**

# Short names keep the app small; tool/build_release.sh keeps each version's mapping in mappings/
# and tool/pull_reports.sh turns report stacks back into real names (docs/DECISIONS.md).
