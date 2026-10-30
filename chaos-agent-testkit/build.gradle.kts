dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-bootstrap"))
    // Inside the agent jar at runtime (see chaos-agent-bootstrap); compile against it only.
    compileOnly(project(":chaos-agent-startup-config"))
    testImplementation(project(":chaos-agent-startup-config"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.testkit"
    }
}
