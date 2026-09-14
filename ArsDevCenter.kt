package com.arspos.anglerriausyndicate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider

/** V8.4.17 diagnostics + evidence integrity + official update metadata. */
@Composable
fun ArsDevCenterDialog(context: Context, onDismiss: () -> Unit) {
    var report by remember { mutableStateOf(ArsFlightRecorder.buildReport(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("ARS DEV CENTER", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("FLIGHT RECORDER • VISUAL EVIDENCE • OFFICIAL UPDATE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "V8.4.17 memperbaiki integritas overlay, physical-track ID, dan Rescue Gate V2. Scene/crop/track/anchor tetap terikat ke session/proposal/physicalObject/decision yang sama. Capture diagnostik tidak menurunkan threshold recognition dan tidak mengubah transaksi secara otomatis. Data PIN/pembayaran/pelanggan tidak dimasukkan.",
                    fontSize = 11.sp
                )
                Text(report, fontSize = 9.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { report = ArsFlightRecorder.buildReport(context) }) {
                        Text("REFRESH", fontSize = 10.sp)
                    }
                    Button(onClick = { shareReportToChatGPT(context, report) }) {
                        Text("TEXT REPORT", fontSize = 10.sp)
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { shareDiagnosticPackageToChatGPT(context, report) }
                ) {
                    Text("CHATGPT + EVIDENCE ZIP", fontSize = 10.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("SELESAI") }
        },
        dismissButton = {
            TextButton(onClick = {
                ArsFlightRecorder.clear(context)
                report = ArsFlightRecorder.buildReport(context)
            }) { Text("HAPUS LOG + BUKTI") }
        }
    )
}

fun shareReportToChatGPT(context: Context, report: String) {
    val prompt = diagnosticPrompt(report)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "ARS POS Diagnostic Report V8.4.17")
        putExtra(Intent.EXTRA_TEXT, prompt)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startPreferredChatGptShare(context, intent, "Kirim report ke ChatGPT / OpenAI")
}

/**
 * Builds the diagnostic ZIP off the UI thread, then shares it with ChatGPT when
 * the Android client accepts file sharing. The ZIP contains report.txt plus the
 * evidence folders captured from the same scan pipeline.
 */
fun shareDiagnosticPackageToChatGPT(context: Context, report: String) {
    Toast.makeText(context, "Menyiapkan visual evidence ARS POS…", Toast.LENGTH_SHORT).show()
    Thread {
        runCatching {
            val zip = ArsVisualEvidenceRecorder.buildDiagnosticPackage(context, report)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zip
            )
            val prompt = diagnosticPrompt(report) + "\n\nLampiran ZIP berisi visual evidence yang terikat ke session/track/decision pada report ini."
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, "ARS POS V8.4.17 Diagnostic Package")
                putExtra(Intent.EXTRA_TEXT, prompt)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "ARS POS Diagnostic Package", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Handler(Looper.getMainLooper()).post {
                startPreferredChatGptShare(context, intent, "Kirim report + visual evidence")
            }
        }.onFailure { error ->
            ArsFlightRecorder.event(
                "DIAGNOSTIC_EXPORT_ERROR",
                error.message ?: error::class.java.simpleName,
                mapOf("exception" to error::class.java.name)
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Gagal membuat paket diagnostik. Report error sudah dicatat.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }.start()
}

fun shareCurrentScanEvidence(context: Context, session: String) {
    if (session.isBlank()) {
        Toast.makeText(context, "Session scan belum tersedia.", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(context, "Menyiapkan laporan bergambar sesi scan…", Toast.LENGTH_SHORT).show()
    Thread {
        runCatching {
            val report = ArsFlightRecorder.buildReport(context)
            val zip = ArsVisualEvidenceRecorder.buildSessionDiagnosticPackage(context, report, session)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
            val prompt = diagnosticPrompt(report) + "\n\nFokuskan analisis pada sessionId: $session. Lampiran hanya memuat visual evidence sesi tersebut."
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, "ARS POS V8.4.17 Visual Scan Report")
                putExtra(Intent.EXTRA_TEXT, prompt)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "ARS POS Visual Scan Report", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Handler(Looper.getMainLooper()).post {
                startPreferredChatGptShare(context, intent, "Kirim laporan scan bergambar")
            }
        }.onFailure { error ->
            ArsFlightRecorder.event(
                "SCAN_VISUAL_REPORT_ERROR",
                error.message ?: error::class.java.simpleName,
                mapOf("session" to session, "exception" to error::class.java.name)
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Gagal membuat laporan bergambar. Error sudah dicatat.", Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

private fun diagnosticPrompt(report: String): String = buildString {
    appendLine("Tolong analisis report ARS POS berikut sebagai software engineer Android.")
    appendLine("Build ini menggunakan Phase 4.3.2.37 / V8.4.17 Evidence Integrity + Physical Track Truth + Rescue Gate V2 + Cashier Scan Review.")
    appendLine("Jika ZIP visual evidence terlampir, cocokkan sessionId, proposalId, physicalObjectId, track overlay, object crop, anchor HANDLE/SIDE/TIP, dan decisionRuleId dengan event report. Jangan menilai gambar terpisah dari telemetry yang terikat padanya.")
    appendLine("Prioritaskan force close, memory/native pressure, scanner pipeline, physical-track truth, false NORMAL identity, duplicate quantity, anti-bridge, cross-lane gate, rod authenticity, anchor identity, full-frame rescue safety, latency, dan keamanan update in-place.")
    appendLine("Jika signer/package berubah atau versionCode tidak meningkat, tandai sebagai masalah distribusi prioritas tinggi.")
    appendLine("Jangan sarankan menurunkan threshold recognition hanya agar produk terlihat dikenali.")
    appendLine()
    append(report)
}

private fun startPreferredChatGptShare(context: Context, intent: Intent, chooserTitle: String) {
    val chatGptIntent = Intent(intent).apply { setPackage("com.openai.chatgpt") }
    val manager = context.packageManager
    if (chatGptIntent.resolveActivity(manager) != null) {
        context.startActivity(chatGptIntent)
    } else {
        context.startActivity(
            Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
