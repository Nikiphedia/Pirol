# PIROL ProGuard Rules
# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ch.etasystems.pirol.**$$serializer { *; }
-keepclassmembers class ch.etasystems.pirol.** {
    *** Companion;
}
-keepclasseswithmembers class ch.etasystems.pirol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
