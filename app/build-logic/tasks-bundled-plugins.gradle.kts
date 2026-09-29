// 内置插件打包（v3）：把仓库根目录 plugins/<name>/ 的 TypeScript 插件编译并打包为 .xipk，
// 作为 assets 参与构建。运行时由 PluginManager.installBundledPlugins() 静默安装
// （缺失或版本更新时）。
//
// v3 起插件源码是 TS 模块（main.ts + manifest.json），必须经 Rust CLI（tools/xime-plugin）
// 编译成 IIFE 单文件 main.js 才可能被 QuickJS 宿主加载，因此 xipk 打包的是 CLI 的**产物目录**
// （build/plugin-js/<name>/：main.js + manifest.json + resources/），而不是源码目录。
// 旧的「打包 manifest.yaml + main.lua 源码」形态已随 Lua 插件系统一并废弃。
//
// 产物：app/build/generated/bundledPlugins/plugins/<name>-<version>.xipk

/** 仓库根目录下的插件源码目录。 */
val pluginsSourceDir: File = rootProject.file("plugins")

/** 插件编译产物根目录（与 `xipm build` 默认值、CI 前置步骤一致）。 */
val pluginsOutDir: File = rootProject.file("build/plugin-js")

/** 生成的 assets 根：其下 plugins/ 子目录与运行时 assets 路径 "plugins" 对应。 */
val bundledPluginsAssetsDir: File = layout.buildDirectory.dir("generated/bundledPlugins").get().asFile

/**
 * xipm CLI 的调用前缀：优先用已编译的 release 二进制（省去每次 cargo 启动开销），
 * 缺失时回退 `cargo run`（首次会自行编译，需要 Rust 工具链）。
 */
fun xipmCommand(): List<String> {
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val binary = rootProject.file("tools/xime-plugin/target/release/${if (isWindows) "xipm.exe" else "xipm"}")
    return if (binary.isFile) {
        listOf(binary.absolutePath)
    } else {
        listOf(
            "cargo", "run", "--quiet",
            "--manifest-path", rootProject.file("tools/xime-plugin/Cargo.toml").absolutePath,
            "--"
        )
    }
}

/** 待内置的插件目录：含 v3 manifest.json 的目录，目录名即插件名（决定 xipk 文件名）。 */
fun pluginDirs(): List<File> =
    pluginsSourceDir.listFiles()
        ?.filter { it.isDirectory && File(it, "manifest.json").isFile }
        ?.sortedBy { it.name }
        ?: emptyList()

/** 尚未迁移到 TS 的插件目录（仍只有 Lua 时代的 manifest.yaml）——跳过并告警。 */
fun legacyPluginDirs(): List<File> =
    pluginsSourceDir.listFiles()
        ?.filter { it.isDirectory && File(it, "manifest.yaml").isFile }
        ?.sortedBy { it.name }
        ?: emptyList()

val packageBundledPlugins = tasks.register("packageBundledPlugins") {
    group = "build"
    description = "把 plugins/ 下的全部 TS 插件编译并打包为 xipk 并内置到 assets"

    val sourceDir = pluginsSourceDir
    val outputDir = bundledPluginsAssetsDir
    val cli = xipmCommand()

    inputs.dir(sourceDir).withPropertyName("pluginSources")
    outputs.dir(outputDir).withPropertyName("bundledPluginAssets")

    doLast {
        val targetDir = File(outputDir, "plugins")
        // 全量重建：插件被删除时避免残留旧包继续内置
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        legacyPluginDirs().forEach { dir ->
            logger.warn("packageBundledPlugins: 跳过 ${dir.name}（仍是 manifest.yaml/main.lua，未迁移到 TS 插件）")
        }

        if (pluginDirs().isEmpty()) {
            logger.lifecycle("packageBundledPlugins: plugins/ 下未找到 TS 插件，跳过")
            return@doLast
        }

        val command = cli + listOf(
            "pack", "--all",
            "--plugins-dir", sourceDir.absolutePath,
            "--out", pluginsOutDir.absolutePath,
            "--release-dir", targetDir.absolutePath,
        )
        val process = ProcessBuilder(command)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        // 逐行回显 CLI 输出，构建日志里能看到每个插件的编译/打包结果
        output.lineSequence().filter { it.isNotBlank() }.forEach { logger.lifecycle("  $it") }

        // 静默失败会让 APK 丢掉全部内置插件（用户视角是「插件莫名消失」），必须硬失败
        if (exitCode != 0) {
            throw GradleException("内置插件打包失败（xipm pack 退出码 $exitCode），详见上方输出")
        }

        val xipkCount = targetDir.listFiles { file -> file.extension == "xipk" }?.size ?: 0
        if (xipkCount == 0) {
            throw GradleException("内置插件打包未产出任何 xipk：${targetDir.absolutePath}")
        }
        logger.lifecycle("packageBundledPlugins: 共 $xipkCount 个插件已内置到 assets/plugins")
    }
}

// 说明：assets 源目录的注册（android.sourceSets）在 app/build.gradle.kts 中完成——
// apply(from) 的脚本拿不到 android 扩展访问器。此处只负责打包任务与依赖挂钩。

// merge*Assets 读取生成目录前先完成打包（assets 任务名随 AGP 版本变化，按前缀匹配）
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(packageBundledPlugins)
}
