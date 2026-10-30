plugins {
    id("com.gradleup.shadow")
}

// HTTP client libraries for the child-JVM regression probes. Like the JDBC jars they stay off the
// general fork-probe classpath; probes that need them extend the child classpath through the
// chaos.test.httpClasspath system property (set in tasks.test).
val httpClientProbe: Configuration by configurations.creating

dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-core"))
    implementation(project(":chaos-agent-instrumentation-jdk"))
    implementation(project(":chaos-agent-startup-config"))
    implementation(libs.byte.buddy.agent)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
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
    httpClientProbe(libs.okhttp)
    httpClientProbe(libs.apache.httpclient5)
    httpClientProbe(libs.reactor.netty.http)
}

val agentManifest = mapOf(
    "Automatic-Module-Name" to "com.macstab.chaos.jvm.agent.bootstrap",
    "Premain-Class" to "com.macstab.chaos.jvm.bootstrap.ChaosAgentBootstrap",
    "Agent-Class" to "com.macstab.chaos.jvm.bootstrap.ChaosAgentBootstrap",
    "Can-Redefine-Classes" to "true",
    "Can-Retransform-Classes" to "true",
    "Can-Set-Native-Method-Prefix" to "true",
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

// The agent jar: every runtime dependency merged in, with ByteBuddy and Jackson relocated under
// com.macstab.chaos.jvm.shaded so they cannot clash with the application's own copies (Mockito
// and Hibernate bring ByteBuddy, most frameworks bring Jackson). The API classes carry no
// reference to either library, so they are identical to the ones in the published API jar.
tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes(agentManifest)
    }
    relocate("net.bytebuddy", "com.macstab.chaos.jvm.shaded.net.bytebuddy")
    relocate("com.fasterxml.jackson", "com.macstab.chaos.jvm.shaded.com.fasterxml.jackson")
    mergeServiceFiles()
    exclude(
        "META-INF/*.RSA",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
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
    dependsOn(tasks.shadowJar)
    // Probe mains run in child JVMs whose classpath carries libraries (HTTP clients, JDBC) that
    // the test JVM does not have; keep them out of test discovery, which would have to load and
    // verify them.
    exclude("**/*ProbeMain*")
    systemProperty("chaos.bootstrap.agentJar", tasks.shadowJar.flatMap { it.archiveFile }.get().asFile.absolutePath)

    // Expose the H2 and HikariCP JAR paths so JDBC fork-probes can extend their child-JVM
    // classpath without polluting the general test classpath (see testCompileOnly above).
    val jdbcClasspath = configurations["testCompileClasspath"].resolvedConfiguration.resolvedArtifacts
        .filter { it.name in listOf("h2", "HikariCP", "slf4j-api") }
        .joinToString(File.pathSeparator) { it.file.absolutePath }
    systemProperty("chaos.test.jdbcClasspath", jdbcClasspath)
    systemProperty("chaos.test.httpClasspath", httpClientProbe.asPath)
}
