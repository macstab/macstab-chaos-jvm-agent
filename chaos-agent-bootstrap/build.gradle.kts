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

tasks.jar {
    val runtimeClasspath = configurations.runtimeClasspath.get()
    dependsOn(runtimeClasspath)
    inputs.files(runtimeClasspath)
    manifest {
        attributes(
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
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        runtimeClasspath.filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude(
            "META-INF/*.RSA",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "module-info.class",
        )
    }
}

tasks.test {
    dependsOn(tasks.jar)
    // Probe mains run in child JVMs whose classpath carries libraries (HTTP clients, JDBC) that
    // the test JVM does not have; keep them out of test discovery, which would have to load and
    // verify them.
    exclude("**/*ProbeMain*")
    systemProperty("chaos.bootstrap.agentJar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)

    // Expose the H2 and HikariCP JAR paths so JDBC fork-probes can extend their child-JVM
    // classpath without polluting the general test classpath (see testCompileOnly above).
    val jdbcClasspath = configurations["testCompileClasspath"].resolvedConfiguration.resolvedArtifacts
        .filter { it.name in listOf("h2", "HikariCP", "slf4j-api") }
        .joinToString(File.pathSeparator) { it.file.absolutePath }
    systemProperty("chaos.test.jdbcClasspath", jdbcClasspath)
    systemProperty("chaos.test.httpClasspath", httpClientProbe.asPath)
}
