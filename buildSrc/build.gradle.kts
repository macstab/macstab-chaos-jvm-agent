plugins {
    java
}

dependencies {
    implementation(gradleApi())
    // The API signature tasks read the class files with ASM.
    implementation(libs.asm)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
