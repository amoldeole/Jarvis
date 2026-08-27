# Room entities and Gson manifests are kept by their annotations/reflection.
-keep class com.jarvis.phoneguardian.core.database.** { *; }
-keep class com.jarvis.phoneguardian.core.backup.** { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
