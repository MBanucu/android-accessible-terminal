// The library build. The JVM suites are plain Java with their own runner
// (bash terminal/test.sh), so nothing here depends on JUnit; this file only has
// to produce the artifacts Maven Central and JitPack serve.
plugins {
    id("com.android.library") version "8.7.3"
    `maven-publish`
    `signing`
    // Maven Central accepts uploads through the Central Portal API only (the
    // legacy OSSRH deploy protocol was retired in 2025). Nmcp stages the
    // publications, signs nothing itself, and uploads one bundle. It needs both
    // plugins when the build is a single project: the aggregation plugin is what
    // uploads, the other exposes this project's publications to it.
    id("com.gradleup.nmcp") version "1.6.1"
    id("com.gradleup.nmcp.aggregation") version "1.6.1"
}

group = "io.github.mbanucu"
// Central refuses to overwrite a version, so releases pass their version in:
//   ./gradlew publishAggregationToCentralPortal -PVERSION_NAME=0.2.0
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.0")

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
            // Both are Central requirements: a sources jar and a javadoc jar
            // next to every artifact.
            withSourcesJar()
            withJavadocJar()
        }
    }
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
        // USER_MANAGED leaves the deployment in the portal for a human to
        // release. Change to AUTOMATIC once the first release has gone through
        // validation without a manual fix.
        publishingType.set("USER_MANAGED")
    }
}

dependencies {
    // The single-project form of the aggregation: the upload task otherwise
    // collects the subprojects' publications, and there are none here.
    add("nmcpAggregation", project)
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
