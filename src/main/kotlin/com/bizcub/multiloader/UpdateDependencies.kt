package com.bizcub.multiloader

import org.gradle.api.Project
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

class UpdateDependencies(val project: Project, val ml: MultiLoader) {
    val mod = ml.mod
    val filePath = project.rootDir.resolve("build/multiloader")
    val file = filePath.resolve("dependencies.json")
    val isUpdateEnabled = ml.prop("multiloader.enableDependenciesUpdate") == "true"

    fun getDep(key: String): String {

        fun downloadDependency(): String {
            println("[Multiloader] Downloading dependency...")

            val version = getLastModrinthVersion(key)
            if (version != "not_found") {
                addToConfig(mod.mc, mod.loader, key, version)
                return version
            } else {
                addToConfig(mod.mc, mod.loader, key, ml.getProp(key) as String)
                return ml.getProp(key) as String
            }
        }

        if (isUpdateEnabled) {
            return downloadDependency()
        } else {
            val configValue = getConfigValue(mod.mc, mod.loader, key)
            return configValue ?: downloadDependency()
        }
    }

    fun getLastModrinthVersion(id: String): String {
        val json = JSONArray(URL("https://api.modrinth.com/v2/project/$id/version").readText())

        json.forEachIndexed { i, _ ->
            val obj = json.getJSONObject(i)
            obj.getJSONArray("game_versions").forEach { gameVersion ->
                obj.getJSONArray("loaders").forEach { loader ->
                    if (gameVersion == mod.mc && loader == mod.loader) {
                        return obj.getString("version_number")
                    }
                }
            }
        }
        return "not_found"
    }

    fun addToConfig(versionKey: String, outerKey: String, innerKey: String, value: String) {
        val root: JSONObject = if (file.exists() && file.isFile) {
            try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                throw IOException(e)
            }
        } else {
            JSONObject()
        }

        var versionObj: JSONObject
        if (root.has(versionKey)) {
            versionObj = root.getJSONObject(versionKey)
        } else {
            versionObj = JSONObject()
            root.put(versionKey, versionObj)
        }

        var outerObj: JSONObject
        if (versionObj.has(outerKey)) {
            outerObj = versionObj.getJSONObject(outerKey)
            outerObj.put(innerKey, value)
        } else {
            outerObj = JSONObject().put(innerKey, value)
            versionObj.put(outerKey, outerObj)
        }

        file.writeText(root.toString(4))
    }

    fun getConfigValue(versionKey: String, outerKey: String, innerKey: String): String? {
        if (!file.exists() || !file.isFile) return null

        return try {
            val root = JSONObject(file.readText())
            if (!root.has(versionKey)) return null

            val versionObj = root.getJSONObject(versionKey)
            if (!versionObj.has(outerKey)) return null

            val outerObj = versionObj.getJSONObject(outerKey)
            if (!outerObj.has(innerKey)) return null

            outerObj.getString(innerKey)
        } catch (e: Exception) {
            null
        }
    }

    fun createDepFile() {
        filePath.mkdirs()
        file.createNewFile()
        if (isUpdateEnabled || file.readText().isEmpty()) file.writeText("{}")
    }















    fun forge(version: String) {
        val jsonString = URL("https://files.minecraftforge.net/net/minecraftforge/forge/maven-metadata.json").readText()
        val jsonObject = JSONObject(jsonString)
        val array = jsonObject.getJSONArray(version)

        ml.setProp("forge", "${mod.mc}-${array.get(array.length()-1).toString().split(version)[1].substring(1)}")
    }

    fun neoForge(version: String) {
        val xmlString = URL("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml").readText()
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(xmlString.byteInputStream())
        document.documentElement.normalize()
        val nodeList = document.getElementsByTagName("versions")
        val node = nodeList.item(0)
        var versions = listOf<String>()
        if (node.nodeType == Node.ELEMENT_NODE) {
            val element = node as Element
            versions = element.textContent.replace(" ", "").split("\n")
        }

        versions.reversed().forEach { vers ->
            if (vers.startsWith("$version.")) {
                ml.setProp("neoforge", "$version.${vers.split(version).last().substring(1)}")
                return
            }
        }
    }
}
