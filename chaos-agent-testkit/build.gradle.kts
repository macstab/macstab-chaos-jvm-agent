dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-bootstrap"))
    // Inside the agent jar at runtime (see chaos-agent-bootstrap); compile against it only.
    compileOnly(project(":chaos-agent-startup-config"))
    // BootstrapDispatcher, linked only when the agent put it on the boot path (ChaosAssertions).
    compileOnly(project(":chaos-agent-instrumentation-jdk"))
    testImplementation(project(":chaos-agent-startup-config"))
    // JUnit is the test setup's: every project that runs tests already has it, in the version it
    // chose. Neither the API nor the BOM is exported, so the testkit forces no JUnit version and
    // brings no JUnit onto the class path of a module that depends on it outside its tests.
    compileOnly(platform(libs.junit.bom))
    compileOnly(libs.junit.jupiter.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.testkit"
    }
}
