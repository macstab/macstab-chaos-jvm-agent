dependencies {
    // The deployment module depends on the runtime module so the @BuildStep can
    // reference the @Recorder (ChaosQuarkusRecorder) as a parameter type; Quarkus
    // wires the live recorder instance at build time and the build step records
    // method calls against it.
    api(project(":chaos-agent-quarkus-extension"))

    compileOnly(platform(libs.quarkus.bom))
    compileOnly("io.quarkus:quarkus-core")
    compileOnly("io.quarkus:quarkus-core-deployment")

    testImplementation(platform(libs.quarkus.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.agent.quarkus.deployment"
    }
}
