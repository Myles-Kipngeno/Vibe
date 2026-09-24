package com.vibe.keyboard.keyboard

object KeyboardLayouts {
    val letters = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    val symbols = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "KSh", "_", "&", "-", "+", "(", ")", "/"),
        listOf("%", "*", "\"", "'", ":", ";", "!", "?"),
    )

    /** The ones people actually reach for in chats, most used first. */
    val emoji = listOf(
        "😂", "🤣", "😊", "😍", "🥰", "😘", "😅", "😭",
        "🙏", "🔥", "❤️", "👀", "💀", "🤔", "😏", "😌",
        "🙈", "🥺", "✨", "💯", "👍", "👌", "🙌", "👏",
        "😴", "🌙", "☀️", "😎", "🤗", "😬", "🙄", "😳",
        "😩", "😤", "🥲", "🫶", "💜", "🤍", "🎉", "🤝",
    )
}
