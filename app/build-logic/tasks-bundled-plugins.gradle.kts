// 内置插件打包：把仓库根目录 plugins/<name>/ 全部打成 .xipk 并作为 assets 参与构建。
//
// 为什么用 Gradle 而不是复用 scripts/build-plugins.sh：
//   - 脚本依赖 bash + python3（Windows 本地构建不具备），Gradle 任务纯 JVM 实现，
//     本地与 CI 行为一致；
//   - 作为 assets 源目录参与 mergeAssets，debug/release 两种构建都会内置插件，
//     不再依赖仓库里手工提交的 xipk 二进制。
//
// 产物：app/build/generated/bundledPlugins/plugins/<name>-<version>.xipk
// 运行时由 PluginManager.installBundledPlugins() 静默安装（缺失或版本更新时）。

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 仓库根目录下的插件源码目录。 */
val pluginsSourceDir: File = rootProject.file("plugins")

/** 生成的 assets 根：其下 plugins/ 子目录与运行时 assets 路径 "plugins" 对应。 */
val bundledPluginsAssetsDir: File = layout.buildDirectory.dir("generated/bundledPlugins").get().asFile

/** 从 manifest.yaml 读取 version 字段（与 scripts/build-plugins.sh 同规则）。 */
fun readPluginVersion(manifest: File): String {
    return try {
        manifest.readLines()
            .firstOrNull { it.trimStart().startsWith("version:") }
            ?.substringAfter(":")
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }
            ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }
}

/** 目录名即插件名（与 CI 脚本一致，决定 xipk 文件名）。 */
fun pluginDirs(): List<File> =
    pluginsSourceDir.listFiles()
        ?.filter { it.isDirectory && File(it, "manifest.yaml").isFile }
        ?.sortedBy { it.name }
        ?: emptyList()

val packageBundledPlugins = tasks.register("packageBundledPlugins") {
    group = "build"
    description = "把 plugins/ 下的全部插件打包为 xipk 并内置到 assets"

    val sourceDir = pluginsSourceDir
    val outputDir = bundledPluginsAssetsDir
    inputs.dir(sourceDir).withPropertyName("pluginSources")
    outputs.dir(outputDir).withPropertyName("bundledPluginAssets")

    doLast {
        val targetDir = File(outputDir, "plugins")
        // 全量重建：插件被删除时避免残留旧包继续内置
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        val dirs = pluginDirs()
        if (dirs.isEmpty()) {
            logger.lifecycle("packageBundledPlugins: plugins/ 下未找到插件，跳过")
            return@doLast
        }

        dirs.forEach { pluginDir ->
            val version = readPluginVersion(File(pluginDir, "manifest.yaml"))
            val outFile = File(targetDir, "${pluginDir.name}-$version.xipk")
            ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                pluginDir.walkTopDown()
                    .filter { it.isFile }
                    .filter { !it.name.startsWith(".") }
                    .filter { file ->
                        // 排除点目录（.git 等）下的文件
                        file.relativeTo(pluginDir).invariantSeparatorsPath
                            .split('/')
                            .none { it.startsWith(".") }
                    }
                    .sortedBy { it.relativeTo(pluginDir).invariantSeparatorsPath }
                    .forEach { file ->
                        val entryName = file.relativeTo(pluginDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            logger.lifecycle("Bundled plugin: ${outFile.name}")
        }
        logger.lifecycle("packageBundledPlugins: 共 ${dirs.size} 个插件已内置到 assets/plugins")
    }
}

// 说明：assets 源目录的注册（android.sourceSets）在 app/build.gradle.kts 中完成——
// apply(from) 的脚本拿不到 android 扩展访问器。此处只负责打包任务与依赖挂钩。

// merge*Assets 读取生成目录前先完成打包（assets 任务名随 AGP 版本变化，按前缀匹配）
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(packageBundledPlugins)
}
