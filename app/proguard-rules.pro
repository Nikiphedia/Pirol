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

# T142: Release-Build (R8) — SMB-Bibliothek smbj (T125)
# Kerberos (org.ietf.jgss) und Expression Language (javax.el) gibt es auf Android nicht;
# PIROL nutzt nur NTLM-Anmeldung, die betroffenen Pfade werden nie aufgerufen.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
# smbj und der Event-Bus mbassador arbeiten mit Reflection
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn org.slf4j.**
# ONNX Runtime (JNI ruft Java-Klassen per Namen)
-keep class ai.onnxruntime.** { *; }
