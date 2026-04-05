package com.bizcub.multiloader

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
    fun stonecutterKts() {
        println("stonecutterKts")
    }

    fun init() {
        project.extra["loom.platform"] = mod.loader
        if (isObfuscated) project.extra["fabric.loom.disableObfuscation"] = false

        project.extensions.getByType(BasePluginExtension::class.java).apply {
            archivesName.set(mod.baseName)
        }

        project.version = mod.baseVersion

        project.configurations.all {
            resolutionStrategy.force("net.fabricmc:fabric-loader:latest.release")
        }

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
                "id"            to mod.id,
                "mixin"         to mod.mixin,
                "name"          to mod.name,
                "description"   to mod.description,
                "version"       to mod.version,
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
            displayName.set("${mod.name} ${mod.loader.replaceFirstChar { it.uppercaseChar() }} ${mod.pubStart} v${mod.version}")
            changelog.set(project.rootDir.resolve("CHANGELOG.md").readText())
            version.set(mod.version)
            type.set(STABLE)
            modLoaders.add(mod.loader)
            if (isFabric) modLoaders.add("quilt")

            modrinth {
                projectId.set(mod.modrinth)
                accessToken.set(tokenDir("modrinth"))
                minecraftVersionRange {
                    start.set(mod.pubStart)
                    end.set(mod.pubEnd)
                    includeSnapshots.set(true)
                }
            }
            curseforge {
                projectId.set(mod.curseforge)
                accessToken.set(tokenDir("curseforge"))
                minecraftVersionRange {
                    start.set(mod.pubStart)
                    end.set(mod.pubEnd)
                }
            }
            github {
                accessToken.set(tokenDir("github"))
                repository.set("BizCub/${mod.github}")
                commitish.set("master")
                tagName.set("v${mod.version}-${mod.loader}+${mod.pubStart}")
            }
        }

        var mc = if (isObfuscated) mod.mc.substring(2) else mod.mc
        if (!mc.substring(if (isObfuscated) 2 else 3).contains(".")) mc += ".0"
        updateDependencies.neoForge(mc)

        updateDependencies.forge(mod.mc)

        if (!isClothConfigAvailable) {
            setProp("cloth-config", "17.0.144")
        }

        createRunConfiguration()
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
        val baseName: String get() = "${mod.mixin}-${mod.loader}"
        val baseVersion: String get() = "${mod.version}+${mod.pubStart}"
    }

    val updateDependencies = UpdateDependencies(project, this)

    val reps = mutableListOf<Repository>()
    val deps = mutableListOf<Dependency>()

    class Repository(val repository: String)
    class Dependency(val configuration: String, val dependency: String) {
        val id = dependency.split(":")[1]
        val modConfiguration = "mod${configuration.upperCaseFirst()}"
    }

    fun addRepository(repository: String) {
        reps.add(Repository(repository))
    }

    fun addDependency(configuration: String, dependency: String) {
        deps.add(Dependency(configuration, dependency))
    }

    val clientRunPath: String get() = "../../run/client"
    val serverRunPath: String get() = "../../run/server"
    val scriptPath: String get() = "../../mod.gradle.kts"

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

    val mainTasks = listOf(
        Pair("0 Run Client", "runActiveClient"),
        Pair("0 Run Server", "runActiveServer"),
        Pair("1 Build Active", "buildActive"),
        Pair("1 Build All", "buildAndCollect"),
        Pair("2 Publish Mods", "PublishMods"),
        Pair("2 Publish Modrinth", "PublishModrinth"),
        Pair("2 Publish CurseForge", "PublishCurseforge"),
        Pair("2 Publish GitHub", "PublishGithub"),
        Pair("3 Generation Source", "genSource")
    )
    val publishPlatforms = listOf("Mods", "Modrinth", "Curseforge", "Github")

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
        return updateDependencies.getDep(key)
    }

    fun createDepFile() {
        updateDependencies.createDepFile()
    }
}
