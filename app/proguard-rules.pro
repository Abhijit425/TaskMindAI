# Release builds are not minified by default. If you enable R8, keep serialization models:
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.abhijit.airealityscanner.**$$serializer { *; }
-keepclassmembers class com.abhijit.airealityscanner.** {
    *** Companion;
}
-keepclasseswithmembers class com.abhijit.airealityscanner.** {
    kotlinx.serialization.KSerializer serializer(...);
}
