# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.mymusiccurator.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface com.mymusiccurator.data.remote.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
