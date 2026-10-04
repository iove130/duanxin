# 保留短信接收与解析相关类名，避免被混淆后广播解析失败
-keep class com.google.android.gms.internal.sms.** { *; }
-keep class android.provider.Telephony$* { *; }

# Kotlin 协程调试信息（关闭也不影响运行，保留可读日志）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
