package com.photohub.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photohub.app.data.TakeoutUploadWorker

/**
 * Google Photos -> Synology, once. Takeout is made into Google Drive and Cloud Sync brings it to the
 * Synology, where PhotoHub imports it and then deletes the ZIPs. Nothing goes through the phone.
 */
@Composable
fun MigrationScreen(open: (Dest) -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("Googleフォトの写真を、一度だけSynologyへ移します。スマホで送る必要はありません。",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp))
        SectionTitle("手順")
        Group {
            SettingRow(Icons.Default.Cloud, "1. TakeoutをGoogleドライブに作る",
                "takeout.google.com で「Googleフォト」だけを選び、配信方法「ドライブに追加」、形式 ZIP、サイズ 50GB。すでにドライブにあればそのままでOK")
            GroupDivider()
            SettingRow(Icons.Default.Download, "2. Synologyに届くのを待つ",
                "Cloud Syncがドライブの「Takeout」フォルダをSynologyに持ってきます。どのフォルダか → 「Synologyの保存場所」で確認・変更できます",
                onClick = { open(Dest.PLACES) })
            GroupDivider()
            SettingRow(Icons.Default.Inventory2, "3. Synologyが取り込む",
                "日時と場所を整え、すでにSynologyにある写真とは重複しないよう1枚にまとめます。終わったZIPは自動で削除します（ドライブ側のTakeoutも消えて、ドライブの容量が空きます）",
                onClick = { open(Dest.IMPORT_LOG) })
            GroupDivider()
            SettingRow(Icons.Default.TaskAlt, "4. Googleフォトを空にする",
                "Synologyに保存済みと確認できた写真を、月ごとに案内します。Googleフォトのアプリで消してください",
                onClick = { open(Dest.CLEANUP) })
        }
        Text("取り込みの様子は「Synologyへの取り込み記録」で見られます。30GBで数時間かかります。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp))
    }
}
