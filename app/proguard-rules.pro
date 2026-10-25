# Shadow uses org.json (platform) and plain HttpURLConnection: nothing reflective to keep.
-dontwarn org.jetbrains.annotations.**
# Tink (used by androidx.security-crypto) references annotation-only classes not shipped at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
