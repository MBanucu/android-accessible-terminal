// A single-module build: the root project is the library. That keeps the Maven
// coordinates short - com.github.MBanucu:android-accessible-terminal:<tag> - and
// leaves the sources where the scripts and the README already point: terminal/.
import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Every artifact comes from the repositories here. A project-level
    // repositories {} block would split resolution across two places and is
    // refused instead of silently adding another source.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// AGP reports a generic "SDK location not found" when nothing is configured.
// Resolve the same sources first: ANDROID_HOME, ANDROID_SDK_ROOT,
// local.properties, an explicit android.home, and - on Termux - the conventional
// $HOME/android-sdk install. Fail with one actionable message, and let AGP
// inherit the default through android.home when there is no explicit
// configuration.
val localSdk = file("local.properties").takeIf { it.isFile }?.let { propertiesFile ->
    Properties().apply {
        propertiesFile.inputStream().use(::load)
    }.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
}
val envSdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
    ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
val systemSdk = System.getProperty("android.home")?.takeIf { it.isNotBlank() }
val termuxDefault = File(System.getProperty("user.home"), "android-sdk")
    .takeIf { it.isDirectory }?.absolutePath
val configuredSdk = envSdk ?: localSdk ?: systemSdk ?: termuxDefault
if (configuredSdk == null) {
    throw GradleException(
        """
        Android SDK not found.

        Set ANDROID_HOME to an SDK directory, or copy local.properties.example
        to local.properties and set sdk.dir:

            export ANDROID_HOME="${'$'}HOME/android-sdk"

        On Termux, the one-command build resolves ${'$'}HOME/android-sdk and
        Termux's bionic aapt2 for you:

            bash scripts/termux-build.sh

        See docs/termux-build.md for the full setup.
        """.trimIndent()
    )
}
if (!File(configuredSdk).isDirectory) {
    throw GradleException(
        "Android SDK '$configuredSdk' does not exist. Set ANDROID_HOME to an SDK " +
            "directory, fix sdk.dir in local.properties, or run " +
            "bash scripts/termux-build.sh. See docs/termux-build.md."
    )
}
if (envSdk == null && localSdk == null && systemSdk == null) {
    // AGP's SdkLocator reads this when neither the environment nor
    // local.properties names an SDK. It is the Termux default install.
    System.setProperty("android.home", configuredSdk)
}

rootProject.name = "android-accessible-terminal"
