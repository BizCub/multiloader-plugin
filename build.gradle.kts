plugins {
    `kotlin-dsl`
    id("com.gradle.plugin-publish") version "1.3.0"
}

group = "io.github.bizcub"
version = "0.8.3"

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.kikugie.dev/snapshots")

    maven("https://maven.fabricmc.net")
    maven("https://maven.minecraftforge.net")
    maven("https://maven.neoforged.net/releases")
}

dependencies {
    compileOnly("dev.kikugie:stonecutter:0.9+")
    implementation("me.modmuss50:mod-publish-plugin:2+")
    implementation("dev.kikugie:fletching-table:0.1.0-alpha.23")

    implementation("net.fabricmc:fabric-loom:1.17-SNAPSHOT")
    implementation("net.minecraftforge:forgegradle:7.0+")
    implementation("net.minecraftforge:renamer-gradle:1.0.14")
    implementation("net.neoforged.moddev:net.neoforged.moddev.gradle.plugin:2.0+")

    implementation("org.json:json:20231013")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
}

gradlePlugin {
    website = "https://github.com/BizCub/multiloader-plugin"
    vcsUrl = "https://github.com/BizCub/multiloader-plugin.git"
    plugins {
        create("multiloader") {
            id = "io.github.bizcub.multiloader"
            displayName = "Multiloader Plugin"
            description = "A Gradle plugin that helps download and manage dependencies for multi-loader projects"
            tags = listOf("multiloader", "multi-platform", "minecraft-modding")
            implementationClass = "io.github.bizcub.multiloader.MultiLoaderPlugin"
        }
    }
}
