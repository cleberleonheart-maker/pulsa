pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Pulsa"
include(":app")
// Wear OS: APK do relógio embutido no APK do celular (ver app/build.gradle.kts `wearApp`).
include(":wear")