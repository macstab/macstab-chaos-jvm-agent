// A Spring Boot fat jar (nested jars, Spring Boot's own class loader) started with -javaagent: the
// layout in which install-time presence checks used to miss pool and HTTP client hooks.
plugins {
    id("org.springframework.boot") version "3.5.16"
}

evaluationDependsOn(":chaos-agent-bootstrap")

dependencies {
    implementation(platform(libs.spring.boot3.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.okhttp)
    runtimeOnly(libs.h2)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

val agentJar =
    project(":chaos-agent-bootstrap").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }

tasks.test {
    dependsOn(tasks.bootJar, ":chaos-agent-bootstrap:shadowJar")
    systemProperty("chaos.fatjar.app", tasks.bootJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("chaos.bootstrap.agentJar", agentJar.get().asFile.absolutePath)
}
