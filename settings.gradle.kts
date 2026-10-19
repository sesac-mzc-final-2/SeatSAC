rootProject.name = "seatsac"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(
    "libs:common-core",
    "libs:common-web",
    "libs:common-test",
    "services:gateway",
    "services:catalog",
    "services:booking",
    "services:user",
    "services:payment",
)
