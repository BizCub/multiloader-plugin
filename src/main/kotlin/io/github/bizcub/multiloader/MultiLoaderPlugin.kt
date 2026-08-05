package io.github.bizcub.multiloader

import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import dev.kikugie.fletching_table.extension.FletchingTableExtension
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import dev.kikugie.stonecutter.controller.StonecutterControllerExtension
import dev.kikugie.stonecutter.settings.StonecutterSettingsExtension
import me.modmuss50.mpp.ModPublishExtension
import me.modmuss50.mpp.platforms.modrinth.ModrinthEnvironment
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
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.internal.DefaultTaskExecutionRequest
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun String.upperCaseFirst() = replaceFirstChar { it.uppercaseChar() }
fun String.lowerCaseFirst() = replaceFirstChar { it.lowercaseChar() }

class MultiLoaderPlugin : Plugin<ExtensionAware> {
    override fun apply(project: ExtensionAware) {
        val version = javaClass.`package`.implementationVersion ?: "unknown"
        when (project) {
            is Settings -> {
                val ml = project.extensions.create("multiloader", MultiLoaderSettings::class.java)

                project.rootProject.name = project.extra["mod.name"] as String

                project.gradle.settingsEvaluated {
                    ml.applyStonecutter(project)

                    Logging.getLogger("multiloader").lifecycle("Running Settings MultiLoader $version")
                }
            }
            is Project -> {
                val multiloader = project.extensions.create("multiloader", MultiLoader::class.java)

                project.logger.lifecycle("Running MultiLoader $version")

                val isNameSameAsRootProject = project.name == project.rootProject.name
                if (isNameSameAsRootProject) multiloader.firstInit()
                if (!isNameSameAsRootProject) multiloader.init()
            }
        }
    }
}

open class MultiLoaderSettings {
    val fb = "fabric"; val fg = "forge"; val nf = "neoforge"

    private val pendingVersions = mutableListOf<Pair<String, Array<out String>>>()

    fun match(version: String, vararg loaders: String) {
        pendingVersions.add(version to loaders)
    }

    internal fun applyStonecutter(settings: Settings) {
        val stonecutter = settings.extensions.getByType<StonecutterSettingsExtension>()

        stonecutter.create(settings.rootProject) {
            pendingVersions.forEach { (ver, loaders) ->
                loaders.forEach { loader ->
                    val suffix = if (loader == fg && stonecutter.eval(ver, "<1.21")) ".arch" else ""
                    version("$ver-$loader", ver).buildscript.set("buildscripts/$loader$suffix.gradle.kts")
                }
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
        val modConfiguration = "mod${configuration.upperCaseFirst()}"
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

    class ClassInfo(val entrypointName: String, val classFilePath: String)

    private val updateDependencies = UpdateDependencies(project, this)
    private val hotfixesList = listOf("1.21.10", "1.21.8", "1.21.7", "1.21.3", "1.21.1", "1.20.6", "1.20.4", "1.20.1", "1.19.2", "1.19.1", "1.18.1")
    private val publishPlatforms = listOf("Mods", "Modrinth", "Curseforge", "Github")
    private val mainTasks = listOf(
        Pair("0 Run Client", "runActiveClient"),
        Pair("0 Run Server", "runActiveServer"),
        Pair("1 Build Active", "buildActive"),
        Pair("1 Build All", "buildAndCollect")
    )
    private val entrypoints = mutableListOf(
        "client" to "net.fabricmc.api.ClientModInitializer",
        "main" to "net.fabricmc.api.ModInitializer",
        "modmenu" to "com.terraformersmc.modmenu.api.ModMenuApi"
    )

    private val buildDir: File get() = project.file("build")
    private val resourcesDir: File get() = project.rootProject.file("src/main/resources")
    private val buildResourcesDir: File get() = buildDir.resolve("resources/main")
    private val buildResourcesDirForge: File get() = buildDir.resolve("sourceSets/main")

    val clientRunFile: File get() = project.file("../../run/client")
    val serverRunFile: File get() = project.file("../../run/server")
    val iconFile: File get() = resourcesDir.resolve("icon.png")
    val mixinFile: File get() = resourcesDir.resolve("${mod.mixin}.mixins.json")
    val ctMainFile: File get() = resourcesDir.resolve("${mod.mixin}.ct")
    val ctFabricFile: File get() = buildResourcesDir.resolve("${mod.mixin}.ct")
    val ctForgeArchFile: File get() = ctFabricFile
    val atForgeFile: File get() = buildResourcesDirForge.resolve("META-INF/accesstransformer.cfg")
    val atNeoForgeFile: File get() = buildResourcesDir.resolve("META-INF/accesstransformer.cfg")

    val sc get() = project.extensions.getByType<StonecutterBuildExtension>()
    val scc get() = sc.current
    val scp get() = scc.parsed

    val isFabric: Boolean get() = mod.loader == "fabric"
    val isForge: Boolean get() = mod.loader == "forge"
    val isNeoForge: Boolean get() = mod.loader == "neoforge"
    val isForgeLegacy: Boolean get() = scp < "1.21"
    val isObfuscated: Boolean get() = scp < "26.1"

    val playerName: String get() = "BizarreCube"
    val playerUUID: String get() = updateDependencies.getPlayerUUIDbyName(playerName)

    val fabricJarTask: String get() = if (!isObfuscated) "jar" else "remapJar"

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
        generateAccessFiles()
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
        addTaskToQueue()

        addDependency(repository = "api.modrinth.com/maven")
        if (isNeoForge) addDependency(repository = "maven.neoforged.net/releases")

        project.afterEvaluate {
            afterEvaluate()
        }
    }

    private fun afterEvaluate() {
        configureCommon()
        configureTasks()
    }

    private fun afterProcessResources() {
        createRunConfiguration()
        generateAccessFiles()
        generateModMetadata()
        mixinConfigRegistration()
        entrypointRegistration()
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

    fun addEntrypoint(entrypointName: String, implementedClassName: String) {
        entrypoints.add(entrypointName to implementedClassName)
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

    fun getDep(key: String): String {
        val dep = updateDependencies.getDep(key)

        if (key == "fabric") {
            project.configurations.all {
                resolutionStrategy.force("net.fabricmc:fabric-loader:$dep")
            }
        }

        return dep
    }

    fun isMainCTFileExist(): Boolean {
        return ctMainFile.exists()
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

    private fun getSourceSets(): SourceSetContainer {
        return project.extensions.getByType<SourceSetContainer>()
    }

    private fun publishMods(block: ModPublishExtension.() -> Unit) {
        project.extensions.configure("publishMods", block)
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

    private fun setCustomProjectIcon() {
        val iconFile = resourcesDir.resolve("icon.png")
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

    private fun createRunConfigurationFile(name: String, content: String, replaceName: String, replaceTask: String) {
        val filePath = project.rootDir.resolve(".idea/runConfigurations")
        filePath.mkdirs()

        val file = filePath.resolve("$name.xml")
        file.createNewFile()
        file.writeText(content
            .replace("%NAME%", replaceName)
            .replace("%TASK%", replaceTask)
        )
    }

    private fun definitionFileConfigurationNameAndCreate(resource: String, name: String, task: String = "") {
        val fileName = name.split(" ", limit = 2)[1].replace(" ", "")
        var task1 = task
        if (task.isEmpty()) {
            task1 = fileName.lowerCaseFirst()
        }
        if (project.tasks.findByName(task1) != null) {
            createRunConfigurationFile(fileName, getResource("runConfiguration/$resource.xml"), name, task1)
        }
    }

    private fun createRunConfiguration() {
        mainTasks.forEach { (name, task) ->
            definitionFileConfigurationNameAndCreate("runConfigurationMain", name, task)
        }

        publishPlatforms.forEach { platform ->
            val name = "0 Publish $platform"
            definitionFileConfigurationNameAndCreate("runConfigurationPublish", name)
        }

        publishPlatforms.forEach { platform ->
            val name = "1 Publish $platform ${mod.mc}"
            definitionFileConfigurationNameAndCreate("runConfigurationPublish", name)
        }

        val envs = listOf("Client", "Server")
        val sourceSetList = getSourceSets().stream()
            .filter { it.name != "main" && it.name != "test" }
            .map { it.name }
            .toList()

        envs.forEach { env ->
            sourceSetList.forEach { sourceSet ->
                val name = "0 Run ${sourceSet.upperCaseFirst()} $env"
                definitionFileConfigurationNameAndCreate("runConfigurationMain", name)
            }
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

    private fun access() {
        if ((isForge && !isForgeLegacy) || isNeoForge) {
            val ft = project.extensions.getByType<FletchingTableExtension>()

            ft.accessConverter.register("main") {
                add("${mod.mixin}.ct")
            }
        }
    }

    private fun generateAccessFiles() {
        if (!isMainCTFileExist()) return

        if (isFabric || (isForge && isForgeLegacy)) {
            sc.process(ctMainFile, "build/resources/main/${mod.mixin}.ct")
        }
    }

    private fun generateModMetadata() {
        val buildPath = if (!isForge || isForgeLegacy) buildResourcesDir else buildResourcesDirForge
        buildPath.mkdirs()
        if (!isFabric) buildPath.resolve("META-INF").mkdirs()

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
            if (!resourcesDir.resolve(path).resolve(fileName).exists()) {
                buildPath.resolve(path).resolve(fileName).writeText(getChangedResource("mod/${fileName}"))
            }
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
            named<Jar>("jar") {
                manifest {
                    attributes["MixinConfigs"] = mixinFile.name
                }
            }
            named("processResources") {
                doLast {
                    afterProcessResources()
                }
            }
            named<ProcessResources>("processResources") {
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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

                val map = linkedMapOf<String, Int>()
                map["accessWidener"] = 2
                map["classTweaker"] = 2
                replacements.string(current.parsed >= "26.1") {
                    for ((key, value) in map) {
                        for (i in 1..value) {
                            val str = "$key v$i"
                            replace("$str named", "$str official")
                        }
                    }
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

        project.tasks {
            if (scc.isActive) {
                register("buildActive") { dependsOn(named("buildAndCollect")) }
                register("runActiveClient") { dependsOn(named("runClient")) }
                register("runActiveServer") { dependsOn(named("runServer")) }
            }
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

    private fun mixinConfigRegistration() {
        val tomlFile = if (isForge && !isForgeLegacy)
            buildResourcesDirForge.resolve("META-INF/mods.toml")
        else {
            buildResourcesDir.resolve("META-INF/neoforge.mods.toml")
        }

        if (!isForge && !isNeoForge || !tomlFile.exists()) return

        val mapper = TomlMapper()
        val data = mapper.readValue(tomlFile, MutableMap::class.java) as MutableMap<String, Any>

        val mods = data.getOrPut("mods") { mutableListOf<MutableMap<String, Any>>() } as MutableList<MutableMap<String, Any>>
        if (iconFile.exists()) mods[0]["logoFile"] = "icon.png"

        if (mixinFile.exists()) data["mixins"] = listOf(mapOf("config" to mixinFile.name))

        mapper.writeValue(tomlFile, data)
    }

    private fun entrypointRegistration() {
        val jsonFile = buildResourcesDir.resolve("fabric.mod.json")

        if (!isFabric || !jsonFile.exists()) return

        val classes = analyzeJavaSources().toMutableList()
        classes.removeIf { it.entrypointName == "" }

        val jsonString = jsonFile.readText()
        val json = JSONObject(jsonString)

        val entrypointsKey = JSONObject()
        json.put("entrypoints", entrypointsKey)

        classes.forEach { entrypointsKey.put(it.entrypointName, JSONArray().put(it.classFilePath)) }

        val mixinsKey = JSONArray()
        json.put("mixins", mixinsKey)

        if (mixinFile.exists()) mixinsKey.put(mixinFile.name)

        if (isMainCTFileExist()) json.put("accessWidener", "${mod.mixin}.ct")

        if (iconFile.exists()) json.put("icon", "icon.png")

        jsonFile.writeText(json.toString(4))
    }

    private fun analyzeJavaSources(): List<ClassInfo> {
        StaticJavaParser.setConfiguration(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        )

        val result = mutableListOf<ClassInfo>()

        project.rootProject.rootDir.resolve("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
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
            }

        return result
    }

    private fun configureCommon() {
        project.repositories {
            for (rep in reps) maven(rep.repository)
            mavenLocal()
        }

        project.dependencies {
            for (dep in deps) add(if (isFabric && isObfuscated) dep.modConfiguration else dep.configuration, dep.dependency) {
                for (module in eModules) exclude(module.module)
            }
        }
    }
}
