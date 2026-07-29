package com.carelens.app

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec

internal data class MedicalDocument(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val addedAt: Long,
    val extractionStatus: ExtractionStatus = ExtractionStatus.PENDING,
)

internal enum class ExtractionStatus { PENDING, READY, FAILED }

/** Stores document bytes, OCR text, and the catalog encrypted at rest inside app-private storage. */
internal class DocumentRepository(context: Context) {
    private val root = File(context.filesDir, "carelens_vault")
    private val documentsDir = File(root, "documents")
    private val textDir = File(root, "text")
    private val catalogFile = File(root, "catalog.bin")

    init {
        documentsDir.mkdirs()
        textDir.mkdirs()
    }

    fun load(session: VaultSession): List<MedicalDocument> = synchronized(this) {
        if (!catalogFile.exists()) return emptyList()
        decodeCatalog(readEncrypted(catalogFile, session)).sortedByDescending { it.addedAt }
    }

    fun importFromUri(
        resolver: ContentResolver,
        uri: Uri,
        session: VaultSession,
        displayName: String? = null,
        mimeType: String? = null,
    ): MedicalDocument = synchronized(this) {
        val document = MedicalDocument(
            id = UUID.randomUUID().toString(),
            displayName = displayName ?: resolver.fileName(uri) ?: "Medical document",
            mimeType = mimeType ?: resolver.getType(uri) ?: "application/octet-stream",
            addedAt = System.currentTimeMillis(),
        )
        writeEncryptedStream(resolver, uri, File(documentsDir, "${document.id}.bin"), session)
        val current = load(session).toMutableList().apply { add(document) }
        saveCatalog(current, session)
        document
    }

    fun saveExtractedText(
        documentId: String,
        pages: List<String>,
        session: VaultSession,
    ) = synchronized(this) {
        writeEncrypted(File(textDir, "$documentId.bin"), pages.joinToString(PAGE_SEPARATOR).toByteArray(Charsets.UTF_8), session)

        val updated = load(session).map {
            if (it.id == documentId) it.copy(extractionStatus = ExtractionStatus.READY) else it
        }
        saveCatalog(updated, session)
    }

    fun markExtractionFailed(documentId: String, session: VaultSession) = synchronized(this) {
        val updated = load(session).map {
            if (it.id == documentId) it.copy(extractionStatus = ExtractionStatus.FAILED) else it
        }
        saveCatalog(updated, session)
    }

    fun readExtractedPages(documentId: String, session: VaultSession): List<String> {
        val file = File(textDir, "$documentId.bin")
        if (!file.exists()) return emptyList()
        return readEncrypted(file, session).toString(Charsets.UTF_8).split(PAGE_SEPARATOR)
    }

    fun delete(documentId: String, session: VaultSession) = synchronized(this) {
        File(documentsDir, "$documentId.bin").delete()
        File(textDir, "$documentId.bin").delete()
        saveCatalog(load(session).filterNot { it.id == documentId }, session)
    }

    private fun saveCatalog(documents: List<MedicalDocument>, session: VaultSession) {
        val array = JSONArray()
        documents.forEach { document ->
            array.put(
                JSONObject()
                    .put("id", document.id)
                    .put("name", document.displayName)
                    .put("mime", document.mimeType)
                    .put("addedAt", document.addedAt)
                    .put("status", document.extractionStatus.name),
            )
        }
        writeEncrypted(catalogFile, array.toString().toByteArray(), session)
    }

    private fun decodeCatalog(raw: ByteArray): List<MedicalDocument> {
        val array = JSONArray(raw.toString(Charsets.UTF_8))
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.getJSONObject(index)
                add(
                    MedicalDocument(
                        id = value.getString("id"),
                        displayName = value.getString("name"),
                        mimeType = value.getString("mime"),
                        addedAt = value.getLong("addedAt"),
                        extractionStatus = ExtractionStatus.valueOf(value.getString("status")),
                    ),
                )
            }
        }
    }

    private fun writeEncryptedStream(
        resolver: ContentResolver,
        uri: Uri,
        target: File,
        session: VaultSession,
    ) {
        resolver.openInputStream(uri)?.use { input ->
            writeCipherOutput(target, session) { output -> input.copyTo(output) }
        } ?: error("The selected file could not be read.")
    }

    private fun writeEncrypted(target: File, raw: ByteArray, session: VaultSession) {
        writeCipherOutput(target, session) { output -> output.write(raw) }
        raw.fill(0)
    }

    private fun writeCipherOutput(target: File, session: VaultSession, block: (OutputStream) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        val iv = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, session.key, GCMParameterSpec(128, iv))
        }
        FileOutputStream(temp).use { file ->
            file.write(iv)
            CipherOutputStream(file, cipher).use(block)
        }
        if (!temp.renameTo(target)) error("Unable to store encrypted data.")
    }

    private fun readEncrypted(file: File, session: VaultSession): ByteArray {
        FileInputStream(file).use { input ->
            val iv = input.readNBytes(12)
            require(iv.size == 12) { "Invalid encrypted record." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, session.key, GCMParameterSpec(128, iv))
            }
            return cipher.doFinal(input.readBytes())
        }
    }

    private fun ContentResolver.fileName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.takeIf { it.moveToFirst() }?.getString(0)
        } finally {
            cursor?.close()
        }
    }

    private companion object {
        const val PAGE_SEPARATOR = "\n\n--- CareLens page break ---\n\n"
    }
}
