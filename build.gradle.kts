plugins {
    id("com.android.application") version "8.2.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // F1/E8: versao do KSP tem que casar com o Kotlin (1.9.24 -> 1.0.20).
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}