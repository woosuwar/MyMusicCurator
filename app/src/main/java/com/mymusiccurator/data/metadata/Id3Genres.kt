package com.mymusiccurator.data.metadata

/** ID3v1 숫자 장르 코드("(17)", "17")를 이름으로 바꾸고 표기를 정규화한다. */
object Id3Genres {
    private val ID3V1 = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    fun normalize(raw: String): String? {
        val parts = raw.split(',', ';', '/', '\u0000')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val first = parts.firstOrNull() ?: return null
        val code = Regex("^\\(?(\\d{1,3})\\)?").find(first)
        val name = if (code != null) {
            val rest = first.substring(code.range.last + 1).trim()
            rest.ifEmpty { ID3V1.getOrNull(code.groupValues[1].toInt()) ?: return null }
        } else {
            first
        }
        return TagClassifier.canonicalGenre(name)
    }
}
