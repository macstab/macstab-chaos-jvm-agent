dependencies {
    api(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-core"))
    implementation(libs.byte.buddy)

    compileOnly(libs.okhttp)
    compileOnly(libs.apache.httpclient4)
    compileOnly(libs.apache.httpclient5)
    compileOnly(libs.reactor.netty.http)
    compileOnly(libs.hikari.cp)
    compileOnly(libs.c3p0)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.h2)
    testImplementation(libs.hikari.cp)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.instrumentation.jdk"
    }
}

// HandleTableDocTest reads the docs and the README: a change to them must rerun it.
tasks.test {
    inputs.files(rootProject.fileTree("docs") { include("*.md") }, rootProject.file("README.md"))
        .withPropertyName("documentation")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
