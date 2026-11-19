# Retrofit + kotlinx.serialization
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# smbj pulls optional desktop-only APIs never reached on Android.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# JNI uses the class and method name directly.
-keep,allowoptimization class com.podcastapp.data.NativeSourceLines {
    private native int[] scanNative(java.lang.String);
}

# Offline cache JNI methods are resolved by their original names.
-keep,allowoptimization class com.podcastapp.data.feed.NativeOfflineCache {
    private native <methods>;
}
