# Movie Art Details: R8 rules for release builds.
# The api/ contract (Wallpaper, Event) is kept by the api module's consumer rules.
# The Service, Activity and FileProvider declared in the manifest are kept by AAPT. OkHttp and
# kotlinx.serialization ship their own rules in their jars (META-INF/proguard, META-INF/com.android.tools).

# Line numbers in stack traces, without original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: serializers of the app's own DTOs (tmdb/, cache/, settings/), including private ones.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class io.github.yozha.movieartdetails.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class io.github.yozha.movieartdetails.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Verbose, debug and info logging is not needed in release builds: these android.util.Log calls are stripped.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
