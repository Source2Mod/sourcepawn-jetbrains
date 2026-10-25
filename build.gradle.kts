import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
// CI on a vX.Y.Z tag builds X.Y.Z, everything else uses pluginVersion (gradle.properties or -PpluginVersion=...)
version = providers.environmentVariable("GITHUB_REF_NAME").orNull
    ?.takeIf { Regex("""v\d+\.\d+\.\d+""").matches(it) }
    ?.removePrefix("v")
    ?: providers.gradleProperty("pluginVersion").get()

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
        version = project.version.toString()
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false

    // ./gradlew verifyPlugin: same checks the Marketplace runs on upload, and it fails the build on
    // compatibility problems or internal API usages so CI stops before publishPlugin.
    // Default IDEs: recommended() (the releases matching since/until build).
    // -PverifyIdes=CL:2025.2.4,IU:2025.2.4 picks other IDEs instead.
    pluginVerification {
        failureLevel = listOf(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.INTERNAL_API_USAGES)
        ides {
            val list = providers.gradleProperty("verifyIdes").orNull
            if (list.isNullOrBlank()) {
                recommended()
            } else {
                list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    val (type, ver) = it.split(':', limit = 2)
                    ide(IntelliJPlatformType.fromCode(type), ver)
                }
            }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.runIde {
    // ./gradlew runIde -PrunIdeProject=<dir> opens that project directly
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}
