import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.gradle.jvm.toolchain.JavaLanguageVersion

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.diffplug.spotless:spotless-plugin-gradle:8.2.1")
    }
}

plugins {
    base
}

apply(plugin = "com.diffplug.spotless")

group = "com.macstab.chaos.jvm"
version = "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

val junitBom = libs.junit.bom
val junitPlatformLauncher = libs.junit.platform.launcher

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-parameters",
                "-Xlint:all,-serial,-processing,-try",
                "-Xlint:-classfile",
                "-Werror",
            ),
        )
    }

    dependencies {
        "testRuntimeOnly"(platform(junitBom))
        "testRuntimeOnly"(junitPlatformLauncher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs(
            "-Djdk.attach.allowAttachSelf=true",
            "-XX:+EnableDynamicAgentLoading",
        )
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    tasks.withType<Jar>().configureEach {
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }
}

configure<SpotlessExtension> {
    java {
        target("**/src/*/java/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("*.md", ".gitignore", "gradle/**/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = DistributionType.ALL
}
