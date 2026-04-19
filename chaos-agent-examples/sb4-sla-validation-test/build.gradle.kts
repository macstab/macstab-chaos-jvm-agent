dependencies {
    implementation(project(":chaos-agent-api"))
    testImplementation(project(":chaos-agent-spring-boot4-test-starter"))

    implementation(platform(libs.spring.boot4.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(platform(libs.spring.boot4.dependencies))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.wiremock.standalone)
}
