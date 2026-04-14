pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "macstab-chaos-jvm-agent"

include(
    "chaos-agent-api",
    "chaos-agent-bootstrap",
    "chaos-agent-core",
    "chaos-agent-examples",
    "chaos-agent-instrumentation-jdk",
    "chaos-agent-startup-config",
    "chaos-agent-testkit",
)
