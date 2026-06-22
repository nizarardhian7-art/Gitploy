# ── GitDeploy ProGuard Rules (Production-Ready) ──────────────────────────────

# Keep app entry points
-keep class com.gitdeploy.app.MainActivity { *; }
-keep class com.gitdeploy.app.UploadForegroundService { *; }

# IMPROVEMENT #1: EncryptedSharedPreferences / Tink (security-crypto internals)
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# IMPROVEMENT #1: MasterKey + EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# BouncyCastle (keystore generation)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# AndroidX core / appcompat
-keep class androidx.core.** { *; }
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**

# JSON (used in GHApi)
-keep class org.json.** { *; }

# Kotlin stdlib (transitive via appcompat)
-dontwarn kotlin.**
-keep class kotlin.** { *; }

# Keep all public API models in GHApi (used via reflection in some loggers)
-keep class com.gitdeploy.app.GHApi$* { *; }
-keep class com.gitdeploy.app.AppPrefs { *; }

# Suppress common warnings from transitive deps
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# Preserve line numbers for crash reports (Firebase Crashlytics / Play Console)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Markwon (markdown renderer)
-keep class io.noties.markwon.** { *; }
-keepclassmembers class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ── Retrofit + OkHttp + Gson (migration layer) ──────────────────────────────
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

-keep class com.google.gson.** { *; }
-keep class com.gitdeploy.app.RepoDto { *; }
-keepclassmembers class com.gitdeploy.app.RepoDto { *; }

# ── AndroidX Lifecycle / ViewModel / LiveData ────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(); }
-dontwarn androidx.lifecycle.**

# ── Error handling infrastructure ────────────────────────────────────────────
-keep class com.gitdeploy.app.Resource { *; }
-keep class com.gitdeploy.app.ErrorHandler { *; }
-keep enum com.gitdeploy.app.Resource$Status { *; }
-keep enum com.gitdeploy.app.ErrorHandler$Category { *; }

# ── Room database ─────────────────────────────────────────────────────────────
-keep class com.gitdeploy.app.AppDatabase { *; }
-keep class com.gitdeploy.app.RepoEntity { *; }
-keep interface com.gitdeploy.app.RepoDao { *; }
-keep class com.gitdeploy.app.RepoRepository { *; }
-dontwarn androidx.room.**
