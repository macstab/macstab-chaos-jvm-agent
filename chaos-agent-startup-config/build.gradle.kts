dependencies {
    api(project(":chaos-agent-api"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "io.macstab.chaos.agent.startup"
    }
}
