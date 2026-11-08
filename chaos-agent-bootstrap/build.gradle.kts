import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer
import org.gradle.api.tasks.util.PatternSet

plugins {
    id("com.gradleup.shadow")
}

// HTTP client libraries for the child-JVM regression probes. Like the JDBC jars they stay off the
// general fork-probe classpath; probes that need them extend the child classpath through the
// chaos.test.httpClasspath system property (set in tasks.test).
val httpClientProbe: Configuration by configurations.creating

// Client libraries (Kafka, gRPC, Redis, R2DBC, AWS SDK, Netty) for the client-hook probes; kept
// off the general probe classpath like the HTTP clients, exposed as chaos.test.clientClasspath.
val clientLibraryProbe: Configuration by configurations.creating
// Libraries only the hook-coverage probe loads: the hooks for them are verified, nothing else
// uses them.
val coverageProbe: Configuration by configurations.creating

// The JaCoCo runtime agent for the child JVMs, whose execution data the build remaps onto the
// module classes (see the root build's childJvmCoverage).
val jacocoChildAgent: Configuration by configurations.creating

dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-core"))
    implementation(project(":chaos-agent-instrumentation-jdk"))
    implementation(project(":chaos-agent-startup-config"))
    implementation(libs.byte.buddy.agent)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // PlanFuzzTest reads the plan schema and generates plans from it.
    testImplementation(libs.jackson.databind)
    testImplementation(libs.mockito.core)
    // Compile-only: these JARs must not appear on the general fork-probe classpath because the
    // agent's JDBC retransformation of their Statement subtypes interferes with other instrumentation
    // (e.g. executor hooks). JDBC-specific probes extend the child-JVM classpath via the
    // chaos.test.jdbcClasspath system property (set below in tasks.test).
    testCompileOnly(libs.h2)
    testCompileOnly(libs.hikari.cp)

    testCompileOnly(libs.okhttp)
    testCompileOnly(libs.apache.httpclient5)
    testCompileOnly(libs.reactor.netty.http)
    testCompileOnly(libs.apache.httpclient4)
    jacocoChildAgent(variantOf(libs.jacoco.agent) { classifier("runtime") })
    coverageProbe(libs.apache.httpclient4)
    coverageProbe(libs.c3p0)
    httpClientProbe(libs.okhttp)
    httpClientProbe(libs.apache.httpclient5)
    httpClientProbe(libs.reactor.netty.http)

    for (client in listOf(
        libs.kafka.clients, libs.grpc.core, libs.grpc.inprocess, libs.grpc.stub, libs.jedis,
        libs.lettuce.core, libs.r2dbc.h2, libs.aws.sdk.s3, libs.aws.sdk.url.connection.client,
        libs.netty.transport,
    )) {
        testCompileOnly(client)
        clientLibraryProbe(client)
    }
}

val agentManifest = mapOf(
    "Automatic-Module-Name" to "com.macstab.chaos.jvm.agent.bootstrap",
    // ChaosAgentBootstrap compares it across the agent copies on the class path.
    "Implementation-Version" to project.version.toString(),
    "Premain-Class" to "com.macstab.chaos.jvm.bootstrap.ChaosAgentBootstrap",
    "Agent-Class" to "com.macstab.chaos.jvm.bootstrap.ChaosAgentBootstrap",
    "Can-Redefine-Classes" to "true",
    "Can-Retransform-Classes" to "true",
    // Self-grant access to JDK internals the agent instruments / reflects into.
    // Honoured by the JVM only on -javaagent: (premain) attach; the runtime self-attach
    // path (ChaosAgentBootstrap#installForLocalTests / agentmain) grants the same opens
    // programmatically via Instrumentation#redefineModule in JdkInstrumentationInstaller.
    // See docs/instrumentation.md "Module access strategy".
    "Add-Opens" to listOf(
        "java.net.http/jdk.internal.net.http",   // HttpClientImpl.send / sendAsync interception
        "java.base/jdk.internal.misc",           // Attach API support paths on JDK 21+
        "java.base/jdk.internal.loader",         // NativeLibraries.load instrumentation
        "java.base/sun.nio.ch",                  // DirectBuffer.cleaner() reflection (DirectBufferPressureStressor)
        "java.base/jdk.internal.ref",            // Modern Cleaner mechanism (Java 9+ replacement for sun.misc.Cleaner)
    ).joinToString(" "),
)

// The thin jar holds only this module's classes. In-repo modules consume it together with the
// module's dependencies; it is not published.
tasks.jar {
    archiveClassifier.set("plain")
    manifest {
        attributes(agentManifest)
    }
}

// The bootstrap bridge (BridgeInjector.BRIDGE_CLASSES): the classes inlined advice references,
// which BridgeInjector appends to the bootstrap class path. The agent jar keeps them under
// META-INF/macstab-chaos-bridge/, not under their class names, so a class loader that finds this
// jar before its parent (a web application with the jar in WEB-INF/lib) cannot define a copy of its
// own: the advice woven into its classes then reaches the one copy the agent's delegate is
// installed in. BridgeDelegate stays an ordinary entry: the agent implements it. The child-JVM test
// ChildFirstLoaderIntegrationTest checks this list against BRIDGE_CLASSES.
val bridgeClasses = PatternSet()
    .include(
        "com/macstab/chaos/jvm/instrumentation/bridge/*.class",
        "com/macstab/chaos/jvm/instrumentation/Scheduled*Wrapper*.class",
        "com/macstab/chaos/jvm/instrumentation/HttpUrlExtractor*.class",
        "com/macstab/chaos/jvm/instrumentation/JdbcTargetExtractor*.class",
        "com/macstab/chaos/jvm/instrumentation/ClientCallExtractor*.class",
    )
    .exclude(
        "com/macstab/chaos/jvm/instrumentation/bridge/BridgeDelegate.class",
        "com/macstab/chaos/jvm/instrumentation/bridge/package-info.class",
    )
val instrumentationJar = configurations.runtimeClasspath.get().incoming.artifactView {
    componentFilter {
        it is ProjectComponentIdentifier && it.projectPath == ":chaos-agent-instrumentation-jdk"
    }
}.files
val bridgeLayout by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("bridge-layout"))
    from(instrumentationJar.elements.map { jars -> jars.map { zipTree(it) } }) {
        include(bridgeClasses.includes)
        exclude(bridgeClasses.excludes)
        into("META-INF/macstab-chaos-bridge")
    }
}

// The agent jar: every runtime dependency merged in, with ByteBuddy and Jackson relocated under
// com.macstab.chaos.jvm.shaded so they cannot clash with the application's own copies (Mockito
// and Hibernate bring ByteBuddy, most frameworks bring Jackson). The API classes carry no
// reference to either library, so they are identical to the ones in the published API jar.
tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes(agentManifest)
    }
    // The bridge classes move to META-INF/macstab-chaos-bridge/ (see bridgeClasses).
    from(bridgeLayout)
    exclude { !it.isDirectory && bridgeClasses.asSpec.isSatisfiedBy(it) }
    relocate("net.bytebuddy", "com.macstab.chaos.jvm.shaded.net.bytebuddy")
    relocate("com.fasterxml.jackson", "com.macstab.chaos.jvm.shaded.com.fasterxml.jackson")
    mergeServiceFiles()
    // The NOTICE files of the bundled libraries (ByteBuddy, Jackson) merge into one
    // META-INF/NOTICE. The organisation fields name this project: the transformer's defaults
    // would credit the Apache Software Foundation.
    transform(ApacheNoticeResourceTransformer::class.java) {
        projectName = "Macstab Chaos JVM Agent"
        organizationName = "Macstab GmbH"
        organizationURL = "https://macstab.com"
        inceptionYear = "2026"
    }
    // The libraries' own LICENSE files stay out (ByteBuddy's ASM licence is kept under
    // META-INF/licenses); META-INF/LICENSE is the project's licence.
    into("META-INF") {
        from(rootProject.file("LICENSE"))
    }
    exclude(
        "META-INF/*.RSA",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/LICENSE*",
        "META-INF/maven/**",
        "META-INF/versions/**",
        "**/module-info.class",
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// Published POM declares no dependencies: everything the agent needs is inside the shaded jar.
extensions.configure<PublishingExtension> {
    publications.named<MavenPublication>("maven") {
        from(components["shadow"])
        artifact(tasks.named("sourcesJar"))
        artifact(tasks.named("javadocJar"))
    }
}

tasks.test {
    // Probe mains run in child JVMs whose classpath carries libraries (HTTP clients, JDBC) that
    // the test JVM does not have; keep them out of test discovery, which would have to load and
    // verify them.
    exclude("**/*ProbeMain*")
    // PlanFuzzTest replays a run with -PfuzzSeed=<seed> -PfuzzIterations=<n>.
    providers.gradleProperty("fuzzSeed").orNull?.let { systemProperty("chaos.fuzz.seed", it) }
    providers.gradleProperty("fuzzIterations").orNull?.let { systemProperty("chaos.fuzz.iterations", it) }

    // What the child JVMs run, passed as system properties: the shaded agent jar, the JaCoCo agent,
    // and the libraries that stay off the test classpath (the H2 and HikariCP jars for the JDBC
    // probes, see testCompileOnly above; the HTTP, client and coverage probe libraries). Each is an
    // input, so a rebuilt agent jar reruns the tests instead of reusing a cached result, and each
    // is resolved when the tests start, not when the build is configured.
    val jdbcProbe = configurations.testCompileClasspath.get().incoming.artifactView {
        componentFilter {
            it is ModuleComponentIdentifier && it.module in setOf("h2", "HikariCP", "slf4j-api")
        }
    }.files
    val childJvmFiles = listOf(
        Triple("agentJar", "chaos.bootstrap.agentJar", files(tasks.shadowJar)),
        Triple("jdbcClasspath", "chaos.test.jdbcClasspath", jdbcProbe),
        Triple("httpClasspath", "chaos.test.httpClasspath", httpClientProbe),
        Triple("clientClasspath", "chaos.test.clientClasspath", clientLibraryProbe),
        Triple("coverageClasspath", "chaos.test.coverageClasspath", coverageProbe),
        Triple("jacocoAgent", "chaos.test.jacocoAgent", jacocoChildAgent),
    )
    childJvmFiles.forEach { (property, _, files) ->
        inputs.files(files).withPropertyName(property).withNormalizer(ClasspathNormalizer::class)
    }
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            childJvmFiles.map { (_, systemProperty, files) -> "-D$systemProperty=${files.asPath}" }
        },
    )

    // Every child JVM records its coverage of the agent's classes into one file, started afresh.
    val childExec = layout.buildDirectory.file("jacoco/child-jvms.exec").get().asFile
    systemProperty("chaos.test.jacocoExec", childExec.absolutePath)
    outputs.file(childExec)
    doFirst { childExec.delete() }
}

// Nullness annotations are for compilers; the running agent needs none of them. Excluded from the
// runtime classpath rather than only from the shadow jar, so the SBOM lists what the jar contains.
configurations.runtimeClasspath { exclude(group = "org.jspecify", module = "jspecify") }
