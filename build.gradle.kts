import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.gradle.jvm.toolchain.JavaLanguageVersion

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.spotless.plugin)
        classpath(libs.jacoco.core)
    }
}

plugins {
    base
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.pitest) apply false
    alias(libs.plugins.cyclonedx) apply false
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.japicmp) apply false
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
val jspecify = libs.jspecify
val jacocoVersion = libs.versions.jacoco.get()
val errorProneCore = libs.error.prone.core
val nullaway = libs.nullaway
val jsr305 = libs.jsr305
val pitestToolVersion = libs.versions.pitest.get()
val pitestJunit5ToolVersion = libs.versions.pitestJunit5.get()

// Modules that are for demonstration / measurement only and MUST NOT be published.
val nonPublishableModules = setOf(
    "chaos-agent-benchmarks",
    "chaos-agent-examples",
    "sb3-actuator-live-chaos",
    "sb3-fat-jar-probe",
    "sb3-retry-resilience-test",
    "sb4-sla-validation-test",
    "sb4-virtual-thread-pinning",
)

// The description each published module's POM carries; a published module without one fails the
// build.
val moduleDescriptions = mapOf(
    "chaos-agent-api" to "Macstab chaos agent: the public API (plans, scenarios, selectors, effects, the control plane)",
    "chaos-agent-bom" to "Macstab chaos agent: the bill of materials that aligns the versions of every published module",
    "chaos-agent-bootstrap" to "Macstab chaos agent: the Java agent jar (-javaagent or self-attach), with its dependencies shaded",
    "chaos-agent-core" to "Macstab chaos agent: the runtime that matches scenarios and applies their effects (internal)",
    "chaos-agent-instrumentation-jdk" to "Macstab chaos agent: the JDK and library hooks the agent weaves (internal)",
    "chaos-agent-micrometer" to "Macstab chaos agent: Micrometer metrics for chaos events",
    "chaos-agent-micronaut-integration" to "Macstab chaos agent: Micronaut integration (management endpoint, test extension)",
    "chaos-agent-opentelemetry" to "Macstab chaos agent: OpenTelemetry spans and metrics for chaos events",
    "chaos-agent-quarkus-extension" to "Macstab chaos agent: Quarkus extension (runtime and test support)",
    "chaos-agent-quarkus-extension-deployment" to "Macstab chaos agent: the build steps of the Quarkus extension",
    "chaos-agent-quarkus-management" to "Macstab chaos agent: the Quarkus /q/chaos management route",
    "chaos-agent-quarkus-management-deployment" to "Macstab chaos agent: the build steps of the Quarkus management route",
    "chaos-agent-spring-boot-common" to "Macstab chaos agent: Spring Boot support shared by the Boot 3 and Boot 4 starters",
    "chaos-agent-spring-boot3-starter" to "Macstab chaos agent: Spring Boot 3 starter",
    "chaos-agent-spring-boot3-test-starter" to "Macstab chaos agent: Spring Boot 3 test starter",
    "chaos-agent-spring-boot4-starter" to "Macstab chaos agent: Spring Boot 4 starter",
    "chaos-agent-spring-boot4-test-starter" to "Macstab chaos agent: Spring Boot 4 test starter",
    "chaos-agent-startup-config" to "Macstab chaos agent: the plan format (JSON reader, writer and schema) and the startup configuration",
    "chaos-agent-testkit" to "Macstab chaos agent: JUnit 5 test kit (@WithChaos, sessions, assertions)",
)

// The modules whose classes run in the child JVMs of chaos-agent-bootstrap's tests, inside the
// shaded agent jar. Their coverage includes what those JVMs executed (see childJvmCoverage).
val agentModules =
    listOf(
        "chaos-agent-api",
        "chaos-agent-core",
        "chaos-agent-instrumentation-jdk",
        "chaos-agent-startup-config",
        "chaos-agent-bootstrap",
    )

// Advice bodies are inlined into the JDK and library classes they instrument and never run as the
// advice classes JaCoCo measures; HookCoverageIntegrationTest requires every one of them to match
// a method, and the child-JVM tests exercise what they do. Left out of the coverage denominator.
val inlinedAdvice =
    listOf(
        "**/instrumentation/*Advice.class",
        "**/instrumentation/*Advice$*.class",
    )

// Coverage floor per module: (line, branch) covered ratio enforced by `check`. A ratchet: set a
// little below the measured value and raised as tests are added, never lowered to make a change
// pass. The agent modules count what their unit tests and the child JVMs executed (agentModules).
val coverageFloors = mapOf(
    "chaos-agent-api" to (0.98 to 0.99),
    "chaos-agent-bootstrap" to (0.96 to 0.96),
    "chaos-agent-core" to (0.96 to 0.94),
    "chaos-agent-instrumentation-jdk" to (0.93 to 0.97),
    "chaos-agent-micrometer" to (0.99 to 0.99),
    "chaos-agent-opentelemetry" to (0.99 to 0.99),
    "chaos-agent-micronaut-integration" to (0.96 to 0.96),
    "chaos-agent-quarkus-extension" to (0.92 to 0.91),
    "chaos-agent-quarkus-extension-deployment" to (0.99 to 0.00),
    "chaos-agent-quarkus-management" to (0.99 to 0.94),
    "chaos-agent-quarkus-management-deployment" to (0.99 to 0.00),
    "chaos-agent-spring-boot-common" to (0.98 to 0.96),
    "chaos-agent-spring-boot3-starter" to (0.84 to 0.99),
    "chaos-agent-spring-boot3-test-starter" to (0.99 to 0.99),
    "chaos-agent-spring-boot4-starter" to (0.84 to 0.99),
    "chaos-agent-spring-boot4-test-starter" to (0.99 to 0.99),
    "chaos-agent-startup-config" to (0.93 to 0.94),
    "chaos-agent-testkit" to (0.99 to 0.99),
)

// The BOM (chaos-agent-bom) is a java-platform: it has no code, so none of the Java configuration
// below applies to it, only the publishing further down.
val bomModule = "chaos-agent-bom"

subprojects {
    if (name == bomModule) {
        apply(plugin = "java-platform")
        return@subprojects
    }
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
        toolVersion = jacocoVersion
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

    // -PtestJavaVersion=21 runs the tests on another installed JDK (bytecode stays --release 21).
    // Used by the JDK matrix in CI; behaviour differs between JDK 21 and 25 in places the agent
    // instruments (Thread.sleep, LockSupport, virtual-thread pinning).
    val testJavaVersion = providers.gradleProperty("testJavaVersion").orNull
    val javaToolchains = extensions.getByType<JavaToolchainService>()
    tasks.withType<Test>().configureEach {
        if (testJavaVersion != null) {
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(testJavaVersion.toInt()))
                },
            )
        }
        useJUnitPlatform()
        // A hung test fails after 5 minutes (a test or lifecycle method without its own @Timeout),
        // and a hung test task after 25, instead of holding CI until the job's limit.
        systemProperty("junit.jupiter.execution.timeout.default", "5m")
        timeout.set(java.time.Duration.ofMinutes(25))
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
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    if (name == "chaos-agent-instrumentation-jdk") {
        tasks.withType<JacocoReportBase>().configureEach {
            classDirectories.setFrom(
                classDirectories.files.map { fileTree(it) { exclude(inlinedAdvice) } },
            )
        }
    }

    // The agent modules' floors count what the child JVMs executed too: they are verified in the
    // root project (below), so `:<module>:check` runs only the module's own tests.
    coverageFloors[name]?.takeIf { name !in agentModules }?.let { (line, branch) ->
        val verification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            dependsOn(tasks.named("test"))
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = line.toBigDecimal()
                    }
                    limit {
                        counter = "BRANCH"
                        minimum = branch.toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check") { dependsOn(verification) }
    }

    tasks.withType<Jar>().configureEach {
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
        // Every jar carries the project licence. The shaded agent jar adds it through its own spec
        // (chaos-agent-bootstrap), next to the merged NOTICE of its bundled libraries.
        if (this !is com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar) {
            metaInf { from(rootProject.file("LICENSE")) }
        }
    }

}

// Publishing + signing for library modules (and the BOM) only.
subprojects {
    if (project.name !in nonPublishableModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    // The agent jar publishes its shaded jar instead (see chaos-agent-bootstrap).
                    when (project.name) {
                        bomModule -> from(components["javaPlatform"])
                        "chaos-agent-bootstrap" -> {}
                        else -> from(components["java"])
                    }

                    pom {
                        name.set(project.name)
                        description.set(
                            moduleDescriptions[project.name]
                                ?: throw GradleException("${project.name} is published: add its POM description to moduleDescriptions"),
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
                            connection.set("scm:git:https://github.com/macstab/macstab-chaos-jvm-agent.git")
                            developerConnection.set("scm:git:ssh://git@github.com/macstab/macstab-chaos-jvm-agent.git")
                            url.set("https://github.com/macstab/macstab-chaos-jvm-agent")
                        }
                    }
                }
            }

            repositories {
                // Every publish also lands here, so the release workflow attests the very files it
                // deployed (build provenance and the agent's SBOM).
                maven {
                    name = "Staging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
                }

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

        // A release is signed with an in-memory key (signingKey / signingPassword, ASCII-armoured;
        // the release workflow passes them as ORG_GRADLE_PROJECT_* variables) or with the key ring
        // named by signing.keyId / signing.password / signing.secretKeyRingFile. Snapshots are not
        // signed.
        val signingKey = providers.gradleProperty("signingKey").orNull?.takeIf { it.isNotBlank() }
        val signingPassword = providers.gradleProperty("signingPassword").orNull.orEmpty()
        val keyRing = listOf("signing.keyId", "signing.password", "signing.secretKeyRingFile")
            .all { providers.gradleProperty(it).isPresent }
        val signed = signingKey != null || keyRing
        val snapshot = version.toString().endsWith("SNAPSHOT")
        extensions.configure<SigningExtension> {
            if (signingKey != null) {
                useInMemoryPgpKeys(signingKey, signingPassword)
            }
            if (signed) {
                sign(extensions.getByType(PublishingExtension::class).publications["maven"])
            }
        }

        tasks.withType<Sign>().configureEach {
            onlyIf("a release, not a snapshot") { !snapshot }
        }

        // A release is published signed or not at all: an unsigned release is refused here for every
        // repository (Maven Central, GitHub Packages, the staging directory the release attests),
        // before anything is uploaded. A missing or empty signing key is no key. Snapshots and
        // publishToMavenLocal are not refused.
        tasks.withType<PublishToMavenRepository>().configureEach {
            doFirst {
                if (!snapshot && !signed) {
                    throw GradleException(
                        "Release $version must be signed: set signingKey and signingPassword, " +
                            "or signing.keyId, signing.password and signing.secretKeyRingFile (RELEASE.md).",
                    )
                }
            }
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
    gradleVersion = "9.4.0"
    distributionType = DistributionType.ALL
    // The wrapper refuses a downloaded distribution with another checksum.
    distributionSha256Sum = "b21468753cb43c167738ee04f10c706c46459cf8f8ae6ea132dc9ce589a261f2"
}

// One coverage report over the modules with a coverage floor, built from their own reports and the
// child JVMs' execution data, so it counts what the floors count (inlined advice excluded).
// XML and HTML land in build/reports/jacoco/aggregated/; CI uploads them as an artifact.
apply(plugin = "jacoco")
extensions.configure<JacocoPluginExtension> {
    toolVersion = jacocoVersion
}

// What the child JVMs executed, keyed to the module classes (childJvmCoverage below).
val childCoverage = layout.buildDirectory.file("jacoco/child-jvms-remapped.exec")

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "Aggregates the coverage reports of the modules that have a coverage floor."
    subprojects.filter { it.name in coverageFloors }.forEach { sub ->
        val report = sub.tasks.named<JacocoReport>("jacocoTestReport")
        dependsOn(report)
        executionData.from(report.map { it.executionData })
        classDirectories.from(report.map { it.classDirectories })
        sourceDirectories.from(report.map { it.sourceDirectories })
    }
    // The agent modules' share of what the bootstrap tests' child JVMs executed.
    dependsOn(":childJvmCoverage")
    executionData.from(childCoverage)
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

// The BOM constrains every other published module to this build's version.
project(":$bomModule") {
    val bom = this
    dependencies.constraints {
        publishableSubprojects.filter { it != bom }.forEach { add("api", it) }
    }
}

tasks.register<Javadoc>("aggregatedJavadoc") {
    group = "documentation"
    description = "Aggregate Javadoc from all publishable subprojects into one site."

    setDestinationDir(layout.buildDirectory.dir("docs/aggregated-javadoc").get().asFile)
    title = "macstab-chaos-jvm-agent ${project.version} API"

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

// Asks the Central Portal to publish the staged deployment (RELEASE.md).
tasks.register("releaseToCentral") {
    group = "publishing"
    description = "Publish staged deployment to Maven Central via the Central Portal API."
    // Read when the task is configured: the action below touches no project state.
    val centralUsername = providers.gradleProperty("ossrhUsername")
    val centralPassword = providers.gradleProperty("ossrhPassword")
    val releaseGroup = project.group.toString()
    val releaseVersion = project.version.toString()

    doLast {
        val username = centralUsername.orNull
        val password = centralPassword.orNull

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            logger.warn("Maven Central credentials not configured.")
            logger.warn("Add ossrhUsername / ossrhPassword to ~/.gradle/gradle.properties.")
            throw GradleException("Maven Central credentials missing. Cannot release.")
        }

        logger.lifecycle("Triggering Central Portal manual upload API...")
        logger.lifecycle("  Group:   $releaseGroup")
        logger.lifecycle("  Version: $releaseVersion")

        val apiUrl = "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/com.macstab"

        // The credentials go to curl through its config on stdin, not the command line, where every
        // local user could read them; --fail-with-body turns an HTTP error into a failed exit.
        val process = ProcessBuilder("curl", "--fail-with-body", "--config", "-", "-X", "POST", apiUrl)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.outputStream.bufferedWriter().use { config ->
            val escaped = "$username:$password".replace("\\", "\\\\").replace("\"", "\\\"")
            config.write("user = \"$escaped\"\n")
        }

        val exitCode = process.waitFor()

        if (exitCode == 0) {
            logger.lifecycle("Triggered publish to Maven Central.")
            logger.lifecycle("Artifacts sync to Maven Central in ~10-30 minutes.")
            logger.lifecycle("Check: https://central.sonatype.com/artifact/$releaseGroup")
        } else {
            logger.error("Failed to publish. Manual fallback:")
            logger.error("  1. https://central.sonatype.com/ -> Deployments")
            logger.error("  2. Find $releaseGroup")
            logger.error("  3. Click Publish")
            throw GradleException("Failed to publish to Maven Central (exit code: $exitCode)")
        }
    }
}

// The agent modules' coverage floors, verified here with what the child JVMs of the bootstrap tests
// executed: those JVMs run the agent jar, so its modules' floors count both. `./gradlew check` (and
// `build`) runs them; `:<module>:check` does not start the child-JVM suite.
agentModules.forEach { module ->
    val (line, branch) = coverageFloors.getValue(module)
    val report = project(":$module").tasks.named<JacocoReport>("jacocoTestReport")
    val verification = tasks.register<JacocoCoverageVerification>("${module}CoverageVerification") {
        group = "verification"
        description = "Checks the coverage floor of $module, child-JVM execution included."
        dependsOn(report, ":childJvmCoverage")
        executionData.from(report.map { it.executionData }, childCoverage)
        classDirectories.from(report.map { it.classDirectories })
        sourceDirectories.from(report.map { it.sourceDirectories })
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = line.toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = branch.toBigDecimal()
                }
            }
        }
    }
    tasks.named("check") { dependsOn(verification) }
}

// The child JVMs run the shaded agent jar. Shading re-encodes every class (the constant pool is
// reordered, the bytecode is the same), so JaCoCo's class ids differ from the module classes' and
// their execution data would be ignored. This task keys each class's execution data to the id of
// the module class of the same name. JaCoCo checks the probe layout when it analyses a class and
// fails the report if the data does not fit, so a class whose bytecode differed cannot slip through.
tasks.register("childJvmCoverage") {
    description = "Remaps the child JVMs' coverage of the shaded agent jar onto the module classes."
    dependsOn(":chaos-agent-bootstrap:test")
    val source = project(":chaos-agent-bootstrap").layout.buildDirectory.file("jacoco/child-jvms.exec")
    val target = layout.buildDirectory.file("jacoco/child-jvms-remapped.exec")
    val classDirs = agentModules.map { project(":$it").layout.buildDirectory.dir("classes/java/main") }
    // Both are inputs, so new execution data or changed module classes remap again.
    inputs.files(source).withPropertyName("childExecutionData").optional()
    inputs.files(classDirs).withPropertyName("moduleClasses")
    outputs.file(target)
    doLast {
        val out = target.get().asFile
        out.parentFile.mkdirs()
        val loader = org.jacoco.core.tools.ExecFileLoader()
        val src = source.get().asFile
        if (src.exists()) {
            loader.load(src)
        }
        val classes = mutableMapOf<String, ByteArray>()
        classDirs.map { it.get().asFile }.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown().filter { it.extension == "class" }.forEach { file ->
                classes[file.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".class")] = file.readBytes()
            }
        }
        val remapped = org.jacoco.core.data.ExecutionDataStore()
        loader.executionDataStore.contents.forEach { data ->
            classes[data.name]?.let { bytes ->
                remapped.put(
                    org.jacoco.core.data.ExecutionData(
                        org.jacoco.core.internal.data.CRC64.classId(bytes), data.name, data.probes.clone(),
                    ),
                )
            }
        }
        out.outputStream().use { stream ->
            val writer = org.jacoco.core.data.ExecutionDataWriter(stream)
            loader.sessionInfoStore.accept(writer)
            remapped.accept(writer)
        }
    }
}

// ── Public API signatures ────────────────────────────────────────────────────────────────────────
// Each API module commits the signatures of its public API in api/<module>.api: every public or
// protected class, field, constructor and method its class files expose, with JVM descriptors (so a
// change of a parameter, return or field type shows), and the permitted subtypes of sealed types
// (adding one breaks exhaustive switches). The descriptors are erased: generic type arguments,
// annotations and the order of enum constants are not recorded. `check` runs `apiCheck`, which fails
// when the compiled API differs from the file and lists the difference per type; `./gradlew apiDump`
// records an intended change, which then shows in review. Types and members annotated @Internal are
// left out. The tasks live in buildSrc (com.macstab.chaos.gradle). Not API: the internal
// implementation modules (core, instrumentation, the agent jar; docs/configuration-reference.md
// §14.2) and the Quarkus deployment modules, whose build steps only Quarkus calls.
val nonApiModules = setOf(
    "chaos-agent-core",
    "chaos-agent-instrumentation-jdk",
    "chaos-agent-bootstrap",
    "chaos-agent-quarkus-extension-deployment",
    "chaos-agent-quarkus-management-deployment",
)

// The API modules the release named by apiBaseline did not contain yet (gradle.properties): their
// japicmp check is skipped. Every other module's baseline must resolve.
val absentFromBaseline =
    rootProject.property("apiBaselineAbsentModules").toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

subprojects {
    if (name in nonPublishableModules || name in nonApiModules) return@subprojects
    val module = name
    plugins.withType<JavaPlugin> {
        val classes = tasks.named<JavaCompile>("compileJava").flatMap { it.destinationDirectory }
        val apiFile = layout.projectDirectory.file("api/$module.api")
        tasks.register<com.macstab.chaos.gradle.ApiDumpTask>("apiDump") {
            group = "verification"
            description = "Records the public API signatures in api/$module.api."
            this.classes.set(classes)
            this.apiFile.set(apiFile)
        }
        val apiCheck = tasks.register<com.macstab.chaos.gradle.ApiCheckTask>("apiCheck") {
            group = "verification"
            description = "Fails when the public API differs from api/$module.api."
            this.classes.set(classes)
            committedApi.from(apiFile)
            moduleName.set(module)
            marker.set(layout.buildDirectory.file("api/checked"))
        }
        tasks.named("check") { dependsOn(apiCheck) }

        // Binary compatibility with the release named by apiBaseline (gradle.properties), checked by
        // japicmp; the report lands in build/reports/japicmp/. A module the baseline release did not
        // contain is listed in apiBaselineAbsentModules and skipped; for every other module a baseline
        // that cannot be resolved fails the check instead of skipping it. A binary-incompatible change
        // fails the build unless apiBreaksAccepted is true, which a major release sets while it breaks
        // the baseline's API on purpose. Source compatibility is apiCheck's part.
        apply(plugin = "me.champeau.gradle.japicmp")
        val baselineVersion = rootProject.property("apiBaseline").toString()
        val baselineArtifact = "${project.group}:$module:$baselineVersion"
        val baselineJar = configurations.detachedConfiguration(dependencies.create("$baselineArtifact@jar"))
            .incoming.artifactView { lenient(true) }.files
        val baselineClasspath = configurations.detachedConfiguration(dependencies.create(baselineArtifact))
            .incoming.artifactView { lenient(true) }.files
        val breaksAccepted = rootProject.property("apiBreaksAccepted").toString().toBoolean()
        val absent = module in absentFromBaseline
        val jar = tasks.named<Jar>("jar")
        val japicmp = tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("japicmp") {
            group = "verification"
            description = "Compares the binary API of $module with release $baselineVersion."
            oldArchives.from(baselineJar)
            oldClasspath.from(baselineClasspath)
            newArchives.from(jar)
            newClasspath.from(jar, configurations.named("runtimeClasspath"))
            onlyBinaryIncompatibleModified.set(true)
            failOnModification.set(!breaksAccepted)
            ignoreMissingClasses.set(true)
            annotationExcludes.add("@com.macstab.chaos.jvm.api.Internal")
            txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/$module.txt"))
            htmlOutputFile.set(layout.buildDirectory.file("reports/japicmp/$module.html"))
            onlyIf("release $baselineVersion contains $module (apiBaselineAbsentModules)") { !absent }
            doFirst {
                if (baselineJar.isEmpty) {
                    throw GradleException(
                        "The baseline $baselineArtifact could not be resolved. If release $baselineVersion " +
                            "did not contain $module, add it to apiBaselineAbsentModules (gradle.properties).",
                    )
                }
            }
        }
        tasks.named("check") { dependsOn(japicmp) }
    }
}

// ── Mutation testing ─────────────────────────────────────────────────────────────────────────────
// `./gradlew pitest` mutates the bytecode of the modules whose behaviour is decided in plain Java
// (the API's validation, the core's evaluation, the plan parser) and runs their unit tests against
// each mutant: a surviving mutant is a behaviour no test pins down. Too slow for every build, so it
// is not part of `check` and runs on demand; each threshold is set a little below the measured
// score. Reports: <module>/build/reports/pitest/index.html.
val mutationFloors = mapOf(
    "chaos-agent-api" to 95,
    "chaos-agent-core" to 83,
    "chaos-agent-startup-config" to 94,
)
subprojects {
    val floor = mutationFloors[name] ?: return@subprojects
    apply(plugin = "info.solidsoft.pitest")
    extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
        pitestVersion.set(pitestToolVersion)
        junit5PluginVersion.set(pitestJunit5ToolVersion)
        targetClasses.set(listOf("com.macstab.chaos.jvm.*"))
        threads.set(Runtime.getRuntime().availableProcessors())
        outputFormats.set(listOf("HTML", "XML"))
        timestampedReports.set(false)
        mutationThreshold.set(floor)
        timeoutConstInMillis.set(10_000)
        jvmArgs.set(listOf("-XX:+EnableDynamicAgentLoading"))
    }
}

// ── Software bill of materials ───────────────────────────────────────────────────────────────────
// Every published module gets a CycloneDX SBOM of its runtime dependencies. The agent jar's is the
// one that matters most: ByteBuddy and Jackson are shaded into it, so no dependency scanner can
// see them in its (empty) POM.
subprojects {
    if (name in nonPublishableModules) return@subprojects
    plugins.withType<JavaPlugin> {
        apply(plugin = "org.cyclonedx.bom")
        // What ships, not what the tests and probes use.
        tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
            includeConfigs.set(listOf("runtimeClasspath"))
        }
        // The SBOM ships with the artifact it describes, under the classifier the CycloneDX Maven
        // plugin uses, so a scanner reading the repository finds it next to the jar.
        val bom = tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom")
        plugins.withId("maven-publish") {
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().matching { it.name == "maven" }.configureEach {
                    artifact(bom.flatMap { it.jsonOutput }) {
                        classifier = "cyclonedx"
                        extension = "json"
                        builtBy(bom)
                    }
                }
            }
        }
    }
}

// ── Static analysis ──────────────────────────────────────────────────────────────────────────────
// Error Prone runs in every compilation; with -Werror its warnings fail the build like javac's.
// A finding is fixed, or suppressed where it stands with the reason; the few checks disabled below
// say why.
subprojects {
    plugins.withType<JavaPlugin> {
        apply(plugin = "net.ltgt.errorprone")
        dependencies {
            "errorprone"(errorProneCore)
            "errorprone"(nullaway)
            "compileOnly"(jspecify)
            // ByteBuddy's own nullness annotations refer to JSR-305's When; JSpecify mode reads
            // them, so the compiler needs the class (compile time only).
            "compileOnly"(jsr305)
            "testCompileOnly"(jspecify)
        }
        tasks.withType<JavaCompile>().configureEach {
            (options as ExtensionAware).extensions.configure<net.ltgt.gradle.errorprone.ErrorProneOptions> {
                disableWarningsInGeneratedCode.set(true)
                excludedPaths.set(".*/build/generated/.*")
                // NullAway: every reference in production code is non-null unless @Nullable says
                // otherwise (JSpecify), and the compiler proves it. Tests are not checked.
                if (!name.contains("Test")) {
                    option("NullAway:AnnotatedPackages", "com.macstab.chaos.jvm")
                    // JSpecify mode: nullness inside generics (Supplier<@Nullable T>) is checked too.
                    option("NullAway:JSpecifyMode", "true")
                    // JMH state is initialised in @Setup methods, not in constructors.
                    option("NullAway:CustomInitializerAnnotations", "org.openjdk.jmh.annotations.Setup")
                    error("NullAway")
                } else {
                    disable("NullAway")
                }
                // Style checks this codebase decides otherwise, on purpose: parentheses that make
                // precedence explicit, Boolean.FALSE passed as an Object sentinel, and statement
                // switches whose branches throw or fall through to shared code.
                disable("UnnecessaryParentheses", "BooleanLiteral", "RefactorSwitch")
                if (name.contains("Test")) {
                    // The tests and child-JVM probes do on purpose what these checks forbid in
                    // production code: resolve hosts, read the default-zone clocks and Date the
                    // agent skews, fire tasks under chaos without awaiting them, catch the
                    // failures they provoke, assert identity, and yield to force races.
                    disable(
                        "AddressSelection",
                        "JavaTimeDefaultTimeZone",
                        "JavaUtilDate",
                        "FutureReturnValueIgnored",
                        "EmptyCatch",
                        "ReferenceEquality",
                        "ThreadPriorityCheck",
                    )
                }
            }
        }
    }
}
