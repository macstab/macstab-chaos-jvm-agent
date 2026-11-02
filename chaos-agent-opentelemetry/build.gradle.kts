dependencies {
    api(project(":chaos-agent-api"))
    // Provided by the application, which already has OpenTelemetry if it wants these signals.
    compileOnly(libs.opentelemetry.api)

    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(project(":chaos-agent-core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.opentelemetry"
    }
}
