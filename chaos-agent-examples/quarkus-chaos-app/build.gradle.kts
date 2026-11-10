import java.util.Properties
import java.util.zip.ZipFile

// A Quarkus application with the chaos extension and the /q/chaos route, booted by @QuarkusTest:
// the extension's build steps run in a real augmentation, the agent attaches at runtime-init, and
// the tests reach the route over HTTP and the test extension through @QuarkusChaosTest.
//
// The io.quarkus Gradle plugin of the Quarkus release this build uses cannot configure a project on
// Gradle 9, so the test JVM gets the application model @QuarkusTest needs from a file instead: the
// runtime class path and the deployment class path (every runtime extension's deployment artifact),
// each artifact with its coordinates. QuarkusAppModel (a launcher session listener in the tests)
// builds the model from it and hands it to Quarkus as its serialized test application model.

val quarkusDeployment: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    // platform, not enforcedPlatform: the agent's own libraries (ByteBuddy) keep their versions, as
    // they do inside the published agent jar, which shades them.
    implementation(platform(libs.quarkus.bom))
    implementation(project(":chaos-agent-api"))
    implementation(project(":chaos-agent-quarkus-extension"))
    implementation(project(":chaos-agent-quarkus-management"))
    implementation("io.quarkus:quarkus-arc")

    quarkusDeployment(platform(libs.quarkus.bom))
    quarkusDeployment(project(":chaos-agent-quarkus-extension-deployment"))
    quarkusDeployment(project(":chaos-agent-quarkus-management-deployment"))

    testImplementation(project(":chaos-agent-testkit"))
    testImplementation(platform(libs.quarkus.bom))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // QuarkusAppModel is a launcher session listener.
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
}

// The deployment artifact of every external runtime extension on the runtime class path; the two
// project extensions are named above, since their jars are built later.
quarkusDeployment.withDependencies {
    val runtimeJars =
        configurations.runtimeClasspath.get().incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }.files
    for (jar in runtimeJars) {
        if (!jar.name.endsWith(".jar")) continue
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("META-INF/quarkus-extension.properties") ?: return@use
            val properties = Properties()
            zip.getInputStream(entry).use { properties.load(it) }
            properties.getProperty("deployment-artifact")?.let { add(project.dependencies.create(it)) }
        }
    }
}

/** Writes one line per artifact: `R|D <group>:<artifact>:<version> <path>`. */
val quarkusModelInputs =
    tasks.register("quarkusModelInputs") {
        val output = layout.buildDirectory.file("quarkus-model-inputs.txt")
        // The test class path, as the plugin's test model has it: quarkus-junit5's build chain classes
        // must come from the application's class loaders, not from the system class loader.
        val runtime = configurations.testRuntimeClasspath
        val deployment = quarkusDeployment
        inputs.files(runtime)
        inputs.files(deployment)
        outputs.file(output)
        doLast {
            fun lines(kind: String, configuration: Configuration): List<String> =
                configuration.incoming.artifacts.artifacts.map { artifact ->
                    val coordinates =
                        when (val id = artifact.id.componentIdentifier) {
                            is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                            is ProjectComponentIdentifier ->
                                "${project.group}:${id.projectName}:${project.version}"
                            else -> "unknown:${artifact.file.name}:0"
                        }
                    "$kind $coordinates ${artifact.file.absolutePath}"
                }
            output.get().asFile.writeText(
                (lines("R", runtime.get()) + lines("D", deployment)).joinToString("\n"),
            )
        }
    }

tasks.test {
    dependsOn(quarkusModelInputs)
    val modelInputs = layout.buildDirectory.file("quarkus-model-inputs.txt")
    inputs.file(modelInputs).withPropertyName("quarkusModelInputs")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dchaos.quarkus.modelInputs=${modelInputs.get().asFile.absolutePath}",
                "-Dchaos.quarkus.appCoordinates=${project.group}:${project.name}:${project.version}",
            )
        },
    )
}
