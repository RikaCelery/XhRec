rootProject.name = "XhRec"

pluginManagement {
    repositories {
//        maven("https://maven.aliyun.com/repository/gradle-plugin")
//        maven("https://maven.aliyun.com/repository/public")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
//        maven("https://maven.aliyun.com/repository/public")
//        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
        google()
    }
}

// XhCut — remote, event-aware lossless cutter for XhRec recordings.
// Self-contained: it owns its own main class, jar and container image, so the
// root project's application/shadowJar/deploy.sh behaviour is unchanged.
include(":cutter")