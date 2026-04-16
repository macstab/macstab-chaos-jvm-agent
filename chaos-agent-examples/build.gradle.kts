plugins {
    application
}

dependencies {
    implementation(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-bootstrap"))
    testImplementation(project(":chaos-agent-testkit"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

application {
    mainClass = "com.macstab.chaos.examples.ExampleServiceMain"
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.agent.examples"
    }
}
