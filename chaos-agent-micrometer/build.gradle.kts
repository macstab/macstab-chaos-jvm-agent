dependencies {
    api(project(":chaos-agent-api"))
    // Provided by the application, which already has Micrometer if it wants these metrics.
    compileOnly(libs.micrometer.core)

    testImplementation(libs.micrometer.core)
    testImplementation(project(":chaos-agent-core"))
    // ChaosPlatform, for the probe that runs in an agent JVM; there it comes from the agent jar.
    testCompileOnly(project(":chaos-agent-bootstrap"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.micrometer"
    }
}

// ChaosMeterBinderAgentTest starts a JVM with the agent jar: it is a test input, and its path
// reaches the test JVM when it starts.
evaluationDependsOn(":chaos-agent-bootstrap")
val agentJar = project(":chaos-agent-bootstrap").tasks.named<Jar>("shadowJar")
tasks.test {
    val agentJarFile = files(agentJar)
    inputs.files(agentJarFile).withPropertyName("agentJar").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dchaos.bootstrap.agentJar=${agentJarFile.singleFile.absolutePath}")
        },
    )
}
