package com.photohub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.Discovery
import com.photohub.app.data.Setup
import com.photohub.app.data.SetupFailed
import com.photohub.app.data.SetupLink
import com.photohub.app.data.SyncWorker
import kotlinx.coroutines.launch

/**
 * First run: the app finds the NAS on the home Wi-Fi by itself; the person logs in with the
 * Synology (DSM) user name and password they already use for Synology Photos.
 * A QR code from the admin and typing an address remain as spare ways.
 */
@Composable
fun SetupScreen(pendingLink: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var searchRound by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(true) }
    var base by remember { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var linkPhotos by remember { mutableStateOf(true) }
    var needOtp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("http://") }

    fun finished(name: String) {
        scope.launch {
            UiState.refreshBase(app, force = true)
            SyncWorker.runNow(ctx)
            message = "${name}さん、設定できました"
            onDone()
        }
    }

    fun fromQr(text: String) {
        val link = SetupLink.parse(text) ?: run { message = "PhotoHubの設定用QRコードではありません"; return }
        busy = true
        scope.launch {
            try { finished(Setup.run(app, link)) } catch (e: SetupFailed) { message = e.message }
            catch (e: Exception) { message = "設定できませんでした: ${e.message}" } finally { busy = false }
        }
    }

    LaunchedEffect(pendingLink) { pendingLink?.let { fromQr(it) } }
    LaunchedEffect(searchRound) {
        searching = true
        base = Discovery.find(ctx)
        searching = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PhotoHubへようこそ", style = MaterialTheme.typography.headlineSmall)
        when {
            searching -> {
                CircularProgressIndicator()
                Text("自宅のSynologyを探しています…")
            }
            base == null -> {
                Text("Synologyが見つかりません。自宅のWi-Fiにつないでから、もう一度探してください。", textAlign = TextAlign.Center)
                OutlinedButton(onClick = { searchRound++ }) { Text("もう一度探す") }
            }
            else -> {
                Text("Synologyが見つかりました。Synology（Synology Photos）のユーザー名とパスワードでログインします。",
                    textAlign = TextAlign.Center)
                OutlinedTextField(user, { user = it }, label = { Text("ユーザー名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("パスワード") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (needOtp) {
                    OutlinedTextField(otp, { otp = it }, label = { Text("2段階認証のコード（6桁）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                }
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(linkPhotos, { linkPhotos = it })
                    Text("Synology Photosのアルバムも同期する（パスワードを暗号化してSynologyに保存）",
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = !busy && user.isNotBlank() && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                finished(Setup.login(app, base!!, user, pass, if (needOtp) otp else null, linkPhotos))
                            } catch (e: SetupFailed) {
                                if (e.needOtp) needOtp = true
                                message = e.message
                            } finally { busy = false }
                        }
                    }) { Text("ログイン") }
                Text(if (linkPhotos) "パスワードは暗号化して、自宅のSynologyの中だけに保存します（アルバムの同期に使います）。設定からいつでも消せます。"
                    else "パスワードはPhotoHubに保存されません。Synologyで確かめたあと、このスマホ専用の鍵だけを受け取ります。",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
        if (busy) CircularProgressIndicator()
        message?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary) }
        TextButton(onClick = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let { fromQr(it) } }
                .addOnFailureListener { message = "カメラを起動できませんでした: ${it.message}" }
        }) { Text("管理者のQRコードで設定する") }
        if (!manual) TextButton(onClick = { manual = true }) { Text("Synologyのアドレスを入力する") }
        else {
            OutlinedTextField(address, { address = it }, label = { Text("Synologyのアドレス（例 http://192.168.1.10:8765）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                scope.launch {
                    val a = address.trim().trimEnd('/')
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Discovery.isPhotoHub(a) }
                    if (ok) { base = a; searching = false; manual = false; message = null }
                    else message = "そのアドレスではPhotoHubが見つかりません"
                }
            }) { Text("このアドレスを使う") }
        }
    }
}
