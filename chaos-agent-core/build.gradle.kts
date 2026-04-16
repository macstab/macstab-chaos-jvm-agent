dependencies {
    api(project(":chaos-agent-api"))
    implementation(libs.byte.buddy)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "io.macstab.chaos.agent.core"
    }
}
