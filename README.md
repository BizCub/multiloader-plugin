# Multiloader Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.bizcub.multiloader)](https://plugins.gradle.org/plugin/io.github.bizcub.multiloader)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Gradle plugin that simplifies developing Minecraft mods for multiple loaders (**Fabric**, **Forge**, **NeoForge**) and multiple game versions at the same time.

> **Note:** This plugin was built specifically for **BizCub**'s own projects. Almost everything in it is tailored to those projects, and it is **not** intended to be a general-purpose tool. It is published publicly only so that the source code is available and so that anyone who wants to build BizCub's projects can compile them — previously those builds failed because Gradle reported that the plugin did not exist.

The plugin removes the need to manually maintain separate `build.gradle`, `fabric.mod.json`, `mods.toml`, and `neoforge.mods.toml` files for each Minecraft version. Under the hood it uses [Stonecutter](https://github.com/kikugie/stonecutter) to manage multi-version subprojects.

## Features

- **Unified DSL** — a single `multiloader` block to configure the mod ID, name, and dependencies.
- **Automatic project structure** — subprojects for each loader/version combination are created automatically via Stonecutter.
- **Smart dependencies** — automatic resolution of compatible versions of libraries with caching in `build/multiloader/dependencies.json`.
- **Metadata generation** — mod metadata files are generated from a single set of properties.
- **Entrypoint auto-detection** — source code is scanned with `StaticJavaParser` to automatically populate `fabric.mod.json`.
- **Publishing** — integration with Modrinth, CurseForge, and GitHub via [mod-publish-plugin](https://github.com/modmuss50/mod-publish-plugin).
- **IDE integration** — generation of client/server run configurations.

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
    id("io.github.bizcub.multiloader") version "0.8+"
}

multiloader {
    // Declare the supported versions and loaders:  
    match("1.20.1", fb, fg, nf)   // fb = fabric, fg = forge, nf = neoforge  
    match("1.21.1", fb, nf)
}
```  

### build.gradle.kts

```kotlin  
plugins {
    id("io.github.bizcub.multiloader")
}
```  

## Project Structure

1. **Root Project** — the main `build.gradle.kts` and global configuration.
2. **Common Module** — code shared across all loaders.
3. **Stonecutter subprojects** — generated directories for each version/loader.
4. **Dependency cache** — `build/multiloader/dependencies.json`.

## Main Tasks

| Task                | Description                                |  
| ------------------- | ------------------------------------------ |  
| `runActiveClient`   | Run the client of the active version       |  
| `runActiveServer`   | Run the server of the active version       |  
| `buildActive`       | Build the active version                   |  
| `buildAndCollect`   | Build all versions and collect artifacts   |  

## Disclaimer

This project is developed and maintained solely for **BizCub**'s own mod projects. Its design decisions, conventions, and defaults are opinionated and hard-wired to those projects, and there is no plan to generalize it or accept feature requests aimed at other workflows. The repository is public purely for transparency (open source code) and so that people who want to build BizCub's projects can resolve and compile the plugin instead of hitting "plugin not found" build errors. Use it at your own risk; no support or backwards-compatibility guarantees are provided.  
