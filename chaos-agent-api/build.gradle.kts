dependencies {
    api(libs.jackson.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "io.macstab.chaos.agent.api"
    }
}
