// A single-module build: the root project is the library. That keeps the Maven
// coordinates short - com.github.MBanucu:android-accessible-terminal:<tag> - and
// leaves the sources where the scripts and the README already point: terminal/.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-accessible-terminal"
