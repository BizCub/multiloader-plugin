plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.bizcub"
version = "0.6.0"

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
    maven("https://maven.architectury.dev")
    maven("https://maven.minecraftforge.net")
    maven("https://maven.neoforged.net/releases")
}

dependencies {
    compileOnly("dev.kikugie:stonecutter:0.9+")
    compileOnly("me.modmuss50:mod-publish-plugin:2+")
    implementation("dev.kikugie:fletching-table:0.1.0-alpha.23")

    implementation("dev.architectury:architectury-loom:1.17-SNAPSHOT")
    implementation("net.fabricmc:fabric-loom:1.17-SNAPSHOT")
    implementation("net.minecraftforge:forgegradle:7.0+")
    implementation("net.neoforged.moddev:net.neoforged.moddev.gradle.plugin:2.0+")

    implementation("org.json:json:20231013")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.25.8")
}

gradlePlugin {
    plugins {
        create("multiloader") {
            id = "com.bizcub.multiloader"
            implementationClass = "com.bizcub.multiloader.MultiLoaderPlugin"
        }
    }
}
