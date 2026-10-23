// Anannt Room TV: the Free My TV launcher (:tv, Java sources still in app/ so build.sh keeps working), the phone
// companion (:phone) and the command protocol they share (:protocol).
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
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "anannt-room-tv"
include(":protocol", ":tv", ":phone")
