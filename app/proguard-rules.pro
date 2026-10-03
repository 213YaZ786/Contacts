# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.yaz.contacts.** {
    *** Companion;
}
-keepclasseswithmembers class com.yaz.contacts.** {
    kotlinx.serialization.KSerializer serializer(...);
}
