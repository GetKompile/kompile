pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        System.getenv("KOMPILE_MAVEN_REPO")
            ?.takeIf { it.isNotBlank() }
            ?.let { repositoryPath -> maven { url = uri(repositoryPath) } }
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        System.getenv("KOMPILE_MAVEN_REPO")
            ?.takeIf { it.isNotBlank() }
            ?.let { repositoryPath -> maven { url = uri(repositoryPath) } }
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "KompileChatLocal"
include(":app")
