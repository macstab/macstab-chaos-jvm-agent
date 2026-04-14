dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-bootstrap"))
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.jupiter.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "io.macstab.chaos.agent.testkit"
    }
}
