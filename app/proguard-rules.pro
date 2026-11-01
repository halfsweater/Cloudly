# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.halfblanket.cloudly.**$$serializer { *; }
-keepclassmembers class com.halfblanket.cloudly.** { *** Companion; }
-keepclasseswithmembers class com.halfblanket.cloudly.** { kotlinx.serialization.KSerializer serializer(...); }

# Credential Manager / Google Identity
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
