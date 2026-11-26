import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.chaquo.python") {
                useModule("com.chaquo.python:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/releases")
        google()
        mavenCentral()
        maven("http://4thline.org/m2") {
            isAllowInsecureProtocol = true
        }
        maven("https://jitpack.io")
    }
}

rootProject.name = "AVBox"
include(":app")
include(":quickjs")
include(":pyramid")
include(":libs:backdrop")
