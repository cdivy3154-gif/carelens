package com.carelens.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicalRecordOrganizerTest {
    @Test
    fun `copies supported record details with their document page`() {
        val document = MedicalDocument(
            id = "report-1",
            displayName = "Blood report",
            mimeType = "application/pdf",
            addedAt = 1L,
            extractionStatus = ExtractionStatus.READY,
        )

        val items = MedicalRecordOrganizer.organize(
            documents = listOf(document),
            pagesByDocument = mapOf(
                document.id to listOf(
                    """
                    Date: 12/04/2026
                    Hemoglobin: 12.5 g/dL
                    Diagnosis: Iron deficiency anemia
                    Tab. Ferrous sulfate 325 mg OD after food
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(setOf(
            MedicalRecordType.DATE,
            MedicalRecordType.LAB_VALUE,
            MedicalRecordType.DIAGNOSIS,
            MedicalRecordType.MEDICINE,
            MedicalRecordType.PRESCRIPTION,
        ), items.map { it.type }.toSet())
        assertTrue(items.all { it.citation.documentId == "report-1" && it.citation.page == 1 })
    }

    @Test
    fun `does not invent entries when a page has no supported markers`() {
        val document = MedicalDocument("report-2", "Note", "image/jpeg", 1L, ExtractionStatus.READY)
        val items = MedicalRecordOrganizer.organize(listOf(document), mapOf(document.id to listOf("Please rest and drink water.")))

        assertTrue(items.isEmpty())
    }
}
