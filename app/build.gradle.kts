import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "jp.co.softtex.st_andapp_0001_kin001"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "jp.co.softtex.st_andapp_0001_kin001"
        minSdk = 22
        targetSdk = 35
        versionCode = 1
        versionName = "1.000.000"

        // ビルド日時を BuildConfig に追加
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("JST") }
            .format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildToolsVersion = "35.0.0"
}
dependencies {
    /* 25/03/21 東芝TEC RFIDハンドリーダUF-3000 シリーズ SDK  START */
    implementation(files("libs/tecrfidsuite.jar"))
    /* 25/03/21 東芝TEC RFIDハンドリーダUF-3000 シリーズ SDK  E N D */
    implementation("com.android.support:support-v4:28.0.0")
    implementation("com.android.support:design:28.0.0")
    // ログ取得用ライブラリ
    implementation("com.jakewharton.timber:timber:5.0.1")
    // Cameraライブラリ
    implementation ("com.google.zxing:core:3.3.0")
    implementation ("com.journeyapps:zxing-android-embedded:3.6.0")
}

