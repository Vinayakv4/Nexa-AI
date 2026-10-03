# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.nexa.ai.**$$serializer { *; }
-keepclassmembers class com.nexa.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.nexa.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}
