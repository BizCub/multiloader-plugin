plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.bizcub"
version = "0.7.2"

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.kikugie.dev/snapshots")
}

dependencies {
    compileOnly("dev.kikugie:stonecutter:0.9+")
    implementation("me.modmuss50:mod-publish-plugin:2+")
    implementation("dev.kikugie:fletching-table:0.1.0-alpha.23")

    implementation("org.json:json:20231013")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
}

gradlePlugin {
    plugins {
        create("multiloader") {
            id = "com.bizcub.multiloader"
            implementationClass = "com.bizcub.multiloader.MultiLoaderPlugin"
        }
    }
}
