plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// "Login com Google" — Web client ID (do Google Cloud Console).
// Configure em gradle.properties (googleWebClientId=...) ou na env GOOGLE_WEB_CLIENT_ID.
val googleWebClientId: String = (
    (project.findProperty("googleWebClientId") as String?)
        ?: System.getenv("GOOGLE_WEB_CLIENT_ID")
        ?: ""
)

android {
    namespace = "com.pulsa.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pulsa.player"
        minSdk = 23
targetSdk = 34
        versionCode = 118
        versionName = "5.7.1"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("pulsa") {
            val env = System.getenv()
            storeFile = file(env["KEYSTORE_PATH"] ?: "keystore/pulsa.keystore")
            storePassword = env["KEYSTORE_PASSWORD"] ?: "android"
            keyAlias = env["KEY_ALIAS"] ?: "androiddebugkey"
            keyPassword = env["KEY_PASSWORD"] ?: "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pulsa")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("pulsa")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    testImplementation("junit:junit:4.13.2")
}
