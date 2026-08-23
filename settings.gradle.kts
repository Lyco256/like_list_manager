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
        exclusiveContent {
            forRepository {
                ivy {
                    name = "bundledOpenCv"
                    url = uri(rootDir)
                    patternLayout {
                        artifact("ppocr-sdk/libs/[artifact]-[revision].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeModule("com.quickbirdstudios", "opencv")
            }
        }
    }
}

rootProject.name = "like-list-manager"
include(":app")
include(":macrobenchmark")
include(":ppocr-sdk")
