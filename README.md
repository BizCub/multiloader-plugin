# Multiloader Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.bizcub.multiloader)](https://plugins.gradle.org/plugin/io.github.bizcub.multiloader)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Gradle plugin that simplifies developing Minecraft mods for multiple loaders (**Fabric**, **Forge**, **NeoForge**) and multiple game versions at the same time.

The plugin removes the need to manually maintain separate `build.gradle`, `fabric.mod.json`, `mods.toml`, and `neoforge.mods.toml` files for each Minecraft version. Under the hood it uses [Stonecutter](https://github.com/kikugie/stonecutter) to manage multi-version subprojects.

## Features

- **Unified DSL** — a single `multiloader` block to configure the mod ID, name, and dependencies.
- **Automatic project structure** — subprojects for each loader/version combination are created automatically via Stonecutter.
- **Smart dependencies** — automatic resolution of compatible versions of popular libraries (Fabric API, Architectury, etc.) with caching in `build/multiloader/dependencies.json`.
- **Metadata generation** — mod metadata files are generated from a single set of properties.
- **Entrypoint auto-detection** — source code is scanned with `StaticJavaParser` to automatically populate `fabric.mod.json`.
- **Publishing** — integration with Modrinth, CurseForge, and GitHub via [mod-publish-plugin](https://github.com/modmuss50/mod-publish-plugin).
- **IDE integration** — generation of client/server run configurations.

## Requirements

- **Gradle 8.1+** (the reference implementation uses Gradle 9.3.0)
- **Java 8+** (for modern Minecraft versions 1.16.5+)

## Installation

Apply the plugin in two places.

### settings.gradle.kts

The plugin is applied at the settings stage and works together with Stonecutter:

```kotlin  
pluginManagement {  
    repositories {  
        gradlePluginPortal()  
        mavenCentral()  
        maven("https://maven.kikugie.dev/snapshots")  
    }  
}  
  
plugins {  
    id("com.bizcub.multiloader") version "0.7+"  
}  
  
multiloader {  
    // Declare the supported versions and loaders:  
    match("1.20.1", fb, fg, nf)   // fb = fabric, fg = forge, nf = neoforge  
    match("1.21.1", fb, nf)  
}  
```  

### build.gradle.kts (root)

```kotlin  
plugins {  
    id("com.bizcub.multiloader")  
}  
```  

## Project Structure

1. **Root Project** — the main `build.gradle.kts` and global configuration.
2. **Common Module** — code shared across all loaders.
3. **Stonecutter subprojects** — generated directories for each version/loader.
4. **Dependency cache** — `build/multiloader/dependencies.json`.

Loader buildscripts are expected at `buildscripts/<loader>.gradle.kts` (for Forge on versions `<1.21`, `buildscripts/forge.arch.gradle.kts` is used).

## Main Tasks

| Task                | Description                                |  
| ------------------- | ------------------------------------------ |  
| `runActiveClient`   | Run the client of the active version       |  
| `runActiveServer`   | Run the server of the active version       |  
| `buildActive`       | Build the active version                   |  
| `buildAndCollect`   | Build all versions and collect artifacts   |  

## Building the Plugin

```bash  
./gradlew build  
```  
