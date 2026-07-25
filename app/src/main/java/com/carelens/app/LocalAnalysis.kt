package com.carelens.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal data class SourceCitation(val documentId: String, val documentName: String, val page: Int)

internal data class GroundedAnswer(
    val answer: String,
    val citations: List<SourceCitation>,
    val safetyNote: String,
)

/** Bundled OCR only. It never sends document pixels or text off-device. */
internal class LocalTextExtractor(private val resolver: ContentResolver) {
    suspend fun extract(uri: Uri, mimeType: String): List<String> = withContext(Dispatchers.IO) {
        val images = if (mimeType == "application/pdf") renderPdf(uri) else listOf(decodeImage(uri))
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        try {
            images.map { bitmap ->
                val image = InputImage.fromBitmap(bitmap, 0)
                val english = latin.process(image).await().text
                val hindi = devanagari.process(image).await().text
                mergeOcr(english, hindi)
            }
        } finally {
            latin.close()
            devanagari.close()
            images.forEach { it.recycle() }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap =
        resolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        } ?: error("The selected image could not be decoded.")

    private fun renderPdf(uri: Uri): List<Bitmap> {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("The PDF could not be opened.")
        descriptor.use { fileDescriptor ->
            PdfRenderer(fileDescriptor).use { renderer ->
                require(renderer.pageCount <= MAX_PDF_PAGES) { "PDF has more than $MAX_PDF_PAGES pages." }
                return List(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        val scale = (MAX_RENDER_WIDTH.toFloat() / page.width).coerceAtMost(2f)
                        val width = (page.width * scale).toInt()
                        val height = (page.height * scale).toInt()
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
    }

    private fun mergeOcr(latin: String, devanagari: String): String =
        listOf(latin.trim(), devanagari.trim()).filter { it.isNotBlank() }.distinct().joinToString("\n")

    private companion object {
        const val MAX_PDF_PAGES = 20
        const val MAX_RENDER_WIDTH = 1600
    }
}

/**
 * A deterministic, document-only retrieval layer. It deliberately does not provide diagnosis,
 * dosage, or treatment instructions and never uses network or general-knowledge generation.
 */
internal object DocumentGroundedAssistant {
    fun answer(
        question: String,
        documents: List<MedicalDocument>,
        pagesByDocument: Map<String, List<String>>,
        hindi: Boolean,
    ): GroundedAnswer {
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .toSet()
        val matches = buildList {
            documents.forEach { document ->
                pagesByDocument[document.id].orEmpty().forEachIndexed { index, page ->
                    val score = terms.count { page.lowercase().contains(it) }
                    if (score > 0) add(Triple(document, index, snippet(page, terms)))
                }
            }
        }.take(3)
        val note = if (hindi) {
            "यह उत्तर केवल आपके अपलोड किए गए दस्तावेज़ों पर आधारित है। यह निदान या उपचार निर्देश नहीं है।"
        } else {
            "This answer is based only on your uploaded documents. It is not a diagnosis or treatment instruction."
        }
        if (matches.isEmpty()) {
            return GroundedAnswer(
                if (hindi) "मुझे आपके अपलोड किए गए दस्तावेज़ों में इसका प्रमाण नहीं मिला।" else "I could not find evidence for this in your uploaded documents.",
                emptyList(),
                note,
            )
        }
        val prefix = if (hindi) "आपके दस्तावेज़ों में यह लिखा है:" else "Your documents state:"
        return GroundedAnswer(
            "$prefix\n${matches.joinToString("\n\n") { it.third }}",
            matches.map { SourceCitation(it.first.id, it.first.displayName, it.second + 1) },
            note,
        )
    }

    fun recommendations(
        documents: List<MedicalDocument>,
        pagesByDocument: Map<String, List<String>>,
        hindi: Boolean,
    ): List<GroundedAnswer> {
        val trigger = listOf("urgent", "emergency", "immediately", "follow up", "repeat test", "consult")
        val recommendations = mutableListOf<GroundedAnswer>()
        documents.forEach { document ->
            pagesByDocument[document.id].orEmpty().forEachIndexed { index, page ->
                if (trigger.any { page.contains(it, ignoreCase = true) }) {
                    val text = if (hindi) {
                        "इस दस्तावेज़ में फॉलो-अप या जल्द चिकित्सकीय सलाह का उल्लेख है। कृपया इसे किसी योग्य चिकित्सक से चर्चा करें।"
                    } else {
                        "This document mentions follow-up or prompt medical advice. Please discuss it with a qualified clinician."
                    }
                    recommendations += GroundedAnswer(
                        text,
                        listOf(SourceCitation(document.id, document.displayName, index + 1)),
                        if (hindi) "CareLens कोई निदान, दवा की खुराक या उपचार नहीं देता।" else "CareLens does not provide diagnosis, medication dosage, or treatment instructions.",
                    )
                }
            }
        }
        return recommendations.distinctBy { it.citations }
    }

    private fun snippet(page: String, terms: Set<String>): String {
        val match = page.lines().firstOrNull { line -> terms.any { line.contains(it, ignoreCase = true) } }
            ?: page.take(350)
        return match.take(500)
    }
}
