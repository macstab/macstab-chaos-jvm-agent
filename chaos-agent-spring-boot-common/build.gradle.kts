dependencies {
    api(project(":chaos-agent-api"))
    // Inside the agent jar (chaos-agent-bootstrap) at runtime; declaring it here would put a
    // second, unshaded copy on the application classpath.
    compileOnly(project(":chaos-agent-core"))
    testImplementation(project(":chaos-agent-core"))
    implementation(project(":chaos-agent-bootstrap"))
    compileOnly(project(":chaos-agent-startup-config"))
    testImplementation(project(":chaos-agent-startup-config"))

    // Compile-time only so this module can be linked against either Spring Boot 3 or 4
    // at the consumer's classpath. The Spring APIs used here (@AutoConfiguration,
    // @ConditionalOnProperty, @ConfigurationProperties, Actuator @Endpoint) are stable
    // across both versions; consumers (-boot3-starter / -boot4-starter) supply their own
    // version-specific spring-boot-* dependencies at runtime.
    compileOnly(platform(libs.spring.boot3.dependencies))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
    compileOnly("org.springframework:spring-context")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation(platform(libs.spring.boot3.dependencies))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-actuator")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework:spring-web")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.spring.boot.common"
    }
}

// This module is the shared core of the Spring Boot 3 and 4 starters and is exercised through
// them: their tests run its classes (the same class files, unshaded), so its coverage counts what
// they execute as well as its own tests.
val starterTests =
    listOf(
        ":chaos-agent-spring-boot3-starter",
        ":chaos-agent-spring-boot4-starter",
        ":chaos-agent-spring-boot3-test-starter",
        ":chaos-agent-spring-boot4-test-starter",
    )
tasks.withType<JacocoReportBase>().configureEach {
    dependsOn(starterTests.map { "$it:test" })
    executionData(starterTests.map { project(it).layout.buildDirectory.file("jacoco/test.exec") })
}

// The module is compiled against Spring Boot 3 and runs on Boot 3 or 4, whichever the consumer's
// starter brings, so its tests run on both: `test` on Boot 3, `testBoot4` on the same test classes
// with Boot 4 on the class path (its platform raises every Spring artifact to Boot 4's version).
val testBoot4Runtime: Configuration by configurations.creating {
    isCanBeConsumed = false
    extendsFrom(configurations.testImplementation.get(), configurations.testRuntimeOnly.get())
}
dependencies {
    testBoot4Runtime(platform(libs.spring.boot4.dependencies))
}
val testBoot4 = tasks.register<Test>("testBoot4") {
    description = "Runs the tests on Spring Boot 4."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + testBoot4Runtime
    shouldRunAfter(tasks.test)
}
tasks.check { dependsOn(testBoot4) }
tasks.withType<JacocoReportBase>().configureEach {
    dependsOn(testBoot4)
    executionData(testBoot4.get())
}
