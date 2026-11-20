import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
}

// Bump appVersion in every commit that changes the app (rules in CLAUDE.md). MAJOR.MINOR.PATCH.
val appVersion = "0.33.0"

// versionCode = minutes since 2026-01-01 at build time, so every new build installs over the last one,
// whichever computer built it.
val buildMinutes = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
val buildDate = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date())

android {
    namespace = "com.autodate"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.autodate.app"
        // takeScreenshot() from an accessibility service needs API 30.
        minSdk = 30
        targetSdk = 37
        versionCode = buildMinutes
        versionName = "$appVersion ($buildDate)"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
