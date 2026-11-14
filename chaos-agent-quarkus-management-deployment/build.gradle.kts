dependencies {
    api(project(":chaos-agent-quarkus-management"))
    api(libs.quarkus.vertx.http.deployment)

    compileOnly(platform(libs.quarkus.bom))
    compileOnly("io.quarkus:quarkus-core-deployment")

    testImplementation(platform(libs.quarkus.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("io.quarkus:quarkus-core-deployment")
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.quarkus.management.deployment"
    }
}
