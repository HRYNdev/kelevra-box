pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}
rootProject.name = "sing-box"
include(":app")
// десктопный клиент: собирается отдельно, на сборку APK не влияет
include(":desktop")
include(":libxposed-api")
project(":libxposed-api").projectDir = file("third_party/libxposed-api")
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("third_party/termux-app/terminal-emulator")
include(":terminal-view")
project(":terminal-view").projectDir = file("third_party/termux-app/terminal-view")
