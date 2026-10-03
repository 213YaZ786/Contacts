package com.yaz.contacts.core.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What reads a business card: Tesseract with the languages of the phone,
 * their data fetched once on the user's tap from Tesseract's own repository
 * at a pinned revision, each file checked by its size and SHA-256 before it
 * is kept; replaced only when an app update pins another revision.
 */
object OcrModels {

    /** tessdata_fast at this commit (Apache-2.0). */
    private const val REVISION = "87416418657359cb625c412a48b6e1d6d41c29bd"

    data class Pin(val code: String, val bytes: Long, val sha: String)

    private val PINS = listOf(
        Pin("eng", 4113088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
        Pin("fra", 1130365, "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca"),
        Pin("deu", 1525436, "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d"),
        Pin("spa", 2294433, "6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464"),
        Pin("ita", 2701314, "b8f89e1e785118dac4d51ae042c029a64edb5c3ee42ef73027a6d412748d8827"),
        Pin("por", 1982756, "c4932b937207a9514b7514d518b931a99938c02a28a5a5a553f8599ed58b7deb"),
        Pin("nld", 6050296, "ced0e5e046a84c908a6aa7accbef9a232c4a5d9a8276691b81c6ee64d02963f6"),
        Pin("ara", 1432056, "e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729"),
        Pin("rus", 3861738, "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"),
        Pin("chi_sim", 2469156, "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730"),
        Pin("jpn", 2471260, "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d"),
        Pin("kor", 1677415, "6b85e11d9bbf07863b97b3523b1b112844c43e713df8b66418a081fd1060b3b2"),
        Pin("tur", 4550554, "7393381111e1152420fc4092cb44eef4237580d21b92bf30d7d221aad192c6b7"),
        Pin("pol", 4765518, "c4476cdbc0e33d898d32345122b7be1cbf85ace15f920f06c7714756e1ef79b2"),
        Pin("hin", 1122751, "4c73ffc59d497c186b19d1e90f5d721d678ea6b2e277b719bee4e2af12271825")
    )

    private val BY_LANGUAGE = mapOf(
        "en" to "eng", "fr" to "fra", "de" to "deu", "es" to "spa", "it" to "ita", "pt" to "por", "nl" to "nld",
        "ar" to "ara", "ru" to "rus", "zh" to "chi_sim", "ja" to "jpn", "ko" to "kor", "tr" to "tur", "pl" to "pol", "hi" to "hin"
    )

    /** English and the phone's own languages, as far as Tesseract has them. */
    fun wanted(): List<Pin> {
        val locales = android.os.LocaleList.getDefault()
        val codes = linkedSetOf("eng")
        for (i in 0 until locales.size()) BY_LANGUAGE[locales[i].language]?.let(codes::add)
        return codes.mapNotNull { c -> PINS.firstOrNull { it.code == c } }
    }

    private fun dir(context: Context) = File(context.filesDir, "ocr/tessdata")

    fun ready(context: Context): Boolean = wanted().all { File(dir(context), "${it.code}.traineddata").length() == it.bytes }

    fun sizeToFetch(context: Context): Long = wanted().filter { File(dir(context), "${it.code}.traineddata").length() != it.bytes }.sumOf { it.bytes }

    /** Fetches what is missing; false when a file could not be fetched or did not match its pin. */
    suspend fun fetch(context: Context, progress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val folder = dir(context).apply { mkdirs() }
        // Files of another revision or language are let go.
        val keep = PINS.map { "${it.code}.traineddata" }.toSet()
        folder.listFiles().orEmpty().forEach { if (it.name !in keep || PINS.first { p -> "${p.code}.traineddata" == it.name }.bytes != it.length()) it.delete() }
        val missing = wanted().filter { File(folder, "${it.code}.traineddata").length() != it.bytes }
        val total = missing.sumOf { it.bytes }.coerceAtLeast(1)
        var done = 0L
        for (pin in missing) {
            val url = URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$REVISION/${pin.code}.traineddata")
            val ok = runCatching {
                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val temp = File(folder, "${pin.code}.part")
                connection.inputStream.use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            got += n
                            require(got <= pin.bytes)
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            progress((done + got).toFloat() / total)
                        }
                        require(got == pin.bytes)
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != pin.sha) {
                    temp.delete()
                    false
                } else temp.renameTo(File(folder, "${pin.code}.traineddata"))
            }.getOrDefault(false)
            if (!ok) return@withContext false
            done += pin.bytes
        }
        true
    }

    /** The text on [bitmap], read on the phone. */
    suspend fun read(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        val api = TessBaseAPI()
        try {
            val languages = wanted().filter { File(dir(context), "${it.code}.traineddata").length() == it.bytes }.joinToString("+") { it.code }
            if (languages.isEmpty() || !api.init(File(context.filesDir, "ocr").absolutePath, languages)) return@withContext null
            api.setImage(bitmap)
            api.getUTF8Text()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { api.recycle() }
        }
    }

    /** The phone's region, for the numbers on the card. */
    fun region(context: Context): String =
        context.getSystemService(android.telephony.TelephonyManager::class.java)?.let { t -> t.networkCountryIso.ifBlank { t.simCountryIso } }
            ?.takeIf { it.isNotBlank() }?.uppercase() ?: Locale.getDefault().country.ifBlank { "US" }
}
