# J.A.R.V.I.S — Android sesli asistan

Iron Man arayüzlü, Türkçe konuşan kişisel asistan.

## Kurulum
1. **Releases → latest → JARVIS.apk** dosyasını telefonda indir.
2. Aç, "Bilinmeyen kaynaklardan yüklemeye izin ver" de, kur.
3. İlk açılışta mikrofon, konum, rehber, arama ve bildirim izinlerini ver.
4. Yapay zekâ sohbeti için ⚙ → Claude API anahtarını gir (console.anthropic.com).

## Komut örnekleri
- "Saat kaç", "İstanbul'da hava nasıl"
- "Feneri aç / kapat", "Pil durumu"
- "Yarın 7:30'a alarm kur", "10 dakika zamanlayıcı"
- "Annemi ara", "WhatsApp'ı aç", "Sesi kıs", "Sonraki şarkı"
- "YouTube'da lofi müzik", "Google'da Ankara nüfusu"
- "Not al: süt alınacak", "Notlarımı oku"
- API anahtarıyla: her türlü soru + "Yarım saat sonra çamaşırı hatırlat" gibi doğal komutlar

## Derleme
Her `main` push'unda GitHub Actions APK'yı derler ve `latest` release'ine koyar.
