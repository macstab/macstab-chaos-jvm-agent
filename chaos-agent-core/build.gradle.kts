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
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.core"
    }
}

// ./gradlew :chaos-agent-core:test -PupdateDocs rewrites the generated compatibility table of
// docs/configuration-reference.md (CompatibilityMatrixDocTest); without it the test fails when the
// table is stale. The document is an input, so a change to it reruns the test.
tasks.test {
    systemProperty("chaos.docs.update", providers.gradleProperty("updateDocs").isPresent)
    inputs.file(rootProject.file("docs/configuration-reference.md"))
        .withPropertyName("configurationReference")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
