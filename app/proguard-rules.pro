# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.contacts.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.contacts.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
