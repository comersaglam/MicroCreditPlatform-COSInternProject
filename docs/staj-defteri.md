# Staj Defteri — 20 İş Günü

Kaynak: [progress.md](progress.md) (Tur 1–40e). 40 turluk gerçek çalışma günlüğü
20 iş gününe yayıldı. Her gün = formdaki 3 satır + 8 saat. Cumartesi boş.

---

## Hafta 1 — 20.07.2026 → 26.07.2026

**Pazartesi 20.07 · 8 saat**
- Tanışma günü, yapılacak mikrokredi sistemi
- Projesini, detaylarını, kullanılacak tech stack'i
- Öğrenmem gereken teknolojileri konuştuk

**Salı 21.07 · 8 saat**
- Geliştirme ortamını kurdum: Android Studio, JDK,
- emülatör, monorepo klasör yapısı. Kotlin ve Android
- temellerini çalışıp kendime terim sözlüğü çıkardım.

**Çarşamba 22.07 · 8 saat**
- Sistemin katmanlı mimarisini çizdim: POS uygulaması,
- müşteri uygulaması, ortak backend. İki projeyi Compose
- şablonundan XML views'a çevirip cihazda çalıştırdım.

**Perşembe 23.07 · 8 saat**
- 9 fazlı yol haritasını yazdım. POS'un ilk üç ekranını
- MVVM ile kurdum: ViewModel + StateFlow, RecyclerView,
- ListAdapter. Parayı Long (kuruş) olarak tuttum.

**Cuma 24.07 · 8 saat**
- Ayrı Activity'leri tek Activity + Fragment yapısına
- taşıdım. Navigation Component, nested graph ve Safe
- Args ile tip güvenli ekran geçişleri kurdum.

---

## Hafta 2 — 27.07.2026 → 02.08.2026

**Pazartesi 27.07 · 8 saat**
- Ödeme ekranını ayrı bir APK'ya (mock-pos) ayırdım.
- İki uygulama arası intent devrini yazdım: custom
- action, extra ve Android 11 package visibility.

**Salı 28.07 · 8 saat**


**Çarşamba 29.07 · 8 saat**
- Uygulaması olmayan müşteri kaydı açma akışını yazdım.
- Sistemi telefon numarası eksenine taşıyıp her borcu
- OTP onayından geçiren akışı kurdum.

**Perşembe 30.07 · 8 saat**
- Cihaz testinde çıkan crash'i logcat crash buffer ile
- debug ettim. Kök neden navigation graph'a dıştan giriş
- kuralı ve Activity launch mode'uydu; ikisini düzelttim.

**Cuma 31.07 · 8 saat**
- Saf Kotlin modülü (core-domain) açıp domain
- modellerini oraya taşıdım. Kullanıcı/satıcı modelini
- yazıp "müşteri kaydı" ile "hesap" ayrımını netleştirdim.

---

## Hafta 3 — 03.08.2026 → 09.08.2026

**Pazartesi 03.08 · 8 saat**
- POS'a esnaf girişi ekledim; login'i navigation'ın
- başlangıç ekranı yaparak kapı kurdum. Profil ekranı,
- oturum/token mantığı ve POS eşleştirme akışı yazıldı.

**Salı 04.08 · 8 saat**
- Her işleme satıcı sahipliği ekleyip tüm okumaları
- satıcı kapsamlı hale getirdim. Giriş/kayıt ayrımını
- yaptım, telefon normalize hatasını log ile buldum.

**Çarşamba 05.08 · 8 saat**
- Müşteri uygulamasının alıcı tarafını baştan sona
- yazdım: borçlarım, satıcı detayı, onay kartları,
- profil. Ortak mavi paleti iki uygulamaya uyguladım.

**Perşembe 06.08 · 8 saat**
- Aynı uygulamaya satıcı rolünü ekledim (tek hesap iki
- rol), alt menü dinamikleşti. Yazmayı "onaya gönder"
- akışına çevirip tek yazma noktasını korudum.

**Cuma 07.08 · 8 saat**
- POS devrini Token'ın gerçek orderBody JSON formatına
- taşıdım (sepet + kalemler, org.json ile ayrıştırma).
- Ardından tüm API uçlarını ve DB şemasını tasarladım.

---

## Hafta 4 — 10.08.2026 → 16.08.2026

**Pazartesi 10.08 · 8 saat**
- OpenAPI sözleşmesini yazdım, Redocly ile lint ettim.
- Prism ile mock sunucu kaldırıp örnek yanıtları demo
- verisiyle hizaladım ve curl ile doğruladım.

**Salı 11.08 · 8 saat**
- POS'ta core-data modülünü açıp bellekteki sahte veriyi
- Room'a taşıdım: entity, DAO, mapper, KSP kod üretimi.
- Veri artık uygulama kapansa da cihazda kalıcı.

**Çarşamba 12.08 · 8 saat**
- Aynı Room katmanını müşteri uygulamasına kurdum,
- müşteri ekleme akışını üç dala ayırdım. Tarih sıralama
- ve para formatı hatasını iki projede birden düzelttim.

**Perşembe 13.08 · 8 saat**
- Ağ katmanını bağladım: Retrofit, OkHttp, Moshi, token
- interceptor, Hilt. Oturumu DataStore'a taşıdım; outbox
- kuyruğu + WorkManager ile arka plan senkronu yazdım.

**Cuma 14.08 · 8 saat**
- Backend'i kurdum: FastAPI, PostgreSQL, Docker Compose,
- JWT giriş ve idempotent ledger uçları. İki uygulamayı
- gerçek sunucuya bağlayıp cihazda uçtan uca test ettim.
