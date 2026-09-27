package com.vibe.keyboard.memory

import com.vibe.keyboard.engine.TextStats

/**
 * Learns how the user texts, from the user's own messages only: their side of
 * an imported chat, and suggestions they chose to use. A port of
 * `backend/app/core/style_profile.learn_from_messages`, blending rather than
 * replacing so one odd import does not rewrite who they are.
 */
object UserStyleLearner {

    private const val LEARNING_RATE = 0.35

    fun learn(profile: UserStyleProfile, myMessages: List<String>, weight: Double = LEARNING_RATE): UserStyleProfile {
        val mine = myMessages.map { it.trim() }.filter { it.isNotEmpty() }
        if (mine.isEmpty()) return profile
        // The first real sample replaces the defaults outright; they were only placeholders.
        val w = if (profile.learnedFrom == 0 && mine.size >= 10) 1.0 else weight
        return profile.copy(
            shengRatio = blend(profile.shengRatio, TextStats.shengRatio(mine), w),
            avgWords = blend(profile.avgWords, TextStats.avgWords(mine), w),
            emojiRate = blend(profile.emojiRate, TextStats.emojiRate(mine), w),
            laughRate = blend(profile.laughRate, TextStats.laughRate(mine), w),
            questionRate = blend(profile.questionRate, TextStats.questionRate(mine), w),
            topEmojis = (TextStats.topEmojis(mine) + profile.topEmojis).distinct().take(4),
            commonPhrases = (TextStats.commonPhrases(mine) + profile.commonPhrases).distinct().take(10),
            learnedFrom = profile.learnedFrom + mine.size,
            samples = (profile.samples + mine.filter(StyleSamples::isShareableAcrossChats)).distinct().takeLast(40),
        )
    }

    /**
     * One used suggestion is a weak signal: the user picked it, but may still
     * have edited it. It never joins [UserStyleProfile.samples] -- the model
     * wrote it, and samples are meant to be the user's own words.
     */
    fun learnFromUsed(profile: UserStyleProfile, used: String) =
        learn(profile, listOf(used), weight = 0.05).copy(samples = profile.samples)

    private fun blend(current: Double, observed: Double, weight: Double) =
        Math.round((current * (1 - weight) + observed * weight) * 1000) / 1000.0

    fun describe(p: UserStyleProfile): List<String> = buildList {
        add(TextStats.languageMix(p.shengRatio))
        add(if (p.avgWords < 7) "short messages" else if (p.avgWords > 16) "longer messages" else "medium-length messages")
        add(
            when {
                p.emojiRate < 0.1 -> "hardly any emojis"
                p.topEmojis.isNotEmpty() && p.emojiRate >= 0.3 -> "emojis often (${p.topEmojis.take(2).joinToString("")})"
                else -> "some emojis"
            },
        )
        if (p.laughRate >= 0.2) add("laughs a lot")
        if (p.commonPhrases.isNotEmpty()) add("says “${p.commonPhrases.take(3).joinToString("”, “")}”")
    }
}
