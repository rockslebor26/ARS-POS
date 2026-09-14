package com.arspos.anglerriausyndicate

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Phase 4.3.2.37 V8.4.17 — ARS Flight Recorder.
 *
 * Offline-first technical diagnostics only. V8.4.17 repairs evidence integrity,
 * strengthens physical-track truth and Rescue Gate V2, and adds cashier-facing
 * scan-review telemetry without lowering recognition thresholds.
 */
object ArsFlightRecorder {
    private const val DIR = "ars_diagnostics"
    private const val LOG = "flight_recorder.jsonl"
    private const val MAX_BYTES = 1_500_000L
    private const val KEEP_BYTES = 900_000L
    private const val MAX_REPORT_LINES = 180

    @Volatile private var appContext: Context? = null
    @Volatile private var installedCrashHandler = false

    fun install(context: Context) {
        appContext = context.applicationContext
        ArsVisualEvidenceRecorder.install(context)
        if (!installedCrashHandler) {
            synchronized(this) {
                if (!installedCrashHandler) {
                    installedCrashHandler = true
                    val previous = Thread.getDefaultUncaughtExceptionHandler()
                    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                        runCatching {
                            event(
                                type = "UNCAUGHT_CRASH",
                                message = throwable.message ?: throwable::class.java.simpleName,
                                fields = mapOf(
                                    "thread" to thread.name,
                                    "exception" to throwable::class.java.name,
                                    "stack" to throwable.stackTraceToString().take(18_000)
                                )
                            )
                        }
                        previous?.uncaughtException(thread, throwable)
                    }
                }
            }
        }
        val update = ArsUpdateChannel.observeLaunch(context)
        event(
            "APP_START",
            "ARS POS started",
            baseDeviceFields() + mapOf(
                "version" to update.versionName,
                "versionCode" to update.versionCode,
                "installTransition" to update.lastTransition,
                "signingChannel" to update.signingChannel,
                "signerSha256" to update.signerSha256
            )
        )
    }

    fun newSession(prefix: String = "scan"): String =
        "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"

    @Synchronized
    fun event(type: String, message: String = "", fields: Map<String, Any?> = emptyMap()) {
        val context = appContext ?: return
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, LOG)
            rotateIfNeeded(file)

            val obj = JSONObject()
            obj.put("ts", System.currentTimeMillis())
            obj.put("time", isoNow())
            obj.put("type", type)
            obj.put("message", message)
            fields.forEach { (key, value) -> obj.put(key, sanitize(value)) }
            file.appendText(obj.toString() + "\n")
        }
    }

    @Synchronized
    fun buildReport(context: Context): String {
        if (appContext == null) appContext = context.applicationContext
        val update = ArsUpdateChannel.snapshot(context)
        val file = File(File(context.filesDir, DIR), LOG)
        val allLines = if (file.exists()) file.readLines() else emptyList()
        val allParsed = allLines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val recentLines = allLines.takeLast(MAX_REPORT_LINES)

        val currentStart = update.lastTransitionAt.coerceAtLeast(update.firstInstallTime)
        val currentParsed = allParsed.filter { it.optLong("ts", 0L) >= currentStart }

        fun sessions(events: List<JSONObject>, type: String): Set<String> =
            events.asSequence()
                .filter { it.optString("type") == type }
                .map { it.optString("session") }
                .filter { it.isNotBlank() }
                .toSet()

        fun average(events: List<JSONObject>, type: String, key: String = "durationMs"): Long {
            val values = events.filter { it.optString("type") == type }
                .map { it.optLong(key, 0L) }
                .filter { it > 0L }
            return if (values.isEmpty()) 0L else values.sum() / values.size
        }

        fun crashCount(events: List<JSONObject>): Int = events.count {
            val type = it.optString("type")
            type == "UNCAUGHT_CRASH" || type == "SCAN_FATAL"
        }

        fun errorCount(events: List<JSONObject>): Int = events.count {
            val type = it.optString("type")
            type == "SCAN_ERROR" || type == "SCAN_FATAL"
        }

        fun evidenceErrorCount(events: List<JSONObject>): Int = events.count {
            it.optString("type") == "EVIDENCE_ERROR"
        }

        val currentStarted = sessions(currentParsed, "SCAN_START")
        val currentCompleted = sessions(currentParsed, "SCAN_COMPLETE")
        val lifetimeStarted = sessions(allParsed, "SCAN_START")
        val lifetimeCompleted = sessions(allParsed, "SCAN_COMPLETE")
        val currentOrphanCompleted = currentCompleted.minus(currentStarted).size
        val currentIncomplete = currentStarted.minus(currentCompleted).size

        val latestAggregation = currentParsed.lastOrNull { it.optString("type") == "SCAN_AGGREGATION" }
            ?: allParsed.lastOrNull { it.optString("type") == "SCAN_AGGREGATION" }
        val latestProposalMerge = currentParsed.lastOrNull { it.optString("type") == "SCAN_PROPOSAL_MERGE" }
            ?: allParsed.lastOrNull { it.optString("type") == "SCAN_PROPOSAL_MERGE" }
        val latestMemory = currentParsed.lastOrNull { it.optString("type") == "SCAN_MEMORY" }
        val latestDecision = currentParsed.lastOrNull { it.optString("type") == "SCAN_OBJECT" }
        val latestUpdate = allParsed.lastOrNull { it.optString("type") == "APP_UPDATE" }

        return buildString {
            appendLine("ARS POS DEVELOPMENT DIAGNOSTIC REPORT")
            appendLine("Application: ARS POS — ANGLER RIAU SYNDICATE")
            appendLine("Package: ${update.packageName}")
            appendLine("Current Version: ${update.versionName}")
            appendLine("Version Code: ${update.versionCode}")
            appendLine("Phase: 4.3.2.37")
            appendLine("Engine: V8.4.17 — Evidence Integrity + Physical Track Truth + Rescue Gate V2")
            appendLine("Signing Channel: ${update.signingChannel}")
            appendLine("Signer SHA-256: ${update.signerSha256}")
            appendLine("Last Install Transition: ${update.lastTransition}")
            appendLine("Previous Installed Version: ${update.previousVersionName ?: "-"} (${update.previousVersionCode ?: "-"})")
            appendLine("Current Installed Version: ${update.versionName} (${update.versionCode})")
            appendLine("First Install Time: ${ArsUpdateChannel.formatTime(update.firstInstallTime)}")
            appendLine("Last Update Time: ${ArsUpdateChannel.formatTime(update.lastUpdateTime)}")
            appendLine("Transition Recorded At: ${ArsUpdateChannel.formatTime(update.lastTransitionAt)}")
            appendLine("Database Version: 5")
            appendLine("Generated: ${isoNow()}")
            appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            if (latestUpdate != null) {
                appendLine(
                    "Latest Recorded Update: ${latestUpdate.optString("fromVersion", "?")} " +
                        "(${latestUpdate.optLong("fromVersionCode", -1L)}) -> " +
                        "${latestUpdate.optString("toVersion", "?")} " +
                        "(${latestUpdate.optLong("toVersionCode", -1L)})"
                )
            } else if (
                update.lastTransition == "IN_PLACE_UPDATE" &&
                update.previousVersionName != null &&
                update.previousVersionCode != null
            ) {
                // Update identity is persisted outside the diagnostic log, so
                // "Clear Diagnostic Log" must not erase update-chain evidence.
                appendLine(
                    "Latest Recorded Update: ${update.previousVersionName} " +
                        "(${update.previousVersionCode}) -> ${update.versionName} (${update.versionCode}) " +
                        "[persisted update channel]"
                )
            } else {
                appendLine("Latest Recorded Update: none yet on this installation")
            }
            appendLine()
            appendLine("UPDATE CHANNEL RULE")
            appendLine("- applicationId must remain com.arspos.anglerriausyndicate")
            appendLine("- signer SHA-256 must remain identical across official updates")
            appendLine("- versionCode must always increase")
            appendLine("- uninstalling the official app resets app-private update history and local data")
            appendLine()
            appendLine("CURRENT BUILD SUMMARY")
            appendLine("- Build window starts: ${ArsUpdateChannel.formatTime(currentStart)}")
            appendLine("- Scan sessions started: ${currentStarted.size}")
            appendLine("- Scan sessions completed: ${currentCompleted.size}")
            appendLine("- Incomplete sessions: $currentIncomplete")
            appendLine("- Orphan completed sessions: $currentOrphanCompleted")
            appendLine("- Scan errors/fatal events: ${errorCount(currentParsed)}")
            appendLine("- Evidence recorder errors: ${evidenceErrorCount(currentParsed)}")
            appendLine("- Uncaught/fatal crash records: ${crashCount(currentParsed)}")
            appendLine("- Average completed scan: ${average(currentParsed, "SCAN_COMPLETE")} ms")
            appendLine("- Average object scoring: ${average(currentParsed, "SCAN_SCORING")} ms")
            if (latestProposalMerge != null) {
                appendLine("- Latest proposal merge: ${latestProposalMerge.optInt("input", 0)} -> ${latestProposalMerge.optInt("output", 0)}")
                appendLine("- Latest bridge proposals suppressed: ${latestProposalMerge.optInt("bridgeSuppressed", 0)}")
                appendLine("- Latest cross-lane proposals suppressed: ${latestProposalMerge.optInt("crossLaneSuppressed", 0)}")
                appendLine("- Latest background long proposals suppressed: ${latestProposalMerge.optInt("backgroundSuppressed", 0)}")
                appendLine("- Latest NORMAL ownership conflicts: ${latestProposalMerge.optInt("normalOwnershipConflicts", 0)}")
                appendLine("- Latest cross-category visual-only blocked: ${latestProposalMerge.optInt("crossCategoryVisualOnlyBlocked", 0)}")
                appendLine("- Latest cross-category conflicts: ${latestProposalMerge.optInt("crossCategoryConflicts", 0)}")
                appendLine("- Latest max cross-category conflict score: ${latestProposalMerge.optInt("maxCrossCategoryConflictScore", 0)}")
                appendLine("- Latest NORMAL rod-track hits: ${latestProposalMerge.optInt("normalRodTrackHits", 0)}")
                appendLine("- Latest raw LONG axis hypotheses: ${latestProposalMerge.optInt("rawLongHypotheses", 0)}")
                appendLine("- Latest physical objects before/after resolve: ${latestProposalMerge.optInt("physicalObjectsBeforeResolve", 0)}/${latestProposalMerge.optInt("physicalObjectsAfterResolve", 0)}")
                appendLine("- Latest hypotheses merged: ${latestProposalMerge.optInt("hypothesesMerged", 0)}")
                appendLine("- Latest duplicate physical objects prevented: ${latestProposalMerge.optInt("duplicatePhysicalObjectsPrevented", 0)}")
                appendLine("- Latest trusted/uncertain/non-rod tracks: ${latestProposalMerge.optInt("trustedRodTracks", 0)}/${latestProposalMerge.optInt("uncertainRodTracks", 0)}/${latestProposalMerge.optInt("nonRodTracks", 0)}")
                appendLine("- Latest authenticity-suppressed LONG: ${latestProposalMerge.optInt("authenticitySuppressed", 0)}")
                appendLine("- Latest max rod authenticity score: ${latestProposalMerge.optInt("maxRodAuthenticityScore", 0)}")
                appendLine("- Latest rod-structure candidates: ${latestProposalMerge.optInt("rodStructureCandidates", 0)}")
                appendLine("- Latest physical proposal hints: ${latestProposalMerge.optInt("physicalHints", 0)}")
                appendLine("- Latest angle-aware physical tracks: ${latestProposalMerge.optInt("angleAwareTracks", 0)}")
                appendLine("- Latest crossing tracks preserved: ${latestProposalMerge.optInt("crossingTracksPreserved", 0)}")
                appendLine("- Latest axis candidates/confidence: ${latestProposalMerge.optInt("axisCandidates", 0)}/${latestProposalMerge.optInt("maxAxisConfidence", 0)}")
            }
            if (latestAggregation != null) {
                appendLine("- Latest physical aggregation: ${latestAggregation.optInt("before", 0)} -> ${latestAggregation.optInt("after", 0)} (merged ${latestAggregation.optInt("merged", 0)})")
                appendLine("- Latest physical-instance merges: ${latestAggregation.optInt("lineageMerges", 0)}")
                appendLine("- Latest quantity suppressed: ${latestAggregation.optInt("quantitySuppressed", 0)}")
                appendLine("- Latest distinct long instances: ${latestAggregation.optInt("distinctLongInstances", 0)}")
            }
            if (latestMemory != null) {
                appendLine("- Latest heap after scan: ${latestMemory.optLong("heapAfterScanMb", 0L)} MB")
                appendLine("- Latest peak Java heap: ${latestMemory.optLong("peakHeapMb", 0L)} MB")
                appendLine("- Latest native heap: ${latestMemory.optLong("nativeHeapMb", 0L)} MB")
                appendLine("- Latest native heap delta: ${latestMemory.optLong("nativeDeltaMb", 0L)} MB")
                appendLine("- Latest native baseline/growth: ${latestMemory.optLong("nativeBaselineMb", 0L)}/${latestMemory.optLong("nativeGrowthFromBaselineMb", 0L)} MB")
                appendLine("- Latest native high-water: ${latestMemory.optLong("nativeHighWaterMb", 0L)} MB")
                appendLine("- Latest total PSS: ${latestMemory.optLong("totalPssMb", 0L)} MB")
                appendLine("- Latest PSS high-water: ${latestMemory.optLong("pssHighWaterMb", 0L)} MB")
                appendLine("- Latest scanner scan count: ${latestMemory.optInt("scannerScanCount", 0)}")
                appendLine("- Latest native pressure warning: ${latestMemory.optBoolean("nativePressureWarning", false)}")
                appendLine("- Latest temp crops recycled: ${latestMemory.optInt("recycledCrops", 0)}/${latestMemory.optInt("temporaryCrops", 0)}")
            }
            if (latestDecision != null) {
                appendLine("- Latest decision rule: ${latestDecision.optString("decisionRuleId", "-")}")
                appendLine("- Latest decision margin: ${latestDecision.optInt("margin", 0)}")
                appendLine("- Latest rod anchor score/margin: ${latestDecision.optInt("anchor", 0)}/${latestDecision.optInt("anchorMargin", 0)}")
            }
            appendLine()
            appendLine("LIFETIME SUMMARY")
            appendLine("- Scan sessions started: ${lifetimeStarted.size}")
            appendLine("- Scan sessions completed: ${lifetimeCompleted.size}")
            appendLine("- Scan errors/fatal events: ${errorCount(allParsed)}")
            appendLine("- Evidence recorder errors: ${evidenceErrorCount(allParsed)}")
            appendLine("- Uncaught/fatal crash records: ${crashCount(allParsed)}")
            appendLine("- Average completed scan: ${average(allParsed, "SCAN_COMPLETE")} ms")
            appendLine("- Average object scoring: ${average(allParsed, "SCAN_SCORING")} ms")
            appendLine()
            val evidence = ArsVisualEvidenceRecorder.summary(context)
            appendLine("VISUAL EVIDENCE RECORDER")
            appendLine("- Phase/engine: 4.3.2.37 / V8.4.17")
            appendLine("- Evidence sessions retained: ${evidence.sessions}/20")
            appendLine("- Evidence files retained: ${evidence.files}")
            appendLine("- Evidence storage: ${evidence.bytes / 1024L / 1024L} MB")
            appendLine("- Latest evidence session: ${evidence.latestSession.ifBlank { "none" }}")
            appendLine("- Capture source: same scanner analysis bitmap/crop; diagnostics do not alter recognition decisions")
            appendLine()
            appendLine("PRIVACY")
            appendLine("Technical diagnostics only. Scan-product images may be included in exported evidence packages; PIN, payment data and customer details are intentionally excluded.")
            appendLine()
            appendLine("RECENT EVENTS (oldest -> newest)")
            if (recentLines.isEmpty()) appendLine("No diagnostic events yet.")
            recentLines.forEach { appendLine(it) }
        }
    }

    @Synchronized
    fun clear(context: Context) {
        val file = File(File(context.filesDir, DIR), LOG)
        if (file.exists()) file.delete()
        ArsVisualEvidenceRecorder.clear(context)
        event("LOG_CLEARED", "Diagnostic log and visual evidence cleared")
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val bytes = file.readBytes()
        val start = (bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0)
        val tail = bytes.copyOfRange(start, bytes.size)
        var firstNewline = 0
        while (firstNewline < tail.size && tail[firstNewline] != '\n'.code.toByte()) firstNewline++
        val clean = if (firstNewline < tail.size - 1) tail.copyOfRange(firstNewline + 1, tail.size) else tail
        file.writeBytes(clean)
    }

    private fun sanitize(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Number, is Boolean, is String -> if (value is String) value.take(18_000) else value
        else -> value.toString().take(18_000)
    }

    private fun baseDeviceFields(): Map<String, Any?> = mapOf(
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}"
    )

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
}
