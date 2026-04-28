import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.gradle.jvm.toolchain.JavaLanguageVersion

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.diffplug.spotless:spotless-plugin-gradle:8.2.1")
    }
}

plugins {
    base
}

apply(plugin = "com.diffplug.spotless")

group = project.property("group").toString()
version = project.property("version").toString()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

val junitBom = libs.junit.bom
val junitPlatformLauncher = libs.junit.platform.launcher

// Modules that are for demonstration / measurement only and MUST NOT be published.
val nonPublishableModules = setOf(
    "chaos-agent-benchmarks",
    "chaos-agent-examples",
    "sb3-actuator-live-chaos",
    "sb3-retry-resilience-test",
    "sb4-sla-validation-test",
    "sb4-virtual-thread-pinning",
)

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
        withJavadocJar()
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-parameters",
                "-Xlint:all,-serial,-processing,-try",
                "-Xlint:-classfile",
                "-Werror",
            ),
        )
    }

    dependencies {
        "testRuntimeOnly"(platform(junitBom))
        "testRuntimeOnly"(junitPlatformLauncher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs(
            "-Djdk.attach.allowAttachSelf=true",
            "-XX:+EnableDynamicAgentLoading",
        )
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
        // Restrict jacoco to project classes. Without this the agent tries to
        // instrument JDK internals (e.g. sun/util/resources/*) whose class-file
        // version outruns the jacoco ASM bundled with Gradle.
        extensions.configure<JacocoTaskExtension> {
            includes = listOf("com.macstab.chaos.jvm.*")
        }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    tasks.withType<Jar>().configureEach {
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

    // Publishing + signing for library modules only.
    if (project.name !in nonPublishableModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])

                    pom {
                        name.set(project.name)
                        description.set(
                            "JVM in-process chaos engineering agent - bytecode-level fault injection for Java applications",
                        )
                        url.set("https://github.com/macstab/macstab-chaos-jvm-agent")

                        licenses {
                            license {
                                name.set("Apache License 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }

                        developers {
                            developer {
                                id.set("cschnapka")
                                name.set("Christian Schnapka")
                                email.set("christian.schnapka@macstab.com")
                                organization.set("Macstab GmbH")
                                organizationUrl.set("https://macstab.com")
                            }
                        }

                        scm {
                            connection.set("scm:git:git://github.com/macstab/macstab-chaos-jvm-agent.git")
                            developerConnection.set("scm:git:ssh://github.com/macstab/macstab-chaos-jvm-agent.git")
                            url.set("https://github.com/macstab/macstab-chaos-jvm-agent")
                        }
                    }
                }
            }

            repositories {
                val ghActor = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                val ghToken = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String?

                if (ghActor != null && ghToken != null) {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/macstab/macstab-chaos-jvm-agent")
                        credentials {
                            username = ghActor
                            password = ghToken
                        }
                    }
                }

                val ossrhUsername = project.findProperty("ossrhUsername") as String?
                val ossrhPassword = project.findProperty("ossrhPassword") as String?

                if (ossrhUsername != null && ossrhPassword != null) {
                    maven {
                        name = "OSSRH"
                        val releasesRepoUrl =
                            uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                        val snapshotsRepoUrl =
                            uri("https://central.sonatype.com/repository/maven-snapshots/")
                        url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
                        credentials {
                            username = ossrhUsername
                            password = ossrhPassword
                        }
                    }
                }
            }
        }

        extensions.configure<SigningExtension> {
            val signingKeyId = project.findProperty("signing.keyId") as String?
            val signingPassword = project.findProperty("signing.password") as String?
            val signingSecretKeyRingFile = project.findProperty("signing.secretKeyRingFile") as String?

            if (signingKeyId != null && signingPassword != null && signingSecretKeyRingFile != null) {
                val publishing = extensions.getByType(PublishingExtension::class)
                sign(publishing.publications["maven"])
            }
        }

        tasks.withType<Sign>().configureEach {
            onlyIf { !version.toString().endsWith("SNAPSHOT") }
        }
    }
}

configure<SpotlessExtension> {
    java {
        target("**/src/*/java/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("*.md", ".gitignore", "gradle/**/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = DistributionType.ALL
}

// Aggregated Jacoco report across every subproject that ran tests, emitted to
// build/reports/jacoco/aggregated/. XML is used by CI coverage badges and
// HTML is uploaded as a workflow artifact.
apply(plugin = "jacoco-report-aggregation")

val codeCoverageReport = configurations.create("codeCoverageReport") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

subprojects {
    plugins.withType<JacocoPlugin> {
        rootProject.dependencies.add(codeCoverageReport.name, rootProject.dependencies.project(this@subprojects.path))
    }
}

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "Aggregate Jacoco coverage from every subproject."

    val jacocoExecFiles = subprojects.flatMap { sub ->
        sub.tasks.withType<Test>().map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! }
    }
    executionData.setFrom(files(jacocoExecFiles).filter { it.exists() })

    subprojects.forEach { sub ->
        val javaExt = sub.extensions.findByType(JavaPluginExtension::class)
        if (javaExt != null) {
            sourceDirectories.from(javaExt.sourceSets["main"].allSource.srcDirs)
            classDirectories.from(javaExt.sourceSets["main"].output)
        }
        dependsOn(sub.tasks.matching { it.name == "test" })
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregated/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregated/jacoco.xml"))
    }
}

// Aggregated Javadoc across every publishable module, emitted to
// build/docs/aggregated-javadoc. Consumed by .github/workflows/deploy-javadoc.yml
// to publish API docs to GitHub Pages.
val publishableSubprojects = subprojects.filter { it.name !in nonPublishableModules }

tasks.register<Javadoc>("aggregatedJavadoc") {
    group = "documentation"
    description = "Aggregate Javadoc from all publishable subprojects into one site."

    setDestinationDir(layout.buildDirectory.dir("docs/aggregated-javadoc").get().asFile)
    title = "macstab macstab-chaos-jvm-agent ${project.version} API"

    publishableSubprojects.forEach { sub ->
        dependsOn(sub.tasks.matching { it.name == "compileJava" })
        val javaExt = sub.extensions.findByType(JavaPluginExtension::class)
        if (javaExt != null) {
            source += javaExt.sourceSets["main"].allJava
            classpath += javaExt.sourceSets["main"].compileClasspath
            classpath += javaExt.sourceSets["main"].output
        }
    }

    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
        links(
            "https://docs.oracle.com/en/java/javase/21/docs/api/",
        )
    }
}

// Task that prints release instructions (used by RELEASE.md).
tasks.register("releaseToCentral") {
    group = "publishing"
    description = "Publish staged deployment to Maven Central via the Central Portal API."

    doLast {
        val username = project.findProperty("ossrhUsername") as String?
        val password = project.findProperty("ossrhPassword") as String?

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            logger.warn("Maven Central credentials not configured.")
            logger.warn("Add ossrhUsername / ossrhPassword to ~/.gradle/gradle.properties.")
            throw GradleException("Maven Central credentials missing. Cannot release.")
        }

        logger.lifecycle("Triggering Central Portal manual upload API...")
        logger.lifecycle("  Group:   ${project.group}")
        logger.lifecycle("  Version: ${project.version}")

        val apiUrl = "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/com.macstab"

        val process = ProcessBuilder(
            "curl", "-u", "$username:$password",
            "-X", "POST",
            apiUrl,
        ).inheritIO().start()

        val exitCode = process.waitFor()

        if (exitCode == 0) {
            logger.lifecycle("Triggered publish to Maven Central.")
            logger.lifecycle("Artifacts sync to Maven Central in ~10-30 minutes.")
            logger.lifecycle("Check: https://central.sonatype.com/artifact/${project.group}")
        } else {
            logger.error("Failed to publish. Manual fallback:")
            logger.error("  1. https://central.sonatype.com/ -> Deployments")
            logger.error("  2. Find ${project.group}")
            logger.error("  3. Click Publish")
            throw GradleException("Failed to publish to Maven Central (exit code: $exitCode)")
        }
    }
}
