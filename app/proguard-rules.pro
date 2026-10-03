# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.contact.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.contact.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
