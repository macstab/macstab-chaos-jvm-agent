dependencies {
    api(project(":chaos-agent-api"))
    implementation(libs.byte.buddy)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testImplementation(libs.jqwik)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.agent.core"
    }
}
