package com.bizcub.multiloader

import dev.kikugie.fletching_table.extension.FletchingTableExtension
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import me.modmuss50.mpp.ModPublishExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

fun String.upperCaseFirst() = replaceFirstChar { it.uppercaseChar() }
fun String.lowerCaseFirst() = replaceFirstChar { it.lowercaseChar() }

var runConfigurationMainText = ""
var runConfigurationPublishText = ""
var javaSCNumber = 0

class MultiLoaderPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("multiloader", MultiLoader::class.java)
        project.extensions.create("ml", MultiLoader::class.java)

        runConfigurationMainText = this.javaClass.classLoader.getResource("runConfigurationMain.xml").readText()
        runConfigurationPublishText = this.javaClass.classLoader.getResource("runConfigurationPublish.xml").readText()
    }
}

open class MultiLoader(private val project: Project) {
    fun init() {
        project.extra["loom.platform"] = mod.loader
        if (isObfuscated) project.extra["fabric.loom.disableObfuscation"] = false

        var pubStart = mod.pubStart
        var pubEnd = mod.pubEnd

        if (prop("multiloader.editPublishVersions") == "true") {
            val publishVersionList = getPublishVersion(mod.mc)
            pubStart = propIf("pub-start", publishVersionList.first())
            pubEnd = propIf("pub-end", publishVersionList.last())
        }

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
        }

        project.tasks.named<Jar>("jar") {
            manifest {
                attributes["MixinConfigs"] = "${mod.mixin}.mixins.json"
            }
        }

        project.tasks.withType<ProcessResources> {
            fun properties(files: Iterable<String>, vararg properties: Pair<String, Any>) {
                for ((name, value) in properties) inputs.property(name, value)
                filesMatching(files) {
                    expand(properties.toMap())
                }
            }
            properties(
                listOf("fabric.mod.json", "META-INF/*.toml"),
                "ModMenu"       to $$"$ModMenu",
                "Server"       to $$"$Server",
                "id"            to mod.id,
                "mixin"         to mod.mixin,
                "name"          to mod.name,
                "description"   to mod.description,
                "version"       to project.version,
                "modrinth"      to mod.modrinth,
                "github"        to mod.github,
                "author"        to "Bizarre Cube",
                "license"       to "MIT"
            )
        }

        project.configure<JavaPluginExtension> {
            javaSCNumber = when {
                scp >= "26.1"   -> 25
                scp >= "1.20.5" -> 21
                scp >= "1.18"   -> 17
                scp >= "1.17"   -> 16
                else            -> 8
            }
            val javaVersion = JavaVersion.toVersion(javaSCNumber)
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }

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

        if (!isClothConfigAvailable) {
            setProp("cloth-config", "17.0.144")
        }

        addDependency("api.modrinth.com/maven")
        if (isNeoForge) addDependency("maven.neoforged.net/releases")

        createRunConfiguration()
    }

    fun access() {
        val ft = project.extensions.getByType<FletchingTableExtension>()

        ft.accessConverter.register("main") {
            add("${mod.mixin}.ct")
        }
    }

    val sc get() = project.extensions.getByType<StonecutterBuildExtension>()
    val scc get() = sc.current
    val scp get() = scc.parsed

    val mod = Mod()
    inner class Mod {
        val mc: String get() = scc.version
        val mcExact: String get() = propIf("version", mod.mc)
        val loader: String get() = scc.project.substringAfterLast("-")
        val id: String get() = modProp("id")
        val mixin: String get() = mod.id.replace("_", "-")
        val name: String get() = modProp("name")
        val description: String get() = modProp("description")
        val version: String get() = modProp("version")
        val modrinth: String get() = modProp("modrinth")
        val curseforge: String get() = modProp("curseforge")
        val github: String get() = modProp("github")
        val pubStart: String get() = propIf("pub-start", mc)
        val pubEnd: String get() = propIf("pub-end", mc)
        val javaNumber: Int get() = javaSCNumber
    }

    val updateDependencies = UpdateDependencies(project, this)

    fun addPublishDep(requirement: String, mrSlug: String, cfSlug: String = mrSlug) {
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

    val reps = mutableListOf<Repository>()
    val deps = mutableListOf<Dependency>()

    class Repository(val repository: String)
    class Dependency(val configuration: String, val dependency: String) {
        val id = dependency.split(":")[1]
        val modConfiguration = "mod${configuration.upperCaseFirst()}"
    }

    fun addDependency(repository: String) {
        reps.add(Repository("https://$repository"))
    }

    fun addDependency(configuration: String, dependency: String) {
        deps.add(Dependency(configuration, dependency))
    }

    fun addDependency(repository: String, configuration: String, dependency: String) {
        reps.add(Repository("https://$repository"))
        deps.add(Dependency(configuration, dependency))
    }

    val clientRunPath: String get() = "../../run/client"
    val serverRunPath: String get() = "../../run/server"
    val scriptPath: String get() = "../../mod.gradle.kts"

    val ctFabricPath: String get() = "src/main/resources/${mod.mixin}.ct"
    val ctFabricProcessPath: String get() = "build/resources/main/${mod.mixin}.ct"
    val ctForgeArchPath: String get() = "build/generated/stonecutter/main/resources/${mod.mixin}.ct"
    val atForgePath: String get() = "build/sourceSets/main/META-INF/accesstransformer.cfg"
    val atNeoForgePath: String get() = "build/resources/main/META-INF/accesstransformer.cfg"

    val isFabric: Boolean get() = mod.loader == "fabric"
    val isForge: Boolean get() = mod.loader == "forge"
    val isNeoForge: Boolean get() = mod.loader == "neoforge"

    val isObfuscated: Boolean get() = scp < "26.1"
    val isClothConfigAvailable: Boolean get() = !(isForge && scp > "1.21.3")
    val isAppleSkinAvailable: Boolean get() = !(isForge && scp > "1.20.4")

    fun prop(key: String): String? = project.findProperty(key)?.toString()
    fun modProp(key: String) = prop("mod.$key") as String
    fun getProp(key: String) = prop(propName(key))
    fun setProp(key: String, value: Any?) = value.also { project.extra[versionExactlyProp(key)] = it }
    fun propName(key: String) = if (prop(versionExactlyProp(key)) != null) versionExactlyProp(key) else versionProp(key)
    fun propIf(key: String, fallback: String) = prop(propName(key)) ?: fallback
    fun versionProp(key: String) = "${mod.mc}.$key"
    fun versionExactlyProp(key: String) = "${mod.mc}-${mod.loader}.$key"

    val hotfixesList = listOf("1.21.10", "1.21.8", "1.21.7", "1.21.3", "1.21.1", "1.20.6", "1.20.4", "1.20.1")

    fun getMinCompatVersion(version: String): String {
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

    fun getPublishVersion(version: String): List<String> {

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

    val mainTasks = listOf(
        Pair("0 Run Client", "runActiveClient"),
        Pair("0 Run Server", "runActiveServer"),
        Pair("1 Build Active", "buildActive"),
        Pair("1 Build All", "buildAndCollect"),
        Pair("2 Publish All", "PublishAll"),
        Pair("2 Publish Modrinth", "PublishModrinth"),
        Pair("2 Publish CurseForge", "PublishCurseforge"),
        Pair("2 Publish GitHub", "PublishGithub"),
        Pair("3 Generation Source", "genSource")
    )
    val publishPlatforms = listOf("All", "Modrinth", "Curseforge", "Github")

    fun createRunConfiguration() {
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
            createFile(fileName, runConfigurationMainText, name, task)
        }

        publishPlatforms.forEach { platform ->
            val name = "Publish $platform ${mod.mc}"
            val fileName = name.replace(" ", "")
            val task = fileName.lowerCaseFirst()
            createFile(fileName, runConfigurationPublishText, name, task)
        }
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

    fun createDepFile() {
        updateDependencies.createDepFile()

        val iconFile = project.file("src/main/resources/icon.png")
        if (iconFile.exists()) {
            iconFile.copyTo(project.file(".idea/icon.png"), true)
        }
    }
}
