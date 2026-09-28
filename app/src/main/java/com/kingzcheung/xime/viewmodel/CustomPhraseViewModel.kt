package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.DictImportParser
import com.kingzcheung.xime.settings.PersonalDictManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class CustomPhraseUiState(
    val entries: List<DictEntry> = emptyList(),
    val filteredEntries: List<DictEntry> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val editIndex: Int = -1,
    val editWord: String = "",
    val editCode: String = "",
    val editWeight: String = "",
    // ── 词库导入 ──
    val showImportDialog: Boolean = false,
    val importText: String = "",
    /** 统一频次（权重）：条目自身未带频次时套用；留空表示不设置 */
    val importDefaultWeight: String = "",
    val isImporting: Boolean = false,
    /** 导入结果提示（一次性，展示后由 UI 调用 consumeImportMessage 清空） */
    val importMessage: String? = null
)

class CustomPhraseViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private var schemaId: String? = null

    private val _uiState = MutableStateFlow(CustomPhraseUiState())
    val uiState: StateFlow<CustomPhraseUiState> = _uiState.asStateFlow()

    fun setSchema(id: String?) {
        if (schemaId == id) return
        schemaId = id
        loadEntries()
    }

    private fun loadEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = withContext(Dispatchers.IO) {
                PersonalDictManager.loadCustomPhrases(context, schemaId)
            }
            _uiState.update {
                it.copy(
                    entries = entries,
                    filteredEntries = filterEntries(entries, ""),
                    searchQuery = "",
                    isLoading = false
                )
            }
        }
    }

    fun addEntry(word: String, code: String, weight: Int? = null) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.entries
            val updated = current + DictEntry(trimmedWord, trimmedCode, weight)
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, updated) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = updated, filteredEntries = filterEntries(updated, query))
            }
        }
    }

    fun updateEntry(index: Int, word: String, code: String, weight: Int? = null) {
        val trimmedWord = word.trim()
        val trimmedCode = code.trim()
        if (trimmedWord.isEmpty() || trimmedCode.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            current[index] = DictEntry(trimmedWord, trimmedCode, weight)
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, current) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
        }
    }

    fun deleteEntry(index: Int) {
        viewModelScope.launch {
            val current = _uiState.value.entries.toMutableList()
            if (index < 0 || index >= current.size) return@launch
            current.removeAt(index)
            withContext(Dispatchers.IO) { PersonalDictManager.saveCustomPhrases(context, schemaId, current) }
            _uiState.update {
                val query = it.searchQuery
                it.copy(entries = current, filteredEntries = filterEntries(current, query))
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredEntries = filterEntries(it.entries, query)
            )
        }
    }

    fun clearSearch() {
        setSearchQuery("")
    }

    fun showAddDialog() {
        _uiState.update { it.copy(showAddDialog = true, editWord = "", editCode = "", editWeight = "", editIndex = -1) }
    }

    fun hideAddDialog() {
        _uiState.update { it.copy(showAddDialog = false) }
    }

    fun showEditDialog() {
        _uiState.update { it.copy(showEditDialog = true) }
    }

    fun hideEditDialog() {
        _uiState.update { it.copy(showEditDialog = false) }
    }

    fun setEditing(index: Int, entry: DictEntry) {
        _uiState.update { it.copy(
            editIndex = index,
            editWord = entry.word,
            editCode = entry.code,
            editWeight = entry.weight?.toString() ?: ""
        ) }
    }

    fun setEditWord(word: String) {
        _uiState.update { it.copy(editWord = word) }
    }

    fun setEditCode(code: String) {
        _uiState.update { it.copy(editCode = code) }
    }

    fun setEditWeight(weight: String) {
        _uiState.update { it.copy(editWeight = weight) }
    }

    // ── 词库导入 ──

    fun showImportDialog() {
        _uiState.update { it.copy(showImportDialog = true) }
    }

    fun hideImportDialog() {
        _uiState.update { it.copy(showImportDialog = false) }
    }

    fun setImportText(text: String) {
        _uiState.update { it.copy(importText = text) }
    }

    fun setImportDefaultWeight(weight: String) {
        _uiState.update { it.copy(importDefaultWeight = weight) }
    }

    fun consumeImportMessage() {
        _uiState.update { it.copy(importMessage = null) }
    }

    /**
     * 导入词表：解析为「词+编码+频次」条目，合并进当前自定义短语表，
     * 落盘后触发增量部署使导入的词立即生效。
     *
     * 解析与合并均为纯函数（[DictImportParser]）；缺少编码的行无法被 rime
     * 命中，会在结果中计数并提示。
     */
    fun importEntries() {
        val state = _uiState.value
        if (state.isImporting) return
        val text = state.importText
        if (text.isBlank()) {
            _uiState.update { it.copy(importMessage = "请先选择词表文件或粘贴词表内容") }
            return
        }
        val defaultWeight = state.importDefaultWeight.trim().toIntOrNull()

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val parsed = withContext(Dispatchers.Default) {
                DictImportParser.parse(text, defaultWeight)
            }
            if (parsed.entries.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importMessage = buildString {
                            append("未解析出可导入的词条")
                            if (parsed.skippedNoCode > 0) {
                                append("：${parsed.skippedNoCode} 行缺少编码（rime 需编码才能命中）")
                            }
                        }
                    )
                }
                return@launch
            }

            val merged = DictImportParser.merge(state.entries, parsed.entries)
            withContext(Dispatchers.IO) {
                PersonalDictManager.saveCustomPhrases(context, schemaId, merged.entries)
                // 词表内容变化 → 增量部署，否则引擎仍用旧的编译产物
                runCatching { RimeConfigHelper.ensureDeployment(context) }
            }

            _uiState.update {
                val query = it.searchQuery
                it.copy(
                    entries = merged.entries,
                    filteredEntries = filterEntries(merged.entries, query),
                    showImportDialog = false,
                    importText = "",
                    isImporting = false,
                    importMessage = buildString {
                        append("导入完成：新增 ${merged.added} 条")
                        if (merged.updated > 0) append("，更新频次 ${merged.updated} 条")
                        if (merged.unchanged > 0) append("，已有 ${merged.unchanged} 条未变")
                        if (parsed.skippedNoCode > 0) {
                            append("；跳过 ${parsed.skippedNoCode} 行（缺少编码）")
                        }
                    }
                )
            }
        }
    }

    private fun filterEntries(entries: List<DictEntry>, query: String): List<DictEntry> {
        if (query.isEmpty()) return entries
        val lowerQuery = query.lowercase(Locale.ROOT)
        return entries.filter {
            it.word.contains(query) ||
                it.code.contains(query, ignoreCase = true) ||
                it.code.lowercase(Locale.ROOT).contains(lowerQuery)
        }
    }
}
