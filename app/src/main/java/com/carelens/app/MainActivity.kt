package com.carelens.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var lockSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { CareLensApp(lockSignal.intValue) }
    }

    override fun onStop() {
        super.onStop()
        // A vault never remains open while the app is not visible. Configuration changes do not
        // count as leaving the app and therefore retain the in-memory session.
        if (!isChangingConfigurations) lockSignal.intValue++
    }
}

private enum class AppLanguage { ENGLISH, HINDI }
private enum class Screen { LANGUAGE, CREATE, PHRASE, LOCKED, RECOVER, ERASE, HOME, INSIGHTS }

private data class PendingImport(
    val uri: Uri,
    val displayName: String? = null,
    val mimeType: String? = null,
    val temporaryCameraFile: File? = null,
    val persistedReadGrant: Boolean = false,
)

/** The selected language is device-local metadata and is available before vault unlock. */
private class LanguageStore(context: Context) {
    private val preferences = context.getSharedPreferences("carelens_settings", Context.MODE_PRIVATE)

    fun load(): AppLanguage = when (preferences.getString("language", null)) {
        AppLanguage.HINDI.name -> AppLanguage.HINDI
        else -> AppLanguage.ENGLISH
    }

    fun save(language: AppLanguage) {
        preferences.edit().putString("language", language.name).apply()
    }
}

@Composable
private fun CareLensApp(lockSignal: Int) {
    val context = LocalContext.current.applicationContext
    val vaultStore = remember { VaultStore(context) }
    val documentRepository = remember { DocumentRepository(context) }
    val languageStore = remember { LanguageStore(context) }
    var language by remember { mutableStateOf(languageStore.load()) }
    var screen by remember { mutableStateOf(if (vaultStore.hasVault()) Screen.LOCKED else Screen.LANGUAGE) }
    var session by remember { mutableStateOf<VaultSession?>(null) }
    var pendingSecret by remember { mutableStateOf("") }
    var recoveryPhrase by remember { mutableStateOf("") }
    var documents by remember { mutableStateOf<List<MedicalDocument>>(emptyList()) }
    var pagesByDocument by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    val updateLanguage: (AppLanguage) -> Unit = { selected ->
        language = selected
        languageStore.save(selected)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pendingImport = PendingImport(uri = it, persistedReadGrant = true)
        }
    }
    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (saved && file != null) {
            pendingImport = PendingImport(
                uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                displayName = "Camera scan ${System.currentTimeMillis()}.jpg",
                mimeType = "image/jpeg",
                temporaryCameraFile = file,
            )
        } else {
            file?.delete()
        }
    }
    val startCameraCapture: () -> Unit = {
        runCatching {
            val directory = File(context.cacheDir, "camera").apply { mkdirs() }
            File.createTempFile("carelens_scan_", ".jpg", directory)
        }.onSuccess { file ->
            pendingCameraFile = file
            cameraCapture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        }.onFailure { importFailed = true }
    }

    LaunchedEffect(lockSignal) {
        if (lockSignal > 0 && session != null) {
            session?.clear()
            session = null
            screen = Screen.LOCKED
        }
    }

    LaunchedEffect(session, pendingImport) {
        val activeSession = session ?: run {
            documents = emptyList()
            pagesByDocument = emptyMap()
            return@LaunchedEffect
        }
        val import = pendingImport
        if (import == null) {
            val loaded = withContext(Dispatchers.IO) { documentRepository.load(activeSession) }
            documents = loaded
            pagesByDocument = withContext(Dispatchers.IO) {
                loaded.filter { it.extractionStatus == ExtractionStatus.READY }.associate { document ->
                    document.id to documentRepository.readExtractedPages(document.id, activeSession)
                }
            }
            return@LaunchedEffect
        }
        importing = true
        importFailed = false
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val document = documentRepository.importFromUri(
                    context.contentResolver,
                    import.uri,
                    activeSession,
                    import.displayName,
                    import.mimeType,
                )
                var staging: File? = null
                try {
                    staging = documentRepository.decryptDocumentForOcr(document, activeSession)
                    val stagingUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        staging,
                    )
                    val pages = LocalTextExtractor(context.contentResolver).extract(stagingUri, document.mimeType)
                    documentRepository.saveExtractedText(document.id, pages, activeSession)
                } catch (error: Exception) {
                    if (error is java.util.concurrent.CancellationException) throw error
                    documentRepository.markExtractionFailed(document.id, activeSession)
                } finally {
                    staging?.let(documentRepository::deleteOcrStaging)
                }
                val loaded = documentRepository.load(activeSession)
                loaded to loaded.filter { it.extractionStatus == ExtractionStatus.READY }.associate { saved ->
                    saved.id to documentRepository.readExtractedPages(saved.id, activeSession)
                }
            }
        }
        result.onSuccess { (loaded, pages) ->
            documents = loaded
            pagesByDocument = pages
        }.onFailure { importFailed = true }
        import.temporaryCameraFile?.delete()
        if (import.persistedReadGrant) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(import.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        pendingImport = null
        importing = false
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = CareLensBackground) {
            when (screen) {
                Screen.LANGUAGE -> LanguageScreen(
                    language = language,
                    onLanguageSelected = updateLanguage,
                    onContinue = { screen = Screen.CREATE },
                )
                Screen.CREATE -> CreateVaultScreen(
                    language = language,
                    onBack = { screen = Screen.LANGUAGE },
                    onSecretAccepted = { secret ->
                        pendingSecret = secret
                        recoveryPhrase = VaultSecretPolicy.generateRecoveryPhrase()
                        screen = Screen.PHRASE
                    },
                )
                Screen.PHRASE -> RecoveryPhraseScreen(
                    language = language,
                    phrase = recoveryPhrase,
                    onBack = {
                        pendingSecret = ""
                        recoveryPhrase = ""
                        screen = Screen.CREATE
                    },
                    onConfirmed = {
                        session = vaultStore.createVault(pendingSecret, recoveryPhrase)
                        pendingSecret = ""
                        recoveryPhrase = ""
                        screen = Screen.HOME
                    },
                )
                Screen.LOCKED -> UnlockScreen(
                    language = language,
                    onUnlock = { secret ->
                        vaultStore.unlock(secret)?.let {
                            session = it
                            screen = Screen.HOME
                            true
                        } ?: false
                    },
                    onRecover = { screen = Screen.RECOVER },
                    onErase = { screen = Screen.ERASE },
                )
                Screen.RECOVER -> RecoverVaultScreen(
                    language = language,
                    onBack = { screen = Screen.LOCKED },
                    onRecover = { phrase, newSecret ->
                        vaultStore.recover(phrase, newSecret)?.let {
                            session = it
                            screen = Screen.HOME
                            true
                        } ?: false
                    },
                )
                Screen.ERASE -> EraseVaultScreen(
                    language = language,
                    onCancel = { screen = Screen.LOCKED },
                    onErase = {
                        session?.clear()
                        session = null
                        vaultStore.wipeVault()
                        screen = Screen.LANGUAGE
                    },
                )
                Screen.HOME -> HomeScreen(
                    language = language,
                    onLanguageSelected = updateLanguage,
                    documents = documents,
                    importing = importing,
                    importFailed = importFailed,
                    onChooseDocument = { documentPicker.launch(arrayOf("image/*", "application/pdf")) },
                    onCaptureScan = startCameraCapture,
                    onOpenInsights = { screen = Screen.INSIGHTS },
                    onLock = {
                        session?.clear()
                        session = null
                        screen = Screen.LOCKED
                    },
                )
                Screen.INSIGHTS -> InsightsScreen(
                    language = language,
                    documents = documents,
                    pagesByDocument = pagesByDocument,
                    onBack = { screen = Screen.HOME },
                )
            }
        }
    }
}

@Composable
private fun LanguageScreen(language: AppLanguage, onLanguageSelected: (AppLanguage) -> Unit, onContinue: () -> Unit) {
    Page {
        BrandMark()
        Spacer(Modifier.height(44.dp))
        Heading(t(language, "Welcome to CareLens", "CareLens में आपका स्वागत है"))
        Body(t(language, "Your medical information stays on this phone.", "आपकी चिकित्सा जानकारी इसी फ़ोन पर रहती है।"))
        Spacer(Modifier.height(34.dp))
        Text(t(language, "Choose language", "भाषा चुनें"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        ChoiceButton("English", language == AppLanguage.ENGLISH) { onLanguageSelected(AppLanguage.ENGLISH) }
        Spacer(Modifier.height(12.dp))
        ChoiceButton("हिन्दी", language == AppLanguage.HINDI) { onLanguageSelected(AppLanguage.HINDI) }
        Spacer(Modifier.weight(1f))
        PrivacyCard(t(language, "Local-only. No Internet permission. No cloud account.", "केवल फ़ोन पर। इंटरनेट अनुमति नहीं। कोई क्लाउड खाता नहीं।"))
        Spacer(Modifier.height(18.dp))
        PrimaryButton(t(language, "Continue", "आगे बढ़ें"), onClick = onContinue)

    }
}

@Composable
private fun CreateVaultScreen(language: AppLanguage, onBack: () -> Unit, onSecretAccepted: (String) -> Unit) {
    var method by remember { mutableStateOf(LockMethod.PIN) }
    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val isPin = method == LockMethod.PIN
    val hint = if (isPin) t(language, "At least 6 digits", "कम-से-कम 6 अंक") else t(language, "At least 10 characters", "कम-से-कम 10 अक्षर")

    Page {
        BackButton(language, onBack)
        Spacer(Modifier.height(28.dp))
        BrandMark()
        Spacer(Modifier.height(26.dp))
        Heading(t(language, "Create your secure vault", "अपना सुरक्षित वॉल्ट बनाएँ"))
        Body(t(language, "Choose the lock used to encrypt your private records.", "अपने निजी रिकॉर्ड एन्क्रिप्ट करने के लिए लॉक चुनें।"))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            ChoiceButton(t(language, "PIN", "पिन"), isPin, Modifier.weight(1f)) { method = LockMethod.PIN; secret = ""; confirm = ""; error = null }
            Spacer(Modifier.width(12.dp))
            ChoiceButton(t(language, "Password", "पासवर्ड"), !isPin, Modifier.weight(1f)) { method = LockMethod.PASSWORD; secret = ""; confirm = ""; error = null }
        }
        Spacer(Modifier.height(20.dp))
        SecretField(secret, if (isPin) t(language, "App PIN", "ऐप पिन") else t(language, "App password", "ऐप पासवर्ड"), hint, isPin) { secret = it; error = null }
        Spacer(Modifier.height(12.dp))
        SecretField(confirm, t(language, "Confirm lock", "लॉक की पुष्टि करें"), hint, isPin) { confirm = it; error = null }
        error?.let { ErrorText(it) }
        Spacer(Modifier.height(20.dp))
        PrivacyCard(t(language, "Your lock is never stored. Keep the recovery phrase shown next somewhere safe.", "आपका लॉक कभी संग्रहीत नहीं किया जाता। अगला रिकवरी वाक्यांश सुरक्षित रखें।"))
        Spacer(Modifier.weight(1f))
        PrimaryButton(t(language, "Continue", "आगे बढ़ें")) {
            error = when {
                !VaultSecretPolicy.isValid(method, secret) -> hint
                secret != confirm -> t(language, "The two entries do not match.", "दोनों प्रविष्टियाँ समान नहीं हैं।")
                else -> null
            }
            if (error == null) onSecretAccepted(secret)
        }
    }
}

@Composable
private fun RecoveryPhraseScreen(language: AppLanguage, phrase: String, onBack: () -> Unit, onConfirmed: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    Page {
        BackButton(language, onBack)
        Spacer(Modifier.height(28.dp))
        Heading(t(language, "Save your recovery phrase", "अपना रिकवरी वाक्यांश सहेजें"))
        Body(t(language, "Write these 12 words down in order. They are the only way to reset a forgotten app lock. CareLens cannot show them again.", "इन 12 शब्दों को क्रम से लिख लें। भूले हुए ऐप लॉक को रीसेट करने का यही एकमात्र तरीका है। CareLens इन्हें फिर नहीं दिखा सकता।"))
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Text(phrase, modifier = Modifier.padding(20.dp), fontSize = 20.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, color = CareLensInk)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text(t(language, "I have written down this phrase.", "मैंने यह वाक्यांश लिख लिया है।"), modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(t(language, "Create secure vault", "सुरक्षित वॉल्ट बनाएँ"), enabled = saved, onClick = onConfirmed)
    }
}

@Composable
private fun UnlockScreen(language: AppLanguage, onUnlock: (String) -> Boolean, onRecover: () -> Unit, onErase: () -> Unit) {
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Page {
        BrandMark()
        Spacer(Modifier.height(44.dp))
        Heading(t(language, "Unlock your vault", "अपना वॉल्ट अनलॉक करें"))
        Body(t(language, "Enter your app PIN or password.", "अपना ऐप पिन या पासवर्ड दर्ज करें।"))
        Spacer(Modifier.height(24.dp))
        SecretField(secret, t(language, "PIN or password", "पिन या पासवर्ड"), "", false) { secret = it; error = false }
        if (error) ErrorText(t(language, "That PIN or password did not unlock this vault.", "यह पिन या पासवर्ड वॉल्ट नहीं खोल सका।"))
        Spacer(Modifier.height(16.dp))
        PrimaryButton(t(language, "Unlock", "अनलॉक करें")) { error = !onUnlock(secret); if (!error) secret = "" }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRecover, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(t(language, "Forgot your lock? Use recovery phrase", "लॉक भूल गए? रिकवरी वाक्यांश इस्तेमाल करें")) }
        TextButton(onClick = onErase, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(t(language, "Erase this vault", "यह वॉल्ट मिटाएँ"), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RecoverVaultScreen(language: AppLanguage, onBack: () -> Unit, onRecover: (String, String) -> Boolean) {
    var phrase by remember { mutableStateOf("") }
    var newSecret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Page {
        BackButton(language, onBack)
        Spacer(Modifier.height(28.dp))
        Heading(t(language, "Reset forgotten lock", "भूला हुआ लॉक रीसेट करें"))
        Body(t(language, "Enter your 12-word recovery phrase, then choose a new PIN or password.", "अपना 12-शब्द का रिकवरी वाक्यांश दर्ज करें, फिर नया पिन या पासवर्ड चुनें।"))
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(value = phrase, onValueChange = { phrase = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text(t(language, "Recovery phrase", "रिकवरी वाक्यांश")) }, minLines = 3, shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(12.dp))
        SecretField(newSecret, t(language, "New PIN or password", "नया पिन या पासवर्ड"), t(language, "6 digits or 10 characters", "6 अंक या 10 अक्षर"), false) { newSecret = it; error = null }
        Spacer(Modifier.height(12.dp))
        SecretField(confirm, t(language, "Confirm new lock", "नए लॉक की पुष्टि करें"), "", false) { confirm = it; error = null }
        error?.let { ErrorText(it) }
        Spacer(Modifier.weight(1f))
        PrimaryButton(t(language, "Reset and unlock", "रीसेट और अनलॉक करें")) {
            error = when {
                !VaultSecretPolicy.isValidRecoveryPhrase(phrase) -> t(language, "Enter the 12-word recovery phrase exactly as saved.", "12-शब्द का रिकवरी वाक्यांश ठीक वैसे ही दर्ज करें जैसे सहेजा था।")
                !VaultSecretPolicy.isValidPinOrPassword(newSecret) -> t(language, "Use at least 6 PIN digits or a 10-character password.", "कम-से-कम 6 पिन अंक या 10-अक्षर का पासवर्ड इस्तेमाल करें।")
                newSecret != confirm -> t(language, "The two entries do not match.", "दोनों प्रविष्टियाँ समान नहीं हैं।")
                !onRecover(phrase, newSecret) -> t(language, "This recovery phrase did not unlock the vault.", "यह रिकवरी वाक्यांश वॉल्ट नहीं खोल सका।")
                else -> null
            }
        }
    }
}

@Composable
private fun EraseVaultScreen(language: AppLanguage, onCancel: () -> Unit, onErase: () -> Unit) {
    Page {
        Heading(t(language, "Erase this vault?", "यह वॉल्ट मिटाएँ?"))
        Spacer(Modifier.height(12.dp))
        Body(t(language, "This permanently removes the encrypted vault and all documents stored in it from this device. It cannot be undone.", "यह इस डिवाइस से एन्क्रिप्टेड वॉल्ट और उसमें रखे सभी दस्तावेज़ स्थायी रूप से हटा देगा। इसे वापस नहीं किया जा सकता।"))
        Spacer(Modifier.height(28.dp))
        PrimaryButton(t(language, "Permanently erase vault", "वॉल्ट स्थायी रूप से मिटाएँ"), destructive = true, onClick = onErase)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(t(language, "Cancel", "रद्द करें")) }
    }
}

@Composable
private fun HomeScreen(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    documents: List<MedicalDocument>,
    importing: Boolean,
    importFailed: Boolean,
    onChooseDocument: () -> Unit,
    onCaptureScan: () -> Unit,
    onOpenInsights: () -> Unit,
    onLock: () -> Unit,
) {
    Page {
        BrandMark()
        Spacer(Modifier.height(44.dp))
        Heading(t(language, "Your vault is unlocked", "आपका वॉल्ट अनलॉक है"))
        Body(t(language, "CareLens locks automatically whenever it leaves the screen. Documents are encrypted, read, and understood only on this phone.", "CareLens स्क्रीन से हटते ही अपने-आप लॉक हो जाता है। दस्तावेज़ केवल इसी फ़ोन पर एन्क्रिप्ट, पढ़े और समझे जाते हैं।"))
        Spacer(Modifier.height(28.dp))
        PrivacyCard(t(language, "Your vault key exists only in memory while this screen is open.", "इस स्क्रीन के खुले रहने तक ही आपकी वॉल्ट कुंजी मेमोरी में रहती है।"))
        Spacer(Modifier.height(18.dp))
        PrimaryButton(t(language, "Add photo or PDF", "फ़ोटो या PDF जोड़ें"), onClick = onChooseDocument)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCaptureScan, modifier = Modifier.fillMaxWidth()) {
            Text(t(language, "Scan with camera", "कैमरे से स्कैन करें"))
        }
        if (importing) {
            Spacer(Modifier.height(12.dp))
            Text(t(language, "Encrypting and reading document offline…", "दस्तावेज़ को एन्क्रिप्ट और ऑफ़लाइन पढ़ा जा रहा है…"), color = CareLensMuted)
        }
        if (importFailed) {
            ErrorText(t(language, "The document could not be imported. Please try another file.", "दस्तावेज़ आयात नहीं हो सका। कृपया दूसरी फ़ाइल आज़माएँ।"))
        }
        if (documents.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(t(language, "Encrypted documents", "एन्क्रिप्ट किए गए दस्तावेज़"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            documents.forEach { document ->
                Spacer(Modifier.height(10.dp))
                DocumentCard(document, language)
            }
        }
        if (documents.any { it.extractionStatus == ExtractionStatus.READY }) {
            Spacer(Modifier.height(18.dp))
            PrimaryButton(t(language, "Ask your documents", "अपने दस्तावेज़ों से पूछें"), onClick = onOpenInsights)
        }
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = {
                onLanguageSelected(
                    if (language == AppLanguage.ENGLISH) AppLanguage.HINDI else AppLanguage.ENGLISH,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(t(language, "Use Hindi", "अंग्रेज़ी इस्तेमाल करें"))
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(t(language, "Lock now", "अभी लॉक करें"), onClick = onLock)
    }
}

@Composable
private fun DocumentCard(document: MedicalDocument, language: AppLanguage) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(document.displayName, fontWeight = FontWeight.SemiBold, color = CareLensInk)
            Spacer(Modifier.height(4.dp))
            Text(
                when (document.extractionStatus) {
                    ExtractionStatus.PENDING -> t(language, "Stored securely · Waiting to be read", "सुरक्षित रूप से संग्रहीत · पढ़े जाने की प्रतीक्षा में")
                    ExtractionStatus.READY -> t(language, "Stored securely · Offline text ready", "सुरक्षित रूप से संग्रहीत · ऑफ़लाइन टेक्स्ट तैयार है")
                    ExtractionStatus.FAILED -> t(language, "Stored securely · Text could not be read", "सुरक्षित रूप से संग्रहीत · टेक्स्ट पढ़ा नहीं जा सका")
                },
                style = MaterialTheme.typography.bodySmall,
                color = CareLensMuted,
            )
        }
    }
}

@Composable
private fun InsightsScreen(
    language: AppLanguage,
    documents: List<MedicalDocument>,
    pagesByDocument: Map<String, List<String>>,
    onBack: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<GroundedAnswer?>(null) }
    val readableDocuments = documents.filter { it.extractionStatus == ExtractionStatus.READY }
    val recommendations = remember(documents, pagesByDocument, language) {
        DocumentGroundedAssistant.recommendations(
            documents = readableDocuments,
            pagesByDocument = pagesByDocument,
            hindi = language == AppLanguage.HINDI,
        )
    }

    Page {
        BackButton(language, onBack)
        Spacer(Modifier.height(28.dp))
        Heading(t(language, "Ask your documents", "अपने दस्तावेज़ों से पूछें"))
        Body(t(language, "CareLens searches only the offline text in this vault and always shows where an answer came from.", "CareLens केवल इस वॉल्ट के ऑफ़लाइन टेक्स्ट में खोजता है और हर उत्तर का स्रोत दिखाता है।"))
        Spacer(Modifier.height(18.dp))
        PrivacyCard(t(language, "Not medical advice. Do not use this for diagnosis, treatment, or medication dosing.", "यह चिकित्सीय सलाह नहीं है। इसका उपयोग निदान, उपचार या दवा की खुराक के लिए न करें।"))
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = question,
            onValueChange = { question = it; answer = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(t(language, "Question about your documents", "अपने दस्तावेज़ों के बारे में प्रश्न")) },
            placeholder = { Text(t(language, "For example: What follow-up is mentioned?", "उदाहरण: किस फ़ॉलो-अप का उल्लेख है?")) },
            minLines = 3,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = t(language, "Find in my documents", "मेरे दस्तावेज़ों में खोजें"),
            enabled = question.trim().isNotEmpty(),
        ) {
            answer = DocumentGroundedAssistant.answer(
                question = question,
                documents = readableDocuments,
                pagesByDocument = pagesByDocument,
                hindi = language == AppLanguage.HINDI,
            )
        }
        answer?.let { result ->
            Spacer(Modifier.height(20.dp))
            InsightCard(result, language)
        }
        if (recommendations.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(t(language, "Follow-up mentioned in your documents", "आपके दस्तावेज़ों में उल्लिखित फ़ॉलो-अप"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            recommendations.forEach { result ->
                Spacer(Modifier.height(10.dp))
                InsightCard(result, language)
            }
        }
    }
}

@Composable
private fun InsightCard(result: GroundedAnswer, language: AppLanguage) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(result.answer, color = CareLensInk)
            if (result.citations.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(t(language, "Sources", "स्रोत"), fontWeight = FontWeight.SemiBold, color = CareLensInk)
                result.citations.forEach { citation ->
                    Text(
                        t(language, "${citation.documentName} · page ${citation.page}", "${citation.documentName} · पृष्ठ ${citation.page}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = CareLensMuted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(result.safetyNote, style = MaterialTheme.typography.bodySmall, color = CareLensMuted)
        }
    }
}

@Composable private fun Page(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.Top, content = content)
@Composable private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = CareLensInk)
@Composable private fun Body(text: String) { Spacer(Modifier.height(12.dp)); Text(text, style = MaterialTheme.typography.bodyLarge, color = CareLensMuted) }
@Composable private fun BackButton(language: AppLanguage, onClick: () -> Unit) = OutlinedButton(onClick = onClick) { Text(t(language, "Back", "वापस")) }
@Composable private fun ErrorText(text: String) { Spacer(Modifier.height(8.dp)); Text(text, color = MaterialTheme.colorScheme.error) }

@Composable
private fun BrandMark() = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.background(CareLensTeal, RoundedCornerShape(14.dp)).padding(12.dp), contentAlignment = Alignment.Center) { Text("C", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    Spacer(Modifier.width(10.dp)); Text("CareLens", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CareLensInk)
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) = OutlinedButton(onClick = onClick, modifier = modifier.height(54.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) CareLensTealSoft else Color.White, contentColor = CareLensInk), shape = RoundedCornerShape(14.dp)) { Text(label) }

@Composable
private fun SecretField(value: String, label: String, hint: String, isPin: Boolean, onValueChange: (String) -> Unit) = OutlinedTextField(value = value, onValueChange = { input -> onValueChange(if (isPin) input.filter(Char::isDigit) else input) }, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(hint) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password, imeAction = ImeAction.Done), shape = RoundedCornerShape(14.dp))

@Composable
private fun PrivacyCard(text: String) = Card(colors = CardDefaults.cardColors(containerColor = CareLensTealSoft), shape = RoundedCornerShape(16.dp)) { Text(text, Modifier.padding(16.dp), color = CareLensInk) }

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) = Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = if (destructive) MaterialTheme.colorScheme.error else CareLensTeal), shape = RoundedCornerShape(16.dp)) { Text(label, fontWeight = FontWeight.SemiBold) }

private fun t(language: AppLanguage, english: String, hindi: String) = if (language == AppLanguage.HINDI) hindi else english
private val CareLensTeal = Color(0xFF126A63)
private val CareLensTealSoft = Color(0xFFE0F3F0)
private val CareLensInk = Color(0xFF182926)
private val CareLensMuted = Color(0xFF58716C)
private val CareLensBackground = Color(0xFFF6F9F8)
