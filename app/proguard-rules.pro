# Keep rules for ecarx-adaptapi and missing system classes
-keep class com.ecarx.** { *; }
-dontwarn com.ecarx.**

# Ignore missing system classes referenced by vendor SDK
-dontwarn android.annotation.SystemApi
-dontwarn android.os.IPowerStatusListener$Stub
-dontwarn android.os.IPowerStatusListener
-dontwarn android.os.ServiceManager
-dontwarn android.os.SystemProperties
-dontwarn com.android.internal.annotations.GuardedBy
-dontwarn ecarx.os.LocalConfig
-dontwarn ecarx.power.BrightnessManager
# Защита системного Android Car API и eCarX AdaptAPI от обфускации R8 в Release-сборке
-keep class android.car.** { *; }
-dontwarn android.car.**
-keep class com.ecarx.** { *; }
-dontwarn com.ecarx.**
-keep class dezz.status.widget.car.** { *; }