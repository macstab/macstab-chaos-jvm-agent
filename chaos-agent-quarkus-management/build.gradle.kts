// The /q/chaos management route, as an extension of its own: only applications that add it get
// quarkus-vertx-http through it, and the main chaos extension stays free of an HTTP server.
dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-bootstrap"))
    api(libs.quarkus.vertx.http)
    // Only for the plan size cap, a compile-time constant javac inlines.
    compileOnly(project(":chaos-agent-startup-config"))

    compileOnly(platform(libs.quarkus.bom))
    compileOnly("io.quarkus:quarkus-core")

    testImplementation(platform(libs.quarkus.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("io.quarkus:quarkus-core")
    testImplementation(project(":chaos-agent-core"))
    testImplementation(project(":chaos-agent-startup-config"))
}

tasks.processResources {
    val coordinates = mapOf("group" to project.group.toString(), "version" to project.version.toString())
    inputs.properties(coordinates)
    filesMatching("META-INF/quarkus-extension.properties") {
        expand(coordinates)
    }
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.quarkus.management"
    }
}
