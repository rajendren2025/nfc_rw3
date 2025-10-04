package com.mycios.nfcwriter

import android.app.Activity
import android.nfc.*
import android.nfc.tech.MifareUltralight
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import java.nio.charset.Charset
import kotlin.math.ceil

class NfcRawActivity : Activity(), NfcAdapter.ReaderCallback {

    private enum class Mode { IDLE, RAW_READ, RAW_WRITE }

    private var nfcAdapter: NfcAdapter? = null

    private lateinit var rawData: EditText
    private lateinit var switchHex: Switch
    private lateinit var rawStartPage: EditText
    private lateinit var rawPages: EditText

    private lateinit var status: TextView
    private lateinit var tagInfo: TextView
    private lateinit var lastRead: TextView
    private lateinit var byteInfo: TextView

    @Volatile private var mode: Mode = Mode.IDLE
    @Volatile private var writePacked: String? = null // start|pages|text|isHex(0/1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_text)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) { toast("NFC not available on this device"); finish(); return }

        rawData = findViewById(R.id.rawData)
        switchHex = findViewById(R.id.switchHex)
        rawStartPage = findViewById(R.id.rawStartPage)
        rawPages = findViewById(R.id.rawPages)
        status = findViewById(R.id.status)
        tagInfo = findViewById(R.id.tagInfo)
        lastRead = findViewById(R.id.lastRead)
        byteInfo = findViewById(R.id.byteInfo)

        findViewById<Button>(R.id.btnRawRead).setOnClickListener {
            mode = Mode.RAW_READ
            setStatus("RAW READ: hold Ultralight tag…")
        }
        findViewById<Button>(R.id.btnRawWrite).setOnClickListener {
            val start = (rawStartPage.text?.toString()?.toIntOrNull()) ?: 4
            var pages = rawPages.text?.toString()?.toIntOrNull() ?: -1
            if (start < 4) { toast("Start page must be ≥ 4"); return@setOnClickListener }

            val isHex = if (switchHex.isChecked) 1 else 0
            val text = rawData.text?.toString() ?: ""
            val bytes = if (switchHex.isChecked) parseHex(text) else text.toByteArray(Charset.forName("UTF-8"))
            if (bytes == null) { toast("HEX parse error. Use pairs like: 48 65 6C 6C 6F"); return@setOnClickListener }

            if (pages <= 0) pages = pagesNeeded(bytes.size)
            writePacked = "$start|$pages|$text|$isHex"
            mode = Mode.RAW_WRITE
            setStatus("RAW WRITE: ${bytes.size} bytes → $pages page(s). Hold tag…")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            rawData.setText("")
            lastRead.text = "RAW dump will appear here"
            tagInfo.text = "Tag: (tap to read/write)"
            setStatus("cleared")
            updateByteInfo()
        }

        rawData.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateByteInfo() }
            override fun afterTextChanged(s: Editable?) {}
        })
        switchHex.setOnCheckedChangeListener { _, _ -> updateByteInfo() }
        updateByteInfo()
    }

    private fun pagesNeeded(bytes: Int): Int = if (bytes == 0) 0 else ceil(bytes / 4.0).toInt()

    private fun updateByteInfo() {
        val text = rawData.text?.toString() ?: ""
        val bytes = if (switchHex.isChecked) parseHex(text)?.size ?: 0 else text.toByteArray(Charset.forName("UTF-8")).size
        val pages = pagesNeeded(bytes)
        byteInfo.text = "$bytes bytes (pages needed: $pages)"
    }

    private fun parseHex(s: String): ByteArray? {
        val cleaned = s.replace(Regex("[^0-9A-Fa-f]"), "")
        if (cleaned.isEmpty()) return ByteArray(0)
        if (cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        var i = 0; var j = 0
        while (i < cleaned.length) {
            val v = cleaned.substring(i, i+2).toIntOrNull(16) ?: return null
            out[j++] = v.toByte()
            i += 2
        }
        return out
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        mode = Mode.IDLE
    }

    override fun onTagDiscovered(tag: Tag) {
        try {
            when (mode) {
                Mode.RAW_READ -> {
                    val (dumpHex, dumpAscii) = rawReadUltralight(tag)
                    val info = buildTagInfo(tag)
                    runOnUiThread {
                        tagInfo.text = info
                        lastRead.text = "RAW dump (hex):\n$dumpHex\n\nRAW (ascii):\n$dumpAscii"
                        setStatus("RAW READ OK ✅")
                        mode = Mode.IDLE
                    }
                }
                Mode.RAW_WRITE -> {
                    val packed = writePacked ?: return
                    val parts = packed.split("|", limit=4)
                    val start = parts[0].toInt()
                    val pages = parts[1].toInt()
                    val text = parts[2]
                    val isHex = parts[3].toInt() == 1
                    val bytes = if (isHex) parseHex(text)!! else text.toByteArray(Charset.forName("UTF-8"))
                    rawWriteUltralight(tag, start, pages, bytes)
                    val info = buildTagInfo(tag)
                    runOnUiThread {
                        tagInfo.text = info
                        setStatus("RAW WRITE OK ✅ (pages $start..${start+pages-1})")
                        mode = Mode.IDLE
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            runOnUiThread { setStatus("NFC error: ${e.message}") }
            mode = Mode.IDLE
        }
    }

    private fun rawReadUltralight(tag: Tag): Pair<String,String> {
        val mfu = MifareUltralight.get(tag) ?: throw IllegalStateException("Tag is not MifareUltralight")
        mfu.connect()
        try {
            val start = (rawStartPage.text?.toString()?.toIntOrNull()) ?: 4
            val pages = (rawPages.text?.toString()?.toIntOrNull()) ?: 4
            val hexParts = mutableListOf<String>()
            val asciiParts = mutableListOf<String>()
            var remaining = pages
            var p = start
            while (remaining > 0) {
                val data = mfu.readPages(p) // 16 bytes (4 pages)
                val count = kotlin.math.min(remaining, 4)
                for (i in 0 until count) {
                    val slice = data.copyOfRange(i*4, i*4+4)
                    hexParts.add(String.format("%02d: %s", p+i, slice.joinToString(" ") { "%02X".format(it) }))
                    asciiParts.add(slice.map { b ->
                        val c = b.toInt() and 0xFF
                        if (c in 32..126) c.toChar() else '.'
                    }.joinToString(""))
                }
                p += 4; remaining -= count
            }
            return hexParts.joinToString("\n") to asciiParts.joinToString("")
        } finally {
            try { mfu.close() } catch (_: Exception) {}
        }
    }

    private fun rawWriteUltralight(tag: Tag, start: Int, pages: Int, bytes: ByteArray) {
        if (start < 4) throw IllegalArgumentException("Start page must be ≥ 4")
        val mfu = MifareUltralight.get(tag) ?: throw IllegalStateException("Tag is not MifareUltralight")
        mfu.connect()
        try {
            var offset = 0
            for (i in 0 until pages) {
                val pageBytes = ByteArray(4) { 0x00 }
                for (j in 0 until 4) {
                    if (offset + j < bytes.size) pageBytes[j] = bytes[offset + j]
                }
                mfu.writePage(start + i, pageBytes)
                offset += 4
            }
        } finally {
            try { mfu.close() } catch (_: Exception) {}
        }
    }

    private fun buildTagInfo(tag: Tag): String {
        val uid = bytesToHex(tag.id)
        val techs = tag.techList.joinToString(", ") { it.substringAfterLast('.') }
        return "Tag UID: $uid\nTech: $techs"
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hex = "0123456789ABCDEF".toCharArray()
        val out = CharArray(bytes.size*2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = hex[v ushr 4]
            out[i++] = hex[v and 0x0F]
        }
        return String(out)
    }

    private fun setStatus(msg: String) { status.text = "Status: $msg" }
    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
