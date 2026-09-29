package com.vibe.keyboard.ai

import com.vibe.keyboard.engine.ContextDetector
import com.vibe.keyboard.memory.MemorySource
import com.vibe.keyboard.memory.UserStyleProfile
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * MOCK. A template picker, not a language model.
 *
 * It exists so the keyboard, the card and every interaction can be built and
 * felt without a network or a key. Everything it returns is marked
 * `isPreview = true`, and the card labels it, so nobody mistakes a template for
 * AI output. It follows the other person's language mix (Sheng-leaning or
 * English) and uses whatever context the user typed in, which is enough to make
 * the flow realistic.
 */
class MockAIProvider(
    private val latencyMs: LongRange = 450L..850L,
    private val random: Random = Random.Default,
) : AIProvider {

    override val isPreview: Boolean = true

    override suspend fun suggest(request: SuggestionRequest): Suggestion {
        if (latencyMs.last > 0) delay(random.nextLong(latencyMs.first, latencyMs.last + 1))

        val theirs = request.conversation.lastFromThem?.text.orEmpty()
        val style = request.reply.userStyle?.takeIf { it.hasLearned }
        // Their language first; a user who writes mostly Sheng leans that way anyway.
        val sheng = leansSheng(theirs) || (style != null && style.shengRatio >= 0.45)

        // What the user told Vibe: this time, or before, about someone they just mentioned.
        val told = request.context.values.lastOrNull() ?: relevantNote(request, theirs)

        val goal = request.goal?.takeIf { request.intent == ReplyIntent.REPLY || request.intent == ReplyIntent.CONTINUE }
        val candidates = if (told != null && request.intent == ReplyIntent.REPLY) {
            withContext(told, sheng)
        } else if (goal != null) {
            forGoal(goal, sheng)
        } else {
            templatesFor(request.intent, theirs, sheng, request.reply)
        }

        val avoid = request.avoid.map { it.lowercase() }.toSet()
        val text = candidates.firstOrNull { it.lowercase() !in avoid }
            ?: candidates.filter { it != request.avoid.lastOrNull() }.randomOrNull(random)
            ?: candidates.first()
        return Suggestion(inUserStyle(text, style), isPreview = true)
    }

    /** A note the user saved earlier about a person named in this message. */
    private fun relevantNote(request: SuggestionRequest, theirs: String): String? {
        val names = ContextDetector.candidateNames(theirs).map { "person:${it.lowercase()}" }.toSet()
        return request.reply.memories.firstOrNull { it.source == MemorySource.USER && it.key in names }?.value
    }

    /** Someone who hardly uses emojis should not get a suggestion full of them. */
    private fun inUserStyle(text: String, style: UserStyleProfile?): String {
        if (style == null || style.emojiRate >= 0.1) return text
        val stripped = text.filterNot { Character.isSurrogate(it) || it == '‍' || it == '️' }
        return stripped.replace(Regex("""\s+"""), " ").trim().ifEmpty { text }
    }

    private fun withContext(answer: String, sheng: Boolean): List<String> {
        val trimmed = answer.trim().trimEnd('.', '!')
        // "He's my cousin" reads better mid-sentence as "he's my cousin", but a
        // name the user typed ("Randy is my cousin") keeps its capital.
        val firstWord = trimmed.substringBefore(' ').lowercase()
        val said = if (firstWord in lowercaseOpeners) trimmed.replaceFirstChar { it.lowercase() } else trimmed
        return if (sheng) {
            listOf("😂 Aii ndio, $said", "Haha si nilikuambia, $said 😅", "Ah hiyo? $said 😂")
        } else {
            listOf("😂 Oh that? $said", "Haha yeah, $said 😅", "Long story, but $said 😂")
        }
    }

    private fun templatesFor(intent: ReplyIntent, theirs: String, sheng: Boolean, reply: ReplyContext): List<String> {
        val norm = ContextDetector.normalize(theirs)
        val laughing = listOf("😂", "🤣", "haha", "hahaha", "lol", "lmao").any { theirs.lowercase().contains(it) }
        val tired = listOf("long day", "tired", "nimechoka", "exhausted", "just got home", "nimefika").any { norm.contains(it) }

        return when (intent) {
            ReplyIntent.REPLY -> when {
                laughing -> pick(sheng,
                    en = listOf("😂 How could I forget?", "Stop, I'm still laughing 😂", "😂 You're not serious"),
                    sh = listOf("😂 Aki siwezi sahau", "Wueh, bado nacheka 😂", "😂 Wewe si serious"))
                tired -> pick(sheng,
                    en = listOf("Pole, rest up 😌 what made it so long?", "Ah pole. Did you at least eat?", "Sounds rough. Put your feet up 😌"),
                    sh = listOf("Pole sana, pumzika 😌 ilikuwa aje?", "Aii pole. Umekula lakini?", "Pole, relax sasa 😌"))
                '?' in theirs -> pick(sheng,
                    en = listOf("Honestly? Depends who's asking 😏", "Haha why do you ask? 👀", "Let me think about that one 😅"),
                    sh = listOf("Haha inategemea nani anauliza 😏", "Mbona unauliza? 👀", "Wacha nifikirie hiyo 😅"))
                else -> pick(sheng,
                    en = listOf("Wait, for real? 👀", "No way 😂 then what happened?", "Haha okay tell me more"),
                    sh = listOf("Aki for real? 👀", "Wueh 😂 kisha ikawaje?", "Haha sema zaidi"))
            }
            ReplyIntent.CONTINUE -> continueFrom(reply, sheng) + pick(sheng,
                en = listOf("Okay but you never finished that story 👀", "Wait, so how did the rest of your day go?", "Before I forget, what are you up to tomorrow?"),
                sh = listOf("Sawa lakini hukumaliza hiyo story 👀", "Kwani siku yako iliishaje?", "Kabla nisahau, kesho uko na plans gani?"))
            ReplyIntent.WRAP_UP -> pick(sheng,
                en = listOf("This was fun 😊 talk later?", "Let me let you go, talk soon 😊", "Okay I'll leave you to it. Later 😊"),
                sh = listOf("Hii convo imekuwa poa 😊 tuongee baadaye", "Wacha nikuache, tuongee soon 😊", "Sawa, nitakucheki baadaye 😊"))
            ReplyIntent.GOODNIGHT -> pick(sheng,
                en = listOf("😂 This convo was actually too good. Go get some sleep, goodnight 😊", "Goodnight 😊 sleep well", "Okay sleep, we'll continue tomorrow 😌"),
                sh = listOf("😂 Hii convo ilikuwa too good. Enda ulale, goodnight 😊", "Lala salama 😊", "Sawa lala, tutaendelea kesho 😌"))
            ReplyIntent.PICTURE_REPLY -> pick(sheng,
                en = listOf("Haha earn it first 😏", "😂 Nice try. Maybe later", "You'll see me soon enough 😌"),
                sh = listOf("Haha pata kwanza 😏", "😂 Nice try, baadaye", "Utaniona tu soon 😌"))
            ReplyIntent.MORNING -> pick(sheng,
                en = listOf("Morning 😊 hope you slept well", "Good morning 😊 how did you sleep?", "Morning! Ready for today? 😊"),
                sh = listOf("Morning 😊 ulilala poa?", "Habari ya asubuhi 😊 umeamkaje?", "Morning! Uko ready na leo? 😊"))
            ReplyIntent.BOUNDARY_EXIT -> pick(sheng,
                en = listOf("Understood, I'll give you space. Take care 🙏", "Got it, sorry if I pushed. Take care", "No worries, all the best 🙏"),
                sh = listOf("Sawa, nimeelewa. Take care 🙏", "Nimekuskia, sorry kama nilipush. Take care", "Poa, kila la heri 🙏"))
        }
    }

    private fun forGoal(goal: ReplyGoal, sheng: Boolean): List<String> = when (goal) {
        ReplyGoal.FLIRT -> pick(sheng,
            en = listOf("Careful, I might start missing you 😏", "You're trouble, you know that? 😌", "Keep talking like that and I'll have to see you 👀"),
            sh = listOf("Wacha, utanifanya nikumiss 😏", "Wewe ni noma, unajua? 😌", "Ukiendelea hivi lazima tuonane 👀"))
        ReplyGoal.FUNNY -> pick(sheng,
            en = listOf("I'm telling my lawyer about this 😂", "Okay that's going in my diary 😂", "Not you exposing me like this 😭"),
            sh = listOf("Nitampigia lawyer wangu 😂", "Aki hii naandika kwa diary 😂", "Usinianike hivi 😭"))
        ReplyGoal.SAVAGE -> pick(sheng,
            en = listOf("Stop pretending, even you know you're wrong 😂", "Whose attitude is that? Can't be yours surely", "Okay boss, I'll wait for you to grow up 😏"),
            sh = listOf("Wacha kujifanya, hata wewe unajua uko wrong 😂", "Hiyo attitude ni ya nani? Sio yako surely", "Sawa boss, nitangoja ukue 😏"))
        ReplyGoal.SPICY -> pick(sheng,
            en = listOf("Keep talking like that and I'm coming to get you myself 😏", "I've had some bad thoughts about you today 😌", "That voice of yours should be illegal"),
            sh = listOf("Ukiendelea hivi nitakuja kukuchukua mwenyewe 😏", "Nimekuwa na mawazo mbaya kukuhusu leo 😌", "Hiyo sauti yako ingefaa kuwa illegal"))
        ReplyGoal.PLAYFUL -> pick(sheng,
            en = listOf("Hmm, and what do I get for that? 😏", "Bold of you to assume 😌", "Prove it then 👀"),
            sh = listOf("Hmm, na mimi napata nini? 😏", "Unajiamini sana 😌", "Basi thibitisha 👀"))
        ReplyGoal.ASK_OUT -> pick(sheng,
            en = listOf("Let's continue this over coffee. Saturday? ☕", "We should do this in person. When are you free?", "I know a spot you'd like. This weekend?"),
            sh = listOf("Tuendelee hii tukikunywa coffee. Saturday? ☕", "Tuongee hii face to face. Uko free lini?", "Najua place utapenda. Weekend hii?"))
        ReplyGoal.DEEPER -> pick(sheng,
            en = listOf("Okay real question, what's something you're excited about lately?", "What's the best part of your week so far?", "Tell me something most people don't know about you"),
            sh = listOf("Sawa swali serious, ni nini inakuexcite siku hizi?", "Ni nini imekuwa poa zaidi wiki hii?", "Niambie kitu watu wengi hawajui kukuhusu"))
        ReplyGoal.COMFORT -> pick(sheng,
            en = listOf("I'm sorry, that sounds really hard. I'm here if you want to talk", "That's a lot. Take it easy on yourself today", "Want to talk about it, or should I distract you? 🤍"),
            sh = listOf("Pole sana, hiyo ni ngumu. Niko hapa ukitaka kuongea", "Hiyo ni mingi. Jipe muda leo", "Unataka tuongee ama nikudistract? 🤍"))
    }

    /**
     * A reason to keep going that comes from this conversation: something that
     * keeps coming up in it. With nothing to draw on, the generic lines follow.
     */
    private fun continueFrom(reply: ReplyContext, sheng: Boolean): List<String> {
        val topic = reply.summary?.topics?.firstOrNull() ?: return emptyList()
        return if (sheng) listOf("Sawa lakini tuongelee $topic kidogo 👀") else listOf("Okay but back to $topic for a sec 👀")
    }

    private val lowercaseOpeners = setOf(
        "he", "hes", "he's", "she", "shes", "she's", "they", "theyre", "they're", "we",
        "it", "its", "it's", "that", "thats", "that's", "the", "my", "our", "just", "yeah",
    )

    private fun pick(sheng: Boolean, en: List<String>, sh: List<String>) = if (sheng) sh else en

    /** Match the language they wrote in. Two Sheng/Kiswahili words is enough to lean that way. */
    private fun leansSheng(text: String): Boolean {
        val words = ContextDetector.normalize(text).split(' ')
        val hits = words.count { it in shengMarkers || shengVerb.matches(it) }
        return hits >= 2 || (hits == 1 && words.size <= 3)
    }

    private val shengMarkers = setOf(
        "niaje", "sasa", "poa", "sawa", "manze", "aki", "sema", "nini", "kwani", "mbona",
        "noma", "fiti", "wueh", "buda", "msee", "maze", "bana", "lakini", "ama", "aje",
        "si", "hii", "hiyo", "ile", "uko", "wapi", "leo", "kesho", "tu", "ata", "na", "ni",
        "nimechoka", "nalala", "tao", "mrembo", "dame", "chali",
    )
    private val shengVerb = Regex("""^(ni|u|a|tu|m|wa)(li|me|na|ta|ka)[a-z]{2,}$""")
}
