pluginManagement {
    repositories {
        // dl.google.com = google() 仓库后端 (maven.google.com 在部分网络不可达)
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
    }
}

rootProject.name = "esp_overlay"
include(":app")
