dependencies {
    api(project(":chaos-agent-api"))
    api(project(":chaos-agent-testkit"))
    implementation(project(":chaos-agent-bootstrap"))

    compileOnly(platform(libs.quarkus.bom))
    compileOnly("io.quarkus:quarkus-core")
    compileOnly("io.quarkus:quarkus-core-deployment")
    compileOnly("io.quarkus:quarkus-arc")
    compileOnly("io.quarkus:quarkus-junit5")
    compileOnly(libs.junit.jupiter.api)

    testImplementation(platform(libs.quarkus.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.agent.quarkus"
    }
}
