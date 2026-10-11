package io.github.bizcub.multiloader

import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import dev.kikugie.fletching_table.extension.FletchingTableExtension
import me.modmuss50.mpp.ModPublishExtension
import me.modmuss50.mpp.platforms.modrinth.ModrinthEnvironment
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.minecraftforge.gradle.ForgeGradleExtension
import net.minecraftforge.gradle.MinecraftExtensionForProject
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFile
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MultiLoaderPlugin : Plugin<ExtensionAware> {
    override fun apply(target: ExtensionAware) {
        val version = javaClass.`package`.implementationVersion ?: "unknown"

        when (target) {
            is Settings -> {
                Logging.getLogger("multiloader").lifecycle("Running Settings MultiLoader $version")

                target.rootProject.name = target.extra["mod.id"] as String

                val loaders = listOf("common", "fabric", "forge", "neoforge")
                loaders.forEach { target.include(it) }

                target.gradle.allprojects {
                    pluginManager.apply(MultiLoaderPlugin::class.java)
                }
            }
            is Project -> {
                val multiloader = target.extensions.create("multiloader", MultiLoader::class.java, target)
                target.logger.lifecycle("Running MultiLoader $version")

                if (target == target.rootProject) multiloader.firstInit()
                else multiloader.init()
            }
        }
    }
}

open class MultiLoader(private val project: Project) {
    interface NeoForgeMutex : BuildService<BuildServiceParameters.None>

    val reps = mutableListOf<Repository>()

    class Repository(val repository: String)

    val deps = mutableListOf<Dependency>()

    class Dependency(val configuration: String, val dependency: String) {
        val id = dependency.split(":")[1]
    }

    val eModules = mutableListOf<Module>()

    class Module(val module: String)

    val mrEnvs by lazy { MREnvs() }

    class MREnvs {
        val clientOnly = ModrinthEnvironment.CLIENT_ONLY
        val serverOnly = ModrinthEnvironment.SERVER_ONLY
        val dedicatedServerOnly = ModrinthEnvironment.DEDICATED_SERVER_ONLY
        val clientAndServer = ModrinthEnvironment.CLIENT_AND_SERVER
        val serverOnlyClientOptional = ModrinthEnvironment.SERVER_ONLY_CLIENT_OPTIONAL
        val clientOnlyServerOptional = ModrinthEnvironment.CLIENT_ONLY_SERVER_OPTIONAL
        val clientOrServerPrefersBoth = ModrinthEnvironment.CLIENT_OR_SERVER_PREFERS_BOTH
        val clientOrServer = ModrinthEnvironment.CLIENT_OR_SERVER
        val singleplayerOnly = ModrinthEnvironment.SINGLEPLAYER_ONLY
    }

    val cfEnvs = CFEnvs()

    class CFEnvs {
        val client = "client"
        val server = "server"
        val both = "both"
    }

    val mod = Mod()

    inner class Mod {
        val mc: String get() = modProp("mc")
        val loader: String get() = project.name
        val id: String get() = modProp("id")
        val idDashed: String get() = id.replace("_", "-")
        val name: String get() = modProp("name")
        val description: String get() = modProp("description")
        val version: String get() = modProp("version")
        val group: String get() = "io.github.bizcub"
        val modrinth: String get() = modProp("modrinth")
        val curseforge: String get() = modProp("curseforge")
        val github: String get() = modProp("github")
        var pubStart: String = "not recorded"
        var pubEnd: String = "not recorded"
    }

    class ClassInfo(val entrypointName: String, val classFilePath: String)

    private val updateDependencies = UpdateDependencies(project, this)
    private val loaderProjects = listOf("common", "fabric", "forge", "neoforge")
    private val entrypoints = mutableListOf(
        "client" to "net.fabricmc.api.ClientModInitializer",
        "main" to "net.fabricmc.api.ModInitializer",
        "modmenu" to "com.terraformersmc.modmenu.api.ModMenuApi"
    )
    private val badges = mutableListOf<String>()

    private val rootDir: File get() = project.rootProject.projectDir
    private val resourcesDir: File get() = project.rootProject.project(":common").file("src/main/resources")
    private val buildResourcesDir: File get() = project.file("build/resources/main")
    private val generatedResourcesDir: File get() = project.file("build/generated/mod-metadata")

    val clientRunFile: File get() = project.rootProject.file("run/client")
    val serverRunFile: File get() = project.rootProject.file("run/server")
    val iconFile: File get() = resourcesDir.resolve("icon.png")
    val mixinFile: File get() = resourcesDir.resolve("${mod.idDashed}.mixins.json")
    val ctMainFile: File get() = resourcesDir.resolve("${mod.idDashed}.ct")
    val awBuildFile: File get() = buildResourcesDir.resolve("${mod.idDashed}.ct")
    val atBuildFile: File get() = buildResourcesDir.resolve("META-INF/accesstransformer.cfg")

    val isCommon: Boolean get() = project.name == "common"
    val isFabric: Boolean get() = project.name == "fabric"
    val isForge: Boolean get() = project.name == "forge"
    val isNeoForge: Boolean get() = project.name == "neoforge"

    val playerName: String get() = "BizarreCube"
    val playerUUID: String get() = updateDependencies.getPlayerUUIDbyName(playerName)

    fun prop(key: String): String? = project.findProperty(key)?.toString()
    fun modProp(key: String) = prop("mod.$key") as String
    fun getProp(key: String) = prop(propName(key))
    fun setProp(key: String, value: Any?) = value.also { project.extra[propName(key)] = it }
    fun getPropName(key: String) = propName(key)

    private fun propName(key: String) = if (prop("${mod.loader}.$key") != null) "${mod.loader}.$key" else key

    fun firstInit() {
        createDepFile()
        setCustomProjectIcon()
        updateOrCreateIssueTemplates()
        updateOrCreateChangelogFile()
        updateOrCreateGitIgnoreFile()
        setServerProperties()
        rootInit()
    }

    fun init() {
        neoforgeFix()
        configureInit()
        project.gradle.projectsEvaluated {
            access()
            generateModMetadata()
            mixinConfigRegistration()
            generateFabricModJson()
        }
    }

    private var loaderBlock: (MultiLoader.() -> Unit)? = null

    fun configureLoaders(block: MultiLoader.() -> Unit) {
        project.rootProject.extra["multiloader.loaderBlock"] = block
    }

    private fun replayLoaderBlock() {
        val root = project.rootProject
        if (!root.extra.has("multiloader.loaderBlock")) return
        @Suppress("UNCHECKED_CAST")
        val block = root.extra["multiloader.loaderBlock"] as MultiLoader.() -> Unit
        this.block()
    }

    fun addSourceSet(name: String) {
        val sourceSets = getSourceSets()
        sourceSets.create(name) {
            java {
                compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
                runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
            }
        }
    }

    fun setMREnvironment(mrEnv: ModrinthEnvironment) = publishMods {
        modrinth { environment.set(mrEnv) }
    }

    fun setMREnvironment(mrEnv: String) = publishMods {
        modrinth { environment.set(ModrinthEnvironment.valueOf(mrEnv)) }
    }

    fun setCFEnvironment(cfEnv: String) = publishMods {
        curseforge {
            client.set(cfEnv == cfEnvs.client || cfEnv == cfEnvs.both)
            server.set(cfEnv == cfEnvs.server || cfEnv == cfEnvs.both)
        }
    }

    fun addEntrypoint(entrypointName: String, implementedClassName: String) {
        entrypoints.add(entrypointName to implementedClassName)
    }

    fun addBadges(vararg newBadges: String) {
        badges.addAll(newBadges)
    }

    fun addDependency(
        repository: String = "",
        configuration: String = "implementation",
        vararg configurations: String = arrayOf(),
        dependency: String = "",
        excludedModules: List<String> = listOf(),
        isPublishDepEnabled: Boolean = false,
        isPublishDepRequired: Boolean = false,
        publishProjectId: String = ""
    ) {
        if (repository.isNotEmpty()) {
            reps.add(Repository("https://$repository"))
        }
        if (dependency.isNotEmpty()) {
            if (configurations.isEmpty()) {
                deps.add(Dependency(configuration, dependency))
            } else {
                configurations.forEach {
                    deps.add(Dependency(it, dependency))
                }
            }
        }
        if (isPublishDepEnabled) {
            addPublishDep(
                if (isPublishDepRequired) "requires" else "optional",
                publishProjectId.ifEmpty { deps[deps.size - 1].id }
            )
        }
        excludedModules.forEach { module -> eModules.add(Module(module)) }
    }

    fun getSimpleConfigLibDep(version: String = ""): String {
        return if (version.isEmpty()) {
            "maven.modrinth:simple-config-lib:${getDep("simple-config-lib")}"
        } else {
            "io.github.bizcub:simple-config-lib:${version}-${mod.loader}+${mod.mc}"
        }
    }

    fun getDep(key: String, useId: Boolean = false): String {
        val dep = updateDependencies.getDep(key, useId)

        if (key == "fabric" && isFabric) {
            project.configurations.matching { it.name.endsWith("implementation", true) }.configureEach {
                resolutionStrategy.force("net.fabricmc:fabric-loader:$dep")
            }
        }

        return dep
    }

    fun isMainCTFileExist(): Boolean = ctMainFile.exists()

    private fun getSourceSets(): SourceSetContainer =
        project.extensions.getByType<SourceSetContainer>()

    private fun getExtraSourceSet(): SourceSet? =
        if (getSourceSets().count() > 2)
            getSourceSets().first { it.name != "main" && it.name != "test" }
        else null

    private fun realPublishTarget(platform: String) = if (platform == "All") "Mods" else platform

    private fun publishMods(block: ModPublishExtension.() -> Unit) {
        project.rootProject.extensions.configure("publishMods", block)
    }

    private fun addPublishDep(requirement: String, mrSlug: String, cfSlug: String = mrSlug) {
        project.rootProject.extensions.configure<ModPublishExtension>("publishMods") {
            modrinth {
                when (requirement) {
                    "requires" -> requires(mrSlug)
                    "optional" -> optional(mrSlug)
                }
            }
            curseforge {
                when (requirement) {
                    "requires" -> requires(cfSlug)
                    "optional" -> optional(cfSlug)
                }
            }
        }
    }

    private fun createDepFile() {
        updateDependencies.createDepFile()
    }

    private fun getResource(resource: String): String =
        this.javaClass.classLoader.getResource(resource)!!.readText()

    private fun writeIfChanged(file: File, content: String) {
        if (!file.exists() || file.readText() != content) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    private fun setCustomProjectIcon() {
        val icon = resourcesDir.resolve("icon.png")
        if (icon.exists()) {
            icon.copyTo(project.file(".idea/icon.png"), true)
        }
    }

    private fun setServerProperties() {
        serverRunFile.mkdirs()
        writeIfChanged(serverRunFile.resolve("eula.txt"), "eula=true")
        val propertiesFile = serverRunFile.resolve("server.properties")
        if (!propertiesFile.exists()) {
            propertiesFile.writeText("online-mode=false")
        }
    }

    private fun rootInit() {
        project.pluginManager.apply("base")
        project.pluginManager.apply("me.modmuss50.mod-publish-plugin")
        project.pluginManager.apply("java")

        project.version = "${mod.version}+${mod.mc}"

        registerMergedJarTask()
        registerRootTasks()
        configureModPublication()

        project.gradle.taskGraph.whenReady {
            if (project.rootProject.extra.has("multiloader.publishCheckDone")) return@whenReady

            val hasPublishTask = allTasks.any { task ->
                task.name == "publishMods" ||
                        task.name.startsWith("publishModrinth") ||
                        task.name.startsWith("publishCurseforge") ||
                        task.name.startsWith("publishGithub")
            }
            if (!hasPublishTask) return@whenReady

            project.rootProject.extra["multiloader.publishCheckDone"] = true
            checkPublishConflict()
        }

        project.gradle.projectsEvaluated {
            createRunConfiguration()
        }
    }

    private fun checkPublishConflict() {
        val id = mod.modrinth
        if (id.isBlank()) return

        val token = try {
            File("C:\\Tokens\\modrinth.txt").readText().trim()
        } catch (e: Exception) {
            ""
        }

        val latest = updateDependencies.getModrinthLatestVersion(id, token) ?: return
        val (latestVersion, latestChangelog) = latest
        if (latestVersion.isBlank()) return

        val newVersion = project.version.toString()
        val newChangelog = processChangelog().trim()

        val versionConflict = latestVersion.trim() == newVersion
        val changelogConflict = latestChangelog.trim().isNotEmpty() && latestChangelog.trim() == newChangelog
        if (!versionConflict && !changelogConflict) return

        val problems = mutableListOf<String>()
        if (versionConflict) problems.add("version '$latestVersion'")
        if (changelogConflict) problems.add("changelog")

        project.logger.lifecycle(
            "[Multiloader] WARNING: Modrinth already has the latest release with the same ${problems.joinToString(" and ")}."
        )

        val forced = (project.findProperty("multiloader.forcePublish") as String?)?.lowercase()
        if (forced != null) {
            if (forced == "true" || forced == "y" || forced == "yes") {
                project.logger.lifecycle("[Multiloader] forcePublish=true -> continuing despite duplicate.")
                return
            }
            throw org.gradle.api.GradleException(
                "[Multiloader] Publication aborted (forcePublish=$forced): duplicate version/changelog already exists on Modrinth."
            )
        }

        project.logger.lifecycle("[Multiloader] Continue publishing anyway? Type 'y' to force, anything else to abort:")
        val answer = (System.console()?.readLine() ?: readlnOrNull()).orEmpty().trim().lowercase()
        if (answer != "y" && answer != "yes") {
            throw org.gradle.api.GradleException(
                "[Multiloader] Publication aborted by user: duplicate version/changelog already exists on Modrinth."
            )
        }

        project.logger.lifecycle("[Multiloader] Forcing publication despite duplicate.")
    }

    private fun removeDependencyKeys() {
        val availableEntries = updateDependencies.listKeys()
        if (availableEntries.isEmpty()) {
            project.logger.lifecycle("[Multiloader] dependencies.json is empty, nothing to remove.")
            return
        }

        project.logger.lifecycle("[Multiloader] Available keys:")
        availableEntries.forEachIndexed { index, entry ->
            project.logger.lifecycle("    ${index + 1}. $entry")
        }
        project.logger.lifecycle("[Multiloader] Enter numbers or keys to remove (comma/space separated):")

        val raw = (System.console()?.readLine() ?: readlnOrNull()).orEmpty()
        val tokens = raw.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }

        val keys = tokens.map { token ->
            val selectedNumber = token.toIntOrNull()
            if (selectedNumber != null && selectedNumber in 1..availableEntries.size) {
                availableEntries[selectedNumber - 1].substringBefore(" = ").trim()
            } else {
                token
            }
        }

        updateDependencies.removeKeysFromConfig(keys)
    }

    private fun clearCache() {
        updateDependencies.clearCache()
    }

    private fun access() {
        val ft = project.extensions.getByType<FletchingTableExtension>()
        val main = project.the<SourceSetContainer>().getByName("main")

        ft.apply {
            accessConverter.register(main) {
                add("${mod.idDashed}.ct")
            }
        }
    }

    private fun registerRootTasks() {
        project.tasks {
            register("updateDependencies") {
                group = "multiloader"
                doLast { updateDependencies.updateAllCachedDeps() }
            }
            register("removeDependencyKeys") {
                group = "multiloader"
                doLast { removeDependencyKeys() }
            }
            register("clearCache") {
                group = "multiloader"
                doLast { clearCache() }
            }
        }
    }

    private fun registerMergedJarTask() {
        project.tasks.register<Jar>("buildMerged") {
            group = "build"
            archiveFileName.set("${mod.idDashed}-${mod.version}+${mod.mc}.jar")
            destinationDirectory.set(rootDir.resolve("build/libs/${mod.version}"))
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE

            loaderProjects.forEach { dependsOn(":$it:classes") }

            val seen = mutableSetOf<String>()
            loaderProjects.forEach { name ->
                val sub = project.rootProject.project(":$name")
                val ss = sub.extensions.getByType<SourceSetContainer>()["main"]
                from(ss.output.classesDirs) {
                    eachFile {
                        if (!seen.add(path)) {
                            logger.warn("[Multiloader] class collision across loaders: $path (from :$name)")
                        }
                    }
                }
            }

            from(buildResourcesDir)
        }
    }

    private fun configureModPublication() {
        mod.pubStart = mod.mc
        mod.pubEnd = if (prop("multiloader.lockPublishVersion") == "true") mod.mc else "latest"

        project.extensions.configure<ModPublishExtension>("publishMods") {
            fun tokenProvider(token: String) = project.provider {
                File("C:\\Tokens\\$token.txt").readText()
            }
            file.set(project.tasks.named<Jar>("buildMerged").flatMap { it.archiveFile })
            displayName.set("${mod.name} ${mod.version}v ${mod.mc}")
            changelog.set(project.provider { processChangelog() })
            version.set(project.version.toString())
            val releaseType = when {
                mod.version.contains("-beta.") -> BETA
                mod.version.contains("-alpha.") -> ALPHA
                else -> STABLE
            }
            type.set(releaseType)
            listOf("fabric", "forge", "neoforge", "quilt").forEach { modLoaders.add(it) }

            modrinth {
                projectId.set(mod.modrinth)
                accessToken.set(tokenProvider("modrinth"))
                minecraftVersionRange {
                    start.set(mod.pubStart)
                    end.set(mod.pubEnd)
                    includeSnapshots.set(true)
                }
            }
            curseforge {
                projectId.set(mod.curseforge)
                accessToken.set(tokenProvider("curseforge"))
                minecraftVersionRange {
                    start.set(mod.pubStart)
                    end.set(mod.pubEnd)
                }
            }
            github {
                accessToken.set(tokenProvider("github"))
                repository.set("BizCub/${mod.github}")
                commitish.set("master")
                tagName.set("v${project.version}")
            }
        }
    }

    private fun neoforgeFix() {
        if (isNeoForge || isForge || isCommon) {
            val mutex = project.gradle.sharedServices.registerIfAbsent(
                "createMinecraftArtifactsMutex",
                NeoForgeMutex::class.java
            ) {
                maxParallelUsages.set(1)
            }
            project.tasks.named { it == "createMinecraftArtifacts" }.configureEach {
                usesService(mutex)
            }
        }
    }

    private fun processChangelog(): String {
        val text = rootDir.resolve("CHANGELOG.md").readText()

        val lines = text.trimEnd().lines()
        if (lines.isEmpty()) return text

        val lastLine = lines.last().trim()

        val idRegex = Regex("^[A-Za-z0-9_-]{11}$")
        if (!idRegex.matches(lastLine)) return text

        val videoBlock = """  
        **▼ Click below to watch the video review on YouTube ▼**  
  
        [![preview](https://img.youtube.com/vi/$lastLine/maxresdefault.jpg)](https://www.youtube.com/watch?v=$lastLine)  
        """.trimIndent()

        val withoutId = lines.dropLast(1).joinToString("\n").trimEnd()
        return if (withoutId.isEmpty()) videoBlock else "$withoutId\n\n$videoBlock"
    }

    private fun createRunConfiguration() {
        val dir = rootDir.resolve(".idea/runConfigurations")
        dir.mkdirs()

        val desired = linkedMapOf<String, String>()

        fun add(name: String, task: String, folder: String = "") {
            val fileName = name.replace(" ", "")
            val folderAttr = if (folder.isEmpty()) "" else " folderName=\"$folder\""
            desired["Multiloader_$fileName.xml"] = getResource("runConfiguration/main.xml")
                .replace("%NAME%", name)
                .replace("%TASK%", task)
                .replace("%FOLDER%", folderAttr)
        }

        add("Run Fabric Client", ":fabric:runClient", "Run")
        add("Run Fabric Server", ":fabric:runServer", "Run")
        add("Run Forge Client", ":forge:runClient", "Run")
        add("Run Forge Server", ":forge:runServer", "Run")
        add("Run NeoForge Client", ":neoforge:runClient", "Run")
        add("Run NeoForge Server", ":neoforge:runServer", "Run")

        add("Build", "buildMerged", "Build")

        add("Update Dependencies", "updateDependencies", "Cache")
        add("Clear Cache", "clearCache", "Cache")
        add("Remove Keys", "removeDependencyKeys", "Cache")

        add("Publish Mods", "publishMods", "Publish")
        add("Publish Modrinth", "publishModrinth", "Publish")
        add("Publish Curseforge", "publishCurseforge", "Publish")
        add("Publish Github", "publishGithub", "Publish")

        dir.listFiles { f -> f.extension == "xml" && f.name.startsWith("Multiloader_") }
            ?.filter { it.name !in desired }
            ?.forEach(File::delete)

        desired.forEach { (name, content) ->
            val file = dir.resolve(name)
            if (!file.exists() || file.readText() != content) file.writeText(content)
        }
    }

    private fun updateOrCreateIssueTemplates() {
        val issueTemplatesDir = rootDir.resolve(".github/ISSUE_TEMPLATE")
        issueTemplatesDir.mkdirs()
        writeIfChanged(issueTemplatesDir.resolve("bug-report.yml"), getResource("issueTemplate/bug-report.yml"))
        writeIfChanged(issueTemplatesDir.resolve("new-feature.yml"), getResource("issueTemplate/new-feature.yml"))
        writeIfChanged(issueTemplatesDir.resolve("config.yml"), getResource("issueTemplate/config.yml"))
    }

    private fun updateOrCreateChangelogFile() {
        rootDir.resolve("CHANGELOG.md").createNewFile()
    }

    private fun updateOrCreateGitIgnoreFile() {
        writeIfChanged(rootDir.resolve(".gitignore"), getResource("mainFiles/gitignore.txt"))
    }

    private fun generateModMetadata() {
        generatedResourcesDir.mkdirs()

        val changeMap = listOf(
            "id" to mod.id,
            "mixin" to mod.idDashed,
            "name" to mod.name,
            "description" to mod.description,
            "version" to "${mod.version}+${mod.mc}",
            "modrinth" to mod.modrinth,
            "github" to mod.github,
            "author" to "Bizarre Cube",
            "license" to "MIT"
        )

        fun getChangedResource(resource: String, escapeNewLines: Boolean): String {
            var changed = getResource(resource)
            changeMap.forEach { (k, v) ->
                val value =
                    if (escapeNewLines && k == "description") v.toString().replace("\n", "\\n") else v.toString()
                changed = changed.replace($$"${$${k}}", value)
            }
            return changed
        }

        fun process(fileName: String, path: String = "", escapeNewLines: Boolean = false) {
            if (!resourcesDir.resolve(path).resolve(fileName).exists()) {
                generatedResourcesDir.resolve(path).mkdirs()
                generatedResourcesDir.resolve(path).resolve(fileName)
                    .writeText(getChangedResource("mod/$fileName", escapeNewLines))
            }
        }

        when {
            isFabric   -> process("fabric.mod.json", escapeNewLines = true)
            isForge    -> {
                process("pack.mcmeta")
                process("mods.toml", "META-INF")
            }
            isNeoForge -> process("neoforge.mods.toml", "META-INF")
        }
    }

    private fun mixinConfigRegistration() {
        when {
            isForge    -> listOf(generatedResourcesDir.resolve("META-INF/mods.toml"))
            isNeoForge -> listOf(generatedResourcesDir.resolve("META-INF/neoforge.mods.toml"))
            else       -> emptyList()
        }.forEach { tomlFile ->
            if (!tomlFile.exists()) return@forEach

            val mapper = TomlMapper()

            @Suppress("UNCHECKED_CAST")
            val data = mapper.readValue(tomlFile, MutableMap::class.java) as MutableMap<String, Any>

            @Suppress("UNCHECKED_CAST")
            val mods =
                data.getOrPut("mods") { mutableListOf<MutableMap<String, Any>>() } as MutableList<MutableMap<String, Any>>
            if (iconFile.exists()) mods[0]["logoFile"] = "icon.png"
            if (iconFile.exists()) mods[0]["iconFile"] = "icon.png"

            if (mixinFile.exists()) data["mixins"] = listOf(mapOf("config" to mixinFile.name))

            mapper.writeValue(tomlFile, data)
        }
    }

    private fun generateFabricModJson() {
        val jsonFile = generatedResourcesDir.resolve("fabric.mod.json")
        if (!jsonFile.exists()) return

        val classes = analyzeJavaSources().toMutableList()
        classes.removeIf { it.entrypointName == "" }

        val json = JSONObject(jsonFile.readText())

        val entrypointsKey = JSONObject()
        json.put("entrypoints", entrypointsKey)
        classes.forEach { entrypointsKey.put(it.entrypointName, JSONArray().put(it.classFilePath)) }

        val mixinsKey = JSONArray()
        json.put("mixins", mixinsKey)
        if (mixinFile.exists()) mixinsKey.put(mixinFile.name)

        if (isMainCTFileExist()) json.put("accessWidener", "${mod.idDashed}.ct")
        if (iconFile.exists()) json.put("icon", "icon.png")

        if (badges.isNotEmpty()) {
            json.put(
                "custom",
                JSONObject().put("modmenu", JSONObject().put("badges", JSONArray(badges)))
            )
        }

        jsonFile.writeText(json.toString(4))
    }

    private fun analyzeJavaSources(): List<ClassInfo> {
        StaticJavaParser.setConfiguration(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        )

        val result = mutableListOf<ClassInfo>()

        val fabricSources = project.rootProject.file("fabric/src/main/java")
        if (!fabricSources.exists()) return result

        fabricSources.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                try {
                    val cu = StaticJavaParser.parse(file)
                    val pkg = cu.packageDeclaration.map { it.name.asString() }.orElse("")

                    val imports = cu.imports
                        .filter { !it.isStatic }
                        .associate { it.name.asString().substringAfterLast('.') to it.name.asString() }

                    fun resolveType(typeName: String): String =
                        if (typeName.contains('.')) typeName
                        else imports[typeName] ?: if (pkg.isNotEmpty()) "$pkg.$typeName" else typeName

                    fun fullClassName(cls: ClassOrInterfaceDeclaration): String {
                        val names = mutableListOf<String>()
                        var c: ClassOrInterfaceDeclaration? = cls
                        while (c != null) {
                            names.add(0, c.nameAsString)
                            c = c.parentNode.orElse(null) as? ClassOrInterfaceDeclaration
                        }
                        val classPart = names.joinToString("$")
                        return if (pkg.isNotEmpty()) "$pkg.$classPart" else classPart
                    }

                    cu.findAll(ClassOrInterfaceDeclaration::class.java)
                        .filter { !it.isInterface }
                        .mapNotNull { cls ->
                            val implemented = cls.implementedTypes.map { resolveType(it.nameWithScope) }
                            entrypoints.firstOrNull { it.second in implemented }?.let { (entryName, _) ->
                                ClassInfo(entryName, fullClassName(cls))
                            }
                        }
                        .forEach { result.add(it) }
                } catch (e: Exception) {
                    val shortMessage = e.message
                        ?.substringBefore("Problem stacktrace :")
                        ?.trim()
                        ?: e.javaClass.simpleName
                    project.logger.warn("Multiloader: failed to parse Java source '${file.path}', skipping it: $shortMessage")
                }
            }

        return result
    }

    private fun configureInit() {
        when {
            isCommon || isFabric -> {
                project.pluginManager.apply("fabric-loom")
                if (isMainCTFileExist())
                    project.extensions.getByType<LoomGradleExtensionAPI>().accessWidenerPath = awBuildFile
            }
            isNeoForge -> project.pluginManager.apply("net.neoforged.moddev")
            else -> {
                project.pluginManager.apply("net.minecraftforge.gradle")

                val commonMain = project.rootProject.project(":common")
                    .extensions.getByType<SourceSetContainer>()["main"]

                project.extensions.getByType<MinecraftExtensionForProject>().runs {
                    configureEach {
                        mods {
                            register(mod.id) {
                                source(getSourceSets()["main"])
                                source(commonMain)
                            }
                            if (getExtraSourceSet() != null)
                                register("testmod") { source(getExtraSourceSet()!!) }
                        }
                    }
                    register("client") { workingDir.set(clientRunFile) }
                    register("server") { workingDir.set(serverRunFile) }
                }
            }
        }

        project.pluginManager.apply("java")
        project.pluginManager.apply("dev.kikugie.fletching-table")

        replayLoaderBlock()
        applyDependencies()

        project.extensions.getByType(BasePluginExtension::class.java).apply {
            archivesName.set(mod.idDashed)
        }

        project.configure<JavaPluginExtension> {
            val javaVersion = JavaVersion.toVersion(25)
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }

        project.afterEvaluate {
            when {
                isCommon   -> configureFabric()
                isFabric   -> configureFabric()
                isNeoForge -> configureNeoForge()
                else       -> configureForge()
            }
            configureTasks()
        }

        project.tasks {
            named<Jar>("jar") {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                if (mixinFile.exists()) {
                    manifest { attributes["MixinConfigs"] = mixinFile.name }
                }
            }
        }
    }

    private fun applyDependencies() {
        project.repositories {
            for (rep in reps) maven(rep.repository)
            mavenLocal()

            if (isForge) {
                val minecraft = project.extensions.getByType<MinecraftExtensionForProject>()
                val fg = project.extensions.getByType<ForgeGradleExtension>()
                minecraft.mavenizer(this)
                maven(fg.forgeMaven)
                maven(fg.minecraftLibsMaven)
            }
        }

        project.dependencies {
            if (!isCommon && !isForge) add("implementation", project.project(":common"))

            if (isCommon || isFabric) {
                "minecraft"("com.mojang:minecraft:${mod.mc}")
                add(if (isCommon) "compileOnly" else "implementation", "net.fabricmc:fabric-loader:${getDep("fabric")}")
            }

            if (isForge) {
                val minecraft = project.extensions.getByType<MinecraftExtensionForProject>()
                "implementation"(minecraft.dependency("net.minecraftforge:forge:${getDep("forge")}"))
                "annotationProcessor"("net.minecraftforge:eventbus-validator:7.0.0")

                val commonMain = project.rootProject.project(":common")
                    .extensions.getByType<SourceSetContainer>()["main"]
                add("compileOnly", commonMain.output)
                add("runtimeOnly", commonMain.output)
            }

            for (dep in deps) add(dep.configuration, dep.dependency) {
                for (module in eModules) exclude(module.module)
            }
        }

        if (isForge) {
            project.repositories {
                val minecraft = project.extensions.getByType<MinecraftExtensionForProject>()
                val fg = project.extensions.getByType<ForgeGradleExtension>()
                minecraft.mavenizer(this)
                maven(fg.forgeMaven)
                maven(fg.minecraftLibsMaven)
            }
        }
    }

    private fun configureTasks() {
        fun configureTask(dependentTask: String, dependencyTask: String) {
            project.tasks.findByName(dependentTask)?.dependsOn(dependencyTask)
        }
        configureTask("validateAccessWidener", "processResources")
        configureTask("createMinecraftArtifacts", "processResources")

        project.tasks.withType<ProcessResources>().configureEach {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            from(generatedResourcesDir)
            if (!isCommon) from(resourcesDir)
        }

        project.tasks.withType<JavaExec>().configureEach {
            if (name == "runClient") {
                if (mixinFile.exists() && isForge) args("--mixin.config=${mixinFile.name}")
                args("--username=$playerName", "--uuid=$playerUUID")
            }
            if (name == "runServer") {
                standardInput = System.`in`
                args("--nogui")
            }
        }
    }

    private fun configureCommon() {
    }

    private fun configureFabric() {
        val loom = project.extensions.getByType<LoomGradleExtensionAPI>()
        loom.apply {
            runConfigs {
                getExtraSourceSet()?.let { ess -> configureEach { sourceSet = ess.name } }
                getByName("client") { runDirectory.set(clientRunFile) }
                getByName("server") { runDirectory.set(serverRunFile) }
            }
        }
    }

    private fun configureForge() {
        val minecraft = project.extensions.getByType<MinecraftExtensionForProject>()
        minecraft.apply {
            mappings("official", mod.mc)
            if (isMainCTFileExist()) accessTransformers.from(atBuildFile)
        }

        val copyCommon = project.tasks.register<Copy>("copyCommonResources") {
            from(resourcesDir)
            from(generatedResourcesDir)
            into(project.extensions.getByType<SourceSetContainer>()["main"].output.resourcesDir!!)
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        project.tasks.named("processResources") { finalizedBy(copyCommon) }
        project.tasks.named("classes") { dependsOn(copyCommon) }
    }

    private fun configureNeoForge() {
        project.dependencies.add("implementation", project.project(":common"))

        project.pluginManager.apply("net.neoforged.moddev")
        project.extensions.configure<NeoForgeExtension> {
            version = getDep("neoforge")
            if (isMainCTFileExist()) accessTransformers.from(atBuildFile)
            val commonMain = project.rootProject.project(":common")
                .extensions.getByType<SourceSetContainer>().named("main").get()
            mods {
                register("main") {
                    sourceSet(getSourceSets().named("main").get())
                    sourceSet(commonMain)
                    getExtraSourceSet()?.let { ess -> sourceSet(ess) }
                }
            }
            runs {
                configureEach { disableIdeRun() }
                register("client") { gameDirectory.set(clientRunFile); client() }
                register("server") { gameDirectory.set(serverRunFile); server() }
            }
        }
    }
}
