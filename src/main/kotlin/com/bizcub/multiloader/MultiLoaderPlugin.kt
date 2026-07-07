package com.bizcub.multiloader

import dev.kikugie.fletching_table.extension.FletchingTableExtension
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import dev.kikugie.stonecutter.controller.StonecutterControllerExtension
import me.modmuss50.mpp.ModPublishExtension
import me.modmuss50.mpp.platforms.modrinth.ModrinthEnvironment
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.internal.DefaultTaskExecutionRequest
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.text.replace

fun String.upperCaseFirst() = replaceFirstChar { it.uppercaseChar() }
fun String.lowerCaseFirst() = replaceFirstChar { it.lowercaseChar() }

class MultiLoaderPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val multiloader = project.extensions.create("multiloader", MultiLoader::class.java)

        val version = javaClass.`package`.implementationVersion ?: "unknown"
        project.logger.lifecycle("Running MultiLoader $version")

        val isNameSameAsRootProject = project.name == project.rootProject.name
        if (isNameSameAsRootProject) multiloader.firstInit()
        if (!isNameSameAsRootProject) multiloader.init()
    }
}

open class MultiLoader(private val project: Project) {
    interface NeoForgeMutex : BuildService<BuildServiceParameters.None>

    val reps = mutableListOf<Repository>()
    class Repository(val repository: String)

    val deps = mutableListOf<Dependency>()
    class Dependency(val configuration: String, val dependency: String) {
        val id = dependency.split(":")[1]
        val modConfiguration = "mod${configuration.upperCaseFirst()}"
    }

    val eModules = mutableListOf<Module>()
    class Module(val module: String)

    val mrEnvs = MREnvs()
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
        val mc: String get() = scc.version
        val mcExact: String get() = propIf("version", mc)
        val loader: String get() = scc.project.substringAfterLast("-")
        val id: String get() = modProp("id")
        val mixin: String get() = id.replace("_", "-")
        val name: String get() = modProp("name")
        val description: String get() = modProp("description")
        val version: String get() = modProp("version")
        val modrinth: String get() = modProp("modrinth")
        val curseforge: String get() = modProp("curseforge")
        val github: String get() = modProp("github")
        val pubStart: String get() = propIf("pub-start", mc)
        val pubEnd: String get() = propIf("pub-end", mc)
    }

    class Entrypoint(val entrypointName: String, val implementedClassName: String, var className: String, var classFile: File)

    private var isArchitectury = false

    private val updateDependencies = UpdateDependencies(project, this)
    private val hotfixesList = listOf("1.21.10", "1.21.8", "1.21.7", "1.21.3", "1.21.1", "1.20.6", "1.20.4", "1.20.1", "1.19.2", "1.19.1", "1.18.1")
    private val publishPlatforms = listOf("Mods", "Modrinth", "Curseforge", "Github")
    private val mainTasks = listOf(
        Pair("0 Run Client", "runActiveClient"),
        Pair("0 Run Server", "runActiveServer"),
        Pair("1 Build Active", "buildActive"),
        Pair("1 Build All", "buildAndCollect"),
        Pair("2 Publish Mods", "PublishMods"),
        Pair("2 Publish Modrinth", "PublishModrinth"),
        Pair("2 Publish CurseForge", "PublishCurseforge"),
        Pair("2 Publish GitHub", "PublishGithub")
    )
    private val entrypoints = mutableListOf(
        Entrypoint("client", "net.fabricmc.api.ClientModInitializer", "", File("")),
        Entrypoint("main", "net.fabricmc.api.ModInitializer", "", File("")),
        Entrypoint("modmenu", "com.terraformersmc.modmenu.api.ModMenuApi", "", File(""))
    )

    val sc get() = project.extensions.getByType<StonecutterBuildExtension>()
    val scc get() = sc.current
    val scp get() = scc.parsed

    val isFabric: Boolean get() = mod.loader == "fabric"
    val isForge: Boolean get() = mod.loader == "forge"
    val isNeoForge: Boolean get() = mod.loader == "neoforge"
    val isObfuscated: Boolean get() = scp < "26.1"

    val clientRunFile: File get() = project.file("../../run/client")
    val serverRunFile: File get() = project.file("../../run/server")
    val mixinFile: File get() = project.rootProject.file("src/main/resources/${mod.mixin}.mixins.json")
    val ctFabricFile: File get() = project.rootProject.file("src/main/resources/${mod.mixin}.ct")
    val ctForgeArchFile: File get() = project.file("build/generated/stonecutter/main/resources/${mod.mixin}.ct")
    val atForgeFile: File get() = project.file("build/sourceSets/main/META-INF/accesstransformer.cfg")
    val atNeoForgeFile: File get() = project.file("build/resources/main/META-INF/accesstransformer.cfg")
    val ctFabricProcessPath: String get() = "build/resources/main/${mod.mixin}.ct"

    fun prop(key: String): String? = project.findProperty(key)?.toString()
    fun modProp(key: String) = prop("mod.$key") as String
    fun getProp(key: String) = prop(propName(key))
    fun setProp(key: String, value: Any?) = value.also { project.extra[versionExactlyProp(key)] = it }
    fun propName(key: String) = if (prop(versionExactlyProp(key)) != null) versionExactlyProp(key) else versionProp(key)
    fun propIf(key: String, fallback: String) = prop(propName(key)) ?: fallback
    fun versionProp(key: String) = "${mod.mc}.$key"
    fun versionExactlyProp(key: String) = "${mod.mc}-${mod.loader}.$key"

    fun firstInit() {
        createDepFile()
        setCustomProjectIcon()
        setStonecutterParameters()
    }

    fun init() {
        setServerProperties()
        neoforgeFix()
        createRunConfiguration()
        access()
        setProperties()
        updateOrCreateIssueTemplates()

        var pubStart = mod.pubStart
        var pubEnd = mod.pubEnd

        if (prop("multiloader.editPublishVersions") == "true") {
            val publishVersionList = getPublishVersion(mod.mc)
            pubStart = propIf("pub-start", publishVersionList.first())
            pubEnd = propIf("pub-end", publishVersionList.last())
        }

        configureGradle(pubStart)
        configureModPublication(pubStart, pubEnd)
        generateModMetadata()
        addTaskToQueue()

        addDependency(repository = "api.modrinth.com/maven")
        if (isNeoForge) addDependency(repository = "maven.neoforged.net/releases")

        project.afterEvaluate {
            afterEvaluate()
        }
    }

    private fun afterEvaluate() {
        configureTasks()
    }

    fun setBuiltFile(builtFile: Provider<RegularFile>) {
        publishMods {
            file.set(builtFile)
        }

        project.tasks.named<Copy>("buildAndCollect") {
            from(builtFile)
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

    fun architectury() {
        isArchitectury = true
    }

    fun addEntrypoint(entrypointName: String, implementedClassName: String) {
        entrypoints.add(Entrypoint(entrypointName, implementedClassName, "", File("")))
    }

    fun addDependency(
        repository: String = "",
        configuration: String = "implementation",
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
            deps.add(Dependency(configuration, dependency))
        }
        if (isPublishDepEnabled) {
            addPublishDep(
                if (isPublishDepRequired) "requires" else "optional",
                publishProjectId.ifEmpty { deps[deps.size - 1].id }
            )
        }
        excludedModules.forEach { module -> eModules.add(Module(module)) }
    }

    fun getDep(key: String): String {
        val dep = updateDependencies.getDep(key)

        if (key == "fabric") {
            project.configurations.all {
                resolutionStrategy.force("net.fabricmc:fabric-loader:$dep")
            }
        }

        return dep
    }

    fun getMinCompatVersion(version: String): String {
        fun checkVersion(version: String): String {
            return if (sc.eval(version, ">=26.1")) {
                if (version.count { it == '.' } >= 2) {
                    version.reversed().split(".", limit = 2).last().reversed()
                } else version
            } else {
                if (hotfixesList.contains(version)) {
                    if (version.count { it == '.' } >= 2) {
                        val minorVers = version.substringAfterLast(".").toInt() - 1
                        val minorVersStr = if (minorVers == 0) "" else ".$minorVers"
                        "${version.reversed().split(".", limit = 2).last().reversed()}$minorVersStr"
                    } else version
                } else version
            }
        }

        var tempVersion = checkVersion(version)
        if (version == tempVersion) {
            return version
        } else {
            while (true) {
                if (tempVersion == checkVersion(tempVersion)) {
                    return tempVersion
                } else {
                    tempVersion = checkVersion(tempVersion)
                }
            }
        }
    }

    private fun publishMods(block: ModPublishExtension.() -> Unit) {
        project.extensions.configure<ModPublishExtension>("publishMods", block)
    }

    private fun addPublishDep(requirement: String, mrSlug: String, cfSlug: String = mrSlug) {
        project.extensions.configure<ModPublishExtension>("publishMods") {
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

    private fun getPublishVersion(version: String): List<String> {

        fun calculate(version: String): List<String> {
            fun add(int: Int): Int {
                return int + 1
            }

            fun remove(int: Int): Int {
                return if (int - 1 >= 0) {
                    int - 1
                } else int
            }

            val split = version.split(".")
            val strVersion = "${split[0]}.${split[1]}"
            val pubVersion = if (split.count() != 2) {
                split[2].toInt()
            } else 0

            return listOf("$strVersion.${remove(pubVersion)}", "$strVersion.${add(pubVersion)}")
        }

        if (!isObfuscated) {
            val allVersionsList = updateDependencies.getMinecraftVersionList("https://piston-meta.mojang.com/mc/game/version_manifest.json")

            val list = mutableListOf<String>()
            val publishVersion = if (version.split(".").count() == 3) {
                version.substring(0, version.length - 2)
            } else version

            var lastVersion = version
            for (vers in allVersionsList) {
                if (vers.startsWith(publishVersion)) {
                    lastVersion = vers
                    break
                }
            }

            list.add(publishVersion)
            list.add(lastVersion)
            return list
        } else {
            val list = mutableListOf<String>()
            for (i in 0..1) {
                var publishVersion = version
                while (true) {
                    val tempVersion = calculate(publishVersion)[i]
                    if (hotfixesList.contains(if (i == 0) publishVersion else tempVersion)) {
                        publishVersion = tempVersion
                    } else break
                }
                if (publishVersion.endsWith(".0"))
                    publishVersion = publishVersion.substring(0, publishVersion.length - 2)
                list.add(publishVersion)
            }
            return list
        }
    }

    private fun createDepFile() {
        updateDependencies.createDepFile()
    }

    private fun getResource(resource: String): String {
        return this.javaClass.classLoader.getResource(resource).readText()
    }

    private fun access() {
        val ft = project.extensions.getByType<FletchingTableExtension>()

        ft.accessConverter.register("main") {
            add("${mod.mixin}.ct")
        }
    }

    private fun setCustomProjectIcon() {
        val iconFile = project.file("src/main/resources/icon.png")
        if (iconFile.exists()) {
            iconFile.copyTo(project.file(".idea/icon.png"), true)
        }
    }

    private fun setServerProperties() {
        serverRunFile.mkdirs()
        val eulaFile = serverRunFile.resolve("eula.txt")
        val propertiesFile = serverRunFile.resolve("server.properties")
        eulaFile.createNewFile()
        eulaFile.writeText("eula=true")
        if (!propertiesFile.exists()) {
            propertiesFile.createNewFile()
            propertiesFile.writeText("online-mode=false")
        }
    }

    private fun configureModPublication(pubStart: String, pubEnd: String) {
        if (getProp("version") == null) {
            project.extensions.configure<ModPublishExtension>("publishMods") {
                fun tokenDir(token: String) = File("C:\\Tokens\\$token.txt").readText()
                displayName.set("${mod.name} ${mod.loader.replaceFirstChar { it.uppercaseChar() }} $pubStart v${mod.version}")
                changelog.set(project.rootDir.resolve("CHANGELOG.md").readText())
                version.set(project.version.toString())
                val releaseType = if (mod.version.contains("-beta.")) BETA
                else if (mod.version.contains("-alpha.")) ALPHA
                else STABLE
                type.set(releaseType)
                modLoaders.add(mod.loader)
                if (isFabric) modLoaders.add("quilt")

                modrinth {
                    projectId.set(mod.modrinth)
                    accessToken.set(tokenDir("modrinth"))
                    minecraftVersionRange {
                        start.set(pubStart)
                        end.set(pubEnd)
                        includeSnapshots.set(true)
                    }
                }
                curseforge {
                    projectId.set(mod.curseforge)
                    accessToken.set(tokenDir("curseforge"))
                    minecraftVersionRange {
                        start.set(pubStart)
                        end.set(pubEnd)
                    }
                }
                github {
                    accessToken.set(tokenDir("github"))
                    repository.set("BizCub/${mod.github}")
                    commitish.set("master")
                    tagName.set("v${project.version}")
                }
            }
        }
    }

    private fun neoforgeFix() {
        if (isNeoForge) {
            // This whole thing prevents neoforge from frying your computer by recompiling Minecraft on multiple versions
            val mutex = project.gradle.sharedServices.registerIfAbsent("createMinecraftArtifactsMutex", NeoForgeMutex::class.java) {
                maxParallelUsages.set(1)
            }

            project.tasks.named { it == "createMinecraftArtifacts" }.configureEach {
                usesService(mutex)
            }
        }
    }

    private fun setProperties() {
        project.extra["loom.platform"] = mod.loader
        if (isObfuscated) project.extra["fabric.loom.disableObfuscation"] = false

        if (isForge && scp > "1.21.3") {
            setProp("cloth-config", "17.0.144")
        }
    }

    private fun createRunConfiguration() {
        val filePath = project.rootDir.resolve(".idea/runConfigurations")
        filePath.mkdirs()

        fun createFile(name: String, content: String, replaceName: String, replaceTask: String) {
            val file = filePath.resolve("$name.xml")
            file.createNewFile()
            file.writeText(content
                .replace("%NAME%", replaceName)
                .replace("%TASK%", replaceTask)
            )
        }

        mainTasks.forEach { (name, task) ->
            val fileName = name.split(" ", limit = 2)[1].replace(" ", "")
            createFile(fileName, getResource("runConfiguration/runConfigurationMain.xml"), name, task)
        }

        publishPlatforms.forEach { platform ->
            val name = "Publish $platform ${mod.mc}"
            val fileName = name.replace(" ", "")
            val task = fileName.lowerCaseFirst()
            createFile(fileName, getResource("runConfiguration/runConfigurationPublish.xml"), name, task)
        }
    }

    private fun updateOrCreateIssueTemplates() {
        val issueTemplatesDir = project.rootDir.resolve(".github/ISSUE_TEMPLATE")
        issueTemplatesDir.mkdirs()
        val bugReportFile = issueTemplatesDir.resolve("bug-report.yml")
        bugReportFile.writeText(getResource("issueTemplate/bug-report.yml"))
        val newFeatureFile = issueTemplatesDir.resolve("new-feature.yml")
        newFeatureFile.writeText(getResource("issueTemplate/new-feature.yml"))
        val issueConfigFile = issueTemplatesDir.resolve("config.yml")
        issueConfigFile.writeText(getResource("issueTemplate/config.yml"))
    }

    private fun generateModMetadata() {
        val buildPath = project.projectDir.resolve(if (!isForge || isArchitectury) "build/resources/main" else "build/sourceSets/main")
        buildPath.resolve("META-INF").mkdirs()

        val changeMap = listOf(
            "id"            to mod.id,
            "mixin"         to mod.mixin,
            "name"          to mod.name,
            "description"   to if (isFabric) mod.description.replace("\n", "\\n") else mod.description,
            "version"       to project.version,
            "modrinth"      to mod.modrinth,
            "github"        to mod.github,
            "author"        to "Bizarre Cube",
            "license"       to "MIT"
        )

        fun getChangedResource(resource: String): String {
            var changedResource = getResource(resource)
            changeMap.forEach { changedResource = changedResource.replace($$"${$${it.first}}", it.second.toString()) }
            return changedResource
        }

        fun process(fileName: String, path: String = "") {
            buildPath.resolve(path).resolve(fileName).writeText(getChangedResource("mod/${fileName}"))
        }

        process("pack.mcmeta")
        if (isFabric) process("fabric.mod.json")
        if (isForge) process("mods.toml", "META-INF")
        if (isNeoForge) process("neoforge.mods.toml", "META-INF")
    }

    private fun configureGradle(pubStart: String) {
        project.extensions.getByType(BasePluginExtension::class.java).apply {
            archivesName.set(mod.mixin)
        }

        project.version = "${mod.version}-${mod.loader}+$pubStart"

        project.tasks {
            publishPlatforms.forEach { publish ->
                register<Copy>("publish$publish${mod.mc}") {
                    group = "publishing"
                    dependsOn("publish$publish")
                }
            }
            register<Copy>("buildAndCollect") {
                group = "build"
                into(project.rootDir.resolve("build/libs/${mod.version}"))
                dependsOn("build")
            }
            if (scc.isActive) {
                register("buildActive") { dependsOn(named("buildAndCollect")) }
                register("runActiveClient") { dependsOn(named("runClient")) }
                register("runActiveServer") { dependsOn(named("runServer")) }
            }
            named<Jar>("jar") {
                manifest {
                    attributes["MixinConfigs"] = mixinFile.name
                }
            }
            named("processResources") {
                doLast {
                    entrypointRegistration()
                }
            }
        }

        project.configure<JavaPluginExtension> {
            val javaSCNumber = when {
                scp >= "26.1"   -> 25
                scp >= "1.20.5" -> 21
                scp >= "1.18"   -> 17
                scp >= "1.17"   -> 16
                else            -> 8
            }
            val javaVersion = JavaVersion.toVersion(javaSCNumber)
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
            toolchain.languageVersion.set(JavaLanguageVersion.of(javaSCNumber))
        }
    }

    private fun setStonecutterParameters() {
        project.pluginManager.withPlugin("dev.kikugie.stonecutter") {
            val ext = project.extensions.findByType(StonecutterControllerExtension::class.java) ?: return@withPlugin
            ext.parameters {
                val (version, loader) = current.project.split('-', limit = 2)
                properties.tags(version, loader)
                constants.match(node.metadata.project.substringAfterLast('-'), "fabric", "neoforge", "forge")
                swaps["mod_id"] = "\"${project.property("mod.id")}\";"
                replacements.string(current.parsed >= "26.1") {
                    replace("classTweaker v1 named", "classTweaker v1 official")
                }
            }
        }
    }

    private fun configureTasks() {
        fun configureTask(task1: String, task2: String) {
            project.tasks.findByName(task1)?.dependsOn(task2)
        }

        configureTask("validateAccessWidener", "processResources")
        configureTask("createMinecraftArtifacts", "processResources")

        if (isNeoForge) {
            project.tasks.named<JavaExec>("runServer") {
                standardInput = System.`in`
            }
        }
    }

    private fun addTaskToQueue() {
        val isIdeaSync = System.getProperty("idea.sync.active", "false").toBoolean()
        if (isIdeaSync) {
            val sp = project.gradle.startParameter
            sp.setTaskRequests(
                sp.taskRequests + DefaultTaskExecutionRequest(
                    listOf("processResources"),
                    project.path,
                    project.projectDir
                )
            )
        }
    }

    private fun entrypointRegistration() {
        val jsonFile = project.file("build/resources/main/fabric.mod.json")

        if (!isFabric || !jsonFile.exists()) return

        val entrypointPairs = Files.walk(project.rootProject.file("src/main/java").toPath())
            .filter { Files.isRegularFile(it) }
            .map { Pair(it.toString().replace("\\", ".").split("src.main.java.")[1].dropLast(5), it.toFile()) }
            .toList()
            .toMutableList()

        entrypoints.forEach { entry ->
            entrypointPairs.forEach { pair ->
                if (pair.second.readText().contains(entry.implementedClassName)) {
                    entry.className = pair.first
                    entry.classFile = pair.second
                }
            }
        }

        entrypoints.removeIf { it.className == "" }

        val jsonString = jsonFile.readText()
        val json = JSONObject(jsonString)
        val entrypointsKey = json.optJSONObject("entrypoints") ?: JSONObject().also {
            json.put("entrypoints", it)
        }

        entrypoints.forEach { entrypoint ->
            entrypointsKey.put(entrypoint.entrypointName, JSONArray().put(entrypoint.className))
        }

        jsonFile.writeText(json.toString(4))
    }
}
