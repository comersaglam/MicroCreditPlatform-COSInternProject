# Ertelenenler — nereye kadar getirdik, neyi bıraktık

> **Bu dosya ne işe yarar:** Aşağıdakilerin hepsi **bilinçli** karardı, hiçbiri unutulmuş iş
> değil. Ama altı ay sonra koda bakan (sen dahil) "burası neden yarım?" diye soracak. Cevaplar
> burada, gerekçesiyle ve nereye bakması gerektiğiyle.
>
> Son güncelleme: 2026-08-06, FAZ 4 (ağ + sync) bitiminde.
> Kalıcı adım günlüğü: [progress.md](progress.md). Kararların gerekçesi:
> [architecture-pos.md](architecture-pos.md), [veresiye-platform-tasarim.md](veresiye-platform-tasarim.md).

---

## Şu an neredeyiz (tek paragraf)

**app-pos** ağ katmanını tamamladı: Retrofit + DTO + mapper (`:core-network`), Hilt, diske
yazılan session, outbox + `SyncEngine`, WorkManager ile arka plan drain. Yazma yolu uçtan uca
kurulu — veresiye Room'a düşer, kuyruğa girer, sunucuya gönderilir. **Ama gönderdiği yer henüz
gerçek bir sunucu değil** (`backend/` boş, Prism statik mock). **app-mobile** hâlâ FAZ 3'te:
Room var, ağ yok. Sıradaki iş **FAZ 5 — backend**.

---

## A. app-pos — yarım bırakılanlar

### A.1 `login()` gerçek `AuthApi`'yi çağırmıyor  🔴 en büyük borç
**Nerede:** [`OfflineFirstRepository.kt:78`](../app-pos/core-data/src/main/java/com/example/app_pos/data/OfflineFirstRepository.kt) — `TODO(phase 4b)`

Giriş hâlâ **lokal** doğruluyor: Room'daki `users` tablosunda numarayı bulur, kendi ürettiği
sahte `SessionDto`'yu diske yazar. Ağ hiç kullanılmaz.

**Neden böyle:** Prism ayakta olmadan da uygulama açılabilsin diye. Aşama 7'nin asıl kazancı
(kapat-aç'ta login sormama) ağa bağımlı olmamalıydı.

**Ama yarısı gerçek:** üretilen session, **gerçek `SessionDto` şeklinde** `TokenStore`'a
(DataStore) yazılıyor. Yani kalıcılık yolu zaten çalışıyor; backend gelince **sadece token'ın
KAYNAĞI** değişecek — `remote.requestOtp()` → `remote.verifyOtp()` → aynı `tokens.save()`.

### A.2 `OtpService` mock — her kod kabul ediliyor
**Nerede:** [`OtpService.kt:41`](../app-pos/app/src/main/java/com/example/app_pos/data/OtpService.kt) — `verifyOtp` **her zaman `true`**

`requestOtp` hiçbir şey göndermiyor, `verifyOtp` girilen kodu kontrol etmiyor. Ekran, durumlar
(`SENDING/READY/VERIFYING/DONE/ERROR`) ve `hasApp` dalı (app-push vs SMS) gerçek — sadece içi boş.

**Mimari gerilim (kayıt altına alınmıştı):** OTP zorunlu ↔ offline-first çelişiyor. Sinyalsizken
onay nasıl alınacak? Şimdilik mock bypass ediyor; backend gelince offline onay politikası
(`PENDING` durumu?) yeniden konuşulacak.

### A.3 `RemoteDataSource`'ın ~25 metodundan **1'i** çağrılıyor
**Nerede:** [`RemoteDataSource.kt`](../app-pos/core-data/src/main/java/com/example/app_pos/data/remote/RemoteDataSource.kt)

Tek gerçek çağrı [`SyncEngine.kt:70`](../app-pos/core-data/src/main/java/com/example/app_pos/data/sync/SyncEngine.kt)'teki `createTransaction`. **Okuma yolu tamamen lokal** —
müşteri listesi, bakiye, geçmiş hepsi Room'dan geliyor, sunucudan hiç okunmuyor.

**Neden kasıtlı:** offline-first'ün tanımı bu. Okumanın ağa bağlanması ayrı bir tasarım işi
(cache invalidation, "hangisi doğru" sorusu). Yazma yolu önce, çünkü kaybolursa geri gelmeyen
tek şey o.

**Neden yine de yazıldı:** app-mobile bu modülü **düz kopyalayacak** — 7 API'nin hepsi sarmalı
ki simetri korunsun.

### A.4 Gelen onay (approvals) UI'si yok
**Nerede:** `approvals` tablosu app-pos Room'unda **var ama hiç okunmuyor** (entity + DAO
iskele; `approvalDao()` çağıran tek satır yok).

app-mobile'da bu **aktif** (Onaylar sekmesi). app-pos'ta esnaf sadece onay **gönderiyor**,
gelen onay kutusu yok. Üç onay hattının ([db-schema.md A.6](db-schema.md)) app-pos ayağı eksik.

### A.5 `observeUnsentCount()` — yazıldı, hiçbir ekran kullanmıyor
**Nerede:** [`Repository.kt`](../app-pos/core-domain/src/main/java/com/example/app_pos/model/Repository.kt) → `LocalSource` → `OutboxDao.observeCount()`

Kuyrukta bekleyen kayıt sayısını veren zincir baştan sona kurulu, tek tüketicisi yok
(test fake'i hariç). **Dashboard'a "N kayıt gönderilmedi" rozeti = tek fragment değişikliği.**
Ucuz ve faydalı; backend'den sonraki ilk küçük iş için iyi aday.

### A.6 `TokenStore` şifresiz
**Nerede:** [`DataStoreTokenStore.kt`](../app-pos/core-network/src/main/java/com/example/app_pos/network/auth/DataStoreTokenStore.kt)

Dokümanlar "şifreli saklanır (EncryptedSharedPreferences / Keystore)" diyordu; düz DataStore
kullanıldı. **Gerekçe:** `EncryptedSharedPreferences` **deprecated**, ve dükkân tezgâhındaki bir
POS için app sandbox'ı + cihazın dosya-tabanlı şifrelemesi gerçekçi tehdidi karşılıyor.

`TokenStore` bir **arayüz** olduğu için Keystore destekli impl sonradan drop-in:
`DataModule`'de tek binding değişir, hiçbir çağıran etkilenmez.

### A.7 Room destructive migration
**Nerede:** [`DataModule.kt:37`](../app-pos/core-data/src/main/java/com/example/app_pos/data/di/DataModule.kt) — `fallbackToDestructiveMigration(dropAllTables = true)`

Şema değişince veri **siliniyor**. Mock fazında doğru: gerçek kullanıcı verisi yok, migration
yazmak boşa emek. Şemalar `exportSchema = true` ile diske yazılıyor (`core-data/schemas/`) —
gerçek migration'lar backend + canlı veri geldiğinde bu JSON'lar diff'lenerek yazılacak.
Şu an DB **v2**.

### A.8 `:app`'te `BuildConfig` kapalı
`buildConfig` feature'ı sadece `:core-network`'te açık. `:app` debug/release ayrımını
`NetworkConfig.isDebug` üzerinden okuyor ([`App.kt`](../app-pos/app/src/main/java/com/example/app_pos/App.kt) — WorkManager log seviyesi). Aynı bilgi için ikinci bir
generated sınıf üretmeye gerek yok. Not düşüldü ki ileride biri "neden `BuildConfig.DEBUG`
yazamıyorum?" diye takılmasın.

---

## B. app-mobile — henüz FAZ 3'te

app-pos FAZ 4'ü bitirdi, app-mobile **bitirmedi**. Aradaki fark:

| Konu | app-pos | app-mobile |
|---|---|---|
| `:core-network` | ✅ var | ❌ **yok** |
| Outbox + WorkManager | ✅ var | ❌ yok |
| DI | ✅ Hilt | ❌ `RepositoryProvider` (elle singleton) |
| Session | ✅ diskte (`TokenStore`) | ❌ **RAM'de** (app kapanınca gider) |
| `createdAt` formatı | ✅ ISO-8601 (`yyyy-MM-dd'T'HH:mm:ss'Z'`) | ❌ **`dd.MM.yyyy HH:mm`** |
| Approvals | iskele | ✅ **aktif** |

### B.1 Timestamp formatı ayrışması  ⚠️ dikkat
**Nerede:** [`RoomRepository.kt:331`](../app-mobile/core-data/src/main/java/com/example/app_pos/data/RoomRepository.kt) — hâlâ `"dd.MM.yyyy HH:mm"`

app-pos Aşama 0'da ISO-8601'e geçti, **app-mobile geçmedi**. Yani iki app **aynı DB şemasına
farklı formatta `created_at` yazıyor**. Backend tek format bekleyecek → mirror turunda ilk iş bu.

İlgili: app-mobile'ın DAO'ları hâlâ `substr()` sıralama hack'ini kullanıyor
([`Daos.kt:31`](../app-mobile/core-data/src/main/java/com/example/app_pos/data/db/dao/Daos.kt)); app-pos'ta ISO'ya geçilince o hack silinmişti (düz
`ORDER BY createdAt` yetiyor).

### B.2 Mirror sırası (öneri)
Backend bittikten sonra: (1) timestamp ISO'ya geçir, (2) `:core-network` kopyala,
(3) Hilt'e geç, (4) outbox + WorkManager, (5) approval/buyer uçlarını gerçek backend'e bağla.

---

## C. Backend — `backend/` klasörü BOŞ

Contract ve şema **yazılı ve hazır**, implementasyon yok:
- [`shared-contracts/openapi.yaml`](../shared-contracts/openapi.yaml) — 852 satır, 24 uç, Redocly lint temiz
- [`db-schema.md`](db-schema.md) — Bölüm A'da 6 tablo, SQL'e hazır

**Backend'in onurlandırması gerekenler** (contract'tan çıkan, client bunlara güveniyor):
1. `Idempotency-Key == transaction_id`; aynı key+aynı gövde → **200** (orijinal), aynı
   key+**farklı** gövde → **409**, sessiz overwrite DEĞİL
2. Bakiye **türetilir, saklanmaz** — `core-domain/Ledger.kt::balanceOf` ile birebir aynı
3. Append-only: UPDATE/DELETE yok, düzeltme = ters işaretli yeni satır
4. `seller_id` gövdeden **asla** güvenilmez, token'dan
5. Yeni enum değeri = **breaking change** (client bilinmeyen `type`'ı düşürüyor —
   `EnumMapping.kt`)
6. Telefon E.164 normalizasyonu sunucuda, `RoomLocalDataSource.storedPhone` ile aynı
7. `POST /auth/otp/verify` **auto-register YAPMAZ** → kayıtsız numara `404 user_not_found`,
   client `POST /users` ile ayrı adımda kaydeder (KESİN KARAR, [api-endpoints.md](api-endpoints.md) A.1)

---

## D. FAZ 8'e bırakılanlar (contract'ta `future` tag'li)

Bunlar **kod iskeleti olarak var** ama hiçbir yere bağlı değil — "imza hazır, gövde sonra" deseni.

| Ne | Nerede |
|---|---|
| `/sync/transactions` (toplu drain) | `SyncApi` + `RemoteDataSource.syncTransactions` |
| `/transactions/{id}/settle` (PGW fişi) | `SyncApi.settle` |
| `/insights` (ML risk skoru) | `InsightsDto` — KVKK incelemesine bağlı (FAZ 7) |
| `/me/credit-offers` (mikrokredi) | `CreditOfferDto` + `credit_offers` tablosu |
| `/devices` (FCM push) | `DeviceCreateDto` + `devices` tablosu |
| `fx_rates` (döviz/altın geçmişi) | Room entity + DAO, doldurulmuyor |
| `audit_log` (denetim izi) | Room entity + DAO — FAZ 7 regülasyon |
| PGW settle intent'i (Aşama 0'ın **tersi** yön) | henüz kod yok |

**Kural (plan kararı):** ileri-faz tabloları **koda da eklenir**, sadece dokümanda kalmaz —
"şu an gerekeni detaylı, geleceği taslak". Böylece şema bir kez oturur, sonradan migration
gerekmez.
