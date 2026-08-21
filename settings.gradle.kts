import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// GitHub Packages serves even a public artifact only to an authenticated caller,
// and the SDK's keyboard is the one dependency this repo does not vendor. Three
// sources are tried in turn, and a source only counts if it supplies both halves
// — half a pair is how you end up sending one place's username with another
// place's token and reading 401 as "the token is wrong".
//
//   local.properties        gpr.user / gpr.key, what a person sets once
//   GH_PACKAGES_*           an explicit read:packages PAT, if the repo has one
//   GITHUB_ACTOR / _TOKEN   the token every Actions run is handed for free
//
// The last one is why this builds in CI with no secrets configured at all. It
// used to need a PAT that a personal account does not share between repositories,
// so a repo that nobody had got round to that for simply never built.
val ghCredentials = listOf(
    localProperties.getProperty("gpr.user") to localProperties.getProperty("gpr.key"),
    System.getenv("GH_PACKAGES_USER") to System.getenv("GH_PACKAGES_TOKEN"),
    System.getenv("GITHUB_ACTOR") to System.getenv("GITHUB_TOKEN"),
).firstOrNull { (user, token) -> !user.isNullOrBlank() && !token.isNullOrBlank() }

val ghUsername = ghCredentials?.first
val ghPassword = ghCredentials?.second

if (ghCredentials == null) {
    logger.warn(
        "No GitHub Packages credentials, so the SDK's keyboard dependency will fail " +
            "to resolve with a 401. Put gpr.user and gpr.key in local.properties — see INSTALL.md.",
    )
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages-Keyboard"
            url = uri("https://maven.pkg.github.com/lightphone/light-keyboard")
            credentials {
                username = ghUsername
                password = ghPassword
            }
        }
    }
}

rootProject.name = "bright-sudoku"

includeBuild("plugin")
include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")
include(":sdk:server")
include(":sdk:emulator")
include(":tool")
include(":examples:ui-demo")
project(":examples:ui-demo").projectDir = file("examples/ui-demo")
include(":examples:weather")
project(":examples:weather").projectDir = file("examples/weather")
include(":examples:authenticator")
project(":examples:authenticator").projectDir = file("examples/authenticator")
include(":examples:audio-demo")
project(":examples:audio-demo").projectDir = file("examples/audio-demo")
