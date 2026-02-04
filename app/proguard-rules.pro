# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep NanoHTTPD classes
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# Keep SMBJ classes
-keep class com.hierynomus.** { *; }

# Keep AWS SDK classes
-keep class com.amazonaws.** { *; }

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.lensdaemon.** { *; }
