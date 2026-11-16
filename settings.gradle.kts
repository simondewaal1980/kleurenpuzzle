// ---------------------------------------------------------------------------
// settings.gradle.kts
//
// Dit bestand vertelt Gradle:
//  1. waar plug-ins (AGP, Kotlin, ...) vandaan mogen komen;
//  2. waar bibliotheken (AndroidX, Kotlin, ...) vandaan mogen komen;
//  3. welke modules (deelprojecten) er in dit project zitten.
//
// Gradle is de build-tool van Android. Je hoeft dit bestand zelden aan te
// passen; alleen als je een nieuwe module toevoegt of een repository wijzigt.
// ---------------------------------------------------------------------------

pluginManagement {
    repositories {
        // De Google-Maven-repository bevat Android Gradle Plugin en AndroidX.
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        // Maven Central bevat Kotlin en de meeste "gewone" Java/Kotlin-libraries.
        mavenCentral()
        // De Gradle Plugin Portal bevat community-plug-ins.
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

// Deze plug-in zorgt ervoor dat Gradle automatisch een JDK downloadt als de
// juiste Java-versie niet op de computer staat (handig op een nieuwe machine).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Kleuren"

// :format    = puur Kotlin: het .kplate-bestandsformaat (ook door de generator
//              en de unit tests gebruikt).
// :pipeline  = puur Kotlin: foto -> kleurplaat (kwantisatie + segmentatie).
// :app       = de Android-app zelf.
// :tools:generator = desktop-CLI om de meegeleverde landschappen te maken.
include(":app")
include(":format")
include(":pipeline")
include(":tools:generator")
