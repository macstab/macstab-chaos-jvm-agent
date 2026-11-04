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
        classpath("org.jacoco:org.jacoco.core:0.8.12")
    }
}

plugins {
    base
    id("com.gradleup.shadow") version "8.3.9" apply false
    id("info.solidsoft.pitest") version "1.19.0" apply false
    id("org.cyclonedx.bom") version "3.5.1" apply false
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
    "sb3-fat-jar-probe",
    "sb3-retry-resilience-test",
    "sb4-sla-validation-test",
    "sb4-virtual-thread-pinning",
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
    "chaos-agent-api" to (0.81 to 0.73),
    "chaos-agent-bootstrap" to (0.79 to 0.64),
    "chaos-agent-core" to (0.83 to 0.75),
    "chaos-agent-instrumentation-jdk" to (0.81 to 0.59),
    "chaos-agent-micrometer" to (0.89 to 0.60),
    "chaos-agent-opentelemetry" to (0.85 to 0.60),
    "chaos-agent-micronaut-integration" to (0.85 to 0.70),
    "chaos-agent-quarkus-extension" to (0.70 to 0.60),
    "chaos-agent-quarkus-extension-deployment" to (0.38 to 0.0),
    "chaos-agent-quarkus-management" to (0.80 to 0.60),
    "chaos-agent-quarkus-management-deployment" to (0.80 to 0.0),
    "chaos-agent-spring-boot-common" to (0.60 to 0.50),
    "chaos-agent-spring-boot3-starter" to (0.68 to 0.73),
    "chaos-agent-spring-boot3-test-starter" to (0.84 to 0.53),
    "chaos-agent-spring-boot4-starter" to (0.68 to 0.73),
    "chaos-agent-spring-boot4-test-starter" to (0.84 to 0.53),
    "chaos-agent-startup-config" to (0.81 to 0.66),
    "chaos-agent-testkit" to (0.84 to 0.79),
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

    if (name in agentModules) {
        val childCoverage = rootProject.layout.buildDirectory.file("jacoco/child-jvms-remapped.exec")
        tasks.withType<JacocoReportBase>().configureEach {
            dependsOn(":childJvmCoverage")
            executionData(childCoverage)
            if (project.name == "chaos-agent-instrumentation-jdk") {
                classDirectories.setFrom(
                    classDirectories.files.map { fileTree(it) { exclude(inlinedAdvice) } },
                )
            }
        }
    }

    coverageFloors[name]?.let { (line, branch) ->
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
    }

    // Publishing + signing for library modules only.
    if (project.name !in nonPublishableModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    // The agent jar publishes its shaded jar instead (see chaos-agent-bootstrap).
                    if (project.name != "chaos-agent-bootstrap") {
                        from(components["java"])
                    }

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
    // Both are inputs: without them the task counted as up to date and kept data remapped onto
    // classes that no longer exist.
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
// (adding one breaks exhaustive switches). `check` runs `apiCheck`, which fails when the compiled
// API differs from the file; `./gradlew apiDump` records an intended change, which then shows in
// review. Types annotated @Internal are left out. The internal implementation modules (core,
// instrumentation, the agent jar) are not API (docs/configuration-reference.md §14.2).
val internalModules = setOf("chaos-agent-core", "chaos-agent-instrumentation-jdk", "chaos-agent-bootstrap")

fun renderApi(classesDir: File): String {
    val internal = "Lcom/macstab/chaos/jvm/api/Internal;"
    val access = { flags: Int ->
        buildList {
            if (flags and org.objectweb.asm.Opcodes.ACC_PUBLIC != 0) add("public")
            if (flags and org.objectweb.asm.Opcodes.ACC_PROTECTED != 0) add("protected")
            if (flags and org.objectweb.asm.Opcodes.ACC_STATIC != 0) add("static")
            if (flags and org.objectweb.asm.Opcodes.ACC_FINAL != 0) add("final")
            if (flags and org.objectweb.asm.Opcodes.ACC_ABSTRACT != 0) add("abstract")
        }.joinToString(" ")
    }
    val visible = { flags: Int ->
        flags and (org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_PROTECTED) != 0 &&
            flags and org.objectweb.asm.Opcodes.ACC_SYNTHETIC == 0
    }
    class Type(val name: String, val text: String, val outer: String?, var flags: Int, val internal: Boolean)
    val types = mutableMapOf<String, Type>()
    classesDir.walk().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }.forEach { file ->
        val reader = org.objectweb.asm.ClassReader(file.readBytes())
        val lines = mutableListOf<String>()
        var flags = reader.access
        var outer: String? = null
        var isInternal = false
        val permits = mutableListOf<String>()
        reader.accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
                if (descriptor == internal) isInternal = true
                return null
            }
            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, innerAccess: Int) {
                if (name == reader.className) {
                    flags = innerAccess
                    outer = outerName
                }
            }
            override fun visitPermittedSubclass(permittedSubclass: String) {
                permits += permittedSubclass
            }
            override fun visitField(fieldAccess: Int, name: String, descriptor: String, signature: String?, value: Any?): org.objectweb.asm.FieldVisitor? {
                if (visible(fieldAccess)) lines += "  ${access(fieldAccess)} field $name $descriptor"
                return null
            }
            override fun visitMethod(methodAccess: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): org.objectweb.asm.MethodVisitor? {
                if (visible(methodAccess) && methodAccess and org.objectweb.asm.Opcodes.ACC_BRIDGE == 0) {
                    lines += "  ${access(methodAccess)} method $name$descriptor"
                }
                return null
            }
        }, org.objectweb.asm.ClassReader.SKIP_CODE)
        val kind = when {
            flags and org.objectweb.asm.Opcodes.ACC_ANNOTATION != 0 -> "@interface"
            flags and org.objectweb.asm.Opcodes.ACC_INTERFACE != 0 -> "interface"
            flags and org.objectweb.asm.Opcodes.ACC_ENUM != 0 -> "enum"
            reader.superName == "java/lang/Record" -> "record"
            else -> "class"
        }
        val header = buildString {
            append(access(flags and org.objectweb.asm.Opcodes.ACC_INTERFACE.inv() and org.objectweb.asm.Opcodes.ACC_ABSTRACT.let { if (kind == "interface" || kind == "@interface") it.inv() else -1 }))
            append(" ").append(kind).append(" ").append(reader.className)
            if (reader.superName != null && reader.superName != "java/lang/Object" && kind == "class") append(" extends ").append(reader.superName)
            if (reader.interfaces.isNotEmpty()) append(" implements ").append(reader.interfaces.sorted().joinToString(", "))
            if (permits.isNotEmpty()) append(" permits ").append(permits.sorted().joinToString(", "))
        }
        types[reader.className] = Type(reader.className, (listOf(header) + lines.sorted()).joinToString("\n"), outer, flags, isInternal)
    }
    // A type is API when it and every type enclosing it are visible and not @Internal.
    fun exported(type: Type): Boolean =
        visible(type.flags) && !type.internal && (type.outer == null || types[type.outer]?.let(::exported) ?: false)
    return types.values.filter(::exported).sortedBy { it.name }.joinToString("\n\n", postfix = "\n") { it.text }
}

subprojects {
    if (name in nonPublishableModules || name in internalModules) return@subprojects
    val module = name
    plugins.withType<JavaPlugin> {
        val classes = layout.buildDirectory.dir("classes/java/main")
        val apiFile = layout.projectDirectory.file("api/$module.api")
        tasks.register("apiDump") {
            group = "verification"
            description = "Records the public API signatures in api/$module.api."
            dependsOn("classes")
            inputs.dir(classes).withPropertyName("classes")
            outputs.file(apiFile)
            doLast {
                apiFile.asFile.parentFile.mkdirs()
                apiFile.asFile.writeText(renderApi(classes.get().asFile))
            }
        }
        val apiCheck = tasks.register("apiCheck") {
            group = "verification"
            description = "Fails when the public API differs from api/$module.api."
            dependsOn("classes")
            inputs.dir(classes).withPropertyName("classes")
            inputs.file(apiFile).withPropertyName("committedApi").optional()
            val marker = layout.buildDirectory.file("api/checked")
            outputs.file(marker)
            doLast {
                val committed = apiFile.asFile
                val actual = renderApi(classes.get().asFile)
                if (!committed.isFile || committed.readText() != actual) {
                    val expectedLines = if (committed.isFile) committed.readLines().toSet() else emptySet()
                    val actualLines = actual.lines().toSet()
                    val removed = (expectedLines - actualLines).take(20).joinToString("\n") { "- $it" }
                    val added = (actualLines - expectedLines).take(20).joinToString("\n") { "+ $it" }
                    throw GradleException(
                        "The public API of $module changed. If intended, record it with " +
                            "./gradlew :$module:apiDump and review the diff of api/$module.api.\n$removed\n$added",
                    )
                }
                marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok") }
            }
        }
        tasks.named("check") { dependsOn(apiCheck) }
    }
}

// ── Mutation testing ─────────────────────────────────────────────────────────────────────────────
// `./gradlew pitest` mutates the bytecode of the modules whose behaviour is decided in plain Java
// (the API's validation, the core's evaluation, the plan parser) and runs their unit tests against
// each mutant: a surviving mutant is a behaviour no test pins down. Too slow for every build, so it
// is not part of `check`; the threshold is a ratchet like the coverage floors, set a little below
// the measured score and only ever raised. Reports: <module>/build/reports/pitest/index.html.
val mutationFloors = mapOf(
    "chaos-agent-api" to 66,
    "chaos-agent-core" to 59,
    "chaos-agent-startup-config" to 70,
)
subprojects {
    val floor = mutationFloors[name] ?: return@subprojects
    apply(plugin = "info.solidsoft.pitest")
    extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
        pitestVersion.set("1.30.1")
        junit5PluginVersion.set("1.2.3")
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
    }
}
