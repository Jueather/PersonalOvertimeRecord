# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Security Rules - Data Models
-keep class com.example.personalovertimerecord.data.** { *; }
-keep class com.example.personalovertimerecord.data.db.** { *; }

# 团队同步 DTO（Gson 反射序列化，字段名即 JSON key，与服务端 camelCase 对齐）
-keep class com.example.personalovertimerecord.data.model.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }

# Tink（security-crypto 底层库）引用了 Android 运行时不存在的 JSR-305 注解，仅是编译期引用，可安全忽略
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# Keep data classes for JSON serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson 反射支持：TypeToken 泛型解析与 JSON 字段名映射
# （BackupData/AttendanceEntityBackup/UpdateInfo 位于 utils 包，字段名即 JSON key，
#   混淆会破坏备份文件格式兼容性，必须保留）
-keep class com.example.personalovertimerecord.utils.BackupData { *; }
-keep class com.example.personalovertimerecord.utils.AttendanceEntityBackup { *; }
-keep class com.example.personalovertimerecord.utils.UpdateManager$UpdateInfo { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature

# 保留行号便于崩溃日志（GlobalExceptionHandler）还原定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Kotlin Metadata
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }