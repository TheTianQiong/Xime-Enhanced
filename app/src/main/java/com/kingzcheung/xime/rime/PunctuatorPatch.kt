package com.kingzcheung.xime.rime

/**
 * default.custom.yaml 中 `punctuator.full_shape` 的用户覆盖（纯文本处理，便于单测）。
 *
 * 只改「值」，保留原有键的写法（带引号或裸键）与缩进、注释：
 * ```
 *       "~": "～"              →  "~": "～"     （用户改后写回其字符）
 *       "*": ["＊", "・", "×"]  →  "*": "＊"     （多候选收敛为用户指定的单个字符）
 *       .: {commit: "。"}       →  .: "。"       （映射形式同样收敛）
 * ```
 * 未命中的键、注释行与块外内容一律原样保留；[overrides] 为空时原样返回。
 */
internal object PunctuatorPatch {

    private const val PUNCTUATOR_KEY = "punctuator:"
    private const val FULL_SHAPE_KEY = "full_shape:"

    /**
     * @param yaml       default.custom.yaml 文本
     * @param overrides  ASCII 输入键 → 用户自定义中文标点（如 `"~" to "～"`）
     */
    fun apply(yaml: String, overrides: Map<String, String>): String {
        if (overrides.isEmpty()) return yaml
        val lines = yaml.lines().toMutableList()
        val range = fullShapeRange(lines) ?: return yaml
        var changed = false
        for (i in range) {
            val entry = parseEntry(lines[i]) ?: continue
            val replacement = overrides[entry.key] ?: continue
            val newLine = lines[i].substring(0, entry.valueStart) + "\"" + replacement + "\""
            if (newLine != lines[i]) {
                lines[i] = newLine
                changed = true
            }
        }
        if (!changed) return yaml
        val joined = lines.joinToString("\n")
        // lines() 会吃掉结尾换行，原文有则补回，避免每次同步都产生无意义 diff
        return if (yaml.endsWith("\n")) joined + "\n" else joined
    }

    /** 定位 `punctuator:` 之下 `full_shape:` 块的条目行下标（不含块标题行）。 */
    private fun fullShapeRange(lines: List<String>): IntRange? {
        var inPunctuator = false
        var punctuatorIndent = -1
        for (i in lines.indices) {
            val trimmed = lines[i].trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = lines[i].length - trimmed.length

            if (!inPunctuator) {
                if (trimmed.startsWith(PUNCTUATOR_KEY)) {
                    inPunctuator = true
                    punctuatorIndent = indent
                }
                continue
            }
            // 离开 punctuator 块（同级或更浅的键）即结束
            if (indent <= punctuatorIndent) return null
            if (!trimmed.startsWith(FULL_SHAPE_KEY)) continue

            val blockIndent = indent
            var end = i + 1
            while (end < lines.size) {
                val t = lines[end].trimStart()
                if (t.isEmpty()) {
                    end++
                    continue
                }
                val ind = lines[end].length - t.length
                if (ind <= blockIndent) break
                end++
            }
            return (i + 1) until end
        }
        return null
    }

    private data class Entry(val key: String, val valueStart: Int)

    /**
     * 解析一行 `键: 值`。键可能是带引号的（含 `":"` 这类键本身是冒号的情况），
     * 也可能是裸键（如 `-`、`.`、`_`）。[Entry.valueStart] 为值起始下标。
     */
    private fun parseEntry(line: String): Entry? {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val indent = line.length - trimmed.length
        if (indent == 0) return null

        val keyEnd: Int
        val colonIndex: Int
        if (trimmed.startsWith("\"")) {
            // 带引号的键：跳过转义，找到收尾引号，其后第一个 ':' 为分隔符
            var i = 1
            while (i < trimmed.length) {
                when (trimmed[i]) {
                    '\\' -> i += 2
                    '"' -> break
                    else -> i++
                }
            }
            if (i >= trimmed.length) return null
            keyEnd = i + 1 // 含收尾引号
            colonIndex = trimmed.indexOf(':', i + 1)
        } else {
            colonIndex = trimmed.indexOf(':')
            keyEnd = colonIndex
        }
        if (colonIndex <= 0) return null

        val rawKey = trimmed.substring(0, keyEnd).trim()
        val key = rawKey.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        if (key.isEmpty()) return null

        // 跳过冒号后的空白：保留原有的「: 」分隔，只替换值本身
        var valueStart = indent + colonIndex + 1
        while (valueStart < line.length && line[valueStart] == ' ') valueStart++

        return Entry(key = key, valueStart = valueStart)
    }
}
