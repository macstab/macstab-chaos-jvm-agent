plugins {
    application
}

dependencies {
    implementation(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-core"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator.annprocess)
}

application {
    mainClass = "org.openjdk.jmh.Main"
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.benchmarks"
    }
}
