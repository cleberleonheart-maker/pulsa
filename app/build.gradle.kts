plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // F1/E8: gerador do Room. Declarado agora, so passa a gerar algo em E8.
    id("com.google.devtools.ksp")
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
        versionCode = 124
        versionName = "5.9.3"
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
    // F1: sessao/biblioteca de midia (E2/E6), HLS/DASH do radio e do video (E4/E5),
    // OkHttp como DataSource, Room para playlists/favoritas (E8) e WorkManager para o
    // trabalho de fundo (E9). Nada e usado ainda - E1 e so dependencia, comportamento zero.
    implementation("androidx.media3:media3-session:1.3.1")
    // Legendas (SRT/VTT/ASS) no video: traz o SubtitleView e os parsers de SRT/VTT que o
    // PlayerView usa. Sem isso o Media3 decodifica a legenda mas nao ha onde desenhar.
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
}
