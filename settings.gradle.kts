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

rootProject.name = "SharkHub"
include(":app")
// Phone app that installs Shark Hub on the car over Wi-Fi ADB (the Android twin of tools/installer).
include(":installer")
