package com.mymusiccurator

import com.mymusiccurator.data.metadata.AudioTagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class AudioTagReaderTest {

    private fun syncSafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(),
        ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
    )

    private fun be(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    private fun frame(id: String, body: ByteArray, v4: Boolean) =
        id.toByteArray(Charsets.ISO_8859_1) + (if (v4) syncSafe(body.size) else be(body.size)) + byteArrayOf(0, 0) + body

    private fun latin1(s: String) = byteArrayOf(0) + s.toByteArray(Charsets.ISO_8859_1)
    private fun utf8(s: String) = byteArrayOf(3) + s.toByteArray(Charsets.UTF_8)
    private fun utf16(s: String) = byteArrayOf(1) + s.toByteArray(Charsets.UTF_16)  // BOM 포함

    private fun id3(major: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, f -> acc + f } + ByteArray(32) // padding
        return "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, 0) + syncSafe(body.size) + body + ByteArray(100) // audio
    }

    @Test
    fun readsId3v23WithTxxxMood() {
        val txxx = byteArrayOf(0) + "MOOD".toByteArray() + byteArrayOf(0) + "Chill".toByteArray()
        val data = id3(
            3,
            frame("TIT2", utf16("밤편지"), false),
            frame("TPE1", utf16("아이유"), false),
            frame("TCON", latin1("(17)"), false),
            frame("TBPM", latin1("72"), false),
            frame("TYER", latin1("2017"), false),
            frame("APIC", ByteArray(500) { 1 }, false),
            frame("TXXX", txxx, false),
        )
        val tags = AudioTagReader.read(data.inputStream())!!
        assertEquals("밤편지", tags.title)
        assertEquals("아이유", tags.artist)
        assertEquals("Rock", tags.genre)
        assertEquals(72, tags.bpm)
        assertEquals(2017, tags.year)
        assertEquals("Chill", tags.mood)
    }

    @Test
    fun readsId3v24AndPrefersOriginalReleaseYear() {
        val data = id3(
            4,
            frame("TIT2", utf8("Dynamite"), true),
            frame("TCON", utf8("K-Pop\u0000Dance"), true),
            frame("TBPM", utf8("114.4"), true),
            frame("TMOO", utf8("Happy"), true),
            frame("TDRC", utf8("2021-07-09"), true),
            frame("TDOR", utf8("2020-08-21"), true),
            frame("TCOM", utf8("David Stewart"), true),
        )
        val tags = AudioTagReader.read(data.inputStream())!!
        assertEquals("K-Pop", tags.genre)
        assertEquals(114, tags.bpm)
        assertEquals("Happy", tags.mood)
        assertEquals(2020, tags.year)
        assertEquals("David Stewart", tags.composer)
    }

    @Test
    fun readsFlacVorbisComments() {
        fun le(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
        val comments = listOf("TITLE=Clair de Lune", "GENRE=Classical", "BPM=60", "MOOD=calm", "DATE=1905")
        val block = ByteArrayOutputStream().apply {
            val vendor = "test".toByteArray()
            write(le(vendor.size)); write(vendor)
            write(le(comments.size))
            comments.forEach { c -> val b = c.toByteArray(); write(le(b.size)); write(b) }
        }.toByteArray()
        val streamInfo = byteArrayOf(0, 0, 0, 34) + ByteArray(34)
        val vorbisHeader = byteArrayOf((0x80 or 4).toByte(), (block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte())
        val data = "fLaC".toByteArray() + streamInfo + vorbisHeader + block

        val tags = AudioTagReader.read(data.inputStream())!!
        assertEquals("Clair de Lune", tags.title)
        assertEquals("Classical", tags.genre)
        assertEquals(60, tags.bpm)
        assertEquals("calm", tags.mood)
        assertEquals(1905, tags.year)
    }

    @Test
    fun returnsNullForUnknownFormat() {
        assertNull(AudioTagReader.read(ByteArray(64) { 7 }.inputStream()))
    }
}
