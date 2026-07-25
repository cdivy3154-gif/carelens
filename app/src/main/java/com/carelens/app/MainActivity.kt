package com.carelens.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CareLensApp() }
    }
}

private enum class AppLanguage { ENGLISH, HINDI }

internal enum class LockMethod { PIN, PASSWORD }

private enum class OnboardingStep { LANGUAGE, VAULT, HOME }

private data class Copy(
    val welcome: String,
    val welcomeDetail: String,
    val chooseLanguage: String,
    val languageDetail: String,
    val continueLabel: String,
    val privacyPromise: String,
    val createVault: String,
    val vaultDetail: String,
    val appPin: String,
    val appPassword: String,
    val pinHint: String,
    val passwordHint: String,
    val confirmSecret: String,
    val secretsDoNotMatch: String,
    val minimumPin: String,
    val createVaultButton: String,
    val biometricNote: String,
    val back: String,
    val homeGreeting: String,
    val homeDetail: String,
    val addDocument: String,
    val noDocuments: String,
    val localOnly: String,
    val minimumPassword: String = "",
)

private fun copyFor(language: AppLanguage): Copy = when (language) {
    AppLanguage.ENGLISH -> Copy(
        welcome = "Welcome to CareLens",
        welcomeDetail = "Your private medical record assistant, designed to work on your phone.",
        chooseLanguage = "Choose your language",
        languageDetail = "You can change this later in Settings. CareLens explanations will use your selected language.",
        continueLabel = "Continue",
        privacyPromise = "Local-only by design. No Internet permission. No cloud account.",
        createVault = "Create your private vault",
        vaultDetail = "Choose a lock for your medical records. If you forget it, CareLens cannot recover your vault.",
        appPin = "App PIN",
        appPassword = "App password",
        pinHint = "At least 6 digits",
        passwordHint = "Use a strong password",
        confirmSecret = "Confirm",
        secretsDoNotMatch = "The two entries do not match.",
        minimumPin = "Your PIN must contain at least 6 digits.",
        minimumPassword = "Your password must contain at least 10 characters.",
        createVaultButton = "Create secure vault",
        biometricNote = "Fingerprint and secure face unlock are planned for a later security milestone. This first version uses your app PIN or password.",
        back = "Back",
        homeGreeting = "Your private health space",
        homeDetail = "Add a report or photo to start building your personal medical timeline.",
        addDocument = "Add medical document",
        noDocuments = "No documents yet",
        localOnly = "CareLens is designed for local processing only.",
    )
    AppLanguage.HINDI -> Copy(
        welcome = "CareLens में आपका स्वागत है",
        welcomeDetail = "आपका निजी मेडिकल रिकॉर्ड सहायक, जो आपके फ़ोन पर काम करने के लिए बनाया गया है।",
        chooseLanguage = "अपनी भाषा चुनें",
        languageDetail = "आप इसे बाद में सेटिंग्स में बदल सकते हैं। CareLens की व्याख्याएँ चुनी हुई भाषा में होंगी।",
        continueLabel = "आगे बढ़ें",
        privacyPromise = "डिज़ाइन से केवल फ़ोन पर। इंटरनेट अनुमति नहीं। कोई क्लाउड खाता नहीं।",
        createVault = "अपना निजी वॉल्ट बनाएँ",
        vaultDetail = "अपने मेडिकल रिकॉर्ड के लिए लॉक चुनें। यदि आप इसे भूल जाते हैं, तो CareLens आपका वॉल्ट वापस नहीं ला सकता।",
        appPin = "ऐप पिन",
        appPassword = "ऐप पासवर्ड",
        pinHint = "कम-से-कम 6 अंक",
        passwordHint = "एक मजबूत पासवर्ड इस्तेमाल करें",
        confirmSecret = "पुष्टि करें",
        secretsDoNotMatch = "दोनों प्रविष्टियाँ एक जैसी नहीं हैं।",
        minimumPin = "आपके पिन में कम-से-कम 6 अंक होने चाहिए।",
        createVaultButton = "सुरक्षित वॉल्ट बनाएँ",
        biometricNote = "वॉल्ट बनने के बाद, यदि आपका फ़ोन समर्थन करता है, तो फिंगरप्रिंट या सुरक्षित फेस अनलॉक चालू किया जा सकेगा।",
        back = "वापस",
        homeGreeting = "आपकी निजी स्वास्थ्य जगह",
        homeDetail = "अपनी निजी मेडिकल टाइमलाइन बनाना शुरू करने के लिए कोई रिपोर्ट या फोटो जोड़ें।",
        addDocument = "मेडिकल दस्तावेज़ जोड़ें",
        noDocuments = "अभी कोई दस्तावेज़ नहीं है",
        localOnly = "CareLens केवल फ़ोन पर प्रोसेसिंग के लिए बनाया गया है।",
    )
}

@Composable
private fun CareLensApp() {
    var language by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var step by remember { mutableStateOf(OnboardingStep.LANGUAGE) }
    val vaultStore = remember { VaultStore(LocalContext.current.applicationContext) }
    val copy = copyFor(language)

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = CareLensBackground) {
            when (step) {
                OnboardingStep.LANGUAGE -> LanguageScreen(
                    language = language,
                    copy = copy,
                    onLanguageSelected = { language = it },
                    onContinue = { step = OnboardingStep.VAULT },
                )
                OnboardingStep.VAULT -> VaultScreen(
                    copy = copy,
                    onBack = { step = OnboardingStep.LANGUAGE },
                    onVaultCreated = { secret ->
                        vaultStore.createVault(secret)
                        step = OnboardingStep.HOME
                    },
                )
                OnboardingStep.HOME -> HomeScreen(copy = copy)
            }
        }
    }
}

@Composable
private fun LanguageScreen(
    language: AppLanguage,
    copy: Copy,
    onLanguageSelected: (AppLanguage) -> Unit,
    onContinue: () -> Unit,
) {
    AppPage {
        BrandMark()
        Spacer(Modifier.height(40.dp))
        Text(copy.welcome, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(copy.welcomeDetail, style = MaterialTheme.typography.bodyLarge, color = CareLensMuted)
        Spacer(Modifier.height(40.dp))
        Text(copy.chooseLanguage, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(copy.languageDetail, color = CareLensMuted)
        Spacer(Modifier.height(24.dp))
        LanguageChoice("English", "English", language == AppLanguage.ENGLISH) {
            onLanguageSelected(AppLanguage.ENGLISH)
        }
        Spacer(Modifier.height(12.dp))
        LanguageChoice("हिन्दी", "Hindi", language == AppLanguage.HINDI) {
            onLanguageSelected(AppLanguage.HINDI)
        }
        Spacer(Modifier.weight(1f))
        PrivacyCard(copy.privacyPromise)
        Spacer(Modifier.height(18.dp))
        PrimaryButton(copy.continueLabel, onContinue)
    }
}

@Composable
private fun VaultScreen(copy: Copy, onBack: () -> Unit, onVaultCreated: (String) -> Unit) {
    var method by remember { mutableStateOf(LockMethod.PIN) }
    var secret by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val hint = if (method == LockMethod.PIN) copy.pinHint else copy.passwordHint

    AppPage {
        OutlinedButton(onClick = onBack) { Text(copy.back) }
        Spacer(Modifier.height(28.dp))
        BrandMark()
        Spacer(Modifier.height(28.dp))
        Text(copy.createVault, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(copy.vaultDetail, style = MaterialTheme.typography.bodyLarge, color = CareLensMuted)
        Spacer(Modifier.height(28.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            LockMethodButton(copy.appPin, method == LockMethod.PIN, Modifier.weight(1f)) {
                method = LockMethod.PIN
                secret = ""
                confirmation = ""
                error = null
            }
            Spacer(Modifier.width(12.dp))
            LockMethodButton(copy.appPassword, method == LockMethod.PASSWORD, Modifier.weight(1f)) {
                method = LockMethod.PASSWORD
                secret = ""
                confirmation = ""
                error = null
            }
        }
        Spacer(Modifier.height(20.dp))
        SecretField(
            value = secret,
            label = if (method == LockMethod.PIN) copy.appPin else copy.appPassword,
            hint = hint,
            isPin = method == LockMethod.PIN,
            onValueChange = { secret = it; error = null },
        )
        Spacer(Modifier.height(12.dp))
        SecretField(
            value = confirmation,
            label = copy.confirmSecret,
            hint = hint,
            isPin = method == LockMethod.PIN,
            onValueChange = { confirmation = it; error = null },
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        PrivacyCard(copy.biometricNote)
        Spacer(Modifier.weight(1f))
        PrimaryButton(copy.createVaultButton) {
            error = when {
                !VaultSecretPolicy.isValid(method, secret) -> {
                    if (method == LockMethod.PIN) copy.minimumPin else copy.minimumPassword
                }
                secret.isBlank() -> hint
                secret != confirmation -> copy.secretsDoNotMatch
                else -> null
            }
            if (error == null) onVaultCreated(secret)
        }
    }
}

@Composable
private fun HomeScreen(copy: Copy) {
    AppPage {
        BrandMark()
        Spacer(Modifier.height(40.dp))
        Text(copy.homeGreeting, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(copy.homeDetail, style = MaterialTheme.typography.bodyLarge, color = CareLensMuted)
        Spacer(Modifier.height(32.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(copy.noDocuments, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(copy.localOnly, color = CareLensMuted)
                Spacer(Modifier.height(20.dp))
                PrimaryButton(copy.addDocument) { /* Document import is the next milestone. */ }
            }
        }
    }
}

@Composable
private fun AppPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(CareLensTeal, RoundedCornerShape(14.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("C", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text("CareLens", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CareLensInk)
    }
}

@Composable
private fun LanguageChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(76.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) CareLensTealSoft else Color.White,
            contentColor = CareLensInk,
        ),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) CareLensTeal else CareLensBorder),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = CareLensMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LockMethodButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) CareLensTealSoft else Color.White,
            contentColor = CareLensInk,
        ),
        border = BorderStroke(1.dp, if (selected) CareLensTeal else CareLensBorder),
        shape = RoundedCornerShape(14.dp),
    ) { Text(label, textAlign = TextAlign.Center) }
}

@Composable
private fun SecretField(
    value: String,
    label: String,
    hint: String,
    isPin: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(if (isPin) input.filter(Char::isDigit) else input) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun PrivacyCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CareLensTealSoft),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            color = CareLensInk,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CareLensTeal),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

private val CareLensTeal = Color(0xFF126A63)
private val CareLensTealSoft = Color(0xFFE0F3F0)
private val CareLensInk = Color(0xFF182926)
private val CareLensMuted = Color(0xFF58716C)
private val CareLensBorder = Color(0xFFC6D7D3)
private val CareLensBackground = Color(0xFFF6F9F8)

@Preview(showBackground = true, backgroundColor = 0xFFF6F9F8)
@Composable
private fun CareLensPreview() {
    CareLensApp()
}
