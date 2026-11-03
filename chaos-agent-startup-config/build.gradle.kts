dependencies {
    api(project(":chaos-agent-api"))
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
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
    // The documentation tests read these files directly (CrossLayerDocSyncTest: the docs;
    // SemanticsLawTraceabilityTest: the law tags in every module's tests): a change to any of them
    // must rerun the tests instead of leaving them up to date.
    inputs.files(rootProject.fileTree("docs") { include("*.md") }, rootProject.file("README.md"))
        .withPropertyName("documentation")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree(rootProject.projectDir) { include("*/src/test/java/**/*.java") })
        .withPropertyName("lawTaggedTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
