package io.klymapo.rupert

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Paper = Color(0xFFF8F3E8)
private val Wine = Color(0xFF6E1F22)
private val Ink = Color(0xFF3A2320)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La tablet es la "casa" de Rupert: pantalla siempre encendida mientras la app está al frente.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Wine, background = Paper, surface = Paper)) {
                RupertApp()
            }
        }
    }

    @Composable
    private fun RupertApp() {
        var showSettings by remember { mutableStateOf(!Prefs(this).isSignedIn) }
        val permissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                RupertService.send(this, RupertService.ACTION_START)
            }
        }
        val start = {
            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(onClose = { showSettings = false; start() })
        } else {
            FaceScreen(onStart = start, onSettings = { showSettings = true })
        }
    }

    @Composable
    private fun FaceScreen(onStart: () -> Unit, onSettings: () -> Unit) {
        val ui by RupertState.ui.collectAsStateWithLifecycle()
        val pulse = rememberInfiniteTransition(label = "pulse")
        val breathe by pulse.animateFloat(
            initialValue = 1f, targetValue = 1.015f,
            animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        )
        val bob by pulse.animateFloat(
            initialValue = -3f, targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(260), RepeatMode.Reverse),
            label = "bob",
        )
        val talking = ui.mode == Mode.SPEAKING || ui.mode == Mode.EXCITED

        Box(Modifier.fillMaxSize().background(Paper)) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Crossfade(targetState = ui.mode, label = "face") { mode ->
                    Image(
                        painter = painterResource(faceFor(mode)),
                        contentDescription = "Rupert",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .height(460.dp)
                            .graphicsLayer {
                                scaleX = breathe; scaleY = breathe
                                translationY = if (talking) bob else 0f
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (mode == Mode.OFF) onStart()
                                else RupertService.send(this@MainActivity, RupertService.ACTION_TALK)
                            },
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (ui.heard.isNotBlank() && ui.mode != Mode.SLEEPING && ui.mode != Mode.OFF) {
                    Text("Tú: ${ui.heard}", color = Ink.copy(alpha = 0.6f), fontSize = 16.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = subtitleFor(ui),
                    color = Ink,
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Serif,
                    fontStyle = if (ui.mode == Mode.SLEEPING) FontStyle.Italic else FontStyle.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                if (ui.mode == Mode.OFF) {
                    TextButton(onClick = onStart) { Text("Despertar", color = Wine) }
                } else {
                    TextButton(onClick = { RupertService.send(this@MainActivity, RupertService.ACTION_STOP) }) {
                        Text("Apagar", color = Wine)
                    }
                }
                TextButton(onClick = onSettings) { Text("Ajustes", color = Wine) }
            }
        }
    }

    private fun faceFor(mode: Mode): Int = when (mode) {
        Mode.OFF, Mode.SLEEPING -> R.drawable.rupert_sleep
        Mode.LISTENING -> R.drawable.rupert_listen
        Mode.THINKING -> R.drawable.rupert_think
        Mode.SPEAKING -> R.drawable.rupert_talk
        Mode.EXCITED -> R.drawable.rupert_excited
        Mode.ERROR -> R.drawable.rupert_serious
    }

    private fun subtitleFor(ui: UiState): String = when (ui.mode) {
        Mode.OFF -> "Rupert está apagado. Toca su cara o “Despertar”."
        Mode.SLEEPING -> "Zzz… di “${ui.wakeWord.ifBlank { "Rupert" }}” o toca su cara."
        else -> ui.subtitle
    }

    @SuppressLint("BatteryLife")
    @Composable
    private fun SettingsScreen(onClose: () -> Unit) {
        val prefs = remember { Prefs(this) }
        val cloud = remember { RupertCloud(prefs) }
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf(prefs.supabaseUrl) }
        var anon by remember { mutableStateOf(prefs.anonKey) }
        var email by remember { mutableStateOf(prefs.email) }
        var password by remember { mutableStateOf("") }
        var threshold by remember { mutableStateOf(prefs.wakeThreshold) }
        var status by remember {
            mutableStateOf(if (prefs.isSignedIn) "Sesión activa como ${prefs.email}." else "Sin sesión.")
        }
        var modelInfo by remember {
            mutableStateOf(
                if (WakeWord.customModelFile(this).exists()) "Modelo “Rupert” instalado."
                else "Usando “Hey Jarvis” de prueba hasta que importes rupert.tflite."
            )
        }
        val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    val target = WakeWord.customModelFile(this)
                    target.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }.onSuccess {
                    modelInfo = "Modelo “Rupert” instalado."
                    RupertService.send(this, RupertService.ACTION_RELOAD)
                }.onFailure { modelInfo = "No pude importar el modelo: ${it.message}" }
            }
        }

        Row(Modifier.fillMaxSize().background(Paper).padding(24.dp)) {
            Image(
                painter = painterResource(R.drawable.rupert_full),
                contentDescription = null,
                modifier = Modifier.weight(0.35f).fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Column(
                Modifier.weight(0.65f).padding(start = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Ajustes de Rupert", fontSize = 26.sp, fontFamily = FontFamily.Serif, color = Ink)

                Text("Nube (Supabase)", color = Wine, fontSize = 18.sp)
                OutlinedTextField(url, { url = it }, label = { Text("URL del proyecto (https://xxxx.supabase.co)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(anon, { anon = it }, label = { Text("Llave publishable / anon") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Correo") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            prefs.supabaseUrl = url; prefs.anonKey = anon
                            status = "Conectando…"
                            scope.launch {
                                status = withContext(Dispatchers.IO) {
                                    runCatching { cloud.signIn(email, password); cloud.heartbeat() }
                                        .fold({ "Sesión activa como $email." }, { "Error: ${it.message}" })
                                }
                                password = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Wine),
                    ) { Text("Guardar e iniciar sesión") }
                    OutlinedButton(onClick = { prefs.signOut(); status = "Sin sesión." }) { Text("Cerrar sesión") }
                }
                Text(status, color = Ink)

                Text("Wake word", color = Wine, fontSize = 18.sp)
                Text(modelInfo, color = Ink)
                OutlinedButton(onClick = { importModel.launch(arrayOf("*/*")) }) { Text("Importar rupert.tflite") }
                Text("Sensibilidad: ${"%.2f".format(threshold)} (más bajo = despierta más fácil)", color = Ink)
                Slider(
                    value = threshold, onValueChange = { threshold = it },
                    onValueChangeFinished = { prefs.wakeThreshold = threshold },
                    valueRange = 0.2f..0.9f,
                )

                Text("Tablet siempre encendida", color = Wine, fontSize = 18.sp)
                Text("Samsung duerme las apps en segundo plano. Quita la optimización de batería y agrega Rupert a “Aplicaciones que nunca entran en suspensión”.", color = Ink)
                OutlinedButton(onClick = {
                    val pm = getSystemService(PowerManager::class.java)
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } else {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Quitar optimización de batería") }

                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Wine)) { Text("Listo") }
            }
        }
    }
}
