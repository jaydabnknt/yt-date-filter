rootProject.name = "yt-date-filter"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches-gradle-plugin")
            credentials(PasswordCredentials::class)
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.10"
}

settings {
    extensions {
        defaultNamespace = "app.revanced.extension.datefilter"

        // Must resolve to an absolute path, otherwise extensions fail to find the proguard config.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}
