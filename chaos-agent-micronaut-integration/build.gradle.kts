dependencies {
    api(project(":chaos-agent-api"))
    api(project(":chaos-agent-testkit"))
    implementation(project(":chaos-agent-bootstrap"))

    compileOnly(platform(libs.micronaut.bom))
    compileOnly("io.micronaut:micronaut-inject")
    compileOnly("io.micronaut:micronaut-context")
    compileOnly("io.micronaut.test:micronaut-test-junit5")
    compileOnly(libs.junit.jupiter.api)

    testImplementation(platform(libs.micronaut.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("io.micronaut.test:micronaut-test-junit5")
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.micronaut"
    }
}
