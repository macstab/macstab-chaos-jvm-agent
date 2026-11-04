dependencies {
    // The API's nullness is part of its contract: published, so Java tools and Kotlin see it.
    api(libs.jspecify)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "com.macstab.chaos.jvm.agent.api"
    }
}
