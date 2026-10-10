import java.net.URI
import java.security.MessageDigest

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Tesseract4Android, pinned: the weekly upstream check opens an issue when a newer one is out.
val tesseractVersion = "4.9.0"
val tesseractSha = "bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a"
val tesseractAar: File get() = layout.buildDirectory.file("tesseract/tesseract4android-$tesseractVersion.aar").get().asFile
val fetchTesseract by tasks.registering {
    val target = tesseractAar
    val version = tesseractVersion
    val sha = tesseractSha
    outputs.file(target)
    doLast {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (target.exists() && sha256(target.readBytes()) == sha) return@doLast
        val bytes = URI("https://jitpack.io/cz/adaptech/tesseract4android/tesseract4android/$version/tesseract4android-$version.aar").toURL().openStream().use { it.readBytes() }
        check(sha256(bytes) == sha) { "Tesseract4Android: checksum does not match" }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}

android {
    namespace = "com.yaz.contacts"
    // 37 because Compose compiles against it, and the app follows the
    // newest platform rules for contacts, notifications and permissions.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yaz.contacts"
        minSdk = 31
        targetSdk = 37
        versionCode = 13
        versionName = "0.9.2"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("contacts.storeFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("contacts.storePassword").orNull
                keyAlias = providers.gradleProperty("contacts.keyAlias").orNull
                keyPassword = providers.gradleProperty("contacts.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Only attached when the signing properties are supplied, so a
            // local build without a keystore still produces an unsigned APK.
            if (providers.gradleProperty("contacts.storeFile").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The picture decoder in its isolated process is reached through Binder.
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    // Numbers written the way their country writes them and the same number
    // found under two spellings, offline: Google's libphonenumber (Apache-2.0).
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.40")
    // A contact as a QR code, and a QR code read back into a contact: ZXing's core (Apache-2.0).
    implementation("com.google.zxing:core:3.5.4")
    // The passphrase of an encrypted backup turned into its key with Argon2id
    // (RFC 9106): Bouncy Castle's implementation (MIT licence).
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // Reading a business card on the phone: Tesseract through Tesseract4Android
    // (Apache-2.0), only published on JitPack, so fetched at its pinned version
    // and checked against its SHA-256 below instead of trusting that repository.
    implementation(files(tesseractAar))
    implementation("androidx.annotation:annotation:1.9.1")
    // Scanning someone's QR code with the camera: Android's CameraX (Apache-2.0).
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-compose:1.6.2")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

tasks.named("preBuild") { dependsOn(fetchTesseract) }
