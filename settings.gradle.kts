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
    "chaos-agent-benchmarks",
    "chaos-agent-bootstrap",
    "chaos-agent-core",
    "chaos-agent-examples",
    "chaos-agent-examples:sb3-actuator-live-chaos",
    "chaos-agent-examples:sb4-virtual-thread-pinning",
    "chaos-agent-examples:sb3-retry-resilience-test",
    "chaos-agent-examples:sb4-sla-validation-test",
    "chaos-agent-instrumentation-jdk",
    "chaos-agent-micronaut-integration",
    "chaos-agent-quarkus-extension",
    "chaos-agent-spring-boot3-starter",
    "chaos-agent-spring-boot3-test-starter",
    "chaos-agent-spring-boot4-starter",
    "chaos-agent-spring-boot4-test-starter",
    "chaos-agent-startup-config",
    "chaos-agent-testkit",
)
