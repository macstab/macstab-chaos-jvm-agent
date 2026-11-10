dependencies {
    api(project(":chaos-agent-api"))
    // The test support (@MicronautChaosTest, ChaosMicronautExtension, ChaosTestFactory) is built on
    // the testkit, which the application declares where its tests run: the endpoint and the factory
    // an application ships need none of it, so it stays off the production class path.
    compileOnly(project(":chaos-agent-testkit"))
    testImplementation(project(":chaos-agent-testkit"))
    implementation(project(":chaos-agent-bootstrap"))
    // Inside the agent jar (chaos-agent-bootstrap) at runtime: the trust rule for the switches.
    compileOnly(project(":chaos-agent-startup-config"))
    testImplementation(project(":chaos-agent-startup-config"))

    compileOnly(platform(libs.micronaut.bom))
    compileOnly("io.micronaut:micronaut-inject")
    compileOnly("io.micronaut:micronaut-context")
    // The /chaos management endpoint; the application brings micronaut-management to use it.
    compileOnly("io.micronaut:micronaut-management")
    compileOnly("io.micronaut.test:micronaut-test-junit5")
    compileOnly(platform(libs.junit.bom))
    compileOnly(libs.junit.jupiter.api)

    // Generates the bean definitions (ChaosFactory$Definition and friends). Without the
    // processor @Factory / @Bean are inert annotations and ChaosControlPlane is never a bean.
    annotationProcessor(platform(libs.micronaut.bom))
    annotationProcessor("io.micronaut:micronaut-inject-java")

    testImplementation(platform(libs.micronaut.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // ParallelExecutionTest runs its fixture classes in a parallel engine of their own.
    testImplementation(libs.junit.platform.testkit)
    testImplementation(libs.assertj)
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.micronaut:micronaut-management")
    // The escaping test calls the endpoint over HTTP, as a client would; the server needs a JSON
    // codec for its error bodies.
    testImplementation("io.micronaut:micronaut-http-server-netty")
    testImplementation("io.micronaut:micronaut-jackson-databind")
    testAnnotationProcessor(platform(libs.micronaut.bom))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.micronaut"
    }
}
