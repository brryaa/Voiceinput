package com.lboard.aiime.ime.keyboard

/**
 * 键盘布局定义
 */
object KeyboardLayout {

    /**
     * QWERTY 键盘行定义
     */
    val qwertyRows: List<List<KeyData>> = listOf(
        // 第一行
        listOf(
            KeyData("q", "Q"), KeyData("w", "W"), KeyData("e", "E"),
            KeyData("r", "R"), KeyData("t", "T"), KeyData("y", "Y"),
            KeyData("u", "U"), KeyData("i", "I"), KeyData("o", "O"),
            KeyData("p", "P")
        ),
        // 第二行
        listOf(
            KeyData("a", "A"), KeyData("s", "S"), KeyData("d", "D"),
            KeyData("f", "F"), KeyData("g", "G"), KeyData("h", "H"),
            KeyData("j", "J"), KeyData("k", "K"), KeyData("l", "L")
        ),
        // 第三行（带特殊键）
        listOf(
            KeyData("z", "Z"), KeyData("x", "X"), KeyData("c", "C"),
            KeyData("v", "V"), KeyData("b", "B"), KeyData("n", "N"),
            KeyData("m", "M")
        )
    )

    /**
     * 常用中文标点
     */
    val chinesePunctuations = listOf(
        "，", "。", "！", "？", "、", "：", "；",
        """, """, "'", "'", "（", "）",
        "《", "》", "【", "】", "——", "……"
    )

    /**
     * 英文标点
     */
    val englishPunctuations = listOf(
        ",", ".", "!", "?", "'", "\"", ":",
        ";", "(", ")", "-", "_", "/", "@",
        "#", "$", "%", "&", "*", "+"
    )
}

/**
 * 按键数据
 */
data class KeyData(
    val value: String,          // 按键发送的值
    val display: String,        // 按键显示的文本
    val type: KeyType = KeyType.LETTER
)

/**
 * 按键类型
 */
enum class KeyType {
    LETTER,         // 字母键
    BACKSPACE,      // 退格键
    ENTER,          // 回车键
    SPACE,          // 空格键
    SHIFT,          // Shift 键
    SWITCH_LANG,    // 中英切换
    PUNCTUATION,    // 标点符号
    VOICE,          // 语音输入
    COMMA,          // 逗号
    PERIOD          // 句号
}
