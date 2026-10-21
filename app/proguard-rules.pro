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

# Home disk SMB client: org.codelibs:jcifs 3.0.4 (docs/HOME_DISK.md §4.2).
-dontwarn javax.servlet.**
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
-dontwarn jakarta.**
-dontwarn org.slf4j.impl.**
# NTLM needs MD4, which jcifs gets from a BouncyCastleProvider instance by name lookup; R8 can't see it.
# Without these two every sign-in fails in release builds ("no such algorithm: MD4 for provider BC").
-keep class org.bouncycastle.jcajce.provider.digest.MD4** { *; }
-keep class org.bouncycastle.crypto.digests.MD4Digest { *; }
# The Test reads the negotiated SMB dialect by reflection (homedisk/SmbTest.protocolOf); falls back to "SMB 2+" if renamed.
-keepclassmembernames class org.codelibs.jcifs.smb.impl.SmbTreeHandleImpl { *** getSession(); }
-keepclassmembernames class org.codelibs.jcifs.smb.impl.SmbSessionImpl { *** getTransport(); }
-keepclassmembernames class org.codelibs.jcifs.smb.impl.SmbTransportImpl { *** getNegotiateResponse(); }
-keepclassmembernames class * implements org.codelibs.jcifs.smb.internal.SmbNegotiationResponse { *** getSelectedDialect(); }
