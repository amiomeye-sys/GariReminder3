package com.gari.reminder

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

object DateUtil {
    val out: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val dmy = Regex("""(\d{1,2})\s*[/\-.]\s*(\d{1,2})\s*[/\-.]\s*(\d{4}|\d{2})""")
    private val iso = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val named = Regex("""(\d{1,2})[\s\-]+([A-Za-z]{3})[A-Za-z]*[\s\-,]+(\d{4})""")

    private fun mk(y: Int, m: Int, d: Int): LocalDate? = try {
        val yy = if (y < 100) 2000 + y else y
        LocalDate.of(yy, m, d)
    } catch (_: Exception) { null }

    /** Text er moddhe jototuku date ache sob ber kore (order e). */
    fun findAll(text: String): List<LocalDate> {
        val r = mutableListOf<Pair<Int, LocalDate>>()
        iso.findAll(text).forEach { m ->
            mk(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { r += m.range.first to it }
        }
        dmy.findAll(text).forEach { m ->
            mk(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { r += m.range.first to it }
        }
        named.findAll(text).forEach { m ->
            val mi = months.indexOf(m.groupValues[2].lowercase())
            if (mi >= 0) mk(m.groupValues[3].toInt(), mi + 1, m.groupValues[1].toInt())?.let { r += m.range.first to it }
        }
        return r.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun parseOne(s: String): LocalDate? {
        val t = s.trim()
        if (t.isEmpty()) return null
        // Excel serial number (e.g. 46000)
        t.toDoubleOrNull()?.let { n ->
            if (n in 30000.0..70000.0) return LocalDate.of(1899, 12, 30).plusDays(n.toLong())
        }
        return findAll(t).firstOrNull()
    }
}

data class Entry(
    val regNo: String = "",
    val name: String = "",
    val phone: String = "",
    val issue: LocalDate? = null,
    val exp: LocalDate? = null
)

object Importer {
    private val regRe = Regex("""\b([A-Z]{2})[\s\-]?(\d{1,2})[\s\-]?([A-Z]{1,3})[\s\-]?(\d{4})\b""")
    private val phoneRe = Regex("""(?<!\d)(?:\+?91[\s\-]?)?([6-9]\d{9})(?!\d)""")

    fun cleanReg(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    fun fileName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else ""
        } ?: ""

    // ---------- text theke (Word / Photo) ----------
    fun fromText(text: String): List<Entry> {
        val lines = text.lines()
        val perLine = lines.mapNotNull { line ->
            val reg = regRe.find(line.uppercase()) ?: return@mapNotNull null
            build(cleanReg(reg.value), line)
        }
        if (perLine.isNotEmpty()) return perLine
        // Ek gari: puro text theke
        val reg = regRe.find(text.uppercase())?.value?.let { cleanReg(it) } ?: ""
        return listOf(build(reg, text))
    }

    private fun build(reg: String, chunk: String): Entry {
        val dates = DateUtil.findAll(chunk)
        val phone = phoneRe.find(chunk)?.groupValues?.get(1) ?: ""
        // PUC: shesh date = expiry, tar age er date = issue
        val exp = dates.maxOrNull()
        val issue = if (dates.size >= 2) dates.minOrNull() else null
        return Entry(regNo = reg, phone = phone, issue = issue, exp = exp)
    }

    // ---------- Photo (OCR, offline) ----------
    suspend fun fromImage(ctx: Context, uri: Uri): List<Entry> {
        val img = InputImage.fromFilePath(ctx, uri)
        val res = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img).await()
        return fromText(res.text)
    }

    // ---------- Word (.docx) ----------
    fun fromDocx(ctx: Context, uri: Uri): List<Entry> {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val xml = unzipEntry(bytes, "word/document.xml") ?: return emptyList()
        val text = xml.replace("</w:p>", "\n").replace("</w:tc>", " | ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        return fromText(text)
    }

    // ---------- Excel (.xlsx) / CSV ----------
    fun fromSheet(ctx: Context, uri: Uri, name: String): List<Entry> {
        val rows: List<List<String>> =
            if (name.lowercase().endsWith(".csv"))
                ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readLines()
                    .map { l -> l.split(",").map { it.trim().trim('"') } }
            else readXlsx(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        if (rows.isEmpty()) return emptyList()

        fun find(vararg keys: String): Int =
            rows[0].indexOfFirst { h -> keys.any { h.lowercase().contains(it) } }

        val cReg = find("gari", "vehicle", "reg", "number")
        val cName = find("name", "naam", "client", "owner")
        val cPhone = find("phone", "mobile", "mob", "contact")
        val cIssue = find("issue", "start", "from")
        val cExp = find("exp", "last", "valid")
        val hasHeader = cReg >= 0 || cExp >= 0
        val data = if (hasHeader) rows.drop(1) else rows
        // header na thakle order: gari no, naam, phone, issue, expiry
        fun g(r: List<String>, idx: Int, fallback: Int) =
            r.getOrNull(if (hasHeader && idx >= 0) idx else if (hasHeader) -1 else fallback) ?: ""

        return data.mapNotNull { r ->
            val reg = cleanReg(g(r, cReg, 0))
            if (reg.isEmpty()) return@mapNotNull null
            Entry(
                regNo = reg,
                name = g(r, cName, 1),
                phone = g(r, cPhone, 2).filter { it.isDigit() || it == '+' },
                issue = DateUtil.parseOne(g(r, cIssue, 3)),
                exp = DateUtil.parseOne(g(r, cExp, 4))
            )
        }
    }

    private fun unzipEntry(bytes: ByteArray, name: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return null
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
        return n - 1
    }

    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        val shared = mutableListOf<String>()
        unzipEntry(bytes, "xl/sharedStrings.xml")?.let { xml ->
            val p = Xml.newPullParser(); p.setInput(xml.reader())
            var inSi = false; val sb = StringBuilder(); var inT = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> { inSi = true; sb.clear() }
                        "t" -> inT = inSi
                    }
                    XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "t" -> inT = false
                        "si" -> { shared += sb.toString(); inSi = false }
                    }
                }
                ev = p.next()
            }
        }
        val sheet = unzipEntry(bytes, "xl/worksheets/sheet1.xml") ?: return emptyList()
        val p = Xml.newPullParser(); p.setInput(sheet.reader())
        val rows = mutableListOf<MutableList<String>>()
        var cur: MutableList<String>? = null
        var col = 0; var type = ""; var inV = false; var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cur = mutableListOf()
                    "c" -> {
                        col = colIndex(p.getAttributeValue(null, "r") ?: "A1")
                        type = p.getAttributeValue(null, "t") ?: ""
                        while ((cur?.size ?: 0) < col) cur?.add("")
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> {
                    if (inV) cur?.add(if (type == "s") shared.getOrElse(p.text.toInt()) { "" } else p.text)
                    else if (inT && type == "inlineStr") cur?.add(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "row" -> { cur?.let { rows += it }; cur = null }
                }
            }
            ev = p.next()
        }
        return rows
    }
}
