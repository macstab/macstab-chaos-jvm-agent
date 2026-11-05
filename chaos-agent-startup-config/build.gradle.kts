dependencies {
    api(project(":chaos-agent-api"))
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.startup"
    }
}

// ./gradlew :chaos-agent-startup-config:test -PupdatePlanSchema rewrites the published plan schema
// from the plan mapper (PlanSchemaTest); without it the test fails when the schema is stale.
tasks.test {
    systemProperty("chaos.schema.update", providers.gradleProperty("updatePlanSchema").isPresent)
}
