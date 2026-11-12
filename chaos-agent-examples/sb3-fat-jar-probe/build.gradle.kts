// A Spring Boot fat jar (nested jars, Spring Boot's own class loader) started with -javaagent: the
// layout in which install-time presence checks used to miss pool and HTTP client hooks.
plugins {
    alias(libs.plugins.spring.boot3)
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

val agentJar = project(":chaos-agent-bootstrap").tasks.named<Jar>("shadowJar")

tasks.test {
    // Both jars are inputs: a rebuilt agent or application reruns the test instead of reusing a
    // cached result. Their paths reach the test JVM when it starts.
    val appJar = files(tasks.bootJar)
    val agentJarFile = files(agentJar)
    inputs.files(appJar).withPropertyName("fatJar").withNormalizer(ClasspathNormalizer::class)
    inputs.files(agentJarFile).withPropertyName("agentJar").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dchaos.fatjar.app=${appJar.singleFile.absolutePath}",
                "-Dchaos.bootstrap.agentJar=${agentJarFile.singleFile.absolutePath}",
            )
        },
    )
}
