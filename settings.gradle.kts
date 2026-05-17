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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "eidkit-android-demo"
include(":app")

// Local SDK substitution — active only when local.properties contains useLocalSdk=true
// Never set this for release builds.
val localProps = java.util.Properties().also { props ->
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}
if (localProps.getProperty("useLocalSdk") == "true") {
    includeBuild("../eidkit-android") {
        dependencySubstitution {
            substitute(module("ro.eidkit:sdk-android")).using(project(":sdk"))
        }
    }
}
