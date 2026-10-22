plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// O APK do relógio é embutido no APK do celular (`wearApp` em :app), e o empacotador
// exige versão e assinatura iguais às do celular. Ler do `app/build.gradle.kts` mantém a
// única fonte de verdade: quem sobe a versão do app não precisa lembrar deste arquivo.
val phoneBuild = rootProject.file("app/build.gradle.kts").readText()

fun phoneValue(regex: Regex): String =
    regex.find(phoneBuild)?.groupValues?.get(1)
        ?: error("Não achei ${regex.pattern} em app/build.gradle.kts")

val wearVersionCode = phoneValue(Regex("""versionCode\s*=\s*(\d+)""")).toInt()
val wearVersionName = phoneValue(Regex("""versionName\s*=\s*"([^"]+)""""))

android {
    namespace = "com.pulsa.player.wear"
    compileSdk = 34

    defaultConfig {
        // Mesmo applicationId do celular: o Data Layer é escopado por pacote + assinatura,
        // então pacotes diferentes não se enxergam. Isso também é exigência do `wearApp`.
        applicationId = "com.pulsa.player"
        minSdk = 26
        targetSdk = 34
        versionCode = wearVersionCode
        versionName = wearVersionName
    }

    signingConfigs {
        create("pulsa") {
            val env = System.getenv()
            storeFile = rootProject.file(env["KEYSTORE_PATH"] ?: "app/keystore/pulsa.keystore")
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
    // Só o Data Layer. A UI usa as Views da plataforma: o `androidx.wear`/Compose não
    // acrescentaria nada nesta tela de quatro botões e dobraria o tamanho do APK.
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
