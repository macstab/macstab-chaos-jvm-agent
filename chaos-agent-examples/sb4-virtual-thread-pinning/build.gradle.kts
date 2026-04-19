dependencies {
    implementation(project(":chaos-agent-spring-boot4-starter"))
    implementation(project(":chaos-agent-api"))

    implementation(platform(libs.spring.boot4.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation(project(":chaos-agent-spring-boot4-test-starter"))
    testImplementation(platform(libs.spring.boot4.dependencies))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
