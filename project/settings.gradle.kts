pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ChildHelper"
include(":core:common")
include(":core:security")
include(":core:network")
include(":core:p2p")
include(":app:child")
include(":app:parent")
include(":server")
