package com.arspos.anglerriausyndicate

import android.Manifest
import android.app.Application
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.arspos.anglerriausyndicate.data.LoginResult
import com.arspos.anglerriausyndicate.data.PinHasher
import com.arspos.anglerriausyndicate.data.CartLine
import com.arspos.anglerriausyndicate.data.CheckoutResult
import com.arspos.anglerriausyndicate.data.db.ProductEntity
import com.arspos.anglerriausyndicate.data.db.ProductPhotoEntity
import com.arspos.anglerriausyndicate.printer.BluetoothPrinter
import com.arspos.anglerriausyndicate.printer.EscPosReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Black=Color(0xFF080808)
private val Panel=Color(0xFF151515)
private val Gold=Color(0xFFC99A3A)
private val GoldSoft=Color(0xFFE1BC68)
private val Muted=Color(0xFF9A9A9A)

/**
 * Camera helper Phase 4.2.1. Menggunakan TakePicture + FileProvider, bukan
 * kontrak foto URI penuh agar kompatibel dengan lebih banyak aplikasi kamera
 * Android dan tidak bergantung pada thumbnail preview dari kamera.
 */
private fun createCameraUri(context: android.content.Context, prefix: String): Pair<Uri, File> {
    val dir = File(context.cacheDir, "camera_capture").apply { mkdirs() }
    val file = File.createTempFile(prefix, ".jpg", dir)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    return uri to file
}

private fun decodeCameraBitmap(file: File, maxDimension: Int = 1600): Bitmap? {
    if (!file.exists() || file.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null

    var sample = 1
    while (width / sample > maxDimension || height / sample > maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

class MainActivity:ComponentActivity(){
    private val vm by viewModels<PosViewModel>()
    private val printer=BluetoothPrinter()
    private val prefs by lazy{getSharedPreferences("ars_pos",MODE_PRIVATE)}
    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        ArsFlightRecorder.install(this)
        setContent{App(vm,this)}
    }
    fun requestBluetooth(){if(Build.VERSION.SDK_INT>=31)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN),9)}
    fun savePrinter(d:BluetoothDevice){prefs.edit().putString("printer_address",d.address).putString("printer_name",d.name?:"Printer").apply()}
    @SuppressLint("MissingPermission") fun selectedPrinter():BluetoothDevice?{
        val address=prefs.getString("printer_address",null)?:return null
        return printer.pairedDevices().firstOrNull{it.address==address}
    }
    suspend fun print(result:CheckoutResult):String=withContext(Dispatchers.IO){
        val d=selectedPrinter()?:return@withContext "Transaksi tersimpan. Printer belum dipilih."
        printer.print(d,EscPosReceipt.build(result.cart,result.total,result.paid,result.change,result.invoice,result.paymentMethod))
        "Transaksi ${result.invoice} berhasil dicetak."
    }
    suspend fun reprint(invoice:String,items:List<CartLine>,total:Long,paid:Long,change:Long,method:String):String=withContext(Dispatchers.IO){
        val d=selectedPrinter()?:return@withContext "Printer belum dipilih."
        printer.print(d,EscPosReceipt.build(items,total,paid,change,invoice,method));"Cetak ulang $invoice OK"
    }
}

@Composable fun ArsBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(com.arspos.anglerriausyndicate.R.drawable.ars_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f)))
        content()
    }
}

@Composable
fun App(vm: PosViewModel, a: MainActivity) {
    var session by remember { mutableStateOf<LoginResult?>(null) }
    var page by remember { mutableStateOf("home") }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Black,
            surface = Panel,
            primary = Gold,
            onPrimary = Color.Black
        )
    ) {
        val currentSession = session

        if (currentSession == null) {
            Login(vm) { result ->
                session = result
                page = "home"
            }
        } else {
            val navItems = listOf(
                Triple("home", Icons.Default.Home, "Home"),
                Triple("kasir", Icons.Default.ShoppingCart, "Kasir"),
                Triple("produk", Icons.Default.Inventory2, "Produk"),
                Triple("laporan", Icons.Default.Assessment, "Laporan"),
                Triple("printer", Icons.Default.Print, "Printer")
            )

            ArsBackground {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    bottomBar = {
                        NavigationBar(
                            containerColor = Panel.copy(alpha = 0.94f)
                        ) {
                            navItems.forEach { item ->
                                NavigationBarItem(
                                    selected = page == item.first,
                                    onClick = { page = item.first },
                                    icon = { Icon(item.second, contentDescription = item.third) },
                                    label = { Text(item.third, fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (page) {
                            "home" -> Home(vm, currentSession, a)
                            "kasir" -> Cashier(vm, a)
                            "produk" -> Products(vm, a)
                            "laporan" -> Reports(vm, a)
                            "printer" -> Printer(a)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Login(vm: PosViewModel, go: (LoginResult) -> Unit) {
    var u by remember { mutableStateOf("admin") }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Login Fix v1:
    // Logo foreground dihapus karena logo ARS sudah menjadi bagian
    // dari ars_background.png. Ini mencegah dua logo tampil sekaligus.
    ArsBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Tidak ada Image/logo foreground di sini.
            // Logo ARS dari background tetap terlihat di belakang.
            Text(
                "ARS POS",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp
            )

            Text(
                "ANGLER RIAU SYNDICATE",
                color = GoldSoft,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            Text(
                "FISHING STORE • OFFLINE POS",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp
            )

            Spacer(Modifier.height(34.dp))

            OutlinedTextField(
                value = u,
                onValueChange = { u = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Username") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldSoft,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.75f),
                    focusedLabelColor = GoldSoft,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.82f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = GoldSoft
                )
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PIN") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldSoft,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.75f),
                    focusedLabelColor = GoldSoft,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.82f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = GoldSoft
                )
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        vm.login(u, pin).fold(
                            onSuccess = { go(it) },
                            onFailure = { msg = it.message ?: "Login gagal" }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    "LOGIN",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (msg.isNotBlank()) {
                Text(
                    msg,
                    color = GoldSoft,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
fun Header(s: String, subtitle: String = "ANGLER RIAU SYNDICATE") {
    Column(Modifier.padding(18.dp)) {
        Text(s, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = GoldSoft, fontSize = 10.sp)
    }
}

@Composable
fun Home(vm: PosViewModel, s: LoginResult, a: MainActivity) {
    val r by vm.revenue.collectAsState()
    val n by vm.transactions.collectAsState()
    val c by vm.cashBalance.collectAsState()
    var devCenter by remember { mutableStateOf(false) }

    Column {
        Header("DASHBOARD", "${s.displayName} • ${s.role}")
        Card(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(Panel)
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("OMZET HARI INI", color = Color.White.copy(alpha = 0.82f), fontWeight = FontWeight.SemiBold)
                Text(formatRupiah(r), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = GoldSoft)
                Text("Transaksi $n • Kas ${formatRupiah(c)}", color = Color.White.copy(alpha = 0.82f), fontSize = 14.sp)
            }
        }

        OutlinedButton(
            onClick = { devCenter = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("🛠 ARS DEV CENTER • FLIGHT RECORDER")
        }
    }

    if (devCenter) {
        ArsDevCenterDialog(a) { devCenter = false }
    }
}

@Composable
fun Cashier(vm: PosViewModel, a: MainActivity) {
    val ps by vm.products.collectAsState()
    val cart by vm.cart.collectAsState()
    val total by vm.subtotal.collectAsState()
    val query by vm.query.collectAsState()

    var paid by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CASH") }
    var paymentDialog by remember { mutableStateOf(false) }
    var processingPayment by remember { mutableStateOf(false) }
    var successDialog by remember { mutableStateOf<CheckoutResult?>(null) }
    var msg by remember { mutableStateOf("") }

    // Phase 4.3.2.37 V8.4.17: interactive review + safe quantity commit + visual reporting.
    var visualScanDialog by remember { mutableStateOf(false) }
    var scanPreview by remember { mutableStateOf<Bitmap?>(null) }
    var visualMatches by remember { mutableStateOf<List<VisualProductMatch>>(emptyList()) }
    var smartScanObjects by remember { mutableStateOf<List<VisualScanObject>>(emptyList()) }
    var scanningVisual by remember { mutableStateOf(false) }
    var activeScanSession by remember { mutableStateOf("") }
    var selectedScanObjectIndex by remember { mutableStateOf<Int?>(null) }
    var showScanDiagnostics by remember { mutableStateOf(false) }
    var quantityReviewDialog by remember { mutableStateOf(false) }
    var quantityDrafts by remember { mutableStateOf<List<ScanQuantityDraft>>(emptyList()) }
    val scope = rememberCoroutineScope()

    var pendingScanCameraFile by remember { mutableStateOf<File?>(null) }
    val takePictureLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingScanCameraFile
        pendingScanCameraFile = null
        if (success && file != null) {
            val bitmap = runCatching { decodeCameraBitmap(file) }.getOrNull()
            file.delete()
            if (bitmap != null) {
                scanPreview = bitmap
                visualScanDialog = true
                scanningVisual = true
                visualMatches = emptyList()
                smartScanObjects = emptyList()
                val scanSession = ArsFlightRecorder.newSession("smart-scan")
                activeScanSession = scanSession
                selectedScanObjectIndex = null
                showScanDiagnostics = false
                quantityReviewDialog = false
                quantityDrafts = emptyList()
                ArsFlightRecorder.event(
                    "SCAN_START",
                    "Smart Shopping Scan started",
                    mapOf(
                        "session" to scanSession,
                        "previewWidth" to bitmap.width,
                        "previewHeight" to bitmap.height
                    )
                )
                scope.launch {
                    val started = System.currentTimeMillis()
                    val analysisBitmap = runCatching { BitmapSafety.scaledForScan(bitmap) }.getOrNull()
                    try {
                        if (analysisBitmap == null) {
                            ArsFlightRecorder.event("SCAN_ERROR", "Failed to allocate analysis bitmap", mapOf("session" to scanSession))
                            msg = "Scanner tidak dapat menyiapkan gambar. Coba tutup aplikasi lain lalu scan ulang."
                            smartScanObjects = emptyList()
                        } else {
                            withContext(Dispatchers.IO) {
                                ArsVisualEvidenceRecorder.beginSession(scanSession, analysisBitmap)
                            }
                            smartScanObjects = withContext(Dispatchers.Default) { vm.smartVisualScan(analysisBitmap, scanSession) }
                            selectedScanObjectIndex = smartScanObjects.firstOrNull()?.index
                            val scanDuration = System.currentTimeMillis() - started
                            val recognizedCount = smartScanObjects.count { it.match != null }
                            ArsFlightRecorder.event(
                                "SCAN_COMPLETE",
                                "Smart Shopping Scan completed",
                                mapOf(
                                    "session" to scanSession,
                                    "objects" to smartScanObjects.size,
                                    "recognized" to recognizedCount,
                                    "durationMs" to scanDuration
                                )
                            )
                            withContext(Dispatchers.IO) {
                                ArsVisualEvidenceRecorder.completeSession(
                                    session = scanSession,
                                    objects = smartScanObjects.size,
                                    recognized = recognizedCount,
                                    durationMs = scanDuration
                                )
                            }
                        }
                    } catch (oom: OutOfMemoryError) {
                        ArsFlightRecorder.event(
                            "SCAN_FATAL",
                            "OutOfMemoryError during Smart Shopping Scan",
                            mapOf("session" to scanSession, "durationMs" to (System.currentTimeMillis() - started))
                        )
                        smartScanObjects = emptyList()
                        withContext(Dispatchers.IO) {
                            ArsVisualEvidenceRecorder.completeSession(
                                scanSession, 0, 0, System.currentTimeMillis() - started, "FATAL_OOM"
                            )
                        }
                        msg = "Scan dihentikan aman karena memori perangkat penuh. Report sudah dicatat di ARS DEV CENTER."
                    } catch (t: Throwable) {
                        ArsFlightRecorder.event(
                            "SCAN_ERROR",
                            t.message ?: t::class.java.simpleName,
                            mapOf(
                                "session" to scanSession,
                                "exception" to t::class.java.name,
                                "stack" to t.stackTraceToString().take(12000),
                                "durationMs" to (System.currentTimeMillis() - started)
                            )
                        )
                        smartScanObjects = emptyList()
                        withContext(Dispatchers.IO) {
                            ArsVisualEvidenceRecorder.completeSession(
                                scanSession, 0, 0, System.currentTimeMillis() - started, "SCAN_ERROR"
                            )
                        }
                        msg = "Smart Shopping Scan gagal, tetapi aplikasi tetap aman. Buka ARS DEV CENTER untuk report."
                    } finally {
                        analysisBitmap?.recycle()
                        scanningVisual = false
                    }
                }
            } else {
                msg = "Foto tidak dapat dibaca. Silakan coba lagi."
            }
        } else {
            file?.delete()
        }
    }

    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runCatching {
                val (uri, file) = createCameraUri(a, "scan_")
                pendingScanCameraFile = file
                takePictureLauncher.launch(uri)
            }.onFailure {
                pendingScanCameraFile?.delete()
                pendingScanCameraFile = null
                msg = "Kamera tidak dapat dibuka: ${it.message ?: "perangkat tidak mendukung"}"
            }
        } else {
            msg = "Izin kamera diperlukan untuk SCAN FOTO PRODUK."
        }
    }

    fun openVisualScanner() {
        // Phase 4.2.2: setiap scan dimulai sebagai sesi baru.
        // Hapus preview dan kandidat lama SEBELUM kamera dibuka agar hasil
        // scan sebelumnya tidak pernah terbawa ke sesi berikutnya.
        scanPreview = null
        visualMatches = emptyList()
        smartScanObjects = emptyList()
        scanningVisual = false
        activeScanSession = ""
        selectedScanObjectIndex = null
        showScanDiagnostics = false
        quantityReviewDialog = false
        quantityDrafts = emptyList()

        if (ContextCompat.checkSelfPermission(a, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val (uri, file) = createCameraUri(a, "scan_")
                pendingScanCameraFile = file
                takePictureLauncher.launch(uri)
            }.onFailure {
                pendingScanCameraFile?.delete()
                pendingScanCameraFile = null
                msg = "Kamera tidak dapat dibuka: ${it.message ?: "perangkat tidak mendukung"}"
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val paidAmount = paid.toLongOrNull() ?: 0L
    val change = (paidAmount - total).coerceAtLeast(0L)
    val cashEnough = paidAmount >= total

    Column(Modifier.fillMaxSize()) {
        Header("KASIR")

        OutlinedTextField(
            value = query,
            onValueChange = vm::search,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = {
                Text(
                    "Cari nama / SKU / barcode",
                    color = Color.White.copy(alpha = 0.70f)
                )
            },
            singleLine = true
        )

        OutlinedButton(
            onClick = { openVisualScanner() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldSoft),
            border = androidx.compose.foundation.BorderStroke(1.dp, GoldSoft)
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SCAN FOTO PRODUK", fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ps, key = { it.id }) { p ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.add(p) },
                    colors = CardDefaults.cardColors(Panel)
                ) {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${p.sku} • stok ${p.stock}",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                        }
                        Text(formatRupiah(p.sellPrice), color = GoldSoft)
                    }
                }
            }

            if (cart.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(Color(0xFF1B1B1B))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            val totalItems = cart.sumOf { it.quantity }

                            Text(
                                "KERANJANG • $totalItems ITEM",
                                color = GoldSoft,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Spacer(Modifier.height(6.dp))

                            cart.forEach { line ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            line.product.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "Stok tersisa ${line.product.stock - line.quantity} • Harga ${formatRupiah(line.product.sellPrice)}",
                                            color = Color.White.copy(alpha = 0.58f),
                                            fontSize = 10.sp
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { vm.qty(line.product.id, -1) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Remove,
                                                contentDescription = "Kurangi ${line.product.name}",
                                                tint = Color.White
                                            )
                                        }

                                        Text(
                                            "${line.quantity}",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            modifier = Modifier.widthIn(min = 22.dp),
                                            textAlign = TextAlign.Center
                                        )

                                        IconButton(
                                            onClick = { vm.qty(line.product.id, 1) },
                                            enabled = line.quantity < line.product.stock,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = "Tambah ${line.product.name}",
                                                tint = if (line.quantity < line.product.stock) GoldSoft else Muted
                                            )
                                        }

                                        IconButton(
                                            onClick = { vm.remove(line.product.id) },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Hapus ${line.product.name}",
                                                tint = Color(0xFFE57373)
                                            )
                                        }
                                    }
                                }
                                Divider(color = Color.White.copy(alpha = 0.08f))
                            }
                        }
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(Color(0xFF111111))
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                val totalItems = cart.sumOf { it.quantity }

                Text(
                    "JUMLAH BARANG: $totalItems ITEM",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "TOTAL ${formatRupiah(total)}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldSoft
                )

                Button(
                    onClick = {
                        method = "CASH"
                        paid = total.toString()
                        msg = ""
                        paymentDialog = true
                    },
                    enabled = cart.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gold,
                        disabledContainerColor = Color(0xFF2A2A2A),
                        disabledContentColor = Muted
                    )
                ) {
                    Text("BAYAR & CETAK")
                }

                if (msg.isNotBlank()) {
                    Text(
                        msg,
                        color = GoldSoft,
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    if (visualScanDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!scanningVisual) {
                    visualScanDialog = false
                    scanPreview = null
                    smartScanObjects = emptyList()
                }
            },
            title = {
                Text(
                    "SCAN CERDAS PRODUK",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 540.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    scanPreview?.let { bitmap ->
                        if (smartScanObjects.isNotEmpty() && !scanningVisual) {
                            InteractiveScanPreview(
                                bitmap = bitmap,
                                objects = smartScanObjects,
                                selectedObjectIndex = selectedScanObjectIndex,
                                onObjectSelected = { selectedScanObjectIndex = it },
                                modifier = Modifier.heightIn(max = 260.dp)
                            )
                            Text(
                                "Ketuk kotak objek untuk meninjau hasil pengenalan. Hijau = dikenali, merah = belum dikenali.",
                                color = Color.White.copy(alpha = 0.70f),
                                fontSize = 10.sp
                            )
                        } else {
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = "Foto barang pelanggan",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    if (scanningVisual) {
                        Text(
                            "MENGANALISIS OBJEK DAN MENCARI KECOCOKAN...",
                            color = GoldSoft,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Setiap objek diproses terpisah. Kandidat yang tidak melewati ambang kecocokan tidak ditampilkan.",
                            color = Color.White.copy(alpha = 0.70f),
                            fontSize = 11.sp
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = GoldSoft
                        )
                    } else {
                        val detectedCount = smartScanObjects.size
                        val recognized = smartScanObjects.count { it.match != null }
                        val recognizedObjects = smartScanObjects.filter { it.match != null }

                        if (detectedCount == 0) {
                            Text(
                                "TIDAK ADA OBJEK YANG TERDETEKSI",
                                color = Color(0xFFE57373),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Arahkan kamera ke produk dengan jelas. Tidak ada kandidat yang ditampilkan jika objek tidak dapat dipisahkan.",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                "${recognized} DARI ${detectedCount} OBJEK DIKENALI",
                                color = if (recognized > 0) GoldSoft else Color(0xFFE57373),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Periksa identitas objek dan quantity sebelum ditambahkan ke keranjang.",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp
                            )
                            TextButton(onClick = { showScanDiagnostics = !showScanDiagnostics }) {
                                Text(if (showScanDiagnostics) "SEMBUNYIKAN DETAIL DIAGNOSTIK" else "DETAIL DIAGNOSTIK")
                            }
                            if (showScanDiagnostics) {
                                Text(
                                    "Phase 4.3.2.37 / V8.4.17 • Evidence Integrity • Physical Track Truth • Rescue Gate V2. Recognition threshold tetap sama; firewall/track/decision metadata hanya ditampilkan untuk diagnosis.",
                                    color = Color.White.copy(alpha = 0.56f),
                                    fontSize = 9.sp
                                )
                            }

                            if (recognizedObjects.isEmpty()) {
                                Text(
                                    "PRODUK BELUM DIKENALI",
                                    color = Color(0xFFE57373),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Produk belum cukup pasti untuk dimasukkan ke keranjang. Buka Detail diagnostik bila hasil ini perlu dianalisis lebih lanjut.",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 12.sp
                                )
                            } else {
                                val grouped = recognizedObjects.groupBy { it.match!!.product.id }
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(Color(0xFF101010))
                                ) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("HASIL BELANJA TERBACA", color = GoldSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        grouped.values.forEach { group ->
                                            val product = group.first().match!!.product
                                            val qty = group.size
                                            Text(
                                                "$qty × ${product.name} • ${formatRupiah(product.sellPrice * qty)}",
                                                color = Color.White,
                                                fontSize = 11.sp
                                            )
                                        }
                                        val scanTotal = grouped.values.sumOf { group -> group.first().match!!.product.sellPrice * group.size }
                                        Text("TOTAL HASIL SCAN ${formatRupiah(scanTotal)}", color = GoldSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            "Periksa hasil sebelum dijual. Sistem tidak melakukan pembayaran otomatis.",
                                            color = Color.White.copy(alpha = 0.62f),
                                            fontSize = 9.sp
                                        )
                                        Button(
                                            onClick = {
                                                quantityDrafts = grouped.values.mapNotNull { group ->
                                                    val product = group.firstOrNull()?.match?.product ?: return@mapNotNull null
                                                    val detected = group.size.coerceAtLeast(1)
                                                    ScanQuantityDraft(
                                                        product = product,
                                                        detectedQuantity = detected,
                                                        quantity = detected.coerceAtMost(product.stock)
                                                    )
                                                }
                                                quantityReviewDialog = quantityDrafts.isNotEmpty()
                                            },
                                            enabled = !scanningVisual && recognizedObjects.isNotEmpty(),
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Gold)
                                        ) {
                                            Text("TINJAU JUMLAH & TAMBAH", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            smartScanObjects.forEach { objectMatch ->
                                val match = objectMatch.match
                                if (match != null) {
                                    val refBitmap = remember(match.photo.localPath) {
                                        BitmapFactory.decodeFile(match.photo.localPath)
                                    }
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(Panel)
                                    ) {
                                        Row(
                                            Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (refBitmap != null) {
                                                Image(
                                                    refBitmap.asImageBitmap(),
                                                    contentDescription = "Foto ${match.product.name}",
                                                    modifier = Modifier.size(68.dp),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                                Text(
                                                    "✓ OBJEK ${objectMatch.index} • ${match.product.name}",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    "SKU ${match.product.sku} • stok ${match.product.stock}",
                                                    color = Color.White.copy(alpha = 0.68f),
                                                    fontSize = 10.sp
                                                )
                                                Text(
                                                    "${formatRupiah(match.product.sellPrice)} • Final ${objectMatch.finalScore}% • Visual ${objectMatch.visualScore}% • OCR ${objectMatch.textScore}%",
                                                    color = GoldSoft,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                objectMatch.candidates.firstOrNull { it.product.id == match.product.id }?.let { c ->
                                                    if (c.profileScore > 0) {
                                                        Text("Identity Profile ${c.profileScore}%${if (c.masterVerified) " • VERIFIED" else ""}", color = GoldSoft.copy(alpha = 0.85f), fontSize = 8.sp)
                                                    }
                                                }
                                                Text(
                                                    objectMatch.reason,
                                                    color = Color.White.copy(alpha = 0.62f),
                                                    fontSize = 9.sp
                                                )
                                                if (showScanDiagnostics) {
                                                    Text(
                                                        "${objectMatch.proposalType} • ${objectMatch.proposalSource} • ${objectMatch.physicalInstanceHint.ifBlank { objectMatch.lineageId }} • ${objectMatch.decisionRuleId}",
                                                        color = Color.White.copy(alpha = 0.42f),
                                                        fontSize = 7.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (objectMatch.ocrText.isNotBlank()) {
                                                        Text(
                                                            "OCR: ${objectMatch.ocrText.replace("\n", " • ").take(90)}",
                                                            color = Color.White.copy(alpha = 0.55f),
                                                            fontSize = 8.sp,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                            TextButton(
                                                onClick = { selectedScanObjectIndex = objectMatch.index },
                                                enabled = !scanningVisual
                                            ) {
                                                Text(if (selectedScanObjectIndex == objectMatch.index) "DIPILIH" else "PILIH")
                                            }
                                        }
                                    }
                                } else {
                                    Card(
                                        Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(Panel.copy(alpha = 0.92f))
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(
                                                "✕ OBJEK ${objectMatch.index} • TIDAK DIKENALI",
                                                color = Color(0xFFE57373),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                "Visual ${objectMatch.visualScore}% • OCR ${objectMatch.textScore}% • Final ${objectMatch.finalScore}%",
                                                color = GoldSoft,
                                                fontSize = 9.sp
                                            )
                                            Text(
                                                "Alasan: ${objectMatch.reason}",
                                                color = Color.White.copy(alpha = 0.70f),
                                                fontSize = 9.sp
                                            )
                                            if (showScanDiagnostics) {
                                                Text(
                                                    "${objectMatch.proposalType} • ${objectMatch.proposalSource} • ${objectMatch.physicalInstanceHint.ifBlank { objectMatch.lineageId }} • ${objectMatch.decisionRuleId}",
                                                    color = Color.White.copy(alpha = 0.45f),
                                                    fontSize = 7.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (objectMatch.barcode.isNotBlank()) {
                                                    Text(
                                                        "Barcode: ${objectMatch.barcode}",
                                                        color = Color.White.copy(alpha = 0.65f),
                                                        fontSize = 9.sp
                                                    )
                                                }
                                                if (objectMatch.ocrText.isNotBlank()) {
                                                    Text(
                                                        "OCR: ${objectMatch.ocrText.replace("\n", " • ").take(120)}",
                                                        color = Color.White.copy(alpha = 0.65f),
                                                        fontSize = 9.sp,
                                                        maxLines = 3,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                objectMatch.candidates.take(3).forEachIndexed { rank, candidate ->
                                                    Text(
                                                        "${rank + 1}. ${candidate.product.name} | Final ${candidate.finalScore}% | V ${candidate.visualScore}% | OCR ${candidate.textScore}% | ID ${candidate.profileScore}% | ${if (candidate.hasMaster) "MASTER" else "NO MASTER"}",
                                                        color = Color.White.copy(alpha = 0.58f),
                                                        fontSize = 8.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = { shareCurrentScanEvidence(a, activeScanSession) },
                                enabled = !scanningVisual && activeScanSession.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldSoft),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GoldSoft)
                            ) {
                                Text("LAPORKAN HASIL SCAN BERGAMBAR", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!scanningVisual) openVisualScanner()
                    },
                    enabled = !scanningVisual
                ) {
                    Text("📷 SCAN ULANG")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!scanningVisual) {
                            visualScanDialog = false
                            scanPreview = null
                            smartScanObjects = emptyList()
                        }
                    },
                    enabled = !scanningVisual
                ) {
                    Text("SELESAI")
                }
            }
        )
    }

    if (quantityReviewDialog) {
        AlertDialog(
            onDismissRequest = { quantityReviewDialog = false },
            title = { Text("KONFIRMASI JUMLAH", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Jumlah hasil scan harus dikonfirmasi sebelum masuk keranjang. Session yang sama hanya dapat ditambahkan satu kali.",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 11.sp
                    )
                    quantityDrafts.forEach { draft ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Panel)) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(draft.product.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(
                                        "Terdeteksi ${draft.detectedQuantity} • stok ${draft.product.stock}",
                                        color = Color.White.copy(alpha = 0.62f),
                                        fontSize = 9.sp
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        quantityDrafts = quantityDrafts.map {
                                            if (it.product.id == draft.product.id) it.copy(quantity = (it.quantity - 1).coerceAtLeast(0)) else it
                                        }
                                    }
                                ) { Icon(Icons.Default.Remove, contentDescription = "Kurangi") }
                                Text("${draft.quantity}", color = GoldSoft, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 24.dp), textAlign = TextAlign.Center)
                                IconButton(
                                    onClick = {
                                        quantityDrafts = quantityDrafts.map {
                                            if (it.product.id == draft.product.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(it.product.stock)) else it
                                        }
                                    },
                                    enabled = draft.quantity < draft.product.stock
                                ) { Icon(Icons.Default.Add, contentDescription = "Tambah") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val commit = vm.commitScanQuantities(activeScanSession, quantityDrafts)
                        msg = commit.message
                        quantityReviewDialog = false
                        if (commit.committed || commit.duplicateSession) {
                            visualScanDialog = false
                            scanPreview = null
                            smartScanObjects = emptyList()
                        }
                    },
                    enabled = quantityDrafts.any { it.quantity > 0 },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                ) { Text("KONFIRMASI & TAMBAH", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { quantityReviewDialog = false }) { Text("BATAL") }
            }
        )
    }

    if (paymentDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!processingPayment) paymentDialog = false
            },
            title = {
                Text(
                    "PEMBAYARAN",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "TOTAL ${formatRupiah(total)}",
                        color = GoldSoft,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )

                    Text(
                        "Pilih metode pembayaran",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("CASH", "QRIS", "TRANSFER", "DEBIT").forEach { x ->
                            FilterChip(
                                selected = method == x,
                                onClick = {
                                    if (!processingPayment) {
                                        method = x
                                        paid = if (x == "CASH") total.toString() else total.toString()
                                    }
                                },
                                label = { Text(x, fontSize = 8.sp) },
                                enabled = !processingPayment
                            )
                        }
                    }

                    if (method == "CASH") {
                        OutlinedTextField(
                            value = paid,
                            onValueChange = { paid = it.filter(Char::isDigit).take(12) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Uang pelanggan") },
                            placeholder = { Text("Masukkan nominal, contoh 150000") },
                            singleLine = true,
                            enabled = !processingPayment,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        Text(
                            "Nominal yang dimasukkan: ${if (paid.isBlank()) "Rp 0" else formatRupiah(paidAmount)}",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp
                        )

                        // Tombol nominal dibuat 2 kolom agar label tidak pernah terpotong
                        // pada layar Android dengan ukuran/font berbeda.
                        val quickPayments = listOf(
                            "UANG PAS" to total,
                            "Rp 50.000" to 50_000L,
                            "Rp 100.000" to 100_000L,
                            "Rp 200.000" to 200_000L,
                            "Rp 500.000" to 500_000L
                        )

                        quickPayments.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { (label, amount) ->
                                    OutlinedButton(
                                        onClick = { paid = amount.toString() },
                                        enabled = !processingPayment,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 4.dp
                                        )
                                    ) {
                                        Text(
                                            label,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            softWrap = false,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                if (row.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }

                        if (cashEnough) {
                            Text(
                                "KEMBALIAN ${formatRupiah(change)}",
                                color = GoldSoft,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                "UANG KURANG ${formatRupiah(total - paidAmount)}",
                                color = Color(0xFFE57373),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            when (method) {
                                "QRIS" -> "Pembayaran QRIS: nominal otomatis ${formatRupiah(total)}."
                                "TRANSFER" -> "Pembayaran TRANSFER: nominal otomatis ${formatRupiah(total)}."
                                "DEBIT" -> "Pembayaran DEBIT: nominal otomatis ${formatRupiah(total)}."
                                else -> "Pembayaran ditetapkan sebesar total transaksi."
                            },
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp
                        )

                        Text(
                            "Nominal dibayar: ${formatRupiah(total)}",
                            color = GoldSoft,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!processingPayment) {
                            processingPayment = true
                            val finalPaid = if (method == "CASH") paidAmount else total
                            scope.launch {
                                try {
                                    vm.checkout(finalPaid, method).fold(
                                        onSuccess = { result ->
                                            paymentDialog = false
                                            successDialog = result
                                        },
                                        onFailure = { e ->
                                            msg = e.message ?: "Pembayaran gagal"
                                        }
                                    )
                                } finally {
                                    processingPayment = false
                                }
                            }
                        }
                    },
                    enabled = !processingPayment && cart.isNotEmpty() && (method != "CASH" || cashEnough),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gold,
                        disabledContainerColor = Color(0xFF2A2A2A),
                        disabledContentColor = Muted
                    )
                ) {
                    Text(
                        if (processingPayment) "MEMPROSES..." else "KONFIRMASI BAYAR",
                        color = if (processingPayment) Muted else Color.Black
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!processingPayment) paymentDialog = false },
                    enabled = !processingPayment
                ) {
                    Text("BATAL")
                }
            }
        )
    }

    successDialog?.let { result ->
        AlertDialog(
            onDismissRequest = {
                successDialog = null
                msg = "Transaksi ${result.invoice} berhasil disimpan."
            },
            title = {
                Text(
                    "TRANSAKSI BERHASIL",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No. ${result.invoice}", color = Color.White.copy(alpha = 0.88f))
                    Text("Metode: ${result.paymentMethod}", color = Color.White.copy(alpha = 0.88f))
                    Text("Total: ${formatRupiah(result.total)}", color = Color.White.copy(alpha = 0.88f))
                    Text("Dibayar: ${formatRupiah(result.paid)}", color = Color.White.copy(alpha = 0.88f))
                    Text(
                        if (result.paymentMethod == "CASH") {
                            "Kembalian: ${formatRupiah(result.change)}"
                        } else {
                            "Pembayaran lunas"
                        },
                        color = GoldSoft,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            msg = a.print(result)
                            successDialog = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                ) {
                    Text("CETAK STRUK", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        successDialog = null
                        msg = "Transaksi ${result.invoice} berhasil disimpan."
                    }
                ) {
                    Text("SELESAI")
                }
            }
        )
    }
}

@Composable
fun Products(vm: PosViewModel, a: MainActivity) {
    val ps by vm.products.collectAsState()
    var add by remember { mutableStateOf(false) }
    var stockProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var photoProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var masterProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var pendingMasterProduct by remember { mutableStateOf<ProductEntity?>(null) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun savePhoto(context: android.content.Context, bitmap: Bitmap, productId: Long): String {
        val dir = File(context.filesDir, "product_photos").apply { mkdirs() }
        val file = File(dir, "product_${productId}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return file.absolutePath
    }

    var pendingAddPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var pendingAddCameraFile by remember { mutableStateOf<File?>(null) }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf("") }
    val addPhotoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingAddCameraFile
        pendingAddCameraFile = null
        if (success && file != null) {
            pendingAddPhoto = runCatching { decodeCameraBitmap(file) }.getOrNull()
            file.delete()
        } else {
            file?.delete()
        }
    }
    var addCameraError by remember { mutableStateOf("") }

    fun launchAddCamera() {
        runCatching {
            val (uri, file) = createCameraUri(a, "product_")
            pendingAddCameraFile = file
            addPhotoLauncher.launch(uri)
        }.onFailure {
            pendingAddCameraFile?.delete()
            pendingAddCameraFile = null
            addCameraError = "Kamera tidak dapat dibuka: ${it.message ?: "perangkat tidak mendukung"}"
        }
    }

    val addCameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchAddCamera()
        else addCameraError = "Izin kamera ditolak. Izinkan kamera untuk mengambil foto produk."
    }

    fun openAddCamera() {
        if (ContextCompat.checkSelfPermission(a, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchAddCamera()
        } else {
            addCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val masterCameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            masterProduct = pendingMasterProduct
        } else {
            msg = "Izin kamera diperlukan untuk ARS Product Master Studio."
        }
    }

    fun openMasterStudio(p: ProductEntity) {
        if (ContextCompat.checkSelfPermission(a, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            masterProduct = p
        } else {
            pendingMasterProduct = p
            masterCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PRODUK", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Master produk & foto visual", color = GoldSoft, fontSize = 10.sp)
            }
            Button({ add = true }, colors = ButtonDefaults.buttonColors(Gold)) { Text("+ PRODUK") }
        }
        Text(msg, color = GoldSoft, modifier = Modifier.padding(horizontal = 18.dp))
        LazyColumn(Modifier.padding(16.dp)) {
            items(ps, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(Panel)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("${p.sku} • ${p.category} • stok ${p.stock}", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatRupiah(p.sellPrice), color = GoldSoft)
                            Row {
                                TextButton({ masterProduct = p }) { Text("MASTER") }
                                TextButton({ photoProduct = p }) { Text("FOTO") }
                                TextButton({ stockProduct = p }) { Text("STOK +") }
                            }
                        }
                    }
                }
            }
        }
    }

    masterProduct?.let { p ->
        ProductMasterStudio(
            vm = vm,
            product = p,
            onClose = { masterProduct = null }
        )
    }

    photoProduct?.let { p ->
        val photos by vm.productPhotos(p.id).collectAsState(initial = emptyList())
        var pendingGalleryPhoto by remember(p.id) { mutableStateOf<Bitmap?>(null) }
        var pendingGalleryCameraFile by remember(p.id) { mutableStateOf<File?>(null) }
        var galleryCameraError by remember(p.id) { mutableStateOf("") }
        val galleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            val file = pendingGalleryCameraFile
            pendingGalleryCameraFile = null
            if (success && file != null) {
                pendingGalleryPhoto = runCatching { decodeCameraBitmap(file) }.getOrNull()
                file.delete()
            } else {
                file?.delete()
            }
        }
        fun openGalleryCamera() {
            val context = vm.getApplication<Application>()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                galleryCameraError = "Izin kamera belum diberikan. Buka menu tambah produk dan izinkan kamera terlebih dahulu."
                return
            }
            runCatching {
                val (uri, file) = createCameraUri(context, "reference_")
                pendingGalleryCameraFile = file
                galleryLauncher.launch(uri)
            }.onFailure {
                pendingGalleryCameraFile?.delete()
                pendingGalleryCameraFile = null
                galleryCameraError = "Kamera tidak dapat dibuka: ${it.message ?: "perangkat tidak mendukung"}"
            }
        }

        AlertDialog(
            onDismissRequest = { photoProduct = null },
            title = { Text("FOTO PRODUK") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(p.name, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("SKU ${p.sku}", color = GoldSoft, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    if (photos.isEmpty()) {
                        Text("Belum ada foto referensi.", color = Muted)
                    } else {
                        photos.forEach { photo ->
                            val bitmap = remember(photo.localPath) { BitmapFactory.decodeFile(photo.localPath) }
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(Panel)) {
                                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (bitmap != null) {
                                        Image(bitmap.asImageBitmap(), contentDescription = "Foto ${p.name}", modifier = Modifier.size(72.dp), contentScale = ContentScale.Crop)
                                    }
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(if (photo.isPrimary) "FOTO UTAMA" else "FOTO REFERENSI", color = if (photo.isPrimary) GoldSoft else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                    if (!photo.isPrimary) {
                                        TextButton({ scope.launch { vm.setPrimaryProductPhoto(p.id, photo.id) } }) { Text("UTAMA") }
                                    }
                                    TextButton({
                                        scope.launch {
                                            vm.deleteProductPhoto(photo)
                                            runCatching { File(photo.localPath).delete() }
                                        }
                                    }) { Text("HAPUS") }
                                }
                            }
                        }
                    }
                    if (galleryCameraError.isNotBlank()) {
                        Text(galleryCameraError, color = GoldSoft, fontSize = 12.sp)
                    }
                    pendingGalleryPhoto?.let { bitmap ->
                        Spacer(Modifier.height(8.dp))
                        Text("Foto baru siap disimpan", color = GoldSoft, fontSize = 12.sp)
                        Image(bitmap.asImageBitmap(), contentDescription = "Preview foto baru", modifier = Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop)
                        Row {
                            TextButton({ pendingGalleryPhoto = null }) { Text("ULANGI") }
                            TextButton({
                                scope.launch {
                                    val path = savePhoto(vm.getApplication<Application>(), bitmap, p.id)
                                    val primary = photos.isEmpty()
                                    vm.addProductPhoto(p.id, path, primary)
                                    pendingGalleryPhoto = null
                                }
                            }) { Text("SIMPAN FOTO") }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ openGalleryCamera() }) { Text("📷 AMBIL FOTO") } },
            dismissButton = { TextButton({ photoProduct = null }) { Text("SELESAI") } }
        )
    }

    stockProduct?.let { p ->
        var qty by remember(p.id) { mutableStateOf("") }
        var cost by remember(p.id) { mutableStateOf(p.costPrice.toString()) }
        var supplier by remember(p.id) { mutableStateOf("") }
        var invoice by remember(p.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { stockProduct = null },
            title = { Text("STOK MASUK • ${p.name}") },
            text = { Column {
                OutlinedTextField(qty, { qty = it }, Modifier.fillMaxWidth(), label = { Text("Jumlah") })
                OutlinedTextField(cost, { cost = it }, Modifier.fillMaxWidth(), label = { Text("Harga modal/unit") })
                OutlinedTextField(supplier, { supplier = it }, Modifier.fillMaxWidth(), label = { Text("Supplier") })
                OutlinedTextField(invoice, { invoice = it }, Modifier.fillMaxWidth(), label = { Text("No. invoice") })
            } },
            confirmButton = { TextButton({
                scope.launch {
                    vm.stockIn(p.id, qty.toIntOrNull() ?: 0, cost.toLongOrNull() ?: 0, supplier.ifBlank { null }, invoice.ifBlank { null })
                        .fold({ msg = "Stok +${qty.ifBlank { "0" }} berhasil" }, { msg = it.message ?: "Gagal" })
                    stockProduct = null
                }
            }) { Text("SIMPAN") } },
            dismissButton = { TextButton({ stockProduct = null }) { Text("BATAL") } }
        )
    }

    if (add) {
        var name by remember { mutableStateOf("") }
        var sku by remember { mutableStateOf("") }
        var skuTouched by remember { mutableStateOf(false) }
        var barcode by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("LAINNYA") }
        var brand by remember { mutableStateOf("") }
        var variant by remember { mutableStateOf("") }
        var cost by remember { mutableStateOf("") }
        var price by remember { mutableStateOf("") }
        var stock by remember { mutableStateOf("0") }

        AlertDialog(
            onDismissRequest = { add = false; pendingAddPhoto = null; ocrText = "" },
            title = { Text("TAMBAH PRODUK") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    item {
                        pendingAddPhoto?.let { bitmap ->
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = "Foto produk",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp),
                                contentScale = ContentScale.Crop
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = { pendingAddPhoto = null; ocrText = "" },
                                    modifier = Modifier.weight(1f)
                                ) { Text("HAPUS FOTO") }
                                TextButton(
                                    onClick = {
                                        if (!ocrBusy) {
                                            ocrBusy = true
                                            scope.launch {
                                                val draft = runCatching {
                                                    withContext(Dispatchers.Default) {
                                                        SmartProductInput.analyzeMultiOrientation(bitmap)
                                                    }
                                                }.getOrElse {
                                                    SmartProductInput.Draft()
                                                }
                                                if (draft.rawText.isBlank() && draft.barcode.isBlank()) {
                                                    addCameraError = "Barcode/teks tidak terbaca. Tidak masalah: barcode memang opsional. Isi Nama produk secara manual; SKU ARS dibuat otomatis dan foto ini tetap dapat menjadi master visual awal."
                                                } else {
                                                    if (draft.name.isNotBlank()) name = draft.name
                                                    if (draft.brand.isNotBlank()) brand = draft.brand
                                                    if (draft.variant.isNotBlank()) variant = draft.variant
                                                    if (draft.barcode.isNotBlank()) barcode = draft.barcode
                                                    if (draft.suggestedSku.isNotBlank()) sku = draft.suggestedSku
                                                    ocrText = draft.rawText
                                                    addCameraError = "Data foto berhasil dibaca. Periksa kembali sebelum SIMPAN."
                                                }
                                                ocrBusy = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !ocrBusy
                                ) { Text(if (ocrBusy) "MEMBACA..." else "📄 ANALISIS FOTO") }
                            }
                        } ?: run {
                            OutlinedButton(
                                { openAddCamera() },
                                Modifier.fillMaxWidth()
                            ) { Text("📷 AMBIL FOTO PRODUK") }
                            if (addCameraError.isNotBlank()) {
                                Text(addCameraError, color = GoldSoft, fontSize = 12.sp)
                            }
                        }
                    }
                    if (pendingAddPhoto != null && addCameraError.isNotBlank()) {
                        item {
                            Text(addCameraError, color = GoldSoft, fontSize = 12.sp)
                        }
                    }
                    if (ocrText.isNotBlank()) {
                        item {
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(Color(0xFF101010))
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("HASIL ANALISIS FOTO", color = GoldSoft, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text(ocrText, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF101010))) {
                            Column(Modifier.padding(10.dp)) {
                                Text("IDENTITAS MANUAL TETAP DIDUKUNG", color = GoldSoft, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text(
                                    "Barcode tidak wajib. Jika foto tidak memiliki barcode/teks, masukkan Nama + SKU ARS (otomatis bila kosong). Setelah disimpan, foto dapat digunakan sebagai master visual awal.",
                                    color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp
                                )
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { value ->
                                name = value
                                if (!skuTouched && value.isNotBlank()) {
                                    sku = SmartProductInput.generateSku(brand, value, variant)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nama *") }
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = sku,
                            onValueChange = { skuTouched = true; sku = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("SKU ARS") },
                            supportingText = { Text("SKU wajib; barcode tidak wajib.") }
                        )
                    }
                    item { OutlinedTextField(barcode, { barcode = it }, Modifier.fillMaxWidth(), label = { Text("Barcode (opsional)") }) }
                    item { Text("Barcode tidak wajib. SKU ARS tetap menjadi identitas internal produk.", color = GoldSoft, fontSize = 10.sp) }
                    item { OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("Kategori") }) }
                    item { OutlinedTextField(brand, { brand = it }, Modifier.fillMaxWidth(), label = { Text("Merek") }) }
                    item { OutlinedTextField(variant, { variant = it }, Modifier.fillMaxWidth(), label = { Text("Varian / Model") }) }
                    item { OutlinedTextField(cost, { cost = it }, Modifier.fillMaxWidth(), label = { Text("Modal") }) }
                    item { OutlinedTextField(price, { price = it }, Modifier.fillMaxWidth(), label = { Text("Harga jual") }) }
                    item { OutlinedTextField(stock, { stock = it }, Modifier.fillMaxWidth(), label = { Text("Stok awal") }) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !ocrBusy,
                    onClick = {
                        scope.launch {
                            if (name.isBlank()) {
                                msg = "Nama produk wajib diisi. Barcode boleh kosong."
                                return@launch
                            }
                            val finalSku = sku.ifBlank {
                                SmartProductInput.generateSku(brand, name, variant)
                            }
                            sku = finalSku
                            vm.addProduct(
                                name.trim(),
                                finalSku,
                                barcode.trim().ifBlank { null },
                                category.trim().ifBlank { "LAINNYA" },
                                cost.toLongOrNull() ?: 0,
                                price.toLongOrNull() ?: 0,
                                stock.toIntOrNull() ?: 0,
                                brand.trim().ifBlank { null },
                                variant.trim().ifBlank { null }
                            ).fold({ productId ->
                                vm.mergeProductIdentityProfile(
                                    productId = productId,
                                    rawText = ocrText,
                                    brand = brand,
                                    name = name,
                                    variant = variant,
                                    barcode = barcode
                                )
                                pendingAddPhoto?.let { bitmap ->
                                    val path = savePhoto(vm.getApplication<Application>(), bitmap, productId)
                                    // First intake photo becomes a visual master seed.
                                    // It is not counted as a six-angle master until Master Studio completes it.
                                    vm.addProductPhoto(productId, path, true, "UNKNOWN")
                                }
                                msg = if (pendingAddPhoto != null) "Produk + foto berhasil ditambahkan • barcode opsional" else "Produk ditambahkan • barcode opsional"
                            }, { msg = it.message ?: "Gagal" })
                            pendingAddPhoto = null
                            ocrText = ""
                            add = false
                        }
                    }
                ) { Text("SIMPAN") }
            },
            dismissButton = {
                TextButton({ add = false; pendingAddPhoto = null; ocrText = "" }) { Text("BATAL") }
            }
        )
    }

}

@Composable
fun Reports(vm: PosViewModel, a: MainActivity) {
    val r by vm.revenue.collectAsState()
    val n by vm.transactions.collectAsState()
    val c by vm.cashBalance.collectAsState()
    val sales by vm.sales.collectAsState()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var profit by remember { mutableStateOf<com.arspos.anglerriausyndicate.data.ProfitSummary?>(null) }
    var returnSale by remember { mutableStateOf<Long?>(null) }
    var reason by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { profit = vm.profitSummary() }
    Column {
        Header("LAPORAN")
        Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(Panel)) {
            Column(Modifier.padding(18.dp)) {
                Text("Omzet bersih ${formatRupiah(r)}", color = Color.White, fontSize = 16.sp)
                Text("Transaksi $n", color = Color.White, fontSize = 16.sp)
                Text("Saldo kas ${formatRupiah(c)}", color = Color.White, fontSize = 16.sp)
                profit?.let { Text("Laba kotor ${formatRupiah(it.grossProfit)}", color = GoldSoft); Text("Modal ${formatRupiah(it.cost)}", color = Muted) }
                Text(msg, color = GoldSoft)
            }
        }
        Text("TRANSAKSI TERBARU", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(sales.take(30), key = { it.id }) { sale ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(Panel)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sale.invoiceNumber, color = Color.White, fontWeight = FontWeight.Medium)
                            Text("${formatRupiah(sale.grandTotal)} • ${sale.paymentMethod}", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                        }
                        TextButton({
                            scope.launch {
                                val saleItems = vm.saleItems(sale.id)
                                val items = saleItems.map { item ->
                                    CartLine(ProductEntity(id = item.productId, name = item.productName, sku = "", costPrice = item.costPrice, sellPrice = item.sellPrice), item.quantity)
                                }
                                msg = a.reprint(sale.invoiceNumber, items, sale.grandTotal, sale.paidAmount, sale.changeAmount, sale.paymentMethod)
                            }
                        }) { Text("CETAK") }
                        TextButton({ returnSale = sale.id; reason = "" }) { Text("RETUR") }
                    }
                }
            }
        }
    }
    returnSale?.let { id ->
        AlertDialog(
            onDismissRequest = { returnSale = null },
            title = { Text("RETUR TRANSAKSI") },
            text = { OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text("Alasan retur") }) },
            confirmButton = {
                TextButton({
                    scope.launch {
                        vm.processReturn(id, reason.ifBlank { "Retur barang" }).fold(
                            { v -> msg = "Retur ${formatRupiah(v)} berhasil" },
                            { e -> msg = e.message ?: "Retur gagal" }
                        )
                        returnSale = null
                        profit = vm.profitSummary()
                    }
                }) { Text("PROSES") }
            },
            dismissButton = { TextButton({ returnSale = null }) { Text("BATAL") } }
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun Printer(a: MainActivity) {
    var ds by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var sel by remember { mutableStateOf<BluetoothDevice?>(null) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("PRINTER BLUETOOTH")
        Button({
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(a, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                a.requestBluetooth()
            } else {
                ds = BluetoothPrinter().pairedDevices()
                msg = "${ds.size} perangkat terpasang"
            }
        }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(Gold)) { Text("CARI PRINTER PAIRED") }
        Text(msg, color = GoldSoft)
        LazyColumn(Modifier.weight(1f)) {
            items(ds, key = { it.address }) { d ->
                Card(Modifier.fillMaxWidth().padding(4.dp).clickable { sel = d; a.savePrinter(d) }, colors = CardDefaults.cardColors(Panel)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name ?: "Printer", color = Color.White, fontWeight = FontWeight.Medium)
                        Text(d.address, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                        if (sel?.address == d.address) Text("✓ DIPILIH", color = GoldSoft, fontSize = 11.sp)
                    }
                }
            }
        }
        Button(onClick={
            sel?.let { d ->
                scope.launch {
                    msg = try {
                        withContext(Dispatchers.IO) { BluetoothPrinter().print(d, EscPosReceipt.build(emptyList(), 0, 0, 0, "ARS-TEST")) }
                        "TEST PRINT OK"
                    } catch (e: Exception) { "Gagal: ${e.message}" }
                }
            } ?: run { msg = "Pilih printer" }
        }, enabled = sel != null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(Gold)) { Text("TEST PRINT") }
    }
}
