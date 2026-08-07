package com.carelens.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal enum class MedicalRecordType { DATE, LAB_VALUE, MEDICINE, DIAGNOSIS, PRESCRIPTION }

/** A literal, cited detail found in a document. It never infers a diagnosis or a treatment. */
internal data class MedicalRecordItem(
    val type: MedicalRecordType,
    val text: String,
    val observedDate: String? = null,
    val sortDate: LocalDate? = null,
    val citation: SourceCitation,
)

/**
 * Creates an in-memory timeline from encrypted OCR text after the vault has been opened.
 * Nothing is sent away from the device and no clinical conclusion is generated from the text.
 */
internal object MedicalRecordOrganizer {
    fun organize(
        documents: List<MedicalDocument>,
        pagesByDocument: Map<String, List<String>>,
    ): List<MedicalRecordItem> {
        val items = buildList {
            documents.forEach { document ->
                pagesByDocument[document.id].orEmpty().forEachIndexed { pageIndex, page ->
                    val citation = SourceCitation(document.id, document.displayName, pageIndex + 1)
                    addAll(extractPage(page, citation))
                }
            }
        }
        return items.distinctBy { "${it.type}|${it.text.lowercase(Locale.ROOT)}|${it.citation.documentId}|${it.citation.page}" }
            .sortedWith(compareBy<MedicalRecordItem> { it.sortDate == null }.thenBy { it.sortDate }.thenBy { it.citation.documentName })
    }

    private fun extractPage(page: String, citation: SourceCitation): List<MedicalRecordItem> {
        val dates = findDates(page)
        val pageDate = dates.firstOrNull()
        val detailItems = page.lineSequence().flatMap { line ->
            sequence {
                labValue(line)?.let { yield(MedicalRecordItem(MedicalRecordType.LAB_VALUE, it, pageDate?.raw, pageDate?.parsed, citation)) }
                diagnosis(line)?.let { yield(MedicalRecordItem(MedicalRecordType.DIAGNOSIS, it, pageDate?.raw, pageDate?.parsed, citation)) }
                medicine(line)?.let { yield(MedicalRecordItem(MedicalRecordType.MEDICINE, it, pageDate?.raw, pageDate?.parsed, citation)) }
                prescription(line)?.let { yield(MedicalRecordItem(MedicalRecordType.PRESCRIPTION, it, pageDate?.raw, pageDate?.parsed, citation)) }
            }
        }.toList()
        return dates.map { date ->
            MedicalRecordItem(MedicalRecordType.DATE, date.raw, date.raw, date.parsed, citation)
        } + detailItems
    }

    private fun findDates(page: String): List<FoundDate> = DATE_PATTERN.findAll(page).mapNotNull { match ->
        val raw = match.value.trim()
        FoundDate(raw, parseDate(raw))
    }.toList().distinctBy { it.raw }

    private fun labValue(line: String): String? {
        val normalized = line.trim().replace(Regex("\\s+"), " ")
        if (normalized.length !in 3..180 || LAB_MARKERS.none { normalized.contains(it, ignoreCase = true) }) return null
        return normalized.takeIf { NUMBER_PATTERN.containsMatchIn(it) }
    }

    private fun diagnosis(line: String): String? {
        val normalized = line.trim().replace(Regex("\\s+"), " ")
        val marker = DIAGNOSIS_MARKERS.firstOrNull { normalized.contains(it, ignoreCase = true) } ?: return null
        val value = normalized.substringAfter(marker, "").trim().trim(':', '-', '–', '—')
        return value.takeIf { it.length >= 2 }?.take(180)?.let { "$marker: $it" }
    }

    private fun medicine(line: String): String? {
        val normalized = line.trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf {
            it.length in 3..180 && MEDICINE_PATTERN.containsMatchIn(it)
        }
    }

    private fun prescription(line: String): String? {
        val normalized = line.trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf {
            it.length in 3..180 && PRESCRIPTION_PATTERN.containsMatchIn(it)
        }
    }

    private fun parseDate(value: String): LocalDate? {
        val cleaned = value.replace('.', '/').trim()
        DATE_FORMATS.forEach { formatter ->
            try {
                return LocalDate.parse(cleaned, formatter)
            } catch (_: DateTimeParseException) {
                // Try the remaining report date formats.
            }
        }
        return null
    }

    private data class FoundDate(val raw: String, val parsed: LocalDate?)

    private val DATE_PATTERN = Regex(
        """(?i)\\b(?:\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}|(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+\\d{1,2},?\\s+\\d{4})\\b""",
    )
    private val NUMBER_PATTERN = Regex("""\\b\\d+(?:[.,]\\d+)?(?:\\s*(?:%|mg/?dL|g/?dL|mmol/?L|mIU/?L|IU/?L|/uL|cells/?mm3))?\\b""", RegexOption.IGNORE_CASE)
    private val MEDICINE_PATTERN = Regex("""(?i)\\b(?:rx|tab(?:let)?\\.?|cap(?:sule)?\\.?|syrup|inj(?:ection)?\\.?|drops?)\\b""")
    private val PRESCRIPTION_PATTERN = Regex("""(?i)\\b(?:od|bd|tds|qid|sos|once daily|twice daily|three times daily|after food|before food|at night|morning)\\b|दिन में|खाने के (?:बाद|पहले)""")
    private val LAB_MARKERS = listOf(
        "hemoglobin", "haemoglobin", "hba1c", "glucose", "sugar", "creatinine", "urea", "cholesterol",
        "triglyceride", "bilirubin", "platelet", "wbc", "rbc", "tsh", "t3", "t4", "vitamin", "calcium",
        "sodium", "potassium", "albumin", "protein", "uric acid", "alt", "ast", "esr", "crp",
        "हीमोग्लोबिन", "शुगर", "ग्लूकोज", "क्रिएटिनिन", "कोलेस्ट्रॉल",
    )
    private val DIAGNOSIS_MARKERS = listOf("diagnosis", "impression", "assessment", "clinical diagnosis", "निदान", "आकलन", "राय")
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("d/M/uuuu", Locale.US),
        DateTimeFormatter.ofPattern("d-M-uuuu", Locale.US),
        DateTimeFormatter.ofPattern("uuuu/M/d", Locale.US),
        DateTimeFormatter.ofPattern("uuuu-M-d", Locale.US),
        DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US),
        DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US),
    )
}
