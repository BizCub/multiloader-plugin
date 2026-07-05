plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.bizcub"
version = "0.5.0"

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
    compileOnly("me.modmuss50:mod-publish-plugin:2+")
    implementation("dev.kikugie:fletching-table:0.1.0-alpha.23")
    implementation("org.json:json:20231013")
}

gradlePlugin {
    plugins {
        create("multiloader") {
            id = "com.bizcub.multiloader"
            implementationClass = "com.bizcub.multiloader.MultiLoaderPlugin"
        }
    }
}
