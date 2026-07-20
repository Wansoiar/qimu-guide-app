# okHttp3
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

-keep class com.hjq.permissions.** {*;}

-dontwarn com.yanzhenjie.kalle
-keep class com.yanzhenjie.kalle.** {*;}

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** {*;}
-keepattributes Signature
-keepattributes Exceptions

# gson && protobuf
-dontwarn com.google.**
-keep class com.google.gson.** {*;}

# bc 库，SM2 加密解密
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** {*;}

-keep class com.android.mltcode.** { *; }
-keeppackagenames com.android.mltcode.*
-repackageclasses 'com.android.mltcode.paycertificationapi'
