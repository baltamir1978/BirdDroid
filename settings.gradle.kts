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
rootProject.name = "BirdDroid"
include(":app")

// Lo que comparten las apps (huella de Shazam, fichero cifrado, modo escritorio): el repositorio
// droid-common, clonado en la carpeta de al lado. Gradle sustituye `es.altamirano:common` por él.
includeBuild("../droid-common")
