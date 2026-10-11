package com.scientist.localai

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Night = Color(0xFF10151D)
private val Panel = Color(0xFF1A2330)
private val Blue = Color(0xFF8AB4F8)
private data class Message(val user: Boolean, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Night, surface = Panel)) {
                ScientistAiScreen()
            }
        }
    }
}

@Composable
private fun ScientistAiScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(findModels(context)) }
    var selected by remember { mutableStateOf(models.firstOrNull()?.name.orEmpty()) }
    var model by remember { mutableStateOf<LlamaModel?>(null) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Import a compatible .gguf model to begin. Chat runs on this device.") }
    var busy by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf(Message(false, "Welcome to Scientist AI. Import a GGUF model, load it, then chat offline.")) }

    DisposableEffect(Unit) {
        onDispose { model?.let { runCatching { Llama.releaseModel(it) } } }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            status = "Importing model…"
            try {
                val file = withContext(Dispatchers.IO) { importModel(context, uri) }
                models = findModels(context)
                selected = file.name
                status = "Imported ${file.name}. Tap Load model."
            } catch (e: Exception) {
                status = "Import failed: ${e.message ?: "could not read file"}"
            } finally { busy = false }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Night) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scientist AI", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("PRIVATE • ON-DEVICE • OFFLINE", fontSize = 11.sp, color = Blue)
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MODEL LIBRARY", color = Blue, fontWeight = FontWeight.Bold)
                    Text(selected.ifBlank { "No GGUF model imported yet" }, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import .gguf") }
                        Button(onClick = {
                            val file = models.firstOrNull { it.name == selected }
                            if (file == null) status = "Import a .gguf model first."
                            else scope.launch {
                                busy = true
                                status = "Loading model into memory…"
                                try {
                                    model?.let { Llama.releaseModel(it) }
                                    model = withContext(Dispatchers.IO) {
                                        Llama.loadModel(file.absolutePath, LlamaConfig(contextSize = 2048, threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)))
                                    }
                                    status = "Model loaded. You can chat without internet."
                                } catch (e: Exception) {
                                    status = "Couldn't load model: ${e.message ?: "unsupported model or not enough memory"}"
                                } finally { busy = false }
                            }
                        }, enabled = !busy && selected.isNotBlank()) { Text("Load model") }
                    }
                    Text(status, color = Color(0xFFB8C3D1), fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHAT", color = Blue, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (speed.isNotBlank()) Text(speed, color = Color.LightGray, fontSize = 11.sp)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth(if (message.user) 0.9f else 1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = if (message.user) Color(0xFF233A56) else Panel)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (message.user) "YOU" else "SCIENTIST AI", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(message.text, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
                if (busy) item { CircularProgressIndicator(color = Blue, modifier = Modifier.size(22.dp)) }
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Message your local model…") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (draft.isNotBlank() && !busy && model != null) {
                        val text = draft.trim(); draft = ""
                        messages.add(Message(true, text))
                        busy = true
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    Llama.complete(model!!, prompt = text, systemPrompt = "You are Scientist AI, a helpful and honest offline assistant.", maxTokens = 256)
                                }
                                messages.add(Message(false, result.text.trim()))
                                speed = "${String.format("%.1f", result.tokensPerSecond)} tokens/s"
                                status = "Reply generated locally."
                            } catch (e: Exception) {
                                messages.add(Message(false, "Generation failed: ${e.message ?: "model or memory issue"}"))
                            } finally { busy = false }
                        }
                    } else if (model == null) status = "Load a GGUF model before chatting."
                })
            )
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isEmpty() || busy) return@Button
                    if (model == null) { status = "Load a GGUF model before chatting."; return@Button }
                    draft = ""
                    messages.add(Message(true, text))
                    busy = true
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                Llama.complete(model!!, prompt = text, systemPrompt = "You are Scientist AI, a helpful and honest offline assistant.", maxTokens = 256)
                            }
                            messages.add(Message(false, result.text.trim()))
                            speed = "${String.format("%.1f", result.tokensPerSecond)} tokens/s"
                            status = "Reply generated locally."
                        } catch (e: Exception) {
                            messages.add(Message(false, "Generation failed: ${e.message ?: "model or memory issue"}"))
                        } finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = draft.isNotBlank() && !busy
            ) { Text("Send message") }
            Text("Models are not included in the APK. Import a compatible, licensed GGUF file.", color = Color(0xFFB8C3D1), fontSize = 10.sp)
        }
    }
}

private fun findModels(context: Context): List<File> {
    val directory = File(context.filesDir, "models")
    if (!directory.exists()) directory.mkdirs()
    return directory.listFiles { file -> file.isFile && file.extension.equals("gguf", true) }?.sortedBy { it.name.lowercase() } ?: emptyList()
}

private fun importModel(context: Context, uri: Uri): File {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
    }
    val fileName = (name ?: uri.lastPathSegment ?: "model.gguf").substringAfterLast('/')
    require(fileName.endsWith(".gguf", true)) { "Choose a .gguf model file." }
    val safeName = fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    val dir = File(context.filesDir, "models").apply { mkdirs() }
    val destination = File(dir, safeName)
    val input = context.contentResolver.openInputStream(uri) ?: error("Could not open selected file.")
    input.use { source -> destination.outputStream().use { target -> source.copyTo(target) } }
    require(destination.length() > 0L) { "The selected file is empty." }
    return destination
}
