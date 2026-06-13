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

rootProject.name = "AgentChat"

include(":app")
include(":core:designsystem")
include(":core:model")
include(":core:network")
include(":core:harness")
include(":core:provider")
include(":core:prompts")
include(":feature:chat")
include(":feature:settings")
include(":feature:prompts")
