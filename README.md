# 📡 SesZip v0.6 — OFDM + Hyperdimensional Computing

> **Paradigma:** "Bit akışı olmadan bilgi taşı." Dosyayı bit dizisine çevirip CRC/FEC ile göndermek yerine, bir dosyayı **846 OFDM taşıyıcısının üzerine** iki moddan biriyle boya:
>
> - **🚀 BPSK (hızlı):** her taşıyıcıya 1 bit → **~17.6 kbps** (20 kat öncekinden hızlı)
> - **🛡️ HDC (sağlam):** veri, rastgele ±1 **yüksek boyutlu vektörlere** (3 grup × 282 boyut × 10 bit) kodlanır; alıcı **korelasyonla en yakın geometrik komşuyu** bulur → taşıyıcıların %40'ı kaybolsa bile dosya hatasız çıkar (~625 bps)

Android demo uygulaması: alt navigasyon (Dönüştür / Çöz / Fikirler), otomatik APK derleyen GitHub Actions workflow.

![build](https://img.shields.io/github/actions/workflow/status/ahmetbysoy/SesZip/build-apk.yml?label=APK%20Build)

---

## 🚀 APK'yı İndir

Yerelde derlemene gerek yok — her push'ta GitHub Actions otomatik derler:

1. Repo sayfasında **Actions** sekmesine git
2. En üstteki **"Build APK"** run'ına tıkla
3. Alttaki **Artifacts** bölümünden `SesZip-debug-apk` indir (veya Release'e bak)

> Tag açarsan (`git tag v0.6.0 && git push origin v0.6.0`) APK otomatik olarak **Release**'e eklenir.

---

## 🧠 Nasıl Çalışıyor?

### 1. Gerçek OFDM (IFFT/FFT)
```
48 kHz örnekleme • FFT 2048 • cyclic prefix 256 • 846 taşıyıcı (375 Hz..20 kHz, 23.4 Hz aralık)
PN-31 preamble → senkron + kanal kestirimi → ZF eşitleme
```
Her OFDM sembolü 2304 örnek = ~20.8 sembol/sn. Hermitian simetrik spektrum → gerçek ses sinyali.

### 2. Mod A — BPSK (hızlı)
Her taşıyıcı 1 bit (±1). 846 bit/sembol → **17.6 kbps**. Basit, hızlı; taşıyıcı kaybına açık (kodlama yok).

### 3. Mod B — HDC (sağlam) — deneysel paradigma
```
Dosya → bitler → her 30 bit = 1 sembol
sembol → 3 grup × 10 bit
her grup → 282 boyutlu rastgele ±1 vektör (2^10'luk kod tablosundan, seed 777 ile üretilir)
vektörler → OFDM taşıyıcılarına BPSK
```
**Alıcı:** FFT → eşitleme → her grup için `scores[v] = Σ codebook[v][j] · z[j]` → **argmax** → 10 bit.
Bozulan taşıyıcılar vektörü biraz kaydırır ama doğru komşuya en yakın kalır → **zarif bozulma** (geometrik FEC). Kod tablosu hiçbir yerde saklanmaz, iki tarafta da aynı seed ile üretilir.

**Kanıt (Python simülasyonu, oda yankılı kanal):**

| Senaryo | HDC | Çıplak BPSK |
|---|---|---|
| SNR 0dB | ✅ hatasız | hatalı |
| %40 taşıyıcı kaybı @25dB | ✅ hatasız | ~%19 BER |
| %70 taşıyıcı kaybı @25dB | ✅ hatasız | ~%33 BER |
| %30 taşıyıcı işareti çevirme | ✅ hatasız | hatalı |

Shannon'a sadık: HDC kapasite eklemez, **sağlamlık satın alır** (boyut marjı).

### 4. Paket (protokol v6)
`SESZIP06` (8) + sürüm (1) + flags (sıkıştırma | mod) (1) + grup sayısı (1) + bit/grup (1) + isim uzunluğu (1) + isim + payload uzunluğu (8) + **CRC32** (4) + payload
- Deflate ile sıkıştırma denenir (küçülmezse ham)
- Tekrar (1-3×) → alıcı soft-değerleri toplayıp çözer (kova entegrasyonu)

---

## 📱 Uygulama (3 Sekme)

| Sekme | Ne yapar |
|---|---|
| **📤 Dönüştür** | Dosya seç → mod (BPSK/HDC) → tekrar → sese çevir → çal / WAV kaydet |
| **📥 Çöz** | WAV seç → otomatik mod algılama → orijinal dosyayı kaydet |
| **💡 Fikirler** | Paradigma vizyonu + geliştirme fikirleri |

---

## 🛠️ Yerelde Derleme (istersen)

```bash
git clone https://github.com/ahmetbysoy/SesZip.git
cd SesZip
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Gerekli: JDK 17, Android SDK (compileSdk 35).

---

## 📂 Proje Yapısı

```
SesZip/
├── .github/workflows/build-apk.yml   ← otomatik APK (Actions)
├── app/src/main/java/com/seszip/app/
│   ├── MainActivity.kt               ← Compose UI + 3 sekme
│   └── OfdmModem.kt                  ← FFT, OFDM, HDC, paket, WAV (saf Kotlin, bağımlılık yok)
├── gradle/wrapper/
└── README.md
```

---

## 🧪 Demo Sınırları (Dürüstçe)

- **BPSK:** 20MB altı dosya. **HDC:** 8KB altı (hesaplama maliyeti; küçük ve kritik veri için).
- 38-48 kHz PCM WAV çözülür (48k'e otomatik resample); üretilen ses 48 kHz mono.
- Kod tablosu sabit seed (777) — şifreleme değil, kodlama/steganografi demosu.
- Bu bir **şifreleme değildir**; gönderim güvenliği ayrı bir katman gerektirir (yol haritasında).

---

## 📜 Sürüm Geçmişi

- **v0.6.0** — tam yeniden doğuş: gerçek OFDM (846 taşıyıcı) + HDC (sağlam) / BPSK (hızlı) modları; FSK tamamen terk edildi. Python simülasyonuyla kanıtlandı.
- v0.1–v0.5 — katmanlı FSK dönemi (geçmişte kaldı: 160→480 bps, kova entegrasyonu, ultrasonik bant denemeleri).

---

## 📄 Lisans

MIT — serbestçe kullan, geliştir, çatalla. 🎉
