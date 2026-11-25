# B12 — Hatırlatmalar (telefon + Galaxy Watch6)

- B12-telefon.apk : telefona kur (dosyaya dokun, "bilinmeyen kaynak" izni ver).
- B12-saat.apk    : saate kur (aşağıda).

## Saate kurma (bir kerelik)
1. Saatte: Ayarlar > Saat hakkında > Yazılım > "Yazılım sürümü"ne 5 kez dokun (Geliştirici seçenekleri açılır).
2. Ayarlar > Geliştirici seçenekleri > ADB hata ayıklama AÇIK, Kablosuz hata ayıklama AÇIK.
   Saat ve bilgisayar aynı Wi-Fi'da olmalı.
3. Kablosuz hata ayıklama > "Yeni cihaz eşle": ekrandaki IP:port ve 6 haneli kodla bilgisayarda:
     adb pair IP:PORT KOD
     adb connect IP:PORT2      (Kablosuz hata ayıklama ekranındaki ana IP:port)
     adb install -r B12-saat.apk
   adb: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

## Nasıl çalışır
- Her cihaz alarmlarını kendisi kurar: telefon uzaktayken de saat titrer.
- Telefon ile saat Bluetooth üzerinden eşitlenir (internet gerekmez). Ayrıyken yapılan
  değişiklikler bağlantı gelince aktarılır, son yapılan değişiklik geçerli olur.
- Bildirimde: Yaptım / 10 dk ertele / (süre verildiyse) Başla. Birinde basınca öbüründen de kalkar.
- Samsung'da: uygulamadaki "Kısıtlamayı kaldır" uyarısına dokun (pil uyutması alarmları geciktirebilir).

Derleme: build.bat (debug) / build.bat release. İmza anahtarı keystore\ klasöründe: telefon ve saat
uygulaması aynı anahtarla imzalanmalı, yoksa birbirini görmez. Anahtarı silme.
