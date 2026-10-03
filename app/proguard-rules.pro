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

# PDFBox: optional JPEG2000 decoder, not bundled
-dontwarn com.gemalto.jp2.JP2Decoder
