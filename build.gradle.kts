// The library build. The JVM suites are plain Java with their own runner
// (bash terminal/test.sh), so nothing here depends on JUnit; this file only has
// to produce the artifacts Maven Central and JitPack serve.
import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    `signing`
    // Maven Central accepts uploads through the Central Portal API only (the
    // legacy OSSRH deploy protocol was retired in 2025). Nmcp stages the
    // publications, signs nothing itself, and uploads one bundle. It needs both
    // plugins when the build is a single project: the aggregation plugin is what
    // uploads, the other exposes this project's publications to it.
    alias(libs.plugins.nmcp)
    alias(libs.plugins.nmcp.aggregation)
}

group = "io.github.mbanucu"
// Central refuses to overwrite a version, so releases pass their version in:
//   ./gradlew publishAggregationToCentralPortal -PVERSION_NAME=0.2.0
// -Pversion works too, the conventional Gradle spelling used for smoke builds.
version = providers.gradleProperty("VERSION_NAME")
    .orElse(providers.gradleProperty("version"))
    .getOrElse(libs.versions.projectVersion.get())

/** The SDK AGP will use, in the same precedence order as settings.gradle.kts. */
fun configuredSdkDir(): String? {
    val localProperties = layout.projectDirectory.file("local.properties").asFile
    val fromLocal = if (localProperties.isFile) {
        Properties().apply {
            localProperties.inputStream().use { load(it) }
        }.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    return System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
        ?: fromLocal
        ?: System.getProperty("android.home")
}

@DisableCachingByDefault(because = "Reads the machine's build environment")
abstract class CheckBuildEnv : DefaultTask() {
    @get:Input
    abstract val compileSdk: Property<Int>

    @get:Input
    abstract val buildTools: Property<String>

    @get:Input
    abstract val javaSpecificationVersion: Property<String>

    @get:Input
    abstract val osArch: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    @get:Optional
    abstract val termuxPrefix: Property<String>

    /** Null is passed through so the task can report a missing SDK itself. */
    @get:Input
    @get:Optional
    abstract val sdkDir: Property<String>

    @get:Input
    @get:Optional
    abstract val aapt2Override: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localProperties: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val jdk = javaSpecificationVersion.get().removePrefix("1.").substringBefore('.').toIntOrNull() ?: 0
        if (jdk < 17) {
            problems += "JDK $jdk is too old; use JDK 17 or newer (Termux: pkg install openjdk-21)"
        }

        val sdk = sdkDir.orNull
        val platform = compileSdk.get()
        if (sdk == null) {
            problems += "no Android SDK: export ANDROID_HOME=/path/to/android-sdk, copy " +
                "local.properties.example to local.properties and set sdk.dir, or run " +
                "bash scripts/termux-build.sh"
        } else if (!File(sdk).isDirectory) {
            problems += "the Android SDK at $sdk does not exist; set ANDROID_HOME or " +
                "local.properties sdk.dir to a real SDK directory"
        } else {
            val androidJar = File(sdk, "platforms/android-$platform/android.jar")
            if (!androidJar.isFile) {
                problems += "Android platform $platform is not installed in $sdk; run " +
                    "sdkmanager \"platforms;android-$platform\""
            }
            val tools = buildTools.get()
            if (!File(sdk, "build-tools/$tools").isDirectory) {
                problems += "Android build-tools $tools is not installed in $sdk; run " +
                    "sdkmanager \"build-tools;$tools\""
            }
        }

        val aapt2 = aapt2Override.orNull?.takeIf { it.isNotBlank() }
        if (aapt2 == null) {
            // Termux's aapt2 is a bionic binary; AGP's arm64/x86_64 downloads are
            // glibc builds and do not run there. Other arm64 hosts (macOS, glibc
            // Linux) can use AGP's own aapt2.
            val onTermux = termuxPrefix.orNull?.contains("com.termux") == true ||
                javaHome.get().contains("com.termux")
            val arm64 = osArch.get().lowercase().let { it == "aarch64" || it == "arm64" }
            if (onTermux && arm64) {
                problems += "no aapt2 override on Termux; add this to " +
                    "~/.gradle/gradle.properties (Termux's aapt2 is bionic, AGP's is glibc): " +
                    "android.aapt2FromMavenOverride=\$PREFIX/bin/aapt2 - or run " +
                    "bash scripts/termux-build.sh"
            }
        } else {
            val binary = File(aapt2)
            if (!binary.isFile) {
                problems += "android.aapt2FromMavenOverride points at $aapt2, which is not a file; " +
                    "use \$PREFIX/bin/aapt2 on Termux"
            } else if (!binary.canExecute()) {
                problems += "the aapt2 override $aapt2 is not executable; run chmod +x $aapt2"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The Android build environment is not ready:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nSee docs/termux-build.md for a working Termux setup."
            )
        }
        logger.lifecycle(
            "build environment: JDK $jdk, SDK $sdk, platform $platform, " +
                "build-tools ${buildTools.get()}, aapt2 ${aapt2 ?: "AGP's own"}"
        )
    }
}

android {
    namespace = "io.github.mbanucu.terminal"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // The sources keep the path the scripts and the tree use. Nothing needs a
    // conventional src/main/java, and moving them would break samples/build.sh,
    // which compiles this directory into each demo app.
    sourceSets["main"].apply {
        manifest.srcFile("terminal/AndroidManifest.xml")
        java.srcDirs("terminal/src/main/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    publishing {
        singleVariant("release") {
            // Both are Central requirements: a sources jar and a javadoc jar
            // next to every artifact.
            withSourcesJar()
            withJavadocJar()
        }
    }
}

tasks.register<CheckBuildEnv>("checkBuildEnv") {
    group = "verification"
    description = "Fails early, with one message, when the JDK, SDK, compile SDK or aapt2 is missing."
    compileSdk.set(libs.versions.compileSdk.map(String::toInt))
    buildTools.set(libs.versions.buildTools)
    javaSpecificationVersion.set(providers.systemProperty("java.specification.version"))
    osArch.set(providers.systemProperty("os.arch"))
    javaHome.set(providers.systemProperty("java.home"))
    termuxPrefix.set(providers.environmentVariable("PREFIX"))
    sdkDir.set(providers.provider { configuredSdkDir() })
    aapt2Override.set(providers.gradleProperty("android.aapt2FromMavenOverride"))
    localProperties.from(layout.projectDirectory.file("local.properties"))
}

// A broken environment should fail with the message above before AGP's generic
// "SDK location not found" or a missing android.jar shows up in a task.
tasks.named("preBuild") {
    dependsOn("checkBuildEnv")
}

// Maven Central requires a PGP signature on every file. The keys stay out of the
// repository: CI supplies them as secrets and a local release can export them
// into the environment. Without a key the build still works, which is what
// JitPack and `publishToMavenLocal` rely on.
val signingKey = providers.environmentVariable("SIGNING_KEY")
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").getOrElse("")

signing {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword)
    }
}

nmcpAggregation {
    centralPortal {
        username.set(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password.set(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
        // AUTOMATIC publishes as soon as the portal has validated the bundle.
        // 0.2.0 was released by hand first and validated without a fix, so a
        // release now needs only the tag. Use USER_MANAGED to go back to a
        // draft that a human presses Publish on.
        publishingType.set("AUTOMATIC")
    }
}

dependencies {
    // The single-project form of the aggregation: the upload task otherwise
    // collects the subprojects' publications, and there are none here.
    add("nmcpAggregation", project(":"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "android-accessible-terminal"
                pom {
                    name.set("android-accessible-terminal")
                    description.set(
                        "A native Android terminal view that publishes its grid to " +
                            "accessibility services as rows, with an editable prompt node."
                    )
                    url.set("https://github.com/MBanucu/android-accessible-terminal")
                    licenses {
                        license {
                            name.set("GNU General Public License v3.0 or later")
                            url.set("https://www.gnu.org/licenses/gpl-3.0-standalone.html")
                        }
                    }
                    developers {
                        developer {
                            id.set("MBanucu")
                            name.set("MBanucu")
                            url.set("https://github.com/MBanucu")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/MBanucu/android-accessible-terminal.git")
                        developerConnection.set("scm:git:ssh://git@github.com/MBanucu/android-accessible-terminal.git")
                        url.set("https://github.com/MBanucu/android-accessible-terminal")
                    }
                }
            }
        }
    }

    // The publication is created above, and does not exist while the build script
    // is evaluated, so signing is wired here rather than in the `signing` block.
    signing {
        if (signingKey.isPresent) sign(publishing.publications)
    }
}
