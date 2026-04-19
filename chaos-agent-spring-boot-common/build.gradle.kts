dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-core"))
    implementation(project(":chaos-agent-bootstrap"))
    implementation(project(":chaos-agent-startup-config"))

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

    testImplementation(platform(libs.spring.boot3.dependencies))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-actuator")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("org.springframework:spring-context")
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.agent.spring.boot.common"
    }
}
