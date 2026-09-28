package com.kingzcheung.xime.service

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 当前输入框的**背景环境信息**：宿主应用、输入框类型，以及由二者推导的
 * 联想策略。供智能联想做自适应匹配使用。
 *
 * 收集来源（`onStartInput(attribute)`）：
 * - `EditorInfo.packageName` → 宿主应用
 * - `EditorInfo.inputType` → 输入框类型
 *
 * [from] 与 [adaptiveContext] 均为纯函数，便于单测。
 */
internal data class EditorEnvironment(
    /** 宿主应用包名；未知时为空串。 */
    val packageName: String,
    /** 输入框类型。 */
    val fieldKind: FieldKind,
    /**
     * 中文联想是否适用。数字/电话/日期时间类框与密码/原始按键框下，
     * 中文联想既无用（无中文上下文）又可能泄漏输入，故关闭。
     */
    val associationAllowed: Boolean,
) {

    /** 输入框类型分类。 */
    enum class FieldKind {
        /** 普通文本 */
        TEXT,

        /** 多行文本（聊天/笔记，联想最有价值） */
        MULTILINE,

        /** 数字 */
        NUMBER,

        /** 电话号码 */
        PHONE,

        /** 日期时间 */
        DATETIME,

        /** 邮箱地址 */
        EMAIL,

        /** 网址 */
        URL,

        /** 密码 / 终端等秘密框 */
        SECRET,

        /** 其他（含 TYPE_NULL） */
        OTHER,
    }

    companion object {

        /** 未知环境：未收到 onStartInput 时的保守默认值。 */
        val UNKNOWN = EditorEnvironment(
            packageName = "",
            fieldKind = FieldKind.OTHER,
            associationAllowed = true,
        )

        /** 从 EditorInfo 收集环境信息。 */
        fun from(info: EditorInfo?): EditorEnvironment {
            if (info == null) return UNKNOWN
            val kind = classify(info)
            return EditorEnvironment(
                packageName = info.packageName.orEmpty(),
                fieldKind = kind,
                associationAllowed = kind.allowsAssociation,
            )
        }

        /** 输入框类型分类（顺序敏感：先判密码，再按大类细分）。 */
        internal fun classify(info: EditorInfo): FieldKind {
            val inputType = info.inputType
            if (EditorInfoClassifier.isSecretEditor(info)) return FieldKind.SECRET

            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (cls) {
                InputType.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
                InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
                InputType.TYPE_CLASS_DATETIME -> FieldKind.DATETIME
                InputType.TYPE_CLASS_TEXT -> when {
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL

                    variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL

                    // 多行是 FLAG 而非 VARIATION；短信/长文本等 variation 同样视为多行
                    inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 ||
                        variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT ||
                        variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> FieldKind.MULTILINE

                    else -> FieldKind.TEXT
                }

                else -> FieldKind.OTHER
            }
        }

        /** 该类型下中文联想是否适用。 */
        private val FieldKind.allowsAssociation: Boolean
            get() = when (this) {
                // 无中文上下文可依，且联想候选会与数字键盘语义冲突
                FieldKind.NUMBER, FieldKind.PHONE, FieldKind.DATETIME -> false
                // 密码/终端：联想会泄漏输入前缀
                FieldKind.SECRET -> false
                // 邮箱/网址：以英文为主，中文联想基本无用
                FieldKind.EMAIL, FieldKind.URL -> false
                FieldKind.TEXT, FieldKind.MULTILINE, FieldKind.OTHER -> true
            }

        /**
         * 自适应上下文：在「已上屏文本」与「输入框光标前文本」之间择优选一段
         * 作为联想上下文。
         *
         * 输入框前文通常包含已上屏文本**以及**宿主预填内容（如回复邮件时引用的
         * 原文），因此信息更充分；但它可能不可读（密码框返回 null）或过长，
         * 故取尾部 [maxLength] 字符，为空时回退已上屏文本。
         *
         * @param committedText 本输入法累计上屏的文本
         * @param fieldTextBeforeCursor 输入框光标前文本；不可读时为 null
         */
        fun adaptiveContext(
            committedText: String,
            fieldTextBeforeCursor: String?,
            maxLength: Int,
        ): String {
            if (maxLength <= 0) return ""
            val fromField = fieldTextBeforeCursor?.trimEnd()?.takeLast(maxLength).orEmpty()
            if (fromField.isNotBlank()) return fromField
            return committedText.takeLast(maxLength)
        }
    }
}
