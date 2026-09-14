package com.kingzcheung.xime.service

data class CandidateState(
    val candidates: List<String> = emptyList(),
    val candidateComments: List<String> = emptyList(),
    val inputText: String = "",
    val preeditText: String = "",
    val isComposing: Boolean = false,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val associationCandidates: List<String> = emptyList(),
    val pendingEnglishText: String = "",
    val isShowingRecentClipboard: Boolean = false,
    /** 当前宿主是否支持英文候选的"回删替换"（终端等受限宿主为 false，不展示英文候选）。 */
    val englishReplaceSupported: Boolean = true,
    /** 候选词变换映射（插件 candidate_transform 能力）：与 [candidates] 平行；
     *  空列表 = 纯引擎语义（显示 index 即引擎 index）。见 CandidateAction。 */
    val candidateActions: List<CandidateAction> = emptyList()
)
