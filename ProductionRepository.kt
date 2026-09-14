package com.arspos.anglerriausyndicate.data

import com.arspos.anglerriausyndicate.SmartProductInput
import com.arspos.anglerriausyndicate.ProductVisualMatcher
import com.arspos.anglerriausyndicate.VisualProductMatch
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import com.arspos.anglerriausyndicate.data.db.*
import java.text.SimpleDateFormat
import java.util.*

data class ProfitSummary(val revenue:Long,val cost:Long,val grossProfit:Long)
data class LoginResult(val userId:Long,val role:String,val displayName:String)

class ProductionRepository(private val db:ArsDatabase){
    private val dao=db.dao()

    // V8.4.13 rolling in-process memory telemetry. This is diagnostic state
    // only; it never changes recognition decisions.
    private var scannerNativeBaselineMb: Long? = null
    private var scannerNativeHighWaterMb: Long = 0
    private var scannerPssHighWaterMb: Long = 0
    private var scannerScanCount: Int = 0
    private var scannerWarmNativeBaselineMb: Long? = null

    fun products()=dao.products()
    fun search(q:String)=if(q.isBlank())products() else dao.search(q)
    fun sales()=dao.sales()
    fun customers()=dao.customers()
    suspend fun saleItems(id:Long)=dao.saleItems(id)
    fun revenue(start:Long)=dao.revenue(start)
    fun transactionCount(start:Long)=dao.transactionCount(start)
    fun cashBalance()=dao.cashBalance()

    suspend fun login(username:String,pin:String):Result<LoginResult> = runCatching{
        val user=dao.user(username.trim()) ?: error("Username tidak ditemukan")
        require(PinHasher.matches(user.pinHash,pin)){"PIN salah"}
        if (!user.pinHash.startsWith("sha256\$")) dao.updateUser(user.copy(pinHash=PinHasher.hash(pin)))
        LoginResult(user.id,user.role,user.displayName)
    }

    suspend fun seed(){
        if(dao.countProducts()==0){
            listOf(
                ProductEntity(sku="JRG001",barcode="899000000001",name="Joran BC Elito Sidat 180",category="JORAN",brand="Elito",costPrice=95000,sellPrice=150000,wholesalePrice=135000,stock=15,minimumStock=5),
                ProductEntity(sku="REL001",barcode="899000000002",name="Reel Shimano FX 2500",category="REEL",brand="Shimano",costPrice=210000,sellPrice=275000,stock=8,minimumStock=3),
                ProductEntity(sku="SNR001",barcode="899000000003",name="PE Jabrik X9 2.0",category="SENAR",brand="Jabrik",costPrice=55000,sellPrice=85000,stock=12,minimumStock=4),
                ProductEntity(sku="LDR001",barcode="899000000004",name="Leader Relix 40lb",category="LEADER",brand="Relix",costPrice=28000,sellPrice=45000,stock=9,minimumStock=4),
                ProductEntity(sku="KIL001",barcode="899000000005",name="Kail BKK 1/0",category="KAIL",brand="BKK",costPrice=11000,sellPrice=18000,stock=20,minimumStock=5)
            ).forEach{dao.insertProduct(it)}
        }
        dao.insertUser(UserEntity(username="admin",displayName="Administrator",role="ADMIN",pinHash=PinHasher.hash("0000")))
        dao.setSetting(SettingEntity("store_name","ANGLER RIAU SYNDICATE"))
        dao.setSetting(SettingEntity("store_tagline","FISHING STORE"))
    }

    suspend fun addProduct(name:String,sku:String,barcode:String?,category:String,cost:Long,price:Long,stock:Int,brand:String?=null,variant:String?=null)=db.withTransaction{
        require(name.isNotBlank()) { "Nama produk wajib diisi" }
        val normalizedSku = sku.trim().ifBlank { SmartProductInput.generateSku(brand.orEmpty(), name, variant.orEmpty()) }
        val productId = dao.insertProduct(ProductEntity(
            sku=normalizedSku,
            barcode=barcode?.trim()?.ifBlank { null },
            name=name.trim(),
            category=category.trim().ifBlank { "LAINNYA" },
            brand=brand?.trim()?.ifBlank { null },
            variant=variant?.trim()?.ifBlank { null },
            costPrice=cost,
            sellPrice=price,
            stock=stock
        ))
        mergeProductIdentityProfile(
            productId = productId,
            brand = brand.orEmpty(),
            name = name,
            variant = variant.orEmpty(),
            barcode = barcode.orEmpty()
        )
        productId
    }

    fun productPhotos(productId:Long)=dao.productPhotos(productId)

    suspend fun visualProductMatches(bitmap: android.graphics.Bitmap):List<com.arspos.anglerriausyndicate.VisualProductMatch> {
        val photos=dao.allProductPhotos()
        if(photos.isEmpty()) return emptyList()
        val products=HashMap<Long,ProductEntity?>()
        val bestByProduct=HashMap<Long,com.arspos.anglerriausyndicate.VisualProductMatch>()
        for(photo in photos){
            val product=if(products.containsKey(photo.productId)) products[photo.productId] else dao.product(photo.productId).also{products[photo.productId]=it}
            if(product==null || !product.active) continue
            val score=com.arspos.anglerriausyndicate.ProductVisualMatcher.score(bitmap,photo.localPath)
            val match=com.arspos.anglerriausyndicate.VisualProductMatch(product,photo,score)
            val previous=bestByProduct[product.id]
            if(previous==null || score>previous.score) bestByProduct[product.id]=match
        }
        return bestByProduct.values.sortedByDescending { it.score }.take(8)
    }

    /** Phase 4.3.2.37 V8.4.17: evidence integrity + physical-track truth + Rescue Gate V2; identity thresholds unchanged. */
    suspend fun smartVisualScan(bitmap: android.graphics.Bitmap, diagnosticSession: String = ""): List<com.arspos.anglerriausyndicate.VisualScanObject> {
        val allPhotos = dao.allProductPhotos()
        val photos = selectRecognitionReferences(allPhotos)
        val allProducts = dao.products().first().filter { it.active }
        val identityProfiles = loadIdentityProfiles()
        val verifiedProductIds = loadVerifiedProductIds()
        val productsById = allProducts.associateBy { it.id }

        val nativeBeforeScanMb = android.os.Debug.getNativeHeapAllocatedSize() / 1024L / 1024L
        var temporaryCropCount = 0
        var recycledCropCount = 0
        var peakHeapMb = heapUsedMb()
        fun sampleHeap(): Long {
            val value = heapUsedMb()
            if (value > peakHeapMb) peakHeapMb = value
            return value
        }

        fun isLongProduct(product: ProductEntity): Boolean =
            identityProfiles[product.id]?.isLongObject == true || isLikelyLongProduct(product)

        val longProducts = allProducts.filter { isLongProduct(it) }
        val normalProducts = allProducts.filterNot { isLongProduct(it) }
        val longProductIds = longProducts.map { it.id }.toSet()
        val normalPhotos = photos.filter { normalProducts.any { product -> product.id == it.productId } }
        val longPhotos = photos.filter { longProductIds.contains(it.productId) }

        com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
            "SCAN_PIPELINE",
            "Recognition pipeline prepared with typed proposal lanes",
            mapOf(
                "session" to diagnosticSession,
                "bitmap" to "${bitmap.width}x${bitmap.height}",
                "allRefs" to allPhotos.size,
                "selectedRefs" to photos.size,
                "normalRefs" to normalPhotos.size,
                "longRefs" to longPhotos.size,
                "products" to allProducts.size,
                "normalProducts" to normalProducts.size,
                "longProducts" to longProducts.size,
                "heapUsedMb" to sampleHeap(),
                "heapMaxMb" to (Runtime.getRuntime().maxMemory() / 1024L / 1024L)
            )
        )
        if (allProducts.isEmpty()) return emptyList()

        var mergeAxisCount = 0
        // V8.4.17 FIX1: rescue policy is evaluated after the LONG merge block.
        // Persist only the aggregate track truth that Rescue Gate V2 needs;
        // never reference the block-local mergeDiagnostics outside its scope.
        var trustedRodTracksAfterMerge = 0
        var uncertainRodTracksAfterMerge = 0
        var proposals = com.arspos.anglerriausyndicate.SmartVisualScanner.detectProposals(bitmap)
        com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
            "SCAN_PROPOSALS",
            "Initial ML Kit proposals",
            mapOf(
                "session" to diagnosticSession,
                "count" to proposals.size,
                "normal" to proposals.count { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_NORMAL },
                "longLike" to proposals.count { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_LONG }
            )
        )

        val hasLongMaster = longProducts.any { product -> photos.any { it.productId == product.id } }
        if (hasLongMaster) {
            val rawLong = com.arspos.anglerriausyndicate.LongObjectAnalyzer.proposals(bitmap)
            val longProposals = rawLong.mapIndexed { index, proposal ->
                val normalized = com.arspos.anglerriausyndicate.ScanCoordinateNormalizer.normalize(
                    proposal.bounds,
                    0,
                    bitmap.width,
                    bitmap.height
                )
                val normalizedAxis = com.arspos.anglerriausyndicate.ScanCoordinateNormalizer.normalizeAxis(
                    proposal.axisStartX,
                    proposal.axisStartY,
                    proposal.axisEndX,
                    proposal.axisEndY,
                    0,
                    bitmap.width,
                    bitmap.height
                )
                com.arspos.anglerriausyndicate.ScanProposal(
                    proposalId = "LONG-${index + 1}",
                    source = com.arspos.anglerriausyndicate.ScanProposal.SOURCE_LONG,
                    proposalType = com.arspos.anglerriausyndicate.ScanProposal.TYPE_LONG,
                    originalBounds = android.graphics.Rect(proposal.bounds),
                    normalizedBounds = normalized,
                    rotationDegrees = 0,
                    lineageId = "AXIS-${proposal.axisAngleDeg.toInt()}-${index + 1}",
                    physicalInstanceHint = "",
                    quantityEligible = true,
                    rodStructureScore = com.arspos.anglerriausyndicate.LongObjectAnalyzer.rodStructureScore(bitmap, proposal),
                    orientation = proposal.orientation,
                    strength = proposal.strength,
                    axisAngleDeg = normalizedAxis.angleDeg,
                    axisStartX = normalizedAxis.start.x,
                    axisStartY = normalizedAxis.start.y,
                    axisEndX = normalizedAxis.end.x,
                    axisEndY = normalizedAxis.end.y,
                    axisWidthPx = proposal.estimatedWidthPx,
                    axisConfidence = proposal.axisConfidence
                )
            }
            val beforeMerge = proposals.size + longProposals.size
            val mergeDiagnostics = com.arspos.anglerriausyndicate.SmartVisualScanner.mergeProposalMetadataDetailed(
                proposals,
                longProposals,
                bitmap = bitmap,
                maxObjects = 6,
                diagnosticSession = diagnosticSession
            )
            mergeAxisCount = mergeDiagnostics.physicalObjectsAfterResolve
            trustedRodTracksAfterMerge = mergeDiagnostics.trustedRodTracks
            uncertainRodTracksAfterMerge = mergeDiagnostics.uncertainRodTracks
            proposals = mergeDiagnostics.proposals
            com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
                "SCAN_PROPOSAL_MERGE",
                "V8.4.17 physical-track truth + evidence-integrity firewall applied",
                mapOf(
                    "session" to diagnosticSession,
                    "input" to beforeMerge,
                    "output" to proposals.size,
                    "longInput" to longProposals.size,
                    "rawLongHypotheses" to mergeDiagnostics.rawLongHypotheses,
                    "physicalObjectsBeforeResolve" to mergeDiagnostics.physicalObjectsBeforeResolve,
                    "physicalObjectsAfterResolve" to mergeDiagnostics.physicalObjectsAfterResolve,
                    "hypothesesMerged" to mergeDiagnostics.hypothesesMerged,
                    "duplicatePhysicalObjectsPrevented" to mergeDiagnostics.duplicatePhysicalObjectsPrevented,
                    "trustedRodTracks" to mergeDiagnostics.trustedRodTracks,
                    "uncertainRodTracks" to mergeDiagnostics.uncertainRodTracks,
                    "nonRodTracks" to mergeDiagnostics.nonRodTracks,
                    "rodClaimTracks" to proposals.count { it.rodClaim },
                    "rodClaimNormalHits" to proposals.filter { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_NORMAL }.sumOf { it.rodTrackHits },
                    "normalBlockedByRodClaim" to proposals.count { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_NORMAL && it.visualOnlyBlocked },
                    "crossThroughIntersections" to proposals.sumOf { it.crossThroughIntersections },
                    "trueBranchIntersections" to proposals.sumOf { it.trueBranchIntersections },
                    "normalOutput" to proposals.count { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_NORMAL },
                    "longOutput" to proposals.count { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_LONG },
                    "bridgeSuppressed" to proposals.count { it.bridgeSuppressed || it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_AMBIGUOUS_CONTAINER },
                    "crossLaneSuppressed" to mergeDiagnostics.crossLaneSuppressed,
                    "backgroundSuppressed" to proposals.count { it.source == com.arspos.anglerriausyndicate.ScanProposal.SOURCE_BACKGROUND },
                    "authenticitySuppressed" to proposals.count { it.source == com.arspos.anglerriausyndicate.ScanProposal.SOURCE_AUTHENTICITY },
                    "normalOwnershipConflicts" to proposals.count { it.normalOwnershipConflict },
                    "crossCategoryVisualOnlyBlocked" to proposals.count { it.visualOnlyBlocked },
                    "crossCategoryConflicts" to proposals.count { it.crossCategoryConflict },
                    "maxCrossCategoryConflictScore" to (proposals.maxOfOrNull { it.crossCategoryConflictScore } ?: 0),
                    "normalRodTrackHits" to proposals.filter { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_NORMAL }.sumOf { it.rodTrackHits },
                    "rodStructureCandidates" to proposals.count { it.rodStructureScore > 0 },
                    "physicalHints" to proposals.map { it.physicalInstanceHint }.filter { it.isNotBlank() && it.startsWith("TRACK-") }.distinct().size,
                    "angleAwareTracks" to mergeDiagnostics.physicalObjectsAfterResolve,
                    "crossingTracksPreserved" to mergeDiagnostics.crossingTracksPreserved,
                    "axisCandidates" to mergeDiagnostics.rawLongHypotheses,
                    "maxAxisConfidence" to (proposals.maxOfOrNull { it.axisConfidence } ?: 0),
                    "maxRodAuthenticityScore" to (proposals.maxOfOrNull { it.rodAuthenticityScore } ?: 0)
                )
            )
        }

        com.arspos.anglerriausyndicate.ArsVisualEvidenceRecorder.recordTrackOverlay(
            diagnosticSession,
            bitmap,
            proposals
        )

        // Scene barcode fallback remains quantity-eligible because exact barcode
        // is an independent SKU identity signal. Long full-frame rescue is never
        // quantity-eligible in V8.4.13.
        if (proposals.isEmpty()) {
            val sceneOcr = runCatching {
                com.arspos.anglerriausyndicate.SmartProductInput.analyzeMultiOrientation(bitmap)
            }.getOrElse { com.arspos.anglerriausyndicate.SmartProductInput.Draft() }
            if (sceneOcr.barcode.isBlank()) return emptyList()
            val full = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            proposals = listOf(
                com.arspos.anglerriausyndicate.ScanProposal(
                    proposalId = "SCENE-BARCODE-1",
                    source = com.arspos.anglerriausyndicate.ScanProposal.SOURCE_SCENE_BARCODE,
                    proposalType = com.arspos.anglerriausyndicate.ScanProposal.TYPE_NORMAL,
                    originalBounds = android.graphics.Rect(full),
                    normalizedBounds = android.graphics.Rect(full),
                    rotationDegrees = 0,
                    lineageId = "SCENE-BARCODE",
                    physicalInstanceHint = "NORMAL-SCENE-BARCODE",
                    quantityEligible = true,
                    orientation = "FULL_FRAME",
                    strength = 100
                )
            )
        }

        val result = ArrayList<com.arspos.anglerriausyndicate.VisualScanObject>()

        suspend fun analyze(
            proposal: com.arspos.anglerriausyndicate.ScanProposal,
            index: Int,
            allowSceneOcr: Boolean
        ): com.arspos.anglerriausyndicate.VisualScanObject? {
            if (proposal.bridgeSuppressed || proposal.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_AMBIGUOUS_CONTAINER) {
                com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
                    "SCAN_PROPOSAL_SUPPRESSED",
                    "Ambiguous bridge/container proposal excluded from recognition quantity",
                    mapOf(
                        "session" to diagnosticSession,
                        "object" to index,
                        "proposalId" to proposal.proposalId,
                        "proposalType" to proposal.proposalType,
                        "source" to proposal.source,
                        "lineageId" to proposal.lineageId,
                        "physicalInstanceHint" to proposal.physicalInstanceHint,
                        "axisAngleDeg" to proposal.axisAngleDeg,
                        "axisConfidence" to proposal.axisConfidence,
                        "rodStructureScore" to proposal.rodStructureScore,
                        "physicalObjectId" to proposal.physicalObjectId,
                        "hypothesesMerged" to proposal.hypothesesMerged,
                        "physicalMergeReason" to proposal.physicalMergeReason,
                        "rodAuthenticityState" to proposal.rodAuthenticityState,
                        "rodAuthenticityScore" to proposal.rodAuthenticityScore,
                        "branchPenalty" to proposal.branchPenalty,
                        "beamPenalty" to proposal.beamPenalty,
                        "floorLinePenalty" to proposal.floorLinePenalty,
                        "bounds" to "${proposal.normalizedBounds.left},${proposal.normalizedBounds.top},${proposal.normalizedBounds.right},${proposal.normalizedBounds.bottom}"
                    )
                )
                return null
            }
            val objectStarted = System.currentTimeMillis()
            val bounds = proposal.normalizedBounds
            val crop = com.arspos.anglerriausyndicate.SmartVisualScanner.crop(bitmap, proposal) ?: return null
            temporaryCropCount++
            try {
                val cropOcrStarted = System.currentTimeMillis()
                val ocr = runCatching {
                    com.arspos.anglerriausyndicate.SmartProductInput.analyzeMultiOrientation(crop)
                }.getOrElse {
                    com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
                        "SCAN_OCR_ERROR",
                        it.message ?: it::class.java.simpleName,
                        mapOf(
                            "session" to diagnosticSession,
                            "object" to index,
                            "proposalId" to proposal.proposalId,
                            "proposalType" to proposal.proposalType
                        )
                    )
                    com.arspos.anglerriausyndicate.SmartProductInput.Draft()
                }
                val cropOcrDurationMs = System.currentTimeMillis() - cropOcrStarted

                var sceneOcrDurationMs = 0L
                val effectiveOcr = if (allowSceneOcr && ocr.barcode.isBlank()) {
                    val sceneOcrStarted = System.currentTimeMillis()
                    val value = runCatching {
                        com.arspos.anglerriausyndicate.SmartProductInput.analyzeMultiOrientation(bitmap)
                    }.getOrElse { ocr }
                    sceneOcrDurationMs = System.currentTimeMillis() - sceneOcrStarted
                    value
                } else ocr

                val longLane = proposal.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_LONG ||
                    proposal.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_RESCUE
                val laneProducts = if (longLane) longProducts else normalProducts
                val lanePhotos = if (longLane) longPhotos else normalPhotos
                if (laneProducts.isEmpty()) return null

                val scoringStarted = System.currentTimeMillis()

                // V8.4.13 two-stage long matcher:
                // 1) cheap coarse ranking by PRODUCT, not by every reference;
                // 2) expensive rod matcher only for the top three coarse product identities.
                // Final recognition thresholds remain unchanged or stricter.
                var coarseDurationMs = 0L
                var coarseProducts = 0
                var shortlistedProducts = 0
                val scoringPhotos = if (longLane && lanePhotos.size > 4) {
                    val coarseStarted = System.currentTimeMillis()
                    val coarse = com.arspos.anglerriausyndicate.ProductVisualMatcher.scoreBatchCoarse(
                        crop,
                        lanePhotos.map { it.localPath }
                    )
                    coarseDurationMs = System.currentTimeMillis() - coarseStarted

                    val productRanks = lanePhotos.groupBy { it.productId }
                        .map { (productId, group) ->
                            val best = group.maxOfOrNull { coarse[it.localPath] ?: 0 } ?: 0
                            productId to best
                        }
                        .sortedByDescending { it.second }

                    coarseProducts = productRanks.size
                    // V8.4.13: shortlist by product, then preserve identity-bearing
                    // rod anchors. V8.4.9 kept only the top 1-2 coarse frames per
                    // product; that could discard LONG_HANDLE/LONG_SIDE/LONG_TIP
                    // and make two visually similar rods tie. We keep at most the
                    // two strongest product identities and one best frame for each
                    // anchor angle. Rescue remains one-frame evidence-only.
                    val keepProducts = productRanks.take(2).map { it.first }.toSet()
                    shortlistedProducts = keepProducts.size
                    val anchorOrder = listOf(
                        "LONG_HANDLE", "LONG_SIDE", "LONG_TIP", "LONG_FULL_A", "LONG_FULL_B"
                    )

                    lanePhotos
                        .filter { keepProducts.contains(it.productId) }
                        .groupBy { it.productId }
                        .values
                        .flatMap { group ->
                            if (proposal.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_RESCUE) {
                                group.sortedByDescending { coarse[it.localPath] ?: 0 }.take(1)
                            } else {
                                val byAngle = group.groupBy { it.captureAngle.uppercase() }
                                val anchors = anchorOrder.mapNotNull { angle ->
                                    byAngle[angle]?.maxByOrNull { coarse[it.localPath] ?: 0 }
                                }
                                val fallback = group
                                    .filterNot { photo -> anchors.any { it.localPath == photo.localPath } }
                                    .sortedByDescending { coarse[it.localPath] ?: 0 }
                                (anchors + fallback).take(5)
                            }
                        }
                } else {
                    coarseProducts = lanePhotos.map { it.productId }.distinct().size
                    shortlistedProducts = coarseProducts
                    lanePhotos
                }

                val laneScores = if (longLane && proposal.proposalType != com.arspos.anglerriausyndicate.ScanProposal.TYPE_RESCUE) {
                    com.arspos.anglerriausyndicate.ProductVisualMatcher.scoreBatchRodAnchorAware(
                        crop,
                        scoringPhotos.map { photo ->
                            com.arspos.anglerriausyndicate.ProductVisualMatcher.RodAnchorReference(
                                path = photo.localPath,
                                angle = photo.captureAngle
                            )
                        }
                    )
                } else {
                    com.arspos.anglerriausyndicate.ProductVisualMatcher.scoreBatch(
                        crop,
                        scoringPhotos.map { it.localPath },
                        longObject = longLane
                    )
                }
                val references = scoringPhotos.mapNotNull { photo ->
                    val product = productsById[photo.productId] ?: return@mapNotNull null
                    com.arspos.anglerriausyndicate.VisualProductMatch(
                        product,
                        photo,
                        laneScores[photo.localPath] ?: 0
                    )
                }
                val heapAfterScore = sampleHeap()

                com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
                    "SCAN_SCORING",
                    "V8.4.17 rod-claim-safe scoring + evidence integrity binding completed",
                    mapOf(
                        "session" to diagnosticSession,
                        "object" to index,
                        "proposalId" to proposal.proposalId,
                        "proposalType" to proposal.proposalType,
                        "source" to proposal.source,
                        "lineageId" to proposal.lineageId,
                        "physicalInstanceHint" to proposal.physicalInstanceHint,
                        "rotation" to proposal.rotationDegrees,
                        "normalRefs" to if (longLane) 0 else scoringPhotos.size,
                        "longRefs" to if (longLane) scoringPhotos.size else 0,
                        "availableLongRefs" to if (longLane) lanePhotos.size else 0,
                        "coarseProducts" to if (longLane) coarseProducts else 0,
                        "shortlistedProducts" to if (longLane) shortlistedProducts else 0,
                        "coarseDurationMs" to if (longLane) coarseDurationMs else 0L,
                        "rodStructureScore" to proposal.rodStructureScore,
                        "axisAngleDeg" to proposal.axisAngleDeg,
                        "axisConfidence" to proposal.axisConfidence,
                        "axisWidthPx" to proposal.axisWidthPx,
                        "measuredWidthMedianPx" to proposal.measuredWidthMedianPx,
                        "measuredWidthP10Px" to proposal.measuredWidthP10Px,
                        "measuredWidthP90Px" to proposal.measuredWidthP90Px,
                        "widthVariance" to proposal.widthVariance,
                        "taperRatio" to proposal.taperRatio,
                        "curvatureScore" to proposal.curvatureScore,
                        "physicalObjectId" to proposal.physicalObjectId,
                        "hypothesesMerged" to proposal.hypothesesMerged,
                        "physicalMergeReason" to proposal.physicalMergeReason,
                        "rodPositiveEvidence" to proposal.rodPositiveEvidence,
                        "branchPenalty" to proposal.branchPenalty,
                        "beamPenalty" to proposal.beamPenalty,
                        "floorLinePenalty" to proposal.floorLinePenalty,
                        "shadowPenalty" to proposal.shadowPenalty,
                        "rodAuthenticityScore" to proposal.rodAuthenticityScore,
                        "rodAuthenticityState" to proposal.rodAuthenticityState,
                        "trustedRod" to proposal.trustedRod,
                        "rodClaim" to proposal.rodClaim,
                        "rodClaimScore" to proposal.rodClaimScore,
                        "crossThroughIntersections" to proposal.crossThroughIntersections,
                        "trueBranchIntersections" to proposal.trueBranchIntersections,
                        "orientedLongCrop" to (longLane && proposal.hasResolvedAxis),
                        "normalOwnershipConflict" to proposal.normalOwnershipConflict,
                        "crossCategoryConflict" to proposal.crossCategoryConflict,
                        "crossCategoryConflictScore" to proposal.crossCategoryConflictScore,
                        "rodTrackHits" to proposal.rodTrackHits,
                        "visualOnlyBlocked" to proposal.visualOnlyBlocked,
                        "crossCategoryReason" to proposal.crossCategoryReason,
                        "cropOcrDurationMs" to cropOcrDurationMs,
                        "sceneOcrDurationMs" to sceneOcrDurationMs,
                        "durationMs" to (System.currentTimeMillis() - scoringStarted),
                        "heapAfterScoreMb" to heapAfterScore,
                        "peakHeapMb" to peakHeapMb
                    )
                )

                val decisionStarted = System.currentTimeMillis()
                val recognized = SmartRecognitionEngine.recognize(
                    crop = crop,
                    products = laneProducts,
                    references = references,
                    ocr = effectiveOcr,
                    identityProfiles = identityProfiles.filterKeys { id -> laneProducts.any { it.id == id } },
                    verifiedProductIds = verifiedProductIds,
                    visualOnlyAllowed = !proposal.visualOnlyBlocked
                )
                val decisionDurationMs = System.currentTimeMillis() - decisionStarted

                val recognitionMatch = recognized.match
                val quantityMatch = if (proposal.quantityEligible) recognitionMatch else null
                val top = recognized.candidates.firstOrNull()
                val eventReason = if (!proposal.quantityEligible && recognitionMatch != null) {
                    val gate = when (proposal.proposalType) {
                        com.arspos.anglerriausyndicate.ScanProposal.TYPE_RESCUE -> "RESCUE EVIDENCE ONLY"
                        com.arspos.anglerriausyndicate.ScanProposal.TYPE_LONG -> "UNTRUSTED LONG EVIDENCE ONLY"
                        else -> "EVIDENCE ONLY"
                    }
                    "$gate — tidak boleh menambah quantity • ${recognized.reason}"
                } else recognized.reason

                com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
                    "SCAN_OBJECT",
                    eventReason,
                    mapOf(
                        "session" to diagnosticSession,
                        "object" to index,
                        "proposalId" to proposal.proposalId,
                        "source" to proposal.source,
                        "proposalType" to proposal.proposalType,
                        "rotation" to proposal.rotationDegrees,
                        "lineageId" to proposal.lineageId,
                        "physicalInstanceHint" to proposal.physicalInstanceHint,
                        "quantityEligible" to proposal.quantityEligible,
                        "bridgeSuppressed" to proposal.bridgeSuppressed,
                        "normalOwnershipConflict" to proposal.normalOwnershipConflict,
                        "crossCategoryConflict" to proposal.crossCategoryConflict,
                        "crossCategoryConflictScore" to proposal.crossCategoryConflictScore,
                        "rodTrackHits" to proposal.rodTrackHits,
                        "visualOnlyBlocked" to proposal.visualOnlyBlocked,
                        "crossCategoryReason" to proposal.crossCategoryReason,
                        "rodStructureScore" to proposal.rodStructureScore,
                        "axisAngleDeg" to proposal.axisAngleDeg,
                        "axisConfidence" to proposal.axisConfidence,
                        "axisWidthPx" to proposal.axisWidthPx,
                        "measuredWidthMedianPx" to proposal.measuredWidthMedianPx,
                        "measuredWidthP10Px" to proposal.measuredWidthP10Px,
                        "measuredWidthP90Px" to proposal.measuredWidthP90Px,
                        "widthVariance" to proposal.widthVariance,
                        "taperRatio" to proposal.taperRatio,
                        "curvatureScore" to proposal.curvatureScore,
                        "physicalObjectId" to proposal.physicalObjectId,
                        "hypothesesMerged" to proposal.hypothesesMerged,
                        "physicalMergeReason" to proposal.physicalMergeReason,
                        "rodPositiveEvidence" to proposal.rodPositiveEvidence,
                        "branchPenalty" to proposal.branchPenalty,
                        "beamPenalty" to proposal.beamPenalty,
                        "floorLinePenalty" to proposal.floorLinePenalty,
                        "shadowPenalty" to proposal.shadowPenalty,
                        "rodAuthenticityScore" to proposal.rodAuthenticityScore,
                        "rodAuthenticityState" to proposal.rodAuthenticityState,
                        "trustedRod" to proposal.trustedRod,
                        "orientedLongCrop" to (longLane && proposal.hasResolvedAxis),
                        "originalBounds" to "${proposal.originalBounds.left},${proposal.originalBounds.top},${proposal.originalBounds.right},${proposal.originalBounds.bottom}",
                        "normalizedBounds" to "${proposal.normalizedBounds.left},${proposal.normalizedBounds.top},${proposal.normalizedBounds.right},${proposal.normalizedBounds.bottom}",
                        "ocr" to recognized.ocrText.take(180),
                        "barcode" to recognized.barcode,
                        "candidate" to (top?.product?.name ?: ""),
                        "visual" to (top?.visualScore ?: 0),
                        "text" to (top?.textScore ?: 0),
                        "final" to (top?.finalScore ?: 0),
                        "secondFinal" to recognized.secondFinalScore,
                        "margin" to recognized.margin,
                        "anchor" to recognized.anchorScore,
                        "anchorMargin" to recognized.anchorMargin,
                        "decisionRuleId" to recognized.ruleId,
                        "decisionDurationMs" to decisionDurationMs,
                        "totalObjectDurationMs" to (System.currentTimeMillis() - objectStarted),
                        "verified" to (top?.masterVerified ?: false),
                        "recognitionAccepted" to (recognitionMatch != null),
                        "quantityAccepted" to (quantityMatch != null)
                    )
                )

                com.arspos.anglerriausyndicate.ArsVisualEvidenceRecorder.recordDecision(
                    session = diagnosticSession,
                    objectIndex = index,
                    proposal = proposal,
                    crop = crop,
                    decisionRuleId = recognized.ruleId,
                    decisionReason = eventReason,
                    candidate = top?.product?.name.orEmpty(),
                    secondCandidate = recognized.candidates.getOrNull(1)?.product?.name.orEmpty(),
                    visualScore = top?.visualScore ?: 0,
                    finalScore = top?.finalScore ?: 0,
                    margin = recognized.margin,
                    anchorScore = recognized.anchorScore,
                    anchorMargin = recognized.anchorMargin,
                    recognitionAccepted = recognitionMatch != null,
                    quantityAccepted = quantityMatch != null,
                    barcode = recognized.barcode,
                    ocrText = recognized.ocrText
                )

                return com.arspos.anglerriausyndicate.VisualScanObject(
                    index = index,
                    bounds = android.graphics.Rect(bounds),
                    match = quantityMatch,
                    bestScore = recognized.finalScore,
                    visualScore = recognized.visualScore,
                    textScore = recognized.textScore,
                    finalScore = recognized.finalScore,
                    ocrText = recognized.ocrText,
                    barcode = recognized.barcode,
                    reason = eventReason,
                    candidates = recognized.candidates,
                    proposalId = proposal.proposalId,
                    proposalSource = proposal.source,
                    proposalType = proposal.proposalType,
                    proposalRotation = proposal.rotationDegrees,
                    lineageId = proposal.lineageId,
                    physicalInstanceHint = proposal.physicalInstanceHint,
                    quantityEligible = proposal.quantityEligible,
                    bridgeSuppressed = proposal.bridgeSuppressed,
                    originalBounds = android.graphics.Rect(proposal.originalBounds),
                    normalizedBounds = android.graphics.Rect(proposal.normalizedBounds),
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height,
                    decisionRuleId = recognized.ruleId
                )
            } finally {
                if (!crop.isRecycled) {
                    crop.recycle()
                    recycledCropCount++
                }
            }
        }

        proposals.forEachIndexed { index, proposal ->
            analyze(proposal, index + 1, allowSceneOcr = false)?.let { result += it }
        }

        // Long full-frame rescue is diagnostics/evidence only. It can never
        // create a cashier quantity. Also use identity profile isLongObject,
        // not only product-name heuristics, when deciding whether a long SKU
        // was already accepted.
        val hasVerifiedLongMaster = longProducts.any { verifiedProductIds.contains(it.id) }
        val acceptedLongAlready = result.any { item ->
            val productId = item.match?.product?.id ?: return@any false
            longProductIds.contains(productId) && verifiedProductIds.contains(productId)
        }
        val longProposalCount = proposals.count { it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_LONG }
        val resolvedAxisCount = mergeAxisCount
        // V8.4.17 Rescue Gate V2: raw/resolved geometry alone must not suppress
        // rescue. Only a TRUSTED physical rod is a hard blocker. ROD_UNCERTAIN
        // remains visible to telemetry but may still trigger the evidence-only
        // rescue pass. Rescue is quantityEligible=false, so this cannot lower a
        // recognition threshold or fabricate an inventory unit.
        val rescueBlockingTrustedRodTracks = trustedRodTracksAfterMerge
        val uncertainRodTracksForRescue = uncertainRodTracksAfterMerge
        val nonTrustedResolvedAxes = (resolvedAxisCount - rescueBlockingTrustedRodTracks).coerceAtLeast(0)
        val rescueAllowed = hasVerifiedLongMaster && !acceptedLongAlready && rescueBlockingTrustedRodTracks == 0
        val rescueReason = when {
            rescueAllowed -> ""
            acceptedLongAlready -> "LONG_ALREADY_ACCEPTED"
            !hasVerifiedLongMaster -> "NO_VERIFIED_LONG_MASTER"
            rescueBlockingTrustedRodTracks > 0 -> "TRUSTED_ROD_TRACK_PRESENT"
            else -> "NOT_NEEDED"
        }
        com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
            "SCAN_RESCUE_POLICY", "Full-frame rescue eligibility V2",
            mapOf(
                "session" to diagnosticSession,
                "resolvedAxes" to resolvedAxisCount,
                "rescueBlockingTrustedRodTracks" to rescueBlockingTrustedRodTracks,
                "uncertainRodTracksForRescue" to uncertainRodTracksForRescue,
                "nonTrustedResolvedAxes" to nonTrustedResolvedAxes,
                "longProposalCount" to longProposalCount,
                "fullFrameRescueUsed" to rescueAllowed,
                "rescueSkippedReason" to rescueReason
            )
        )
        if (rescueAllowed) {
            // V8.4.13: one conditional full-frame evidence pass only. Multi-rod
            // scenes do not run rescue because a whole-frame score cannot prove
            // quantity or physical separation and was a major latency source.
            val rescueRects = listOf(
                android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            )
            rescueRects.forEachIndexed { offset, rect ->
                val rescue = com.arspos.anglerriausyndicate.ScanProposal(
                    proposalId = "RESCUE-${offset + 1}",
                    source = com.arspos.anglerriausyndicate.ScanProposal.SOURCE_RESCUE,
                    proposalType = com.arspos.anglerriausyndicate.ScanProposal.TYPE_RESCUE,
                    originalBounds = android.graphics.Rect(rect),
                    normalizedBounds = com.arspos.anglerriausyndicate.ScanCoordinateNormalizer.normalize(rect, 0, bitmap.width, bitmap.height),
                    rotationDegrees = 0,
                    lineageId = "RESCUE-EVIDENCE",
                    physicalInstanceHint = "RESCUE-EVIDENCE",
                    quantityEligible = false,
                    bridgeSuppressed = false,
                    orientation = "FULL_FRAME",
                    strength = 20
                )
                analyze(rescue, result.size + offset + 1, allowSceneOcr = false)
            }
        }

        val aggregation = com.arspos.anglerriausyndicate.ScanResultAggregator.aggregate(result.take(12))
        com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
            "SCAN_AGGREGATION",
            "Physical-instance aggregation + quantity safety completed",
            mapOf(
                "session" to diagnosticSession,
                "before" to aggregation.summary.before,
                "after" to aggregation.summary.after,
                "merged" to aggregation.summary.merged,
                "longGroups" to aggregation.summary.longGroups,
                "lineageMerges" to aggregation.summary.lineageMerges,
                "quantitySuppressed" to aggregation.summary.quantitySuppressed,
                "distinctLongInstances" to aggregation.summary.distinctLongInstances,
                "bridgeSuppressed" to proposals.count { it.bridgeSuppressed || it.proposalType == com.arspos.anglerriausyndicate.ScanProposal.TYPE_AMBIGUOUS_CONTAINER }
            )
        )
        if (aggregation.summary.quantitySuppressed > 0) {
            com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
                "SCAN_QUANTITY_SAFETY",
                "Duplicate long-object quantity suppressed",
                mapOf(
                    "session" to diagnosticSession,
                    "suppressed" to aggregation.summary.quantitySuppressed,
                    "finalObjects" to aggregation.summary.after
                )
            )
        }
        val nativeAfterScanMb = android.os.Debug.getNativeHeapAllocatedSize() / 1024L / 1024L
        val memoryInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(memoryInfo)
        val totalPssMb = memoryInfo.totalPss.toLong() / 1024L

        // Rolling baseline is process-local on purpose. A single scan may end
        // above or below its start because Android/native image libraries keep
        // reusable allocations. What matters for leak pressure is persistent
        // growth relative to a stable baseline/high-water across many scans.
        if (scannerNativeBaselineMb == null) scannerNativeBaselineMb = nativeBeforeScanMb
        scannerScanCount += 1
        if (scannerScanCount == 5) scannerWarmNativeBaselineMb = nativeBeforeScanMb
        scannerNativeHighWaterMb = maxOf(scannerNativeHighWaterMb, nativeAfterScanMb)
        scannerPssHighWaterMb = maxOf(scannerPssHighWaterMb, totalPssMb)
        val nativeBaselineMb = scannerNativeBaselineMb ?: nativeAfterScanMb
        val nativeGrowthFromBaselineMb = nativeAfterScanMb - nativeBaselineMb
        val nativePressureWarning = scannerScanCount >= 5 && nativeGrowthFromBaselineMb >= 64L

        com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
            "SCAN_MEMORY",
            "V8.4.17 scanner + visual-evidence memory snapshot",
            mapOf(
                "session" to diagnosticSession,
                "heapAfterScanMb" to sampleHeap(),
                "peakHeapMb" to peakHeapMb,
                "heapMaxMb" to (Runtime.getRuntime().maxMemory() / 1024L / 1024L),
                "nativeBeforeScanMb" to nativeBeforeScanMb,
                "nativeHeapMb" to nativeAfterScanMb,
                "nativeDeltaMb" to (nativeAfterScanMb - nativeBeforeScanMb),
                "nativeBaselineMb" to nativeBaselineMb,
                "warmNativeBaselineMb" to scannerWarmNativeBaselineMb,
                "nativeBeforeGrowthFromWarmMb" to scannerWarmNativeBaselineMb?.let { nativeBeforeScanMb - it },
                "nativeWarningBasis" to "COLD_BASELINE_DELTA_NOT_OS_PRESSURE",
                "nativeGrowthFromBaselineMb" to nativeGrowthFromBaselineMb,
                "nativeHighWaterMb" to scannerNativeHighWaterMb,
                "totalPssMb" to totalPssMb,
                "pssHighWaterMb" to scannerPssHighWaterMb,
                "scannerScanCount" to scannerScanCount,
                "nativePressureWarning" to nativePressureWarning,
                "temporaryCrops" to temporaryCropCount,
                "recycledCrops" to recycledCropCount
            )
        )
        return aggregation.objects
    }

    private fun heapUsedMb(): Long =
        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L / 1024L

    /** V8.4.5: keep a diverse but bounded reference set per product.
     * Master Studio can store many video frames; scoring every full-resolution
     * frame for every proposal causes native bitmap pressure on mobile devices.
     */
    private fun selectRecognitionReferences(photos: List<com.arspos.anglerriausyndicate.data.db.ProductPhotoEntity>): List<com.arspos.anglerriausyndicate.data.db.ProductPhotoEntity> {
        return photos.groupBy { it.productId }.values.flatMap { productPhotos ->
            val selected = LinkedHashMap<Long, com.arspos.anglerriausyndicate.data.db.ProductPhotoEntity>()
            productPhotos.filter { it.isPrimary }.sortedByDescending { it.createdAt }.take(1).forEach { selected[it.id] = it }
            productPhotos
                .groupBy { it.captureAngle.ifBlank { "UNKNOWN" } }
                .values
                .forEach { group ->
                    group.sortedByDescending { it.createdAt }.take(1).forEach { selected[it.id] = it }
                }
            productPhotos.sortedByDescending { it.createdAt }.forEach {
                if (selected.size < 6) selected[it.id] = it
            }
            selected.values.take(6)
        }
    }

    private fun rectOverlap(a: android.graphics.Rect, b: android.graphics.Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toLong() * (bottom - top).toLong()
        val areaA = a.width().toLong() * a.height().toLong()
        val areaB = b.width().toLong() * b.height().toLong()
        val smaller = minOf(areaA, areaB)
        return if (smaller <= 0L) 0f else intersection.toFloat() / smaller.toFloat()
    }

    private fun centeredRect(width: Int, height: Int, fraction: Float): android.graphics.Rect {
        val f = fraction.coerceIn(0.50f, 1f)
        val w = (width * f).toInt().coerceAtLeast(24).coerceAtMost(width)
        val h = (height * f).toInt().coerceAtLeast(24).coerceAtMost(height)
        val left = (width - w) / 2
        val top = (height - h) / 2
        return android.graphics.Rect(left, top, left + w, top + h)
    }

    private fun isLikelyLongProduct(product: ProductEntity): Boolean {
        val hay = "${product.name} ${product.category} ${product.brand.orEmpty()} ${product.variant.orEmpty()}".uppercase()
        return listOf("JORAN", "ROD", "PANCING FIBER", "TELESCOPIC", "TELESCOP", "STICK ROD").any { hay.contains(it) }
    }

    suspend fun verifyProductMaster(productId: Long, bitmap: android.graphics.Bitmap): MasterVerificationResult {
        val product = dao.product(productId) ?: return MasterVerificationResult(
            productId = productId,
            productName = "",
            sku = "",
            barcode = "",
            referenceCount = 0,
            visualScore = 0,
            textScore = 0,
            finalScore = 0,
            ocrText = "",
            detectedBarcode = "",
            accepted = false,
            reason = "Produk tidak ditemukan"
        )
        val profile = ProductIdentityProfileCodec.decode(product.id, dao.setting("identity_profile_${product.id}"))
        val refs = selectRecognitionReferences(dao.allProductPhotos().filter { it.productId == productId })
            .map { photo ->
                VisualProductMatch(
                    product = product,
                    photo = photo,
                    score = ProductVisualMatcher.score(bitmap, photo.localPath, longObject = profile?.isLongObject == true)
                )
            }
        if (refs.isEmpty()) return MasterVerificationResult(
            productId = product.id, productName = product.name, sku = product.sku,
            barcode = product.barcode.orEmpty(), referenceCount = 0, visualScore = 0,
            textScore = 0, finalScore = 0, ocrText = "", detectedBarcode = "",
            accepted = false, reason = "Belum ada referensi master visual"
        )
        val ocr = runCatching { SmartProductInput.analyzeMultiOrientation(bitmap) }
            .getOrElse { SmartProductInput.Draft() }
        val recognized = SmartRecognitionEngine.recognize(
            crop = bitmap, products = listOf(product), references = refs, ocr = ocr,
            identityProfiles = profile?.let { mapOf(product.id to it) }.orEmpty()
        )
        val exactBarcode = ocr.barcode.isNotBlank() &&
            product.barcode.orEmpty().replace(Regex("\\D"), "") == ocr.barcode.replace(Regex("\\D"), "")
        val longObject = profile?.isLongObject == true || isLikelyLongProduct(product)
        val accepted = (refs.size >= (if (longObject) 5 else 3)) && when {
            exactBarcode && recognized.visualScore >= 45 -> true
            recognized.textScore >= 82 && recognized.visualScore >= 30 -> true
            recognized.textScore >= 72 && recognized.visualScore >= 40 -> true
            longObject && recognized.visualScore >= 72 -> true
            recognized.visualScore >= 82 -> true
            else -> false
        }
        val reason = when {
            refs.size < if (longObject) 5 else 3 -> "Referensi terlalu sedikit; minimal ${if (longObject) 5 else 3} frame master diperlukan"
            accepted && exactBarcode -> "Barcode cocok + visual master terverifikasi"
            accepted && recognized.textScore >= 82 -> "Identitas master + OCR + visual terverifikasi"
            accepted && recognized.textScore >= 72 -> "OCR + visual master terverifikasi"
            accepted -> "Visual master terverifikasi"
            else -> "Bukti master belum cukup kuat; tambah frame atau foto uji lebih jelas"
        }
        if (accepted) {
            dao.setSetting(SettingEntity("master_verified_${product.id}", "1"))
            dao.setSetting(SettingEntity("master_verified_score_${product.id}", recognized.finalScore.toString()))
            dao.setSetting(SettingEntity("master_verified_at_${product.id}", System.currentTimeMillis().toString()))
        }
        com.arspos.anglerriausyndicate.ArsFlightRecorder.event(
            "MASTER_VERIFY",
            reason,
            mapOf(
                "productId" to product.id,
                "product" to product.name,
                "refs" to refs.size,
                "visual" to recognized.visualScore,
                "text" to recognized.textScore,
                "final" to recognized.finalScore,
                "accepted" to accepted
            )
        )
        return MasterVerificationResult(
            productId = product.id, productName = product.name, sku = product.sku,
            barcode = product.barcode.orEmpty(), referenceCount = refs.size,
            visualScore = recognized.visualScore, textScore = recognized.textScore,
            finalScore = recognized.finalScore, ocrText = ocr.rawText,
            detectedBarcode = ocr.barcode, accepted = accepted, reason = reason
        )
    }

    private suspend fun loadVerifiedProductIds(): Set<Long> =
        dao.masterVerificationSettings().mapNotNull { setting ->
            if (setting.key.startsWith("master_verified_") && setting.value == "1") {
                setting.key.removePrefix("master_verified_").toLongOrNull()
            } else null
        }.toSet()

    private suspend fun loadIdentityProfiles(): Map<Long, ProductIdentityProfile> =
        dao.identityProfileSettings().mapNotNull { setting ->
            val id = setting.key.removePrefix("identity_profile_").toLongOrNull() ?: return@mapNotNull null
            ProductIdentityProfileCodec.decode(id, setting.value)?.let { id to it }
        }.toMap()

    suspend fun mergeProductIdentityProfile(
        productId: Long,
        rawText: String = "",
        brand: String = "",
        name: String = "",
        variant: String = "",
        modelCode: String = "",
        barcode: String = "",
        recognitionMode: String = "HYBRID",
        isLongObject: Boolean = false
    ) {
        val key = "identity_profile_$productId"
        val old = ProductIdentityProfileCodec.decode(productId, dao.setting(key))
        val mergedText = (listOf(old?.rawText.orEmpty(), rawText)
            .flatMap { it.lines() }
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 2 }
            .distinct()
            .takeLast(80)
            .joinToString("\n"))
        val mergedBrand = if (brand.isNotBlank()) brand.trim() else old?.brand.orEmpty()
        val mergedName = if (name.isNotBlank()) name.trim() else old?.name.orEmpty()
        val mergedVariant = if (variant.isNotBlank()) variant.trim() else old?.variant.orEmpty()
        val mergedModel = if (modelCode.isNotBlank()) modelCode.trim() else old?.modelCode.orEmpty()
        val mergedBarcode = if (barcode.isNotBlank()) barcode.trim() else old?.barcode.orEmpty()
        val aliasSource = buildList {
            addAll(old?.aliases.orEmpty())
            addAll(rawText.lines())
            add(mergedBrand)
            add(mergedName)
            add(mergedVariant)
            add(mergedModel)
        }
        val aliases = aliasSource
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 2 }
            .distinct()
            .takeLast(80)

        val oldLong = old?.isLongObject ?: false
        val merged = ProductIdentityProfile(
            productId = productId,
            rawText = mergedText,
            brand = mergedBrand,
            name = mergedName,
            variant = mergedVariant,
            modelCode = mergedModel,
            barcode = mergedBarcode,
            aliases = aliases,
            recognitionMode = recognitionMode.ifBlank { old?.recognitionMode ?: "HYBRID" },
            isLongObject = isLongObject || oldLong
        )
        dao.setSetting(SettingEntity(key, ProductIdentityProfileCodec.encode(merged)))
    }

    suspend fun productIdentityProfile(productId: Long): ProductIdentityProfile? =
        ProductIdentityProfileCodec.decode(productId, dao.setting("identity_profile_$productId"))

    suspend fun setProductRecognitionMode(productId: Long, mode: String, isLongObject: Boolean) {
        val old = productIdentityProfile(productId)
        val profile = (old ?: ProductIdentityProfile(productId = productId)).copy(
            recognitionMode = mode.ifBlank { "HYBRID" },
            isLongObject = isLongObject,
            updatedAt = System.currentTimeMillis()
        )
        dao.setSetting(SettingEntity("identity_profile_$productId", ProductIdentityProfileCodec.encode(profile)))
        invalidateMasterVerification(productId)
    }

    suspend fun clearProductIdentityProfile(productId: Long) {
        dao.setSetting(SettingEntity("identity_profile_$productId", ""))
    }

    suspend fun isMasterVerified(productId:Long):Boolean = dao.setting("master_verified_${productId}") == "1"
    suspend fun invalidateMasterVerification(productId:Long) { dao.setSetting(SettingEntity("master_verified_${productId}", "0")) }

    suspend fun addProductPhoto(productId:Long, localPath:String, primary:Boolean=false, captureAngle:String="UNKNOWN"):Long {
        if(primary) dao.clearPrimaryPhoto(productId)
        return dao.insertProductPhoto(ProductPhotoEntity(productId=productId, localPath=localPath, isPrimary=primary, captureAngle=captureAngle))
    }

    suspend fun clearProductPhotoAngle(productId:Long, angle:String) = dao.deleteProductPhotosForAngle(productId, angle)

    suspend fun setPrimaryProductPhoto(productId:Long, photoId:Long) {
        dao.clearPrimaryPhoto(productId)
        dao.setPrimaryPhoto(photoId)
    }

    suspend fun deleteProductPhoto(photo:ProductPhotoEntity) = dao.deleteProductPhoto(photo)

    suspend fun stockIn(productId:Long,qty:Int,cost:Long,supplier:String?,invoice:String?) {
        require(qty>0){"Jumlah harus > 0"}
        require(cost>=0){"Harga modal tidak valid"}
        db.withTransaction{
            val p=dao.product(productId) ?: error("Produk tidak ditemukan")
            val receipt=dao.insertReceipt(StockReceiptEntity(supplier=supplier,invoice=invoice,totalCost=cost*qty))
            dao.insertReceiptItems(listOf(StockReceiptItemEntity(0,receipt,productId,qty,cost)))
            dao.changeStock(productId,qty)
            dao.insertMovement(StockMovementEntity(
                productId=productId,
                type="PURCHASE",
                quantity=qty,
                stockBefore=p.stock,
                stockAfter=p.stock+qty,
                note=invoice
            ))
            dao.audit(AuditLogEntity(action="STOCK_IN",referenceId=receipt,detail="${p.name} +$qty"))
        }
    }

    suspend fun cashOut(amount:Long,category:String,description:String?) {
        require(amount>0){"Nominal harus > 0"}
        dao.insertCash(CashTransactionEntity(type="OUT",category=category,amount=amount,description=description))
        dao.audit(AuditLogEntity(action="CASH_OUT",detail="$category Rp $amount"))
    }

    suspend fun checkout(cart:List<CartLine>,paid:Long,method:String):CheckoutResult{
        require(cart.isNotEmpty()){"Keranjang kosong"}
        require(method in setOf("CASH","QRIS","TRANSFER","DEBIT")){"Metode pembayaran tidak valid"}
        val total=cart.sumOf{it.subtotal}
        require(paid>=total){"Uang pembayaran kurang"}
        if(method!="CASH") require(paid==total){"Pembayaran non-CASH harus sesuai total"}
        return db.withTransaction{
            cart.forEach{
                val p=dao.product(it.product.id)?:error("Produk tidak ditemukan")
                require(p.stock>=it.quantity){"Stok ${p.name} tidak mencukupi"}
            }
            val invoice="ARS-"+SimpleDateFormat("yyyyMMdd-HHmmssSSS",Locale.US).format(Date())
            val id=dao.insertSale(SaleEntity(invoiceNumber=invoice,subtotal=total,discount=0,grandTotal=total,
                paidAmount=paid,changeAmount=paid-total,paymentMethod=method))
            dao.insertItems(cart.map{SaleItemEntity(saleId=id,productId=it.product.id,productName=it.product.name,
                quantity=it.quantity,costPrice=it.product.costPrice,sellPrice=it.product.sellPrice,discount=0,subtotal=it.subtotal)})
            cart.forEach{
                val p=dao.product(it.product.id)!!
                dao.changeStock(p.id,-it.quantity)
                dao.insertMovement(StockMovementEntity(
                    productId=p.id,
                    type="SALE",
                    quantity=-it.quantity,
                    stockBefore=p.stock,
                    stockAfter=p.stock-it.quantity,
                    referenceId=id,
                    note=invoice
                ))
            }
            if(method=="CASH") dao.insertCash(CashTransactionEntity(type="IN",category="PENJUALAN",amount=total,referenceId=id))
            dao.audit(AuditLogEntity(action="SALE",referenceId=id,detail="$invoice | $method | Rp $total"))
            CheckoutResult(id,invoice,cart,total,paid,paid-total,method)
        }
    }

    suspend fun processReturn(saleId:Long, reason:String):Long {
        return db.withTransaction {
            val sale=dao.sale(saleId) ?: error("Transaksi tidak ditemukan")
            require(sale.status=="PAID"){"Transaksi tidak dapat diretur"}
            val existing=dao.returnsForSale(saleId).sumOf{it.total}
            require(existing < sale.grandTotal){"Transaksi sudah diretur penuh"}
            val items=dao.saleItems(saleId)
            val refund=items.sumOf{it.subtotal}
            val returnId=dao.insertReturn(ReturnEntity(saleId=saleId,reason=reason,total=refund))
            dao.insertReturnItems(items.map{ReturnItemEntity(returnId=returnId,productId=it.productId,quantity=it.quantity,amount=it.subtotal)})
            items.forEach{
                val p=dao.product(it.productId) ?: error("Produk retur tidak ditemukan")
                val before=p.stock
                dao.changeStock(p.id,it.quantity)
                dao.insertMovement(StockMovementEntity(
                    productId=p.id,
                    type="RETURN",
                    quantity=it.quantity,
                    stockBefore=before,
                    stockAfter=before+it.quantity,
                    referenceId=returnId,
                    note=sale.invoiceNumber
                ))
            }
            if(sale.paymentMethod=="CASH") dao.insertCash(CashTransactionEntity(type="OUT",category="RETUR",amount=refund,referenceId=returnId))
            dao.audit(AuditLogEntity(action="RETURN",referenceId=returnId,detail="${sale.invoiceNumber} | Rp $refund | $reason"))
            refund
        }
    }

    suspend fun profitSummary(start:Long):ProfitSummary{
        val sales=dao.sales().first()
        val relevant=sales.filter{it.status=="PAID"&&it.createdAt>=start}
        val grossSales=relevant.sumOf{it.grandTotal}
        var cost=0L
        relevant.forEach{s->cost+=dao.saleItems(s.id).sumOf{it.costPrice*it.quantity}}
        val returned=relevant.sumOf{s->dao.returnsForSale(s.id).sumOf{it.total}}
        return ProfitSummary(grossSales-returned,cost,(grossSales-returned)-cost)
    }
}
