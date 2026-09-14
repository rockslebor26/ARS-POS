package com.arspos.anglerriausyndicate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * Phase 4.3.2.37 V8.4.17 — Evidence Integrity Recorder + Diagnostic Scan Package.
 * Diagnostic-only: it never changes recognition scores, thresholds, quantity,
 * product identity, transaction state, or database contents.
 */
object ArsVisualEvidenceRecorder {
    private const val ROOT = "ars_diagnostics/evidence"
    private const val EXPORT_DIR = "ars_diagnostic_exports"
    private const val JPEG_QUALITY = 82
    private const val MAX_IMAGE_DIMENSION = 1280
    private const val MAX_SESSIONS = 20
    private const val MAX_TOTAL_BYTES = 35L * 1024L * 1024L
    private const val MAX_OBJECT_EVIDENCE = 8
    private const val MAX_TRACK_EVIDENCE = 8

    data class Summary(val sessions: Int, val files: Int, val bytes: Long, val latestSession: String)

    private data class TrackSnapshot(
        val proposalId: String,
        val physicalObjectId: String,
        val bounds: Rect,
        val axisStartX: Int,
        val axisStartY: Int,
        val axisEndX: Int,
        val axisEndY: Int,
        val authenticityState: String,
        val authenticityScore: Int,
        val rodClaim: Boolean,
        val quantityEligible: Boolean,
        val mergeReason: String
    )

    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(12),
        ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile private var appContext: Context? = null
    private val manifestLock = Any()
    private val trackSnapshots = ConcurrentHashMap<String, MutableList<TrackSnapshot>>()

    fun install(context: Context) {
        appContext = context.applicationContext
        File(context.filesDir, ROOT).mkdirs()
        File(context.cacheDir, EXPORT_DIR).mkdirs()
    }

    fun beginSession(session: String, analysisBitmap: Bitmap) {
        val context = appContext ?: return
        if (session.isBlank()) return
        val dir = sessionDir(context, session).apply { mkdirs() }
        dir.setLastModified(System.currentTimeMillis())
        trackSnapshots[session] = Collections.synchronizedList(mutableListOf<TrackSnapshot>())
        val meta = JSONObject().apply {
            put("sessionId", session)
            put("scanId", session)
            put("phase", "4.3.2.37")
            put("engine", "V8.4.17")
            put("feature", "EVIDENCE_INTEGRITY_PHYSICAL_TRACK_TRUTH_RESCUE_GATE_V2")
            put("analysisWidth", analysisBitmap.width)
            put("analysisHeight", analysisBitmap.height)
            put("status", "STARTED")
            put("startedAt", System.currentTimeMillis())
        }
        runCatching { File(dir, "session.json").writeText(meta.toString(2)) }
        ArsFlightRecorder.event(
            "EVIDENCE_SESSION_START",
            "Visual evidence session opened",
            mapOf("session" to session, "analysisWidth" to analysisBitmap.width, "analysisHeight" to analysisBitmap.height)
        )
        enqueueBitmapSnapshot(
            analysisBitmap,
            File(dir, "scene/analysis_scene.jpg"),
            mapOf("type" to "SCENE_ANALYSIS", "session" to session, "source" to "ANALYSIS_BITMAP")
        )
    }

    /** Captures every physical-track truth state before display pruning. */
    fun captureTrackAudit(session: String, scene: Bitmap, proposal: ScanProposal) {
        val context = appContext ?: return
        if (session.isBlank()) return
        val list = trackSnapshots.getOrPut(session) {
            Collections.synchronizedList(mutableListOf<TrackSnapshot>())
        }
        synchronized(list) {
            if (list.size >= MAX_TRACK_EVIDENCE) return
            list += TrackSnapshot(
                proposal.proposalId,
                proposal.physicalObjectId,
                Rect(proposal.normalizedBounds),
                proposal.axisStartX, proposal.axisStartY,
                proposal.axisEndX, proposal.axisEndY,
                proposal.rodAuthenticityState,
                proposal.rodAuthenticityScore,
                proposal.rodClaim,
                proposal.quantityEligible,
                proposal.physicalMergeReason
            )
        }
        val crop = safeCrop(scene, proposal.normalizedBounds) ?: return
        val id = safeName(proposal.physicalObjectId.ifBlank { proposal.proposalId })
        enqueueOwnedBitmap(
            crop,
            File(sessionDir(context, session), "tracks/${id}_${safeName(proposal.rodAuthenticityState)}.jpg"),
            mapOf(
                "type" to "TRACK_AUDIT_CROP", "session" to session,
                "proposalId" to proposal.proposalId,
                "physicalObjectId" to proposal.physicalObjectId,
                "authenticity" to proposal.rodAuthenticityState,
                "authenticityScore" to proposal.rodAuthenticityScore,
                "rodClaim" to proposal.rodClaim,
                "rodClaimScore" to proposal.rodClaimScore,
                "quantityEligible" to proposal.quantityEligible,
                "bounds" to "${proposal.normalizedBounds.left},${proposal.normalizedBounds.top},${proposal.normalizedBounds.right},${proposal.normalizedBounds.bottom}",
                "axis" to "${proposal.axisStartX},${proposal.axisStartY},${proposal.axisEndX},${proposal.axisEndY}",
                "axisConfidence" to proposal.axisConfidence,
                "measuredWidthMedianPx" to proposal.measuredWidthMedianPx,
                "widthVariance" to proposal.widthVariance,
                "curvatureScore" to proposal.curvatureScore,
                "branchPenalty" to proposal.branchPenalty,
                "mergeReason" to proposal.physicalMergeReason
            )
        )
    }

    /** Draws all audited physical tracks over the exact scanner analysis scene. */
    fun captureTrackOverlay(session: String, scene: Bitmap) {
        val context = appContext ?: return
        if (session.isBlank()) return
        val tracks = trackSnapshots[session]?.let { list -> synchronized(list) { list.toList() } }.orEmpty()
        if (tracks.isEmpty()) return
        val sourceW = scene.width.coerceAtLeast(1)
        val sourceH = scene.height.coerceAtLeast(1)
        val owned = snapshotBitmap(scene, mutable = true) ?: return
        val target = File(sessionDir(context, session), "overlay/physical_track_overlay.jpg")
        enqueueTask(errorContext = mapOf("session" to session, "artifactType" to "PHYSICAL_TRACK_OVERLAY"), onRejected = { if (!owned.isRecycled) owned.recycle() }) {
            try {
                val canvas = Canvas(owned)
                val sx = owned.width.toFloat() / sourceW.toFloat()
                val sy = owned.height.toFloat() / sourceH.toFloat()
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(3f, owned.width / 300f)
                    color = Color.YELLOW
                }
                val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(2f, owned.width / 420f)
                    color = Color.CYAN
                }
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    textSize = max(18f, owned.width / 42f)
                    color = Color.WHITE
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                }
                tracks.forEachIndexed { i, t ->
                    val r = Rect(
                        (t.bounds.left * sx).toInt(), (t.bounds.top * sy).toInt(),
                        (t.bounds.right * sx).toInt(), (t.bounds.bottom * sy).toInt()
                    )
                    canvas.drawRect(r, box)
                    if (t.axisStartX != t.axisEndX || t.axisStartY != t.axisEndY) {
                        canvas.drawLine(t.axisStartX * sx, t.axisStartY * sy, t.axisEndX * sx, t.axisEndY * sy, axis)
                    }
                    val label = "${t.physicalObjectId.ifBlank { t.proposalId }} • ${t.authenticityState} • Q=${if (t.quantityEligible) 1 else 0}"
                    canvas.drawText(label, r.left.coerceAtLeast(4).toFloat(), (r.top - 8).coerceAtLeast((i + 1) * 24).toFloat(), text)
                }
                writeJpeg(owned, target)
                appendManifest(sessionDir(context, session), mapOf(
                    "type" to "PHYSICAL_TRACK_OVERLAY", "session" to session,
                    "trackCount" to tracks.size, "file" to relativeEvidencePath(target)
                ))
            } finally {
                if (!owned.isRecycled) owned.recycle()
            }
        }
    }

    /**
     * Final pipeline hook used by ProductionRepository after track resolution.
     * Physical overlay pixels come from the pre-pruning audited tracks, while
     * the final proposal set is also persisted as structured evidence.
     */
    fun recordTrackOverlay(session: String, scene: Bitmap, proposals: List<ScanProposal>) {
        val context = appContext ?: return
        if (session.isBlank()) return
        val dir = sessionDir(context, session)
        proposals.forEachIndexed { index, proposal ->
            appendManifest(dir, mapOf(
                "type" to "FINAL_PROPOSAL",
                "session" to session,
                "index" to index + 1,
                "proposalId" to proposal.proposalId,
                "physicalObjectId" to proposal.physicalObjectId,
                "physicalInstanceHint" to proposal.physicalInstanceHint,
                "proposalType" to proposal.proposalType,
                "source" to proposal.source,
                "quantityEligible" to proposal.quantityEligible,
                "rodClaim" to proposal.rodClaim,
                "rodAuthenticityState" to proposal.rodAuthenticityState,
                "visualOnlyBlocked" to proposal.visualOnlyBlocked,
                "bounds" to "${proposal.normalizedBounds.left},${proposal.normalizedBounds.top},${proposal.normalizedBounds.right},${proposal.normalizedBounds.bottom}",
                "axis" to "${proposal.axisStartX},${proposal.axisStartY},${proposal.axisEndX},${proposal.axisEndY}"
            ))
        }
        captureFinalProposalOverlay(session, scene, proposals)
        captureTrackOverlay(session, scene)
    }

    /** Final overlay also covers NORMAL-only scenes and visual-only firewall state. */
    private fun captureFinalProposalOverlay(session: String, scene: Bitmap, proposals: List<ScanProposal>) {
        val context = appContext ?: return
        if (session.isBlank() || proposals.isEmpty()) return
        val sourceW = scene.width.coerceAtLeast(1)
        val sourceH = scene.height.coerceAtLeast(1)
        val owned = snapshotBitmap(scene, mutable = true) ?: return
        val target = File(sessionDir(context, session), "overlay/final_proposal_overlay.jpg")
        enqueueTask(errorContext = mapOf("session" to session, "artifactType" to "FINAL_PROPOSAL_OVERLAY"), onRejected = { if (!owned.isRecycled) owned.recycle() }) {
            try {
                val canvas = Canvas(owned)
                val sx = owned.width.toFloat() / sourceW.toFloat()
                val sy = owned.height.toFloat() / sourceH.toFloat()
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(3f, owned.width / 300f)
                }
                val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(2f, owned.width / 420f)
                }
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    textSize = max(18f, owned.width / 44f)
                    color = Color.WHITE
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                }
                proposals.forEachIndexed { i, proposal ->
                    val color = when {
                        proposal.visualOnlyBlocked -> Color.MAGENTA
                        proposal.bridgeSuppressed || proposal.proposalType == ScanProposal.TYPE_AMBIGUOUS_CONTAINER -> Color.RED
                        proposal.trustedRod -> Color.GREEN
                        proposal.rodClaim -> Color.YELLOW
                        proposal.proposalType == ScanProposal.TYPE_NORMAL -> Color.CYAN
                        else -> Color.WHITE
                    }
                    box.color = color
                    axis.color = color
                    val b = proposal.normalizedBounds
                    val r = Rect(
                        (b.left * sx).toInt(), (b.top * sy).toInt(),
                        (b.right * sx).toInt(), (b.bottom * sy).toInt()
                    )
                    canvas.drawRect(r, box)
                    if (proposal.hasResolvedAxis) {
                        canvas.drawLine(
                            proposal.axisStartX * sx, proposal.axisStartY * sy,
                            proposal.axisEndX * sx, proposal.axisEndY * sy, axis
                        )
                    }
                    val id = proposal.physicalObjectId.ifBlank { proposal.physicalInstanceHint.ifBlank { proposal.proposalId } }
                    val gate = when {
                        proposal.visualOnlyBlocked -> "VISUAL_BLOCKED"
                        !proposal.quantityEligible -> "Q0"
                        else -> "Q1"
                    }
                    canvas.drawText(
                        "$id • ${proposal.proposalType} • $gate",
                        r.left.coerceAtLeast(4).toFloat(),
                        (r.top - 8).coerceAtLeast((i + 1) * 24).toFloat(),
                        text
                    )
                }
                writeJpeg(owned, target)
                appendManifest(sessionDir(context, session), mapOf(
                    "type" to "FINAL_PROPOSAL_OVERLAY",
                    "session" to session,
                    "proposalCount" to proposals.size,
                    "file" to relativeEvidencePath(target)
                ))
            } finally {
                if (!owned.isRecycled) owned.recycle()
            }
        }
    }

    /** Binds a recognition decision to the exact object crop and rod anchors. */
    fun captureDecision(
        session: String,
        objectIndex: Int,
        proposal: ScanProposal,
        crop: Bitmap,
        decisionRuleId: String,
        candidate: String,
        visualScore: Int,
        finalScore: Int,
        margin: Int,
        anchorScore: Int,
        anchorMargin: Int,
        recognitionAccepted: Boolean,
        quantityAccepted: Boolean
    ) {
        val context = appContext ?: return
        if (session.isBlank() || objectIndex !in 1..MAX_OBJECT_EVIDENCE) return
        val owned = snapshotBitmap(crop) ?: return
        val dir = sessionDir(context, session)
        val prefix = "obj${objectIndex}_${safeName(proposal.proposalId)}_${safeName(decisionRuleId)}"
        val objectFile = File(dir, "objects/$prefix.jpg")
        enqueueTask(
            errorContext = mapOf(
                "session" to session,
                "artifactType" to "DECISION_OBJECT",
                "proposalId" to proposal.proposalId,
                "physicalObjectId" to proposal.physicalObjectId,
                "decisionRuleId" to decisionRuleId
            ),
            onRejected = { if (!owned.isRecycled) owned.recycle() }
        ) {
            try {
                writeJpeg(owned, objectFile)
                val common = linkedMapOf<String, Any?>(
                    "session" to session, "object" to objectIndex,
                    "proposalId" to proposal.proposalId,
                    "physicalObjectId" to proposal.physicalObjectId,
                    "proposalType" to proposal.proposalType,
                    "decisionRuleId" to decisionRuleId,
                    "candidate" to candidate,
                    "visual" to visualScore, "final" to finalScore,
                    "margin" to margin, "anchor" to anchorScore,
                    "anchorMargin" to anchorMargin,
                    "recognitionAccepted" to recognitionAccepted,
                    "quantityAccepted" to quantityAccepted,
                    "file" to relativeEvidencePath(objectFile)
                )
                appendManifest(dir, common + mapOf("type" to "DECISION_OBJECT"))
                if (proposal.proposalType == ScanProposal.TYPE_LONG) {
                    RodAnchorExtractor.extract(owned)?.let { anchors ->
                        try {
                            val anchorDir = File(dir, "anchors").apply { mkdirs() }
                            val handle = File(anchorDir, "${prefix}_handle.jpg")
                            val side = File(anchorDir, "${prefix}_side.jpg")
                            val tip = File(anchorDir, "${prefix}_tip.jpg")
                            writeJpeg(anchors.handle, handle)
                            writeJpeg(anchors.side, side)
                            writeJpeg(anchors.tip, tip)
                            appendManifest(dir, common + mapOf(
                                "type" to "ROD_ANCHORS",
                                "handle" to relativeEvidencePath(handle),
                                "side" to relativeEvidencePath(side),
                                "tip" to relativeEvidencePath(tip)
                            ))
                        } finally { anchors.recycle() }
                    }
                }
            } finally { if (!owned.isRecycled) owned.recycle() }
        }
    }

    /**
     * Rich decision hook. Image/anchor capture remains delegated to the bounded
     * async capture path; textual OCR/barcode/candidate context is persisted in
     * the same session manifest for 1:1 evidence-to-log correlation.
     */
    fun recordDecision(
        session: String,
        objectIndex: Int,
        proposal: ScanProposal,
        crop: Bitmap,
        decisionRuleId: String,
        decisionReason: String = "",
        candidate: String,
        secondCandidate: String = "",
        visualScore: Int,
        finalScore: Int,
        margin: Int,
        anchorScore: Int,
        anchorMargin: Int,
        recognitionAccepted: Boolean,
        quantityAccepted: Boolean,
        barcode: String = "",
        ocrText: String = ""
    ) {
        captureDecision(
            session = session,
            objectIndex = objectIndex,
            proposal = proposal,
            crop = crop,
            decisionRuleId = decisionRuleId,
            candidate = candidate,
            visualScore = visualScore,
            finalScore = finalScore,
            margin = margin,
            anchorScore = anchorScore,
            anchorMargin = anchorMargin,
            recognitionAccepted = recognitionAccepted,
            quantityAccepted = quantityAccepted
        )
        val context = appContext ?: return
        if (session.isBlank()) return
        appendManifest(sessionDir(context, session), mapOf(
            "type" to "DECISION_CONTEXT",
            "session" to session,
            "object" to objectIndex,
            "proposalId" to proposal.proposalId,
            "physicalObjectId" to proposal.physicalObjectId,
            "trackId" to proposal.physicalObjectId.ifBlank { proposal.physicalInstanceHint },
            "decisionRuleId" to decisionRuleId,
            "decisionReason" to decisionReason,
            "candidate" to candidate,
            "secondCandidate" to secondCandidate,
            "visual" to visualScore,
            "final" to finalScore,
            "margin" to margin,
            "anchor" to anchorScore,
            "anchorMargin" to anchorMargin,
            "recognitionAccepted" to recognitionAccepted,
            "quantityAccepted" to quantityAccepted,
            "barcode" to barcode,
            "ocr" to ocrText.take(220)
        ))
    }

    fun completeSession(session: String, objects: Int, recognized: Int, durationMs: Long, status: String = "COMPLETE") {
        val context = appContext ?: return
        if (session.isBlank()) return
        val dir = sessionDir(context, session).apply { mkdirs() }
        val completion = JSONObject().apply {
            put("sessionId", session); put("status", status); put("objects", objects)
            put("recognized", recognized); put("durationMs", durationMs); put("completedAt", System.currentTimeMillis())
        }
        runCatching { File(dir, "completion.json").writeText(completion.toString(2)) }
        dir.setLastModified(System.currentTimeMillis())
        trackSnapshots.remove(session)
        prune(context)
        ArsFlightRecorder.event(
            "EVIDENCE_SESSION_COMPLETE",
            "Visual evidence session closed",
            mapOf("session" to session, "status" to status, "objects" to objects, "recognized" to recognized, "durationMs" to durationMs, "bytes" to directoryBytes(dir))
        )
    }

    fun summary(context: Context): Summary {
        install(context)
        val root = File(context.filesDir, ROOT)
        val sessions = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        return Summary(
            sessions.size,
            sessions.sumOf { countFiles(it) },
            sessions.sumOf { directoryBytes(it) },
            sessions.firstOrNull()?.name.orEmpty()
        )
    }

    /** ZIP = report + evidence manifest + scene + track crop/overlay + object crop + anchors. */
    fun buildDiagnosticPackage(context: Context, report: String): File {
        install(context)
        flushPending(4_000L)
        val exportDir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        exportDir.listFiles()?.filter { it.extension.equals("zip", true) }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { runCatching { it.delete() } }
        val out = File(exportDir, "ARS_DIAGNOSTIC_V8_4_17_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            addText(zip, "report.txt", report)
            val packageInfo = JSONObject().apply {
                put("phase", "4.3.2.37"); put("engine", "V8.4.17")
                put("version", "1.5.2"); put("versionCode", 152)
                put("package", context.packageName); put("createdAt", System.currentTimeMillis())
                put("description", "Evidence Integrity + Physical Track Truth + Rescue Gate V2 + Visual Scan Report")
                put("retentionSessions", MAX_SESSIONS); put("retentionBytes", MAX_TOTAL_BYTES)
            }.toString(2)
            addText(zip, "package_manifest.json", packageInfo)
            addText(zip, "package_info.json", packageInfo)
            val flightLog = File(File(context.filesDir, "ars_diagnostics"), "flight_recorder.jsonl")
            if (flightLog.exists()) addFile(zip, flightLog, "flight_recorder.jsonl")
            val root = File(context.filesDir, ROOT)
            root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
                ?.take(MAX_SESSIONS)?.forEach { addDirectory(zip, it, "evidence/${it.name}") }
        }
        ArsFlightRecorder.event(
            "EVIDENCE_EXPORT",
            "Diagnostic report + visual evidence ZIP exported",
            mapOf("file" to out.name, "bytes" to out.length())
        )
        return out
    }

    /** Lightweight cashier-facing report: report + flight log + only the selected scan session. */
    fun buildSessionDiagnosticPackage(context: Context, report: String, session: String): File {
        install(context)
        require(session.isNotBlank()) { "sessionId kosong" }
        flushPending(4_000L)
        val focusDir = sessionDir(context, session)
        require(focusDir.exists()) { "Evidence session tidak ditemukan: $session" }
        val exportDir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val out = File(exportDir, "ARS_SCAN_REPORT_V8_4_17_${safeName(session)}_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            addText(zip, "report.txt", report)
            val packageInfo = JSONObject().apply {
                put("phase", "4.3.2.37"); put("engine", "V8.4.17")
                put("version", "1.5.2"); put("versionCode", 152)
                put("package", context.packageName); put("createdAt", System.currentTimeMillis())
                put("focusSessionId", session)
                put("description", "Cashier visual scan report bound to one sessionId")
            }.toString(2)
            addText(zip, "package_manifest.json", packageInfo)
            val flightLog = File(File(context.filesDir, "ars_diagnostics"), "flight_recorder.jsonl")
            if (flightLog.exists()) addFile(zip, flightLog, "flight_recorder.jsonl")
            addDirectory(zip, focusDir, "evidence/${focusDir.name}")
        }
        ArsFlightRecorder.event(
            "SCAN_VISUAL_REPORT_EXPORT",
            "Focused visual scan report exported",
            mapOf("session" to session, "file" to out.name, "bytes" to out.length())
        )
        return out
    }

    fun exportPackage(context: Context): File? =
        runCatching { buildDiagnosticPackage(context, ArsFlightRecorder.buildReport(context)) }.getOrNull()

    fun clear(context: Context) {
        install(context)
        flushPending(2_000L)
        runCatching { File(context.filesDir, ROOT).deleteRecursively() }
        File(context.filesDir, ROOT).mkdirs()
        runCatching { File(context.cacheDir, EXPORT_DIR).deleteRecursively() }
        trackSnapshots.clear()
    }

    private fun enqueueBitmapSnapshot(source: Bitmap, target: File, manifest: Map<String, Any?>) {
        snapshotBitmap(source)?.let { enqueueOwnedBitmap(it, target, manifest) }
    }

    private fun enqueueOwnedBitmap(owned: Bitmap, target: File, manifest: Map<String, Any?>) {
        val context = appContext
        val errorContext = linkedMapOf<String, Any?>()
        manifest["session"]?.let { errorContext["session"] = it }
        errorContext["artifactType"] = manifest["type"] ?: "BITMAP_EVIDENCE"
        manifest["proposalId"]?.let { errorContext["proposalId"] = it }
        manifest["physicalObjectId"]?.let { errorContext["physicalObjectId"] = it }
        enqueueTask(errorContext = errorContext, onRejected = { if (!owned.isRecycled) owned.recycle() }) {
            try {
                writeJpeg(owned, target)
                val session = manifest["session"]?.toString().orEmpty()
                if (context != null && session.isNotBlank()) {
                    appendManifest(sessionDir(context, session), manifest + mapOf("file" to relativeEvidencePath(target)))
                }
            } finally { if (!owned.isRecycled) owned.recycle() }
        }
    }

    private fun enqueueTask(
        errorContext: Map<String, Any?> = emptyMap(),
        onRejected: () -> Unit = {},
        block: () -> Unit
    ) {
        try {
            executor.execute {
                runCatching(block).onFailure { e ->
                    ArsFlightRecorder.event(
                        "EVIDENCE_ERROR",
                        e.message ?: e::class.java.simpleName,
                        errorContext + mapOf("exception" to e::class.java.name)
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            onRejected()
            ArsFlightRecorder.event("EVIDENCE_DROPPED", "Diagnostic evidence queue full", errorContext)
        }
    }

    private fun snapshotBitmap(source: Bitmap, mutable: Boolean = false): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return runCatching {
            val scale = minOf(1f, MAX_IMAGE_DIMENSION.toFloat() / max(source.width, source.height).toFloat())
            val w = max(1, (source.width * scale).toInt())
            val h = max(1, (source.height * scale).toInt())
            val scaled = if (w != source.width || h != source.height) Bitmap.createScaledBitmap(source, w, h, true) else source
            val config = if (mutable) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            try { scaled.copy(config, mutable) }
            finally { if (scaled !== source && !scaled.isRecycled) scaled.recycle() }
        }.getOrNull()
    }

    private fun safeCrop(bitmap: Bitmap, bounds: Rect): Bitmap? {
        if (bitmap.isRecycled) return null
        val left = bounds.left.coerceIn(0, max(0, bitmap.width - 1))
        val top = bounds.top.coerceIn(0, max(0, bitmap.height - 1))
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null
        return runCatching {
            val created = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            if (created === bitmap) bitmap.copy(Bitmap.Config.RGB_565, false) else created
        }.getOrNull()
    }

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) }
    }

    private fun appendManifest(dir: File, fields: Map<String, Any?>) {
        synchronized(manifestLock) {
            dir.mkdirs()
            val obj = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
            }
            File(dir, "evidence_manifest.jsonl").appendText(obj.toString() + "\n")
        }
    }

    private fun sessionDir(context: Context, session: String) = File(File(context.filesDir, ROOT), safeName(session))
    private fun safeName(value: String) = value.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private fun relativeEvidencePath(file: File): String {
        val context = appContext ?: return file.name
        return runCatching { file.relativeTo(File(context.filesDir, ROOT)).path }.getOrElse { file.name }
    }

    private fun prune(context: Context) {
        val root = File(context.filesDir, ROOT)
        val sessions = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.toMutableList() ?: return
        sessions.drop(MAX_SESSIONS).forEach { runCatching { it.deleteRecursively() } }
        val kept = sessions.take(MAX_SESSIONS).toMutableList()
        var total = kept.sumOf { directoryBytes(it) }
        while (total > MAX_TOTAL_BYTES && kept.size > 1) {
            val oldest = kept.removeAt(kept.lastIndex)
            total -= directoryBytes(oldest)
            runCatching { oldest.deleteRecursively() }
        }
    }

    private fun directoryBytes(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun countFiles(dir: File) = dir.walkTopDown().count { it.isFile }

    private fun flushPending(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while ((executor.activeCount > 0 || executor.queue.isNotEmpty()) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(40L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
    }
    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
    }
    private fun addDirectory(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(dir).path.replace(File.separatorChar, '/')
            zip.putNextEntry(ZipEntry("$prefix/$rel")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
    }
}
