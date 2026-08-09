package com.seszip.app

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SesZipApp()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ANA EKRAN: alt navigasyon (3 sekme)
// ---------------------------------------------------------------------------

@Composable
fun SesZipApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Dönüştür" to "📤", "Çöz" to "📥", "Fikirler" to "💡")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> EncodeTab()
                1 -> DecodeTab()
                else -> IdeasTab()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DURUMLAR
// ---------------------------------------------------------------------------

data class EncodeUiState(
    val uri: Uri? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val hdcMode: Boolean = false,
    val repeats: Int = 1,
    val compress: Boolean = true,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val wavPath: String? = null,
    val wavBytes: ByteArray? = null,
    val wavSize: Long = 0,
    val durationSec: Double = 0.0,
)

data class DecodeUiState(
    val uri: Uri? = null,
    val fileName: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val resultText: String? = null,
    val warning: String? = null,
    val decodedName: String? = null,
    val decodedBytes: ByteArray? = null,
)

// ---------------------------------------------------------------------------
// SEKME 1: DÖNÜŞTÜR
// ---------------------------------------------------------------------------

@Composable
fun EncodeTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(EncodeUiState()) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val (name, size) = queryFileInfo(context, uri)
            state = EncodeUiState(uri = uri, fileName = name, fileSize = size)
        }
    }

    val saveWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        val bytes = state.wavBytes
        if (uri != null && bytes != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } catch (e: Exception) {
                state = state.copy(error = "Kaydetme hatası: ${e.message}")
            }
        }
    }

    fun convert() {
        val uri = state.uri ?: return
        val name = state.fileName ?: "dosya"
        val hdcMode = state.hdcMode
        val repeats = state.repeats
        val compress = state.compress
        scope.launch {
            state = state.copy(busy = true, status = "Dosya okunuyor…", error = null)
            try {
                val bytes = withContext(Dispatchers.IO) { readAllBytes(context, uri) }
                val (payload, compressedFlag) = withContext(Dispatchers.Default) {
                    OfdmModem.compress(bytes, compress)
                }
                if (!hdcMode && bytes.size > 20 * 1024 * 1024)
                    throw IllegalArgumentException("BPSK modu 20MB altı için. Daha büyük dosya için streaming gerekir (yol haritası).")
                if (hdcMode && payload.size > 8 * 1024)
                    throw IllegalArgumentException("HDC modu küçük/güvenli dosyalar içindir (8KB altı). Büyük dosya için hızlı BPSK kullan.")

                val est = OfdmModem.estimateSeconds(payload.size.toLong(), hdcMode, name.length, repeats)
                if (est > 900)
                    throw IllegalArgumentException("Ses ${OfdmModem.formatSeconds(est)} sürer — çok uzun. Küçük dosya seç, tekrarı azalt.")

                val wav = withContext(Dispatchers.Default) {
                    OfdmModem.modulateToWav(payload, name, hdcMode, repeats)
                }
                val file = File(context.cacheDir, "seszip_${System.currentTimeMillis()}.wav")
                withContext(Dispatchers.IO) { file.writeBytes(wav) }

                val statusTxt = buildString {
                    append(if (hdcMode) "🛡️ HDC (sağlam)" else "🚀 BPSK (hızlı)")
                    if (compressedFlag) append("  •  sıkıştırma: %${String.format(Locale.US, "%.1f", (1.0 - payload.size.toDouble() / bytes.size) * 100)}")
                    if (repeats > 1) append("  •  $repeats kopya")
                }
                val sampleCount = wav.size / 2  // 16-bit mono -> örnek sayısı
                state = state.copy(
                    busy = false,
                    status = statusTxt,
                    wavPath = file.absolutePath,
                    wavBytes = wav,
                    wavSize = wav.size.toLong(),
                    durationSec = sampleCount.toDouble() / OfdmModem.SAMPLE_RATE,
                )
            } catch (e: Exception) {
                state = state.copy(busy = false, error = e.message ?: "Bilinmeyen hata")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("📡 Dosyayı OFDM Sesine Çevir", "846 taşıyıcı • IFFT/FFT • HDC geometrik kodlama")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1) Dosya Seç (pdf, zip, video, herhangi bir şey)", fontWeight = FontWeight.SemiBold)
                Button(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("📁 Dosya Seç") }
                if (state.fileName != null) {
                    Text("${state.fileName}  •  ${OfdmModem.formatBytes(state.fileSize)}")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2) Mod Seçimi", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.hdcMode,
                        onClick = { state = state.copy(hdcMode = false) },
                        label = { Text("🚀 BPSK (~${(OfdmModem.bitrate(false) / 1000).toInt()} kbps)") }
                    )
                    FilterChip(
                        selected = state.hdcMode,
                        onClick = { state = state.copy(hdcMode = true) },
                        label = { Text("🛡️ HDC (~${OfdmModem.bitrate(true).toInt()} bps)") }
                    )
                }
                Text(
                    if (state.hdcMode)
                        "HDC: dosya 3×10 bit'lik yüksek boyutlu vektörlere 'boyanır'. Taşıyıcıların bir kısmı bozulsa bile korelasyon doğru cevabı bulur (geometrik FEC). Küçük ve kritik dosyalar için."
                    else
                        "BPSK: her taşıyıcıya 1 bit. 20 kat hızlı ama taşıyıcı kaybına açık (hata düzeltme yok)."
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.compress,
                        onCheckedChange = { state = state.copy(compress = it) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Deflate sıkıştırmayı dene")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3) Tekrar (kova entegrasyonu)", fontWeight = FontWeight.SemiBold)
                Text("Paket N kez gönderilir; alıcı soft-değerleri toplayıp çözer — süre N katına çıkar, güvenilirlik artar.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3).forEach { r ->
                        FilterChip(
                            selected = state.repeats == r,
                            onClick = { state = state.copy(repeats = r) },
                            label = { Text("$r×") }
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4) Dönüştür", fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { convert() },
                    enabled = state.uri != null && !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.busy) "Dönüştürülüyor…" else "🔊 Sese Dönüştür") }
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                if (state.wavPath != null) {
                    HorizontalDivider()
                    Text(
                        "Ses: ${OfdmModem.formatBytes(state.wavSize)}  •  Süre: ${OfdmModem.formatSeconds(state.durationSec)}"
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { playFile(state.wavPath!!) }) { Text("▶ Çal") }
                        Button(onClick = {
                            saveWav.launch("seszip_${state.fileName ?: "dosya"}.wav")
                        }) { Text("💾 WAV Kaydet") }
                    }
                }
            }
        }

        IdeasCard("💡 Geliştirme fikirleri", encodeIdeas)
    }
}

// ---------------------------------------------------------------------------
// SEKME 2: ÇÖZ
// ---------------------------------------------------------------------------

@Composable
fun DecodeTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DecodeUiState()) }

    val pickWav = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val (name, _) = queryFileInfo(context, uri)
            state = DecodeUiState(uri = uri, fileName = name)
        }
    }

    val saveDecoded = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = state.decodedBytes
        val name = state.decodedName
        if (uri != null && bytes != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } catch (e: Exception) {
                state = state.copy(error = "Kaydetme hatası: ${e.message}")
            }
        }
    }

    fun decode() {
        val uri = state.uri ?: return
        scope.launch {
            state = state.copy(busy = true, error = null, resultText = null,
                warning = null, decodedName = null, decodedBytes = null)
            try {
                val bytes = withContext(Dispatchers.IO) { readAllBytes(context, uri) }
                val result = withContext(Dispatchers.Default) { OfdmModem.decodeWav(bytes) }
                val p = result.packet
                val ok = p.crcValid
                state = state.copy(
                    busy = false,
                    resultText = if (ok)
                        "Çözüldü ✓  ${p.name} (${OfdmModem.formatBytes(p.data.size.toLong())})" +
                            "  •  ${result.mode}" +
                            (if (result.repeats > 1) "  •  ${result.repeats} kopya entegre" else "") +
                            (if (p.compressed) "  •  sıkıştırılmış" else "  •  ham")
                    else
                        "⚠️ CRC uyuşmazlığı — kayıtta bozulma var, dosya güvenilir değil.",
                    warning = if (ok) null
                    else "Ses kaydındaki hatalar nedeniyle veri doğrulanamadı. Sesi tekrar gönder ya da daha temiz ortamda kaydet.",
                    decodedName = p.name,
                    decodedBytes = p.data,
                )
            } catch (e: Exception) {
                state = state.copy(busy = false, error = e.message ?: "Bilinmeyen hata")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("📥 Sesten Dosyaya Çöz", "FFT + kanal kestirimi + otomatik mod (BPSK/HDC)")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SesZip v0.6'nın ürettiği WAV'ı seç (48 kHz mono)", fontWeight = FontWeight.SemiBold)
                Button(onClick = { pickWav.launch(arrayOf("*/*")) }) { Text("🎵 WAV Seç") }
                state.fileName?.let { Text(it) }
                Button(
                    onClick = { decode() },
                    enabled = state.uri != null && !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.busy) "Çözülüyor…" else "🔓 Sesten Çöz") }
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.resultText?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.decodedBytes != null) {
                    Button(onClick = { saveDecoded.launch(state.decodedName ?: "cozulen.dosya") }) {
                        Text("💾 Dosyayı Kaydet")
                    }
                }
            }
        }

        IdeasCard("💡 Geliştirme fikirleri", decodeIdeas)
    }
}

// ---------------------------------------------------------------------------
// SEKME 3: FİKİRLER
// ---------------------------------------------------------------------------

@Composable
fun IdeasTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("🚀 SesZip v0.6 Vizyonu", "Bit akışı olmadan bilgi taşıma")
        IdeasCard("🎯 Paradigma", visionIdeas)
        IdeasCard("📤 Modülasyon tarafı", encodeIdeas)
        IdeasCard("📥 Çözüm tarafı", decodeIdeas)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("⚠️ Dürüst not", fontWeight = FontWeight.SemiBold)
                Text(
                    "OFDM: 846 taşıyıcı × 20.8 sembol/sn. BPSK ≈ 17.6 kbps (hızlı, kodlama yok). " +
                        "HDC ≈ 625 bps (sağlam, geometrik FEC — Shannon kapasiteyi aşmaz, sağlamlık satın alır). " +
                        "Bu bir şifreleme değil, kodlama/steganografidir."
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ORTAK PARÇALAR
// ---------------------------------------------------------------------------

@Composable
fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun IdeasCard(title: String, ideas: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            ideas.forEach { (head, desc) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("• $head", fontWeight = FontWeight.Medium)
                    Text(desc, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun playFile(path: String) {
    try {
        val mp = MediaPlayer()
        mp.setDataSource(path)
        mp.prepare()
        mp.start()
        mp.setOnCompletionListener { it.release() }
    } catch (_: Exception) {
    }
}

private fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = "dosya"
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0) c.getString(ni)?.let { name = it }
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    return name to size
}

private fun readAllBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("Dosya açılamadı")

// ---------------------------------------------------------------------------
// FİKİR LİSTELERİ
// ---------------------------------------------------------------------------

val encodeIdeas = listOf(
    "QAM (4/16-QAM)" to
        "Taşıyıcı başına genlik+faz (2-4 bit) → BPSK'nın 2-4 katı hız. OFDM altyapısı hazır.",
    "Pilot taşıyıcılar + adaptif eşitleme" to
        "Kanal değişimine (hareket eden telefon) karşı sembol başına pilotlarla sürekli kanal takibi.",
    "CSS chirp (yarasa yöntemi)" to
        "LoRa tarzı linear chirp + matched filter: gürültüye en dirençli, senkronu güçlendirir.",
    "HDC kod tablosu büyütme" to
        "2^10 → 2^12/2^14 (grup başına): hız 20-40% artar, boyut marjı yine yeterli.",
    "Ultrasonik OFDM bandı" to
        "Taşıyıcıları 17-21 kHz'e kaydır → insan kulağı duymaz (48 kHz kayıt şart).",
    "Büyük dosya streaming" to
        "BPSK 20MB sınırını parça parça üretimle kaldır — 500MB hayalinin gerçek yolu.",
    "Reed-Solomon katmanı" to
        "HDC'nin geometrik düzeltmesinin üstüne klasik bit düzeltme — ikisi birleşince çok sağlam.",
)

val decodeIdeas = listOf(
    "Canlı mikrofonla çözüm" to
        "AudioRecord ile sembol pencerelerini gerçek zamanlı FFT'le — kaydı beklemeden çöz (RECORD_AUDIO izni yazıldı).",
    "Çoklu kayıt birleştir (kova)" to
        "Aynı sesi 2-3 kez kaydet, Z matrislerini topla — gerçek SNR kazancı (v0.4'ün mirası).",
    "44.1k kayıt desteği" to
        "En yüksek taşıyıcıları 19 kHz altına çekerek 44.1 kHz kayıtta da tam çözüm.",
    "Senkron güçlendirme" to
        "PN-31 yerine daha uzun PN + korelasyon zirvesi istatistiği — gürültülü ortamda yanlış tetik azalır.",
    "Hata raporu" to
        "Çözüm başarısızsa hangi taşıyıcı bandı bozuk, SNR ne kadar — kullanıcıya yol göster.",
)

val visionIdeas = listOf(
    "Bit akışı olmadan iletişim" to
        "Dosyayı bit dizisine çevirmeden 'anlam'ı yüksek boyutlu temsile boya (HDC) — bu repo'nun tezi.",
    "HDC = beyindeki hesaplama" to
        "10k boyutlu rastgele vektörler, biyolojik nanomorfik hesaplamanın modeli — 'anlam' yakınlıkla kurtarılır.",
    "Geometrik hata düzeltme" to
        "Bit-FEC yerine şekil bazlı düzeltme: %40 taşıyıcı kaybında bile dosya çıkar (kanıtlandı).",
    "Ses spotu (parametrik dizi)" to
        "Ultrasonik taşıyıcı havada demodüle olur — veriyi yalnızca belirli konumda duyulur yapabilirsin.",
    "Çift cihaz hizalama" to
        "İki telefon aynı yayını kaydetsin, Z matrislerini birleştir — mesafe/kalite sorununu çöz.",
    "Güvenlik katmanı" to
        "Paketi şifrele + imzala. 'Sahte ses' çağında verinin gerçekten senden geldiğini kanıtla.",
)
