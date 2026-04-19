dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-core"))
    implementation(project(":chaos-agent-bootstrap"))
    implementation(project(":chaos-agent-startup-config"))
    implementation(project(":chaos-agent-spring-boot-common"))

    compileOnly(platform(libs.spring.boot3.dependencies))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-actuator")
    compileOnly("org.springframework.boot:spring-boot-actuator-autoconfigure")
    compileOnly("org.springframework:spring-context")
    compileOnly("org.springframework:spring-web")

    testImplementation(platform(libs.spring.boot3.dependencies))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-actuator")
    testImplementation("org.springframework.boot:spring-boot-actuator-autoconfigure")
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-web")
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.agent.spring.boot3"
    }
}
