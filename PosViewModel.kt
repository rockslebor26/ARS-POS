package com.arspos.anglerriausyndicate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arspos.anglerriausyndicate.data.*
import com.arspos.anglerriausyndicate.data.db.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class PosViewModel(app:Application):AndroidViewModel(app){
    private val repo=ProductionRepository(ArsDatabase.get(app))
    val query=MutableStateFlow("")
    val products=query.flatMapLatest(repo::search).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val _cart=MutableStateFlow<List<CartLine>>(emptyList())
    val cart=_cart.asStateFlow()
    private val scanCommitLock = Any()
    private val committedScanSessions = LinkedHashSet<String>()
    val subtotal=_cart.map{it.sumOf(CartLine::subtotal)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0L)
    private val start=Calendar.getInstance().apply{
        set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
    }.timeInMillis
    val revenue=repo.revenue(start).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0L)
    val transactions=repo.transactionCount(start).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val cashBalance=repo.cashBalance().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0L)
    val sales=repo.sales().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val customers=repo.customers().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    init{viewModelScope.launch{repo.seed()}}
    fun search(s:String){query.value=s}
    fun add(p:ProductEntity){
        if(p.stock<=0) return
        val x=_cart.value.find{it.product.id==p.id}
        if(x==null){
            _cart.value=_cart.value+CartLine(p,1)
        }else if(x.quantity < p.stock){
            _cart.value=_cart.value.map{if(it.product.id==p.id)it.copy(quantity=it.quantity+1)else it}
        }
    }
    /**
     * V8.4.17 quantity confirmation + duplicate-session guard.
     * One scan session can mutate the cart only once, even if the operator
     * double-taps CONFIRM. A new scan session may legitimately add more units.
     */
    fun commitScanQuantities(sessionId:String, drafts:List<ScanQuantityDraft>):ScanCartCommitResult = synchronized(scanCommitLock){
        val session = sessionId.trim()
        val requested = drafts.filter { it.quantity > 0 }
        if(session.isBlank()) return@synchronized ScanCartCommitResult(false,false,0,0,"Session scan tidak valid.")
        if(requested.isEmpty()) return@synchronized ScanCartCommitResult(false,false,0,0,"Tidak ada quantity yang dipilih.")
        if(committedScanSessions.contains(session)){
            ArsFlightRecorder.event("SCAN_CART_COMMIT_BLOCKED","Duplicate scan commit prevented",mapOf("session" to session))
            return@synchronized ScanCartCommitResult(false,true,0,0,"Hasil scan ini sudah pernah ditambahkan. Penambahan ulang diblokir.")
        }

        var nextCart = _cart.value
        var added = 0
        var affected = 0
        requested.forEach { draft ->
            val product = draft.product
            if(product.stock <= 0) return@forEach
            val currentQty = nextCart.firstOrNull { it.product.id == product.id }?.quantity ?: 0
            val room = (product.stock - currentQty).coerceAtLeast(0)
            val addQty = draft.quantity.coerceAtMost(room)
            if(addQty <= 0) return@forEach
            nextCart = if(currentQty == 0){
                nextCart + CartLine(product, addQty)
            } else {
                nextCart.map { line -> if(line.product.id == product.id) line.copy(quantity = line.quantity + addQty) else line }
            }
            added += addQty
            affected++
        }

        if(added <= 0){
            return@synchronized ScanCartCommitResult(false,false,0,0,"Quantity tidak dapat ditambahkan karena batas stok.")
        }

        _cart.value = nextCart
        committedScanSessions += session
        while(committedScanSessions.size > 100){
            val first = committedScanSessions.firstOrNull() ?: break
            committedScanSessions.remove(first)
        }
        ArsFlightRecorder.event(
            "SCAN_CART_COMMIT",
            "Confirmed scan quantities committed once",
            mapOf("session" to session,"addedQuantity" to added,"affectedProducts" to affected,"requestedProducts" to requested.size)
        )
        ScanCartCommitResult(true,false,added,affected,"$added item dari hasil scan ditambahkan ke keranjang.")
    }

    fun qty(id:Long,d:Int){
        _cart.value=_cart.value.mapNotNull{line ->
            if(line.product.id!=id) line
            else {
                val next=(line.quantity+d).coerceIn(0,line.product.stock)
                line.copy(quantity=next).takeIf{it.quantity>0}
            }
        }
    }
    fun remove(id:Long){
        _cart.value=_cart.value.filterNot{it.product.id==id}
    }
    fun clear(){_cart.value=emptyList()}
    suspend fun login(u:String,p:String)=repo.login(u,p)
    suspend fun checkout(paid:Long,method:String)=runCatching{repo.checkout(_cart.value,paid,method).also{clear()}}
    suspend fun addProduct(n:String,s:String,b:String?,c:String,cost:Long,price:Long,stock:Int,brand:String?=null,variant:String?=null)=runCatching{repo.addProduct(n,s,b,c,cost,price,stock,brand,variant)}
    fun productPhotos(productId:Long)=repo.productPhotos(productId)
    suspend fun findVisualMatches(bitmap:android.graphics.Bitmap)=repo.visualProductMatches(bitmap)
    suspend fun smartVisualScan(bitmap:android.graphics.Bitmap, diagnosticSession:String="")=repo.smartVisualScan(bitmap, diagnosticSession)
    suspend fun verifyProductMaster(productId:Long,bitmap:android.graphics.Bitmap)=repo.verifyProductMaster(productId,bitmap)
    suspend fun isMasterVerified(productId:Long)=repo.isMasterVerified(productId)
    suspend fun productIdentityProfile(productId:Long)=repo.productIdentityProfile(productId)
    suspend fun setProductRecognitionMode(productId:Long,mode:String,isLongObject:Boolean)=runCatching{repo.setProductRecognitionMode(productId,mode,isLongObject)}
    suspend fun invalidateMasterVerification(productId:Long)=repo.invalidateMasterVerification(productId)
    suspend fun mergeProductIdentityProfile(productId:Long,rawText:String="",brand:String="",name:String="",variant:String="",modelCode:String="",barcode:String="",recognitionMode:String="HYBRID",isLongObject:Boolean=false)=runCatching{repo.mergeProductIdentityProfile(productId,rawText,brand,name,variant,modelCode,barcode,recognitionMode,isLongObject)}
    suspend fun addProductPhoto(productId:Long,path:String,primary:Boolean=false,captureAngle:String="UNKNOWN")=runCatching{repo.addProductPhoto(productId,path,primary,captureAngle)}
    suspend fun clearProductPhotoAngle(productId:Long,angle:String)=runCatching{repo.clearProductPhotoAngle(productId,angle)}
    suspend fun setPrimaryProductPhoto(productId:Long,photoId:Long)=runCatching{repo.setPrimaryProductPhoto(productId,photoId)}
    suspend fun deleteProductPhoto(photo:ProductPhotoEntity)=runCatching{repo.deleteProductPhoto(photo)}
    suspend fun stockIn(productId:Long,qty:Int,cost:Long,supplier:String?,invoice:String?)=runCatching{repo.stockIn(productId,qty,cost,supplier,invoice)}
    suspend fun saleItems(id:Long)=repo.saleItems(id)
    suspend fun processReturn(saleId:Long,reason:String)=runCatching{repo.processReturn(saleId,reason)}
    suspend fun profitSummary():ProfitSummary {
        val start=Calendar.getInstance().apply{
            set(Calendar.HOUR_OF_DAY,0);set(Calendar.MINUTE,0);set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
        }.timeInMillis
        return repo.profitSummary(start)
    }
}
