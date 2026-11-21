# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class com.photohub.app.data.api.** { *; }
-keep,includedescriptorclasses class com.photohub.app.**$$serializer { *; }
-keepclassmembers class com.photohub.app.** { *** Companion; }
-keepclasseswithmembers class com.photohub.app.** { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn org.slf4j.**
