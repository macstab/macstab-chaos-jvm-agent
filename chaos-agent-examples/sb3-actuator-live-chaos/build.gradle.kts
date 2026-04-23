plugins {
    id("org.springframework.boot") version "3.5.13" apply false
}

dependencies {
    implementation(project(":chaos-agent-spring-boot3-starter"))
    implementation(project(":chaos-agent-api"))

    implementation(platform(libs.spring.boot3.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation(libs.resilience4j.spring.boot3)

    testImplementation(project(":chaos-agent-spring-boot3-test-starter"))
    testImplementation(platform(libs.spring.boot3.dependencies))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.wiremock.standalone)
}
