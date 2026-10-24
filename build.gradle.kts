import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        val local = providers.gradleProperty("platformLocalPath").orNull
        if (!local.isNullOrBlank()) {
            local(local)
        } else {
            create(
                IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
                providers.gradleProperty("platformVersion").get(),
            )
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false

    // ./gradlew verifyPlugin: same checks the Marketplace runs on upload.
    // -PverifyIdes=CL:2025.2.4,IU:2025.2.4 picks other IDEs, the default is the platform the plugin builds against.
    pluginVerification {
        ides {
            val list = providers.gradleProperty("verifyIdes").orNull
            if (list.isNullOrBlank()) {
                val local = providers.gradleProperty("platformLocalPath").orNull
                if (!local.isNullOrBlank()) {
                    local(local)
                } else {
                    ide(
                        IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
                        providers.gradleProperty("platformVersion").get(),
                    )
                }
            } else {
                list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    val (type, ver) = it.split(':', limit = 2)
                    ide(IntelliJPlatformType.fromCode(type), ver)
                }
            }
        }
    }
}

tasks.runIde {
    // ./gradlew runIde -PrunIdeProject=<dir> opens that project directly
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}
