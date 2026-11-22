// The library build. The JVM suites are plain Java with their own runner
// (bash terminal/test.sh), so nothing here depends on JUnit; this file only has
// to produce the AAR a consumer adds as a Gradle dependency.
plugins {
    id("com.android.library") version "8.7.3"
    `maven-publish`
}

group = "com.github.MBanucu"
// JitPack publishes the tag it was asked for; this is the fallback for a local
// `publishToMavenLocal`.
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0")

android {
    namespace = "io.github.mbanucu.terminal"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    // The sources keep the path the scripts and the tree use. Nothing needs a
    // conventional src/main/java, and moving them would break samples/build.sh,
    // which compiles this directory into each demo app.
    sourceSets["main"].apply {
        manifest.srcFile("terminal/AndroidManifest.xml")
        java.srcDirs("terminal/src/main/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
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
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/MBanucu/android-accessible-terminal.git")
                        url.set("https://github.com/MBanucu/android-accessible-terminal")
                    }
                }
            }
        }
    }
}
