# libtorrent4j: SWIG/JNI bindings are looked up by name from native code.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.kirtanya.reel.** { *** Companion; }
-keepclasseswithmembers class com.kirtanya.reel.** { kotlinx.serialization.KSerializer serializer(...); }
