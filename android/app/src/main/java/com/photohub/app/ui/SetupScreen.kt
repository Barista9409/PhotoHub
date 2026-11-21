package com.photohub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.Setup
import com.photohub.app.data.SetupFailed
import com.photohub.app.data.SetupLink
import com.photohub.app.data.SyncWorker
import kotlinx.coroutines.launch

/** First run: scan the QR code from the admin. Also used when a setup link is opened. */
@Composable
fun SetupScreen(pendingLink: String?, onDone: () -> Unit, onManual: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun apply(text: String) {
        val link = SetupLink.parse(text) ?: run { message = "写真ハブの設定用QRコードではありません"; return }
        busy = true
        message = null
        scope.launch {
            try {
                val name = Setup.run(app, link)
                UiState.refreshBase(app, force = true)
                SyncWorker.runNow(ctx)
                message = "${name}さん、設定できました"
                onDone()
            } catch (e: SetupFailed) {
                message = e.message
            } catch (e: Exception) {
                message = "設定できませんでした: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(pendingLink) { pendingLink?.let { apply(it) } }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("写真ハブへようこそ", style = MaterialTheme.typography.headlineSmall)
        Text("管理者から受け取ったQRコードを読み取ると、設定は終わりです。", textAlign = TextAlign.Center)
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp), onClick = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let { apply(it) } }
                .addOnFailureListener { message = "カメラを起動できませんでした: ${it.message}" }
        }) {
            Icon(Icons.Default.QrCodeScanner, null)
            Gap()
            Text("QRコードで設定")
        }
        if (busy) CircularProgressIndicator()
        message?.let { Text(it, textAlign = TextAlign.Center) }
        Text("自宅のWi-Fiで読み取ると、自宅のネットワークも自動で覚えます。", style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center)
        TextButton(onClick = onManual) { Text("手動で入力する") }
    }
}
