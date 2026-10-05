package com.mymusiccurator.data.metadata

/**
 * Last.fm 같은 서비스의 자유 태그 목록을 장르 / 분위기로 분류한다.
 * 태그는 인기 순서로 들어온다고 가정한다.
 */
object TagClassifier {

    data class Result(val genre: String?, val mood: String?, val genres: List<String>, val tags: List<String>)

    private val GENRE_ALIASES = mapOf(
        "kpop" to "K-Pop", "k pop" to "K-Pop", "korean pop" to "K-Pop", "korean" to "K-Pop", "가요" to "K-Pop",
        "jpop" to "J-Pop", "j pop" to "J-Pop", "japanese" to "J-Pop",
        "hiphop" to "Hip-Hop", "hip hop" to "Hip-Hop", "korean hip hop" to "K-Hip-Hop", "khiphop" to "K-Hip-Hop",
        "rnb" to "R&B", "r and b" to "R&B", "r n b" to "R&B", "rhythm and blues" to "R&B", "contemporary r&b" to "R&B",
        "kindie" to "K-Indie", "korean indie" to "K-Indie",
        "electronica" to "Electronic", "electro" to "Electronic",
        "edm" to "EDM", "ost" to "OST", "soundtrack" to "OST",
        "dnb" to "Drum and Bass", "drum n bass" to "Drum and Bass", "drum & bass" to "Drum and Bass",
        "lofi" to "Lo-Fi", "lo fi" to "Lo-Fi", "lo-fi hip hop" to "Lo-Fi",
        "singer songwriter" to "Singer-Songwriter", "synth pop" to "Synthpop", "synth-pop" to "Synthpop",
        "alt rock" to "Alternative Rock", "citypop" to "City Pop", "trot" to "Trot", "트로트" to "Trot",
        "발라드" to "Ballad", "ballads" to "Ballad", "댄스" to "Dance", "록" to "Rock", "재즈" to "Jazz", "클래식" to "Classical",
    )

    /** 이 단어가 들어간 태그는 장르로 본다 */
    private val GENRE_KEYWORDS = listOf(
        "pop", "rock", "jazz", "hip-hop", "hip hop", "rap", "r&b", "soul", "funk", "disco", "electronic", "edm",
        "house", "techno", "trance", "dubstep", "drum and bass", "ambient", "classical", "metal", "punk", "indie",
        "alternative", "folk", "country", "blues", "reggae", "latin", "ballad", "ost", "soundtrack", "lo-fi",
        "trot", "gospel", "acoustic", "singer-songwriter", "dance", "synthpop", "shoegaze", "emo", "grunge",
        "new wave", "post-rock", "bossa nova", "city pop", "trap", "garage", "chiptune", "orchestral", "opera",
        "k-indie", "k-hip-hop", "musical", "instrumental", "experimental", "idm", "downtempo", "trip-hop", "swing",
    )

    /** 태그 → 분위기 (한국어 라벨) */
    private val MOODS = linkedMapOf(
        "편안한" to listOf("chill", "chillout", "relax", "relaxing", "calm", "mellow", "peaceful", "soft", "easy listening", "soothing", "cozy"),
        "슬픈" to listOf("sad", "melancholy", "melancholic", "depressing", "heartbreak", "sorrow", "tearjerker", "lonely", "emotional"),
        "밝은" to listOf("happy", "feel good", "feelgood", "cheerful", "fun", "uplifting", "sunny", "joyful", "summer"),
        "신나는" to listOf("energetic", "upbeat", "party", "workout", "hype", "groovy", "catchy", "danceable"),
        "강렬한" to listOf("aggressive", "intense", "powerful", "angry", "heavy"),
        "로맨틱" to listOf("romantic", "love", "love songs", "sexy", "sensual", "sweet"),
        "몽환적" to listOf("dreamy", "ethereal", "atmospheric", "psychedelic", "hypnotic", "spacey"),
        "어두운" to listOf("dark", "moody", "gloomy", "haunting"),
        "웅장한" to listOf("epic", "cinematic", "anthemic"),
        "그리운" to listOf("nostalgic", "nostalgia", "sentimental", "bittersweet"),
    )

    private val JUNK = setOf(
        "seen live", "favorites", "favourites", "favorite", "favourite", "my favorite", "favorite songs",
        "loved", "awesome", "amazing", "beautiful", "best", "good", "great", "cool", "albums i own", "spotify",
        "male vocalists", "female vocalists", "male vocalist", "female vocalist", "under 2000 listeners", "all",
    )

    fun moodOf(tag: String): String? {
        val t = tag.lowercase().trim()
        return MOODS.entries.firstOrNull { (_, words) -> t in words }?.key
    }

    fun isGenre(tag: String): Boolean {
        val t = tag.lowercase().trim()
        if (t in GENRE_ALIASES) return true
        return GENRE_KEYWORDS.any { kw -> t == kw || Regex("(^|[\\s-])${Regex.escape(kw)}($|[\\s-])").containsMatchIn(t) }
    }

    fun canonicalGenre(raw: String): String {
        val t = raw.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")
        GENRE_ALIASES[t]?.let { return it }
        GENRE_ALIASES[t.replace("-", " ")]?.let { return it }
        GENRE_ALIASES[t.replace("-", "").replace(" ", "")]?.let { return it }
        return t.split(' ').joinToString(" ") { word ->
            word.split('-').joinToString("-") { part ->
                when (part) {
                    "r&b" -> "R&B"
                    "edm", "ost", "idm" -> part.uppercase()
                    else -> part.replaceFirstChar { it.titlecase() }
                }
            }
        }
    }

    fun classify(rawTags: List<String>, artist: String? = null): Result {
        val artistLower = artist?.lowercase()?.trim()
        val tags = rawTags.map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { t ->
                val l = t.lowercase()
                l in JUNK || l == artistLower || Regex("^\\d{2,4}s?$").matches(l)
            }
            .distinctBy { it.lowercase() }

        val genres = tags.filter(::isGenre).map(::canonicalGenre).distinct()
        val mood = tags.firstNotNullOfOrNull(::moodOf)
        return Result(genre = genres.firstOrNull(), mood = mood, genres = genres, tags = tags.take(10))
    }

    /** 파일 태그의 MOOD 값(영어/한국어 자유 문자열)을 앱의 분위기 라벨로 맞춘다 */
    fun normalizeMood(raw: String): String {
        val first = raw.split(',', ';', '/').first().trim()
        return moodOf(first) ?: MOODS.keys.firstOrNull { it == first } ?: first
    }
}
