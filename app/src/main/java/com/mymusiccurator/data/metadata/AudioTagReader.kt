package com.mymusiccurator.data.metadata

import java.io.InputStream
import java.nio.charset.Charset

/** 오디오 파일 태그에서 뽑아낸 값. 없는 값은 null. */
data class FileTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val bpm: Int? = null,
    val mood: String? = null,
    val year: Int? = null,
    val composer: String? = null,
)

/**
 * MediaMetadataRetriever 가 주지 않는 BPM / 분위기(MOOD) 를 얻기 위해
 * ID3v2(mp3) 와 Vorbis comment(FLAC) 를 직접 읽는다.
 */
object AudioTagReader {
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    fun read(input: InputStream): FileTags? {
        val head = input.readFully(4) ?: return null
        return when {
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                readId3(head, input)
            String(head, Charsets.ISO_8859_1) == "fLaC" -> readFlac(input)
            else -> null
        }
    }

    // ---------------------------------------------------------------- ID3v2

    private fun readId3(head: ByteArray, input: InputStream): FileTags? {
        val rest = input.readFully(6) ?: return null
        val major = head[3].toInt()
        if (major !in 2..4) return null
        val flags = rest[1].toInt() and 0xFF
        val size = syncSafe(rest, 2)
        var tag = input.readFully(minOf(size, MAX_TAG_BYTES), allowShort = true) ?: return null

        // v2.2/v2.3 는 태그 전체에 unsynchronisation 이 걸릴 수 있다
        if (flags and 0x80 != 0 && major < 4) tag = removeUnsync(tag)

        var pos = 0
        if (flags and 0x40 != 0 && major >= 3) {
            pos = if (major == 4) syncSafe(tag, 0) else readInt(tag, 0) + 4
        }

        val frames = mutableMapOf<String, String>()
        val txxx = mutableMapOf<String, String>()
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10

        while (pos + headerLen <= tag.size) {
            val id = String(tag, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break
            val frameSize = when (major) {
                2 -> ((tag[pos + 3].toInt() and 0xFF) shl 16) or ((tag[pos + 4].toInt() and 0xFF) shl 8) or (tag[pos + 5].toInt() and 0xFF)
                3 -> readInt(tag, pos + 4)
                else -> syncSafe(tag, pos + 4)
            }
            val frameFlags = if (major == 2) 0 else ((tag[pos + 8].toInt() and 0xFF) shl 8) or (tag[pos + 9].toInt() and 0xFF)
            val start = pos + headerLen
            if (frameSize <= 0 || start + frameSize > tag.size) break
            pos = start + frameSize

            if (!(id.startsWith("T") || id.startsWith("W"))) continue
            var body = tag.copyOfRange(start, start + frameSize)
            if (major == 4) {
                if (frameFlags and 0x000C != 0) continue // 압축/암호화 프레임은 건너뜀
                if (frameFlags and 0x0001 != 0) body = body.copyOfRange(4.coerceAtMost(body.size), body.size)
                if (frameFlags and 0x0002 != 0) body = removeUnsync(body)
            } else if (major == 3 && frameFlags and 0x00C0 != 0) {
                continue
            }

            if (id == "TXXX" || id == "TXX") {
                parseTxxx(body)?.let { (desc, value) -> txxx[desc.uppercase()] = value }
            } else if (id.startsWith("T")) {
                decodeText(body)?.let { frames[id] = it }
            }
        }

        fun f(vararg ids: String) = ids.firstNotNullOfOrNull { frames[it]?.takeIf(String::isNotBlank) }

        return FileTags(
            title = f("TIT2", "TT2"),
            artist = f("TPE1", "TP1"),
            album = f("TALB", "TAL"),
            genre = f("TCON", "TCO")?.let(Id3Genres::normalize),
            bpm = (f("TBPM", "TBP") ?: txxx["BPM"])?.let(::parseBpm),
            mood = f("TMOO") ?: txxx["MOOD"],
            // "작곡 시기" 에 가까운 원 발매연도를 우선한다
            year = parseYear(f("TDOR", "TORY", "TOR") ?: txxx["ORIGINALYEAR"] ?: f("TDRC", "TYER", "TYE")),
            composer = f("TCOM", "TCM"),
        )
    }

    private fun parseTxxx(body: ByteArray): Pair<String, String>? {
        if (body.isEmpty()) return null
        val enc = body[0].toInt()
        val charset = charsetFor(enc)
        val wide = enc == 1 || enc == 2
        // 설명(description) 끝의 NUL 종결자 위치를 찾는다 (UTF-16 은 2바이트 단위)
        var i = 1
        if (wide) {
            while (i + 1 < body.size && !(body[i].toInt() == 0 && body[i + 1].toInt() == 0)) i += 2
            if (i + 1 >= body.size) return null
        } else {
            while (i < body.size && body[i].toInt() != 0) i++
            if (i >= body.size) return null
        }
        val desc = String(body, 1, i - 1, charset).trim('\u0000', '﻿', ' ')
        val valueStart = i + if (wide) 2 else 1
        if (valueStart > body.size) return null
        val valueBytes = byteArrayOf(enc.toByte()) + body.copyOfRange(valueStart, body.size)
        val value = decodeText(valueBytes) ?: return null
        return desc to value
    }

    internal fun decodeText(body: ByteArray): String? {
        if (body.size < 2) return null
        val text = String(body, 1, body.size - 1, charsetFor(body[0].toInt()))
        // v2.4 는 여러 값을 NUL 로 구분한다
        return text.split('\u0000')
            .map { it.trim('﻿', ' ') }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .ifEmpty { null }
    }

    private fun charsetFor(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun removeUnsync(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    // ---------------------------------------------------------------- FLAC

    private fun readFlac(input: InputStream): FileTags? {
        while (true) {
            val header = input.readFully(4) ?: return null
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val len = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == 4) {
                val block = input.readFully(len) ?: return null
                return parseVorbisComments(block)
            }
            input.skipFully(len.toLong())
            if (last) return null
        }
    }

    internal fun parseVorbisComments(block: ByteArray): FileTags {
        fun le(off: Int) = (block[off].toInt() and 0xFF) or ((block[off + 1].toInt() and 0xFF) shl 8) or
            ((block[off + 2].toInt() and 0xFF) shl 16) or ((block[off + 3].toInt() and 0xFF) shl 24)

        val map = mutableMapOf<String, String>()
        var pos = 0
        val vendorLen = le(pos); pos += 4 + vendorLen
        if (pos + 4 > block.size) return FileTags()
        val count = le(pos); pos += 4
        repeat(count) {
            if (pos + 4 > block.size) return@repeat
            val len = le(pos); pos += 4
            if (len < 0 || pos + len > block.size) return@repeat
            val entry = String(block, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                val value = entry.substring(eq + 1).trim()
                map[key] = map[key]?.let { "$it, $value" } ?: value
            }
        }
        return FileTags(
            title = map["TITLE"],
            artist = map["ARTIST"],
            album = map["ALBUM"],
            genre = map["GENRE"]?.let(Id3Genres::normalize),
            bpm = (map["BPM"] ?: map["TEMPO"])?.let(::parseBpm),
            mood = map["MOOD"],
            year = parseYear(map["ORIGINALDATE"] ?: map["ORIGINALYEAR"] ?: map["DATE"] ?: map["YEAR"]),
            composer = map["COMPOSER"],
        )
    }

    // ---------------------------------------------------------------- helpers

    internal fun parseBpm(raw: String): Int? =
        raw.trim().replace(',', '.').toDoubleOrNull()?.let { Math.round(it).toInt() }?.takeIf { it in 20..400 }

    internal fun parseYear(raw: String?): Int? =
        raw?.let { Regex("(1[89]\\d{2}|20\\d{2})").find(it)?.value?.toInt() }

    private fun InputStream.readFully(n: Int, allowShort: Boolean = false): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return when {
            read == n -> buf
            allowShort && read > 0 -> buf.copyOf(read)
            else -> null
        }
    }

    private fun InputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) {
                if (read() < 0) return
                left--
            } else {
                left -= s
            }
        }
    }
}
