dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-core"))
    implementation(project(":chaos-agent-instrumentation-jdk"))
    implementation(project(":chaos-agent-startup-config"))
    implementation(libs.byte.buddy.agent)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    val runtimeClasspath = configurations.runtimeClasspath.get()
    dependsOn(runtimeClasspath)
    inputs.files(runtimeClasspath)
    manifest {
        attributes(
            "Automatic-Module-Name" to "com.macstab.chaos.agent.bootstrap",
            "Premain-Class" to "com.macstab.chaos.bootstrap.ChaosAgentBootstrap",
            "Agent-Class" to "com.macstab.chaos.bootstrap.ChaosAgentBootstrap",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true",
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
    systemProperty("chaos.bootstrap.agentJar", tasks.jar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
