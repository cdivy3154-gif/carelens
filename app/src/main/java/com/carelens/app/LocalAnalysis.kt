package com.carelens.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
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
                mergeOcr(latin.process(image).await().text, devanagari.process(image).await().text)
            }
        } finally {
            latin.close()
            devanagari.close()
            images.forEach { it.recycle() }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap = resolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it)
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
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

/** A deterministic, document-only retrieval layer with bilingual safety responses. */
internal object DocumentGroundedAssistant {
    fun answer(
        question: String,
        documents: List<MedicalDocument>,
        pagesByDocument: Map<String, List<String>>,
        hindi: Boolean,
    ): GroundedAnswer {
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }.toSet()
        val matches = buildList {
            documents.forEach { document ->
                pagesByDocument[document.id].orEmpty().forEachIndexed { index, page ->
                    if (terms.count { page.lowercase().contains(it) } > 0) {
                        add(Triple(document, index, snippet(page, terms)))
                    }
                }
            }
        }.take(3)
        val safetyNote = if (hindi) {
            "यह उत्तर केवल आपके अपलोड किए गए दस्तावेज़ों पर आधारित है। यह निदान या उपचार निर्देश नहीं है।"
        } else {
            "This answer is based only on your uploaded documents. It is not a diagnosis or treatment instruction."
        }
        if (matches.isEmpty()) {
            return GroundedAnswer(
                if (hindi) "मुझे आपके अपलोड किए गए दस्तावेज़ों में इसका प्रमाण नहीं मिला।" else "I could not find evidence for this in your uploaded documents.",
                emptyList(),
                safetyNote,
            )
        }
        val prefix = if (hindi) "आपके दस्तावेज़ों में यह लिखा है:" else "Your documents state:"
        return GroundedAnswer(
            "$prefix\n${matches.joinToString("\n\n") { it.third }}",
            matches.map { SourceCitation(it.first.id, it.first.displayName, it.second + 1) },
            safetyNote,
        )
    }

    fun recommendations(
        documents: List<MedicalDocument>,
        pagesByDocument: Map<String, List<String>>,
        hindi: Boolean,
    ): List<GroundedAnswer> {
        val trigger = listOf("urgent", "emergency", "immediately", "follow up", "repeat test", "consult")
        return buildList {
            documents.forEach { document ->
                pagesByDocument[document.id].orEmpty().forEachIndexed { index, page ->
                    if (trigger.any { page.contains(it, ignoreCase = true) }) {
                        add(
                            GroundedAnswer(
                                if (hindi) {
                                    "इस दस्तावेज़ में फॉलो-अप या तुरंत चिकित्सकीय सलाह का उल्लेख है। कृपया इस पर किसी योग्य चिकित्सक से चर्चा करें।"
                                } else {
                                    "This document mentions follow-up or prompt medical advice. Please discuss it with a qualified clinician."
                                },
                                listOf(SourceCitation(document.id, document.displayName, index + 1)),
                                if (hindi) {
                                    "CareLens निदान, दवा की खुराक या उपचार निर्देश नहीं देता।"
                                } else {
                                    "CareLens does not provide diagnosis, medication dosage, or treatment instructions."
                                },
                            ),
                        )
                    }
                }
            }
        }.distinctBy { it.citations }
    }

    private fun snippet(page: String, terms: Set<String>): String =
        (page.lines().firstOrNull { line -> terms.any { line.contains(it, ignoreCase = true) } }
            ?: page.take(350)).take(500)
}
