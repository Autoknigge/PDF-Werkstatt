plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// AdMob-IDs von PDF-Werkstatt (öffentliche Kennungen, stehen in jeder APK).
// Überschreibbar per -PADMOB_APP_ID=... usw. oder GitHub-Secrets. Zum Testen mit Google-Test-IDs:
// App ca-app-pub-3940256099942544~3347511713, Banner .../9214589741, App-Start .../9257395921
val admobAppId: String =
    (project.findProperty("ADMOB_APP_ID") as String?)?.takeIf { it.isNotBlank() }
        ?: "ca-app-pub-7178658520690671~7731039871"
val admobBannerId: String =
    (project.findProperty("ADMOB_BANNER_ID") as String?)?.takeIf { it.isNotBlank() }
        ?: "ca-app-pub-7178658520690671/9559126864"
val admobOpenId: String =
    (project.findProperty("ADMOB_OPEN_ID") as String?)?.takeIf { it.isNotBlank() }
        ?: "ca-app-pub-7178658520690671/8695095193"

val ksFile = project.findProperty("KEYSTORE_FILE") as String?
val ksPassword = project.findProperty("KEYSTORE_PASSWORD") as String?
val ksAlias = project.findProperty("KEY_ALIAS") as String?
val ksKeyPassword = project.findProperty("KEY_PASSWORD") as String?
val hasSigning = !ksFile.isNullOrBlank() && !ksPassword.isNullOrBlank() &&
    !ksAlias.isNullOrBlank() && !ksKeyPassword.isNullOrBlank()

android {
    namespace = "de.autoknigge.pdfwerkstatt"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.autoknigge.pdfwerkstatt"
        minSdk = 29
        targetSdk = 36
        // Jeder GitHub-Build zählt hoch (Play Store verlangt steigende Versionscodes). Lokal: 100.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = run + 100
        versionName = "1.0.$run"

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
        buildConfigField("String", "ADMOB_OPEN_ID", "\"$admobOpenId\"")
    }

    signingConfigs {
        // Fester Schlüssel für Testversionen: Updates lassen sich so über die alte Version installieren.
        // Er ist öffentlich und gilt NICHT für den Play Store (dafür gibt es den geheimen Release-Schlüssel).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasSigning) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
