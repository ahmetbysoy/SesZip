package com.seszip.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * SesZip v0.6 — OFDM + Hyperdimensional Computing (HDC) motoru.
 *
 * Paradigma: "Bit akışı olmadan bilgi taşı."
 *  - Gerçek OFDM: 846 taşıyıcı (23.4 Hz aralık, 375 Hz..20 kHz), IFFT/FFT, cyclic prefix,
 *    PN-31 preamble ile senkron, preamble'dan kanal kestirimi + ZF eşitleme.
 *  - MOD A (HIZLI — BPSK): her taşıyıcıya 1 bit → ~17.6 kbps.
 *  - MOD B (SAĞLAM — HDC): her sembol 3 grup × 10 bit; her grup, 282 boyutlu rastgele
 *    ±1 kod tablosundan (2^10 vektör) bir vektörü taşır. Alıcı korelasyonla en yakın
 *    geometrik komşuyu bulur → taşıyıcı kaybında BİLE zarif bozulma (~625 bps).
 *  - Kod tablosu deterministik seed (777) ile üretilir — verici ve alıcı aynıdır.
 *
 * Shannon'a sadık: HDC kapasite eklemez, sağlamlık satın alır (boyut marjı = geometrik FEC).
 */
object OfdmModem {

    // ---------------- OFDM parametreleri ----------------
    const val SAMPLE_RATE = 48000
    const val FFT_SIZE = 2048
    const val CP = 256
    const val SYMBOL_LEN = FFT_SIZE + CP      // 2304 örnek -> 20.83 sembol/sn
    const val FIRST_SUBCARRIER = 8
    const val LAST_SUBCARRIER = 853           // 375 Hz .. ~19.98 kHz (Nyquist marjı)
    const val D = LAST_SUBCARRIER - FIRST_SUBCARRIER + 1   // 846 taşıyıcı
    private const val AMPLITUDE = 30000.0

    // ---------------- HDC parametreleri ----------------
    const val HDC_GROUPS = 3
    const val HDC_BITS = 10                   // grup başına bit
    const val HDC_DIM_PER_GROUP = D / HDC_GROUPS   // 282
    const val HDC_BITS_PER_SYMBOL = HDC_GROUPS * HDC_BITS  // 30
    const val BPSK_BITS_PER_SYMBOL = D        // 846

    // ---------------- Paket ----------------
    private val MAGIC = "SESZIP06".toByteArray(Charsets.US_ASCII)
    private const val PROTOCOL_VERSION = 6
    private const val MAX_WAV_SAMPLES = 24_000_000   // ~8 dk kayıt

    // ---------------- Sabitler ----------------
    private val PN31 = intArrayOf(
        1,0,0,0,0,1,0,0,1,0,1,1,0,0,1,1,1,1,1,0,0,0,1,1,0,1,1,1,0,1,0
    )

    /** Deterministik preamble: PN-31'i 846 taşıyıcıya yay. */
    val preamble: DoubleArray by lazy {
        DoubleArray(D) { i -> if (PN31[i % PN31.size] == 1) 1.0 else -1.0 }
    }

    private val preambleTemplate: ShortArray by lazy { makeSymbolSamples(preamble) }

    /** HDC kod tablosu: [grup][vektör*HDC_DIM_PER_GROUP + boyut] = ±1 (deterministik seed 777). */
    private val hdcCodebook: Array<ByteArray> by lazy {
        val r = Random(777L)
        val m = 1 shl HDC_BITS
        Array(HDC_GROUPS) { ByteArray(m * HDC_DIM_PER_GROUP) { if (r.nextBoolean()) 1 else -1 } }
    }

    // ==================================================================
    // VERİ SINIFLARI
    // ==================================================================

    class PacketData(
        val name: String,
        val data: ByteArray,
        val compressed: Boolean,
        val hdcMode: Boolean,
        val crcValid: Boolean,
    )

    class DecodedResult(
        val packet: PacketData,
        val mode: String,        // "BPSK" veya "HDC"
        val repeats: Int,
    )

    class DemodResult(val z: DoubleArray, val nDataSymbols: Int)

    // ==================================================================
    // FFT / IFFT (iteratif radix-2, in-place)
    // ==================================================================

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + half] * curR - im[i + k + half] * curI
                    val vi = re[i + k + half] * curI + im[i + k + half] * curR
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + half] = ur - vr; im[i + k + half] = ui - vi
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun ifft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fft(re, im)
        for (i in 0 until n) {
            re[i] /= n
            im[i] = -im[i] / n
        }
    }

    // ==================================================================
    // MODÜLASYON (dosya -> WAV örnekleri)
    // ==================================================================

    /** ±1 vektörü (D boyut) OFDM sembolüne çevirir (CP dahil). */
    private fun makeSymbolSamples(vec: DoubleArray): ShortArray {
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (k in 0 until D) {
            re[FIRST_SUBCARRIER + k] = vec[k]
            re[FFT_SIZE - FIRST_SUBCARRIER - k] = vec[k]   // Hermitian -> gerçek sinyal
        }
        ifft(re, im)
        var peak = 0.0
        for (i in 0 until FFT_SIZE) peak = max(peak, abs(re[i]))
        if (peak < 1e-9) peak = 1.0
        val scale = AMPLITUDE / peak
        val out = ShortArray(SYMBOL_LEN)
        for (i in 0 until FFT_SIZE) {
            out[i + CP] = (re[i] * scale).toInt().coerceIn(-32768, 32767).toShort()
        }
        for (i in 0 until CP) out[i] = out[FFT_SIZE + i]   // cyclic prefix
        return out
    }

    private fun bitsToSymbols(bits: BooleanArray): Array<DoubleArray> {
        val n = (bits.size + D - 1) / D
        return Array(n) { s ->
            DoubleArray(D) { k ->
                val idx = s * D + k
                if (idx < bits.size && bits[idx]) 1.0 else -1.0
            }
        }
    }

    private fun bitsToHdcSymbols(bits: BooleanArray): Array<DoubleArray> {
        val n = (bits.size + HDC_BITS_PER_SYMBOL - 1) / HDC_BITS_PER_SYMBOL
        return Array(n) { s ->
            var value = 0
            for (b in 0 until HDC_BITS_PER_SYMBOL) {
                val idx = s * HDC_BITS_PER_SYMBOL + b
                val bit = idx < bits.size && bits[idx]
                value = (value shl 1) or (if (bit) 1 else 0)
            }
            hdcEncodeVector(value)
        }
    }

    private fun hdcEncodeVector(value: Int): DoubleArray {
        val vec = DoubleArray(D)
        for (g in 0 until HDC_GROUPS) {
            val shift = HDC_BITS * (HDC_GROUPS - 1 - g)
            val v = (value shr shift) and ((1 shl HDC_BITS) - 1)
            val row = hdcCodebook[g]
            val base = v * HDC_DIM_PER_GROUP
            for (j in 0 until HDC_DIM_PER_GROUP) {
                vec[g * HDC_DIM_PER_GROUP + j] = row[base + j].toDouble()
            }
        }
        return vec
    }

    /** WAV üretir: preamble + data sembolleri (repeats kez). */
    fun modulateToWav(
        payload: ByteArray, name: String, hdcMode: Boolean, repeats: Int
    ): ByteArray {
        val (data, compressed) = compress(payload, true)
        val packet = buildPacket(data, name, compressed, hdcMode)
        val bits = packetToBits(packet)
        val symbols = if (hdcMode) bitsToHdcSymbols(bits) else bitsToSymbols(bits)

        val total = (1 + symbols.size.toLong() * repeats) * SYMBOL_LEN
        if (total > MAX_WAV_SAMPLES) {
            throw IllegalArgumentException("Ses çok uzun olur. Küçük dosya seç ya da hızlı mod kullan.")
        }
        val out = ShortArray(total.toInt())
        var pos = 0
        System.arraycopy(preambleTemplate, 0, out, pos, SYMBOL_LEN); pos += SYMBOL_LEN
        repeat(repeats) {
            for (sym in symbols) {
                val s = makeSymbolSamples(sym)
                System.arraycopy(s, 0, out, pos, SYMBOL_LEN); pos += SYMBOL_LEN
            }
        }
        return toWav(out)
    }

    // ==================================================================
    // DEMODÜLASYON (WAV -> Z matrisi)
    // ==================================================================

    /** Senkron: kaba->ince arama ile preamble'ın başlangıç örnek indeksini bul. */
    fun findSync(samples: DoubleArray): Int {
        val n = samples.size - SYMBOL_LEN
        if (n <= 0) return -1
        val tpl = preambleTemplate
        fun corr(off: Int): Double {
            var acc = 0.0
            for (i in 0 until SYMBOL_LEN) acc += tpl[i] * samples[off + i]
            return abs(acc)
        }
        var best = 0
        var bestScore = -1.0
        var off = 0
        while (off <= n) {
            val s = corr(off)
            if (s > bestScore) { bestScore = s; best = off }
            off += 256
        }
        var lo = (best - 256).coerceAtLeast(0)
        var hi = (best + 256).coerceAtMost(n)
        bestScore = -1.0
        best = lo
        off = lo
        while (off <= hi) {
            val s = corr(off)
            if (s > bestScore) { bestScore = s; best = off }
            off += 8
        }
        lo = (best - 8).coerceAtLeast(0)
        hi = (best + 8).coerceAtMost(n)
        bestScore = -1.0
        best = lo
        for (off in lo..hi) {
            val s = corr(off)
            if (s > bestScore) { bestScore = s; best = off }
        }
        return if (bestScore > 0) best else -1
    }

    /** Kayıttan Z matrisini çıkarır (preamble kanal kestirimi + ZF eşitleme). */
    fun demodulate(samples: DoubleArray): DemodResult? {
        val start = findSync(samples)
        if (start < 0) return null
        val totalSymbols = (samples.size - start) / SYMBOL_LEN
        if (totalSymbols < 2) return null
        val nData = totalSymbols - 1

        // Preamble FFT -> kanal kestirimi H[k]
        val preRe = DoubleArray(FFT_SIZE)
        val preIm = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) preRe[i] = samples[start + CP + i]
        fft(preRe, preIm)
        val hRe = DoubleArray(D)
        val hIm = DoubleArray(D)
        for (k in 0 until D) {
            val idx = FIRST_SUBCARRIER + k
            hRe[k] = preRe[idx] / preamble[k]
            hIm[k] = preIm[idx] / preamble[k]
        }

        val z = DoubleArray(nData * D)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (s in 0 until nData) {
            val base = start + (s + 1) * SYMBOL_LEN
            for (i in 0 until FFT_SIZE) re[i] = samples[base + CP + i]
            for (i in 0 until FFT_SIZE) im[i] = 0.0
            fft(re, im)
            for (k in 0 until D) {
                val idx = FIRST_SUBCARRIER + k
                val hr = hRe[k]; val hi = hIm[k]
                val mag2 = hr * hr + hi * hi
                z[s * D + k] = if (mag2 > 1e-12)
                    (re[idx] * hr + im[idx] * hi) / mag2   // real(ZF eşitleme)
                else 0.0
            }
        }
        return DemodResult(z, nData)
    }

    // ==================================================================
    // DECODE
    // ==================================================================

    private fun packBits(bits: BooleanArray): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        for (i in bits.indices) {
            if (bits[i]) out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
        }
        return out
    }

    private fun decodeBpsk(z: DoubleArray, nData: Int): ByteArray {
        val bits = BooleanArray(nData * D)
        for (i in 0 until nData * D) bits[i] = z[i] > 0
        return packBits(bits)
    }

    private fun decodeHdc(z: DoubleArray, nData: Int): ByteArray {
        val bits = BooleanArray(nData * HDC_BITS_PER_SYMBOL)
        var bi = 0
        val m = 1 shl HDC_BITS
        for (s in 0 until nData) {
            var value = 0
            for (g in 0 until HDC_GROUPS) {
                var best = 0
                var bestScore = -1e300
                val row = hdcCodebook[g]
                val gz = g * HDC_DIM_PER_GROUP
                for (v in 0 until m) {
                    var acc = 0.0
                    val base = v * HDC_DIM_PER_GROUP
                    for (j in 0 until HDC_DIM_PER_GROUP) acc += row[base + j] * z[s * D + gz + j]
                    if (acc > bestScore) { bestScore = acc; best = v }
                }
                value = (value shl HDC_BITS) or best
            }
            for (b in 0 until HDC_BITS_PER_SYMBOL) {
                bits[bi++] = ((value shr (HDC_BITS_PER_SYMBOL - 1 - b)) and 1) == 1
            }
        }
        return packBits(bits)
    }

    /** Tekrar kopyalarını soft-combine eder (kova entegrasyonu — v0.4'ten miras). */
    private fun combineRepeats(z: DoubleArray, nData: Int, repeats: Int): Pair<DoubleArray, Int> {
        val nPack = nData / repeats
        val zc = DoubleArray(nPack * D)
        for (r in 0 until repeats) {
            for (s in 0 until nPack) {
                val src = (r * nPack + s) * D
                val dst = s * D
                for (k in 0 until D) zc[dst + k] += z[src + k]
            }
        }
        return zc to nPack
    }

    /** WAV'ı çözer: senkron + eşitleme + otomatik mod (BPSK/HDC) + tekrar denemeleri. */
    fun decodeWav(wavBytes: ByteArray): DecodedResult {
        val samples = parseWav(wavBytes)
        val x = DoubleArray(samples.size) { samples[it].toDouble() }
        val dr = demodulate(x)
            ?: throw IllegalArgumentException("Senkron bulunamadı — bu ses SesZip v0.6 ile üretilmiş olmalı (48 kHz).")
        val nData = dr.nDataSymbols

        var best: PacketData? = null
        var bestMode = ""
        var bestRepeats = 1

        // BPSK (hızlı) — R=1..3
        for (r in 1..3) {
            if (nData % r != 0) continue
            val (zc, nPack) = if (r == 1) dr.z to nData else combineRepeats(dr.z, nData, r)
            val raw = decodeBpsk(zc, nPack)
            val p = parsePacket(raw, false)
            if (p != null) {
                if (p.crcValid) return DecodedResult(p, "BPSK", r)
                best = p; bestMode = "BPSK"; bestRepeats = r
            }
        }
        // HDC (sağlam) — R=1..3
        for (r in 1..3) {
            if (nData % r != 0) continue
            val (zc, nPack) = if (r == 1) dr.z to nData else combineRepeats(dr.z, nData, r)
            val raw = decodeHdc(zc, nPack)
            val p = parsePacket(raw, true)
            if (p != null) {
                if (p.crcValid) return DecodedResult(p, "HDC", r)
                best = p; bestMode = "HDC"; bestRepeats = r
            }
        }
        if (best != null) return DecodedResult(best, bestMode, bestRepeats)
        throw IllegalArgumentException("Paket çözülemedi — kayıt çok bozuk ya da farklı bir uygulamayla üretilmiş.")
    }

    // ==================================================================
    // PAKET
    // ==================================================================

    private fun buildPacket(
        data: ByteArray, name: String, compressed: Boolean, hdcMode: Boolean
    ): ByteArray {
        val nb = name.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val flags = (if (compressed) 1 else 0) or (if (hdcMode) 1 shl 1 else 0)
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(PROTOCOL_VERSION)
        out.write(flags)
        out.write(HDC_GROUPS)
        out.write(HDC_BITS)
        out.write(nb.size)
        out.write(nb)
        for (shift in 56 downTo 0 step 8)
            out.write(((data.size.toLong() shr shift) and 0xFF).toInt())
        val crc = CRC32().apply { update(data) }.value.toInt()
        for (shift in 24 downTo 0 step 8)
            out.write(((crc.toLong() shr shift) and 0xFF).toInt())
        out.write(data)
        return out.toByteArray()
    }

    private fun parsePacket(raw: ByteArray, expectedHdc: Boolean): PacketData? {
        val minLen = 8 + 1 + 1 + 1 + 1 + 1 + 8 + 4
        if (raw.size < minLen) return null
        if (!raw.copyOfRange(0, 8).contentEquals(MAGIC)) return null
        if (raw[8].toInt() != PROTOCOL_VERSION) return null
        val flags = raw[9].toInt()
        val hdc = ((flags shr 1) and 1) == 1
        if (hdc != expectedHdc) return null
        val groups = raw[10].toInt() and 0xFF
        val bpg = raw[11].toInt() and 0xFF
        if (groups != HDC_GROUPS || bpg != HDC_BITS) return null
        val nameLen = raw[12].toInt() and 0xFF
        var off = 13 + nameLen
        if (off + 12 > raw.size) return null
        val name = String(raw, 13, nameLen, Charsets.UTF_8)
        var payloadLen = 0L
        for (i in 0 until 8) payloadLen = (payloadLen shl 8) or (raw[off + i].toLong() and 0xFF)
        off += 8
        var crcExpected = 0L
        for (i in 0 until 4) crcExpected = (crcExpected shl 8) or (raw[off + i].toLong() and 0xFF)
        off += 4
        if (payloadLen > raw.size - off) return null
        val payload = raw.copyOfRange(off, off + payloadLen.toInt())
        val crcOk = (CRC32().apply { update(payload) }.value.toLong() and 0xFFFFFFFFL) == crcExpected
        val compressed = (flags and 1) == 1
        var data = payload
        var valid = crcOk
        if (compressed && crcOk) {
            try {
                data = decompress(payload)
            } catch (e: Exception) {
                valid = false
                data = payload
            }
        }
        return PacketData(name, data, compressed, hdc, valid)
    }

    private fun packetToBits(packet: ByteArray): BooleanArray {
        val bits = BooleanArray(packet.size * 8)
        for (i in packet.indices) {
            for (b in 0 until 8) bits[i * 8 + b] = ((packet[i].toInt() shr (7 - b)) and 1) == 1
        }
        return bits
    }

    // ==================================================================
    // SIKIŞTIRMA
    // ==================================================================

    fun compress(data: ByteArray, tryCompress: Boolean): Pair<ByteArray, Boolean> {
        if (!tryCompress || data.isEmpty()) return data to false
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            val c = out.toByteArray()
            if (c.size < data.size) c to true else data to false
        } finally {
            deflater.end()
        }
    }

    private fun decompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ==================================================================
    // WAV G/Ç
    // ==================================================================

    fun toWav(samples: ShortArray): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        writeLeInt(out, 36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeLeInt(out, 16)
        writeLeShort(out, 1)
        writeLeShort(out, 1)
        writeLeInt(out, SAMPLE_RATE)
        writeLeInt(out, SAMPLE_RATE * 2)
        writeLeShort(out, 2)
        writeLeShort(out, 16)
        out.write("data".toByteArray())
        writeLeInt(out, dataSize)
        val bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        out.write(bb.array())
        return out.toByteArray()
    }

    /** WAV'ı PCM16 mono örneklere çevirir (38-48 kHz, otomatik 48k'e resample). */
    fun parseWav(data: ByteArray): ShortArray {
        if (data.size < 44 || String(data, 0, 4) != "RIFF" || String(data, 8, 4) != "WAVE")
            throw IllegalArgumentException("Geçerli bir WAV dosyası değil")
        var off = 12
        var fmtOff = -1
        var dataOff = -1
        var dataSize = 0
        while (off + 8 <= data.size) {
            val id = String(data, off, 4)
            val sz = leInt(data, off + 4)
            if (id == "fmt ") fmtOff = off + 8
            if (id == "data") { dataOff = off + 8; dataSize = sz; break }
            off += 8 + sz + (sz and 1)
        }
        if (fmtOff < 0 || dataOff < 0) throw IllegalArgumentException("WAV parçaları bulunamadı")
        val format = leShort(data, fmtOff)
        val channels = leShort(data, fmtOff + 2)
        val sampleRate = leInt(data, fmtOff + 4)
        val bits = leShort(data, fmtOff + 14)
        if (format != 1) throw IllegalArgumentException("Sadece PCM WAV desteklenir")
        if (channels !in 1..2) throw IllegalArgumentException("1-2 kanal desteklenir")
        if (sampleRate < 38000 || sampleRate > 48000)
            throw IllegalArgumentException("Örnekleme hızı ${sampleRate}Hz desteklenmiyor (38-48 kHz beklenir)")

        val bytesPerSample = bits / 8
        val nSamples = dataSize / (channels * bytesPerSample)
        if (nSamples > MAX_WAV_SAMPLES) throw IllegalArgumentException("Kayıt çok uzun")
        val pcm = ShortArray(nSamples)
        for (i in 0 until nSamples) {
            var sum = 0.0
            for (ch in 0 until channels) {
                val p = dataOff + (i * channels + ch) * bytesPerSample
                val v = if (bits == 16) leShort(data, p).toInt() else ((data[p].toInt() and 0xFF) - 128) * 256
                sum += v / channels.toDouble()
            }
            pcm[i] = sum.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        if (sampleRate == SAMPLE_RATE) return pcm

        val ratio = sampleRate.toDouble() / SAMPLE_RATE
        val dstLen = (pcm.size / ratio).toInt()
        val res = ShortArray(dstLen)
        for (i in 0 until dstLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val s0 = pcm[i0].toDouble()
            val s1 = if (i0 + 1 < pcm.size) pcm[i0 + 1].toDouble() else s0
            res[i] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        return res
    }

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun writeLeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    // ==================================================================
    // YARDIMCILAR
    // ==================================================================

    fun estimateSeconds(payloadBytes: Long, hdcMode: Boolean, nameLen: Int, repeats: Int): Double {
        val header = 25L + nameLen.coerceIn(0, 255)
        val bits = (header + payloadBytes) * 8
        val bitsPerSym = if (hdcMode) HDC_BITS_PER_SYMBOL else BPSK_BITS_PER_SYMBOL
        val symbols = (bits + bitsPerSym - 1) / bitsPerSym
        return (1 + symbols * repeats).toDouble() * SYMBOL_LEN / SAMPLE_RATE
    }

    fun bitrate(hdcMode: Boolean): Double =
        if (hdcMode) SAMPLE_RATE.toDouble() / SYMBOL_LEN * HDC_BITS_PER_SYMBOL
        else SAMPLE_RATE.toDouble() / SYMBOL_LEN * BPSK_BITS_PER_SYMBOL

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.2f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun formatSeconds(sec: Double): String {
        if (sec < 60) return "${sec.toInt()} sn"
        val m = (sec / 60).toInt()
        val s = (sec % 60).toInt()
        if (m < 60) return "${m} dk ${s} sn"
        return "${m / 60} sa ${m % 60} dk"
    }
}
