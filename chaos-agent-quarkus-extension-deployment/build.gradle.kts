dependencies {
    // The deployment module depends on the runtime module so the @BuildStep can
    // reference the @Recorder (ChaosQuarkusRecorder) as a parameter type; Quarkus
    // wires the live recorder instance at build time and the build step records
    // method calls against it.
    api(project(":chaos-agent-quarkus-extension"))

    compileOnly(platform(libs.quarkus.bom))
    compileOnly("io.quarkus:quarkus-core")
    compileOnly("io.quarkus:quarkus-core-deployment")
    compileOnly("io.quarkus:quarkus-arc-deployment")

    testImplementation(platform(libs.quarkus.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("io.quarkus:quarkus-core-deployment")
    testImplementation("io.quarkus:quarkus-arc-deployment")
    // ChaosQuarkusBuildStepTest annotates a fixture with @QuarkusChaosTest, meta-annotated @QuarkusTest.
    testCompileOnly("io.quarkus:quarkus-junit5")
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.quarkus.deployment"
    }
}
