import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** App version for the APK and desktop installers; CI passes the tag (v1.2.3 → 1.2.3) with -PappVersion. */
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "1.1.1"

kotlin {
    // jvmSharedMain: code for both JVM platforms (Android and desktop), e.g. HTTP downloads for updates.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting
        val desktopTest by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.gms.code.scanner)
            implementation(libs.concentus)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.zxing.core)
            implementation(libs.jna)
            implementation(libs.concentus)
        }
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "app.phoneinput"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.phoneinput"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersion.split('.').fold(0) { code, part -> code * 100 + part.toInt() }
        versionName = appVersion
    }
    // Release signing comes from the environment (CI secrets); without it the release APK is unsigned.
    val keystore = System.getenv("ANDROID_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("phoneinput.log", "console")
}

compose.desktop {
    application {
        mainClass = "app.phoneinput.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "PhoneInput"
            windows {
                // Fixed so each new MSI replaces the installed version (the value v1.0.0 shipped with).
                upgradeUuid = "2CCCE26B-1393-384B-94A2-49ED8BCB690A"
                // Start Menu entry and desktop shortcut; the .exe is jpackage's wrapper around the same MSI.
                menu = true
                menuGroup = "PhoneInput"
                shortcut = true
            }
            packageVersion = appVersion
            description = "Use your phone as a microphone"
            vendor = "Dr-At0mic"
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                packageName = "phoneinput"
                appCategory = "sound"
                menuGroup = "AudioVideo;Audio;"
                debMaintainer = "92196795+Dr-At0mic@users.noreply.github.com"
                rpmLicenseType = "GPL-3.0-only"
            }
        }
    }
}
