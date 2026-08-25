// Десктопный клиент Kelevra.
//
// Отдельный модуль в том же проекте: интерфейс тот же, что на телефоне, но
// сборка приложения его не касается — APK собирается без участия :desktop.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// репозитории объявлены централизованно в settings.gradle.kts

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

kotlin {
    jvmToolchain(21)
    // Тот же файл токенов, что и у :app. Правится в одном месте, иначе клиенты разъедутся.
    sourceSets["main"].kotlin.srcDir("../design-tokens/src/main/kotlin")
}

compose.desktop {
    application {
        mainClass = "dev.hryn.kelevra.MainKt"
        nativeDistributions {
            packageName = "Kelevra"
            packageVersion = "1.0.0"
        }
    }
}
