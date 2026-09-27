package com.vibe.keyboard.auto

import com.vibe.keyboard.ai.ReplyIntent
import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.engine.Lexicon
import com.vibe.keyboard.engine.TextStats
import com.vibe.keyboard.engine.VibeSignal

enum class ReplyMode { SUGGEST, AUTO }

/** The Auto rules a user can change. The "never" rules are not in here on purpose. */
data class AutoRulesConfig(
    /** Only when Vibe has this chat's context (a scan or an import). */
    val onlyWithContext: Boolean = true,
    /** Only light, everyday messages; anything long or weighty waits for you. */
    val onlyCasual: Boolean = true,
    /** Press Send too, in apps whose message box has a Send action. Off: fill the box only. */
    val sendWhereSupported: Boolean = true,
    /** Time to read and cancel before it sends. Never zero. */
    val sendDelaySeconds: Int = 5,
)

data class AutoInput(
    val intent: ReplyIntent,
    val signal: VibeSignal,
    val theirMessage: String,
    val suggestion: String,
    val contextReady: Boolean,
    val fieldEmpty: Boolean,
    val canSend: Boolean,
)

sealed interface AutoDecision {
    /** Not automatic: shown as a normal suggestion, with the reason. */
    data class AskFirst(val reason: String) : AutoDecision
    /** Put in the box; the user presses Send. */
    data class InsertOnly(val reason: String) : AutoDecision
    /** Put in the box and press the app's own Send action after a visible countdown. */
    data class Send(val delaySeconds: Int) : AutoDecision
}

/**
 * Decides what Auto mode may do with one suggestion. Generation, insertion
 * and sending are three separate permissions, and each has to be earned.
 *
 * The fixed rules come first and no setting reaches them: Vibe never
 * automatically answers anything but an ordinary message, never a picture
 * request, a boundary, a gap in its context, or anything touching private
 * details. Most of that is enforced before a suggestion even exists (those
 * messages get an alert card, not a reply); this re-checks the reply itself.
 */
object AutoRules {

    fun decide(input: AutoInput, config: AutoRulesConfig): AutoDecision {
        // --- Never automatic -------------------------------------------------
        if (input.intent != ReplyIntent.REPLY) return AutoDecision.AskFirst("only ordinary replies are automatic")
        when (input.signal) {
            is VibeSignal.Reply -> Unit
            is VibeSignal.Emotional -> return AutoDecision.AskFirst("this sounds serious")
            else -> return AutoDecision.AskFirst("this needs you")
        }
        if (!input.fieldEmpty) return AutoDecision.AskFirst("you'd started typing")
        mentionsPrivate(input.suggestion)?.let { return AutoDecision.AskFirst(it) }

        // --- Confidence --------------------------------------------------------
        val theirWords = TextStats.words(input.theirMessage).size
        if (theirWords > 40) return AutoDecision.AskFirst("long message, worth reading yourself")

        // --- The user's rules --------------------------------------------------
        if (config.onlyWithContext && !input.contextReady) return AutoDecision.AskFirst("no context for this chat yet")
        if (config.onlyCasual && !isCasual(input.theirMessage)) return AutoDecision.AskFirst("not a casual moment")

        // --- Sending is its own permission -------------------------------------
        if (!config.sendWhereSupported) return AutoDecision.InsertOnly("Sending is off in Auto rules")
        if (!input.canSend) return AutoDecision.InsertOnly("This app doesn't let keyboards send")
        return AutoDecision.Send(config.sendDelaySeconds.coerceIn(3, 15))
    }

    /** Short, everyday, not heated. A long or intense message is a moment for the user. */
    fun isCasual(message: String): Boolean {
        val words = TextStats.words(message)
        if (words.size > 25) return false
        if (message.count { it == '!' } >= 3 || message.count { it == '?' } >= 3) return false
        val norm = ContextDetector.normalize(message)
        return Lexicon.distressPhrases.none { " $norm ".contains(" $it ") }
    }

    /** Digits that look like a number someone could use, or private words. */
    fun mentionsPrivate(text: String): String? {
        if (Regex("""\d[\d\s-]{3,}\d""").containsMatchIn(text)) return "the reply contains a number"
        val norm = " ${ContextDetector.normalize(text)} "
        val hit = Lexicon.sensitiveRequests.firstOrNull { (phrase, _) -> norm.contains(" $phrase ") }
        return hit?.let { "the reply mentions ${it.second}" }
    }
}
