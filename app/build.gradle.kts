plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.contact.app"
    // 37 because Compose compiles against it, and the app follows the
    // newest platform rules for contacts, notifications and permissions.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.contact.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
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

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
