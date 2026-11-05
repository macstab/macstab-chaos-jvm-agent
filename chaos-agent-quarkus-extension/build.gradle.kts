dependencies {
    api(project(":chaos-agent-api"))
    api(project(":chaos-agent-testkit"))
    implementation(project(":chaos-agent-bootstrap"))

    compileOnly(platform(libs.quarkus.bom))
    compileOnly("io.quarkus:quarkus-core")
    compileOnly("io.quarkus:quarkus-arc")
    compileOnly("io.quarkus:quarkus-junit5")
    compileOnly(libs.junit.jupiter.api)
    // NOTE: quarkus-core-deployment is deliberately NOT depended on here — it
    // belongs to :chaos-agent-quarkus-extension-deployment. Leaking deployment
    // types onto the runtime classpath causes NoClassDefFoundError at first
    // classload in the packaged application (HIGH-34).

    testImplementation(platform(libs.quarkus.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // The recorder runs against Quarkus's runtime types and MicroProfile Config.
    testImplementation("io.quarkus:quarkus-core")
}

// Quarkus bootstrap discovers an extension through META-INF/quarkus-extension.properties in the
// runtime jar; its deployment-artifact entry names the jar holding the @BuildStep classes. The
// quarkus-extension.yaml file next to it is catalog metadata only and is not read at bootstrap.
tasks.processResources {
    val coordinates = mapOf("group" to project.group.toString(), "version" to project.version.toString())
    inputs.properties(coordinates)
    filesMatching("META-INF/quarkus-extension.properties") {
        expand(coordinates)
    }
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.quarkus"
    }
}
