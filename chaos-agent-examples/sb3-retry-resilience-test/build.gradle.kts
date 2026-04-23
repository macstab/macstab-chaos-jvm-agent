dependencies {
    implementation(project(":chaos-agent-api"))
    testImplementation(project(":chaos-agent-spring-boot3-test-starter"))

    implementation(platform(libs.spring.boot3.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.resilience4j.retry)

    testImplementation(platform(libs.spring.boot3.dependencies))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.wiremock.standalone)
}
