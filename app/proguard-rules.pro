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

# Personal app, read only by Claude: keep real class / method names so crash, freeze and ANR
# reports (docs/MACHINE_FIRST_PLAN.md step 1) are readable without a mapping file. R8 still
# shrinks and optimizes.
-dontobfuscate
