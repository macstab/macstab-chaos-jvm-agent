dependencies {
    api(project(":chaos-agent-api"))
    // Provided by the application, which already has Micrometer if it wants these metrics.
    compileOnly(libs.micrometer.core)

    testImplementation(libs.micrometer.core)
    testImplementation(project(":chaos-agent-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.micrometer"
    }
}
