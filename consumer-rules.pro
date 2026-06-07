# consumer-rules.pro

# Защита криптографического ядра LazySodium и JNA
-keep class com.goterl.lazysodium.** { *; }
-keep class net.java.dev.jna.** { *; }
-dontwarn com.goterl.lazysodium.**
-dontwarn net.java.dev.jna.**

# Защита сущностей Room от вырезания полей и изменения имён
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.echo.sdk.db.entity.** { *; }
-keep class com.echo.sdk.api.models.** { *; }

# Защита публичного API (EchoMeshClient и слушатели)
-keep class com.echo.sdk.api.EchoMeshClient { *; }
-keep class com.echo.sdk.api.EchoMessageListener { *; }

# OkHttp и Jsoup
-dontwarn okhttp3.**
-dontwarn org.jsoup.**

# Сохраняем аннотации для Room
-keepattributes *Annotation*
-keepclasseswithmembers class * {
    @androidx.room.* <methods>;
}
