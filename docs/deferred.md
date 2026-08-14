# Ertelenenler — nereye kadar getirdik, neyi bıraktık

> **Bu dosya ne işe yarar:** Aşağıdakilerin hepsi **bilinçli** karardı, hiçbiri unutulmuş iş
> değil. Ama altı ay sonra koda bakan (sen dahil) "burası neden yarım?" diye soracak. Cevaplar
> burada, gerekçesiyle ve nereye bakması gerektiğiyle.
>
> Son güncelleme: 2026-08-13, Tur 38c (ikinci cihaz testi turu) sonrası.
> Kalıcı adım günlüğü: [progress.md](progress.md). Uygulama planı ve §0 kararları:
> [faz5-backend-plan.md](faz5-backend-plan.md). Kararların gerekçesi:
> [architecture-pos.md](architecture-pos.md), [veresiye-platform-tasarim.md](veresiye-platform-tasarim.md).

---

## Şu an neredeyiz (tek paragraf)

**Backend yazıldı ve app-pos ona bağlandı.** FastAPI + PostgreSQL 16 + Docker Compose;
contract'ın **Bölüm A'sının tamamı canlı (22 uç)**, `future` etiketli hiçbir uç yazılmadı.
Bakiye sunucuda `SUM` ile türetiliyor, `transactions` DB seviyesinde append-only (trigger),
idempotency üç yollu (201/200/409). **137 pytest.** app-pos'un `login()` mock'u silindi:
oturum artık sunucunun ürettiği gerçek JWT, giriş telefon+OTP iki adımı üzerinden.

**app-mobile mirror turu 3/4:** ISO timestamp + DB v2 (Tur 36), `:core-network` + Hilt
(Tur 37), offline-first beste + gerçek login + outbox + WorkManager (Tur 38). app-mobile
artık app-pos ile **yazma tarafında simetrik**: aynı 4 modül, aynı Hilt grafiği, aynı
outbox/SyncEngine/WorkManager, aynı iki-adımlı gerçek login. **Tur 38b** ilk cihaz testinde
çıkan dört hatayı düzeltti (çift yazım, telefon normalizasyonu, onaysız yazma, sahte başarı),
**Tur 38c** ikinci turu (takılan onay, OTP UI, müşteri listesi) — 56 test, iki ayrı mutasyon
kontrolüyle doğrulandı. Kalan tek iş **Tur 39: pull ekseni + sunucu tek gerçeklik**.

**Ve yeni keşfedilen yapısal boşluk (§F):** sistemde **hiçbir okuma yolu yok**. İki client da
sunucuya sadece YAZIYOR; `GET /approvals`, `GET /customers`, `GET /transactions` kodda sarmalı
ama **sıfır çağıranı** var. Yani POS, app-mobile'dan gelen bir veresiye onayını **asla göremez**.

---

## A. app-pos — yarım bırakılanlar

### ~~A.1 `login()` gerçek `AuthApi`'yi çağırmıyor~~  ✅ KAPANDI (Tur 34)
Mock silindi. `Repository.login(phone): Boolean` yerine **iki metot**: `requestOtp(phone)` +
`signIn(phone, code)`. Oturum artık sunucunun ürettiği gerçek JWT (refresh token dahil), ve
**kayıtsız numara kararı sunucunun**: `verifyOtp` 404 `user_not_found` → `NeedsRegister`.
Lokal `findUserByPhone` kontrolü kaldırıldı — temiz kurulumda Room boş olduğu için hesabı olan
esnafı bile kayıt ekranına düşürüyordu. Giriş ekranına **OTP adımı eklendi** (tek ekran,
iki adım). Ayrıntı: [progress.md](progress.md) Tur 34.

### ~~A.2 `OtpService` mock — her kod kabul ediliyor~~  ⚠️ KISMEN (Tur 30/34)
**Giriş akışında kapandı:** kodu artık sunucu yargılıyor (`POST /auth/otp/verify`), yanlış kod
`401 invalid_code` alıyor. Sabit kod (`123456`) **sunucuda**, `backend/app/config.py`
`mock_otp_code` — gerçek SMS sağlayıcısı gelince sadece bu sabitin kaynağı değişecek.

**SATIŞ akışında hâlâ mock:** [`OtpService.kt:41`](../app-pos/app/src/main/java/com/example/app_pos/data/OtpService.kt) `verifyOtp` **her zaman `true`**.
Bu ayrı bir dikey (müşteri onayı), sunucu karşılığı `POST /approvals` ile **yazıldı** ama
app-pos henüz ona bağlanmadı → app-mobile mirror turunun işi.

**Mimari gerilim (hâlâ açık):** OTP zorunlu ↔ offline-first çelişiyor. Sinyalsizken onay nasıl
alınacak? Backend geldi ama bu soru cevaplanmadı — `/approvals`'ın UNCLAIMED dalı (anında yaz)
kısmi bir cevap, CLAIMED dalı hâlâ ağ istiyor.

### A.3 `RemoteDataSource`'ın ~25 metodundan **5'i** çağrılıyor
**Nerede:** [`RemoteDataSource.kt`](../app-pos/core-data/src/main/java/com/example/app_pos/data/remote/RemoteDataSource.kt)

Çağrılanlar: `createTransaction` ([`SyncEngine.kt:70`](../app-pos/core-data/src/main/java/com/example/app_pos/data/sync/SyncEngine.kt)) ve Tur 34'te eklenen
`requestOtp` / `verifyOtp` / `logout` / `register`. **Okuma yolu hâlâ tamamen lokal** —
müşteri listesi, bakiye, geçmiş hepsi Room'dan geliyor, sunucudan hiç okunmuyor.

Sunucu tarafı artık **hazır ve test edilmiş** (`GET /customers` bakiyeli, `/transactions`,
`/balances`), yani eksik olan tek şey client'ın onları çağırması + "hangisi doğru" kararı.

**Neden kasıtlı:** offline-first'ün tanımı bu. Okumanın ağa bağlanması ayrı bir tasarım işi
(cache invalidation, "hangisi doğru" sorusu). Yazma yolu önce, çünkü kaybolursa geri gelmeyen
tek şey o.

**Ama artık yeterli değil** — bu maddenin kapsamı §F'de genişletildi: eksik olan tek şey
"client uçları çağırmıyor" değil, pull deseninin **tamamı** (cursor, merge, zamanlayıcı,
çekilen varlık için domain tipi). Tur 39'un konusu.

**Neden yine de yazıldı:** app-mobile bu modülü **düz kopyalayacak** — 7 API'nin hepsi sarmalı
ki simetri korunsun.

### A.4 Gelen onay (approvals) UI'si yok
**Nerede:** `approvals` tablosu app-pos Room'unda **var ama hiç okunmuyor** (entity + DAO
iskele; `approvalDao()` çağıran tek satır yok).

app-mobile'da bu **aktif** (Onaylar sekmesi). app-pos'ta esnaf sadece onay **gönderiyor**,
gelen onay kutusu yok. Üç onay hattının ([db-schema.md A.6](db-schema.md)) app-pos ayağı eksik.

**Tur 39'un işi** — ama tek başına UI eklemek yetmez: kutuyu dolduracak bir **okuma yolu**
da yok (bkz. §F). app-mobile'ın Onaylar ekranı bile sadece lokal tabloyu okuyor.

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

**Tur 34'ten sonra bahis yükseldi (⚠️ yeniden değerlendirilmeli):** diskteki artık sahte bir
UUID değil, **gerçek bir JWT + 30 günlük refresh token**. Sızarsa o hesabın defterine yazma
yetkisi demek. Karar hâlâ savunulabilir (sandbox + FBE), ama gerekçesi "zaten sahte veri"
olmaktan çıktı — FAZ 7'de (regülasyon) bu maddeye tekrar bakılmalı. Ucuz ara adım: refresh
TTL'ini 30 günden kısaltmak (`backend/app/config.py` → `refresh_ttl_seconds`).

### A.7 Room destructive migration
**Nerede:** [`DataModule.kt:37`](../app-pos/core-data/src/main/java/com/example/app_pos/data/di/DataModule.kt) — `fallbackToDestructiveMigration(dropAllTables = true)`

Şema değişince veri **siliniyor**. Mock fazında doğru: gerçek kullanıcı verisi yok, migration
yazmak boşa emek. Şemalar `exportSchema = true` ile diske yazılıyor (`core-data/schemas/`) —
gerçek migration'lar backend + canlı veri geldiğinde bu JSON'lar diff'lenerek yazılacak.
Şu an DB **v2**.

**Tur 34'ten sonra risk AZALDI:** sunucu artık kayıtların sahibi, cihazdaki Room bir önbellek.
Bir wipe'ın kalıcı olarak kaybettiği tek şey **henüz gönderilmemiş outbox satırları** — geri
kalan her şey sunucudan yeniden okunabilir (o okuma yolu henüz yazılmadı, bkz. A.3).
Yani bu madde artık "veri kaybı" değil, "kuyruk kaybı" riski.

**Karşı yönde YENİ borç — Room şeması sunucudan geride:** backend'e migration 0002 ile
`customers.created_by_seller_id` eklendi (C.1), Room'da **yok**. Bugün zarar yok (cihaz
müşteri listesini lokalden okuyor), ama okuma yolu sunucuya bağlanınca bu alan gelecek.

### A.9 Telefon normalizasyonu bug'ı app-pos'ta HÂLÂ VAR  ⚠️ doğrulandı
**Nerede:** [`Daos.kt:65-66`](../app-pos/core-data/src/main/java/com/example/app_pos/data/db/dao/Daos.kt) (`LIKE '%..%'`) vs `:104`, `:111` (`=`);
[`PhoneFormat.kt`](../app-pos/app/src/main/java/com/example/app_pos/util/PhoneFormat.kt) hâlâ `:app`'te (veri katmanı erişemiyor)

app-mobile'da Tur 38b ile düzeltilen hata **app-pos'ta aynen duruyor**: `UserDao` substring
(`LIKE`), `CustomerDao` tam eşitlik (`=`), ikisi de SQL'de `REPLACE`'le normalize ediyor ve
ülke kodunu ele almıyor. Sonuç app-mobile'daki ile aynı olmalı: **login çalışır, claim sessizce
hiçbir satırı bulmaz** → müşteri UNCLAIMED kalır → onaysız-yazma dalına düşer.

**Cihazda doğrulanmadı** (app-pos hiç test edilmedi, §E), ama kod birebir aynı desen.
Düzeltme app-mobile'dakinin kopyası: `PhoneFormat`'ı `:core-domain`'e taşı, sorguları düz
`WHERE phone = :stored` yap, çağıranlar önce `toStored`'dan geçirsin.

### A.8 `:app`'te `BuildConfig` kapalı
`buildConfig` feature'ı sadece `:core-network`'te açık. `:app` debug/release ayrımını
`NetworkConfig.isDebug` üzerinden okuyor ([`App.kt`](../app-pos/app/src/main/java/com/example/app_pos/App.kt) — WorkManager log seviyesi). Aynı bilgi için ikinci bir
generated sınıf üretmeye gerek yok. Not düşüldü ki ileride biri "neden `BuildConfig.DEBUG`
yazamıyorum?" diye takılmasın.

---

## B. app-mobile — mirror 3/4 (Tur 36-37-38 yapıldı)

app-pos FAZ 4'ü bitirdi; app-mobile **yazma tarafında yetişti**. Güncel fark:

| Konu | app-pos | app-mobile |
|---|---|---|
| `:core-network` | ✅ var | ✅ **var (Tur 37)**, ve **çağrılıyor (Tur 38)** |
| DI | ✅ Hilt | ✅ **Hilt (Tur 37)** — `RepositoryProvider` silindi |
| `createdAt` formatı | ✅ ISO-8601 (`yyyy-MM-dd'T'HH:mm:ss'Z'`) | ✅ **ISO-8601 (Tur 36)**, DB v2 |
| Outbox + WorkManager | ✅ var | ✅ **var (Tur 38)** |
| Session | ✅ diskte (`TokenStore`) | ✅ **diskte (Tur 38)**, `prime()` runBlocking |
| Login | ✅ gerçek JWT (`requestOtp`/`signIn`) | ✅ **gerçek JWT (Tur 38)**, tek ekran iki adım |
| `LocalSource` arayüzü | ✅ var | ✅ **var (Tur 38)** |
| Okuma yolu (pull) | ❌ **yok** | ❌ **yok** → **Tur 39** (bkz. §F) |
| Approvals | iskele (gönderiyor, kutu yok) | ✅ kutu var; onay/ret **sunucuya gidiyor (Tur 38)** |

**Kalan asimetri kasıtlı:** app-mobile arka planda **pull yapmaz** (pil), sadece outbox
drain eder. Ön plan poll'u Tur 39'un işi — bkz. §F.3.

### ~~B.1 Timestamp formatı ayrışması~~  ✅ KAPANDI (Tur 36)
`nowStamp()` artık ISO-8601 UTC yazıyor, `substr()` sıralama hack'i **silindi** (düz
`ORDER BY createdAt`), DB **v2**'ye çıktı, `SeedCallback`'in 21 damgası
`backend/app/seed.py` ile **birebir aynı instant**'lara çevrildi (programla diff'lendi).
Yeni `app/util/TimeFormat.kt` gösterim için ISO'yu cihazın saat dilimine çeviriyor.

**Bonus olarak bulunan gerçek bug:** `ApprovalDao.observePendingFor`'un **ORDER BY'ı yoktu** →
kartlar rowid sırasında geliyordu. Bugün görünmüyordu çünkü satırları hep aynı cihaz ekliyor;
Tur 39'un poll'u tabloyu yeniden yazdığında sıra kullanıcının altından kayacaktı.

### B.2 Mirror sırası — 3/4 tamam
1. ~~Timestamp ISO'ya geçir + `substr()` hack'ini sil~~ ✅ **Tur 36**
2. ~~`:core-network` kopyala (app-pos'un Tur 34 hâliyle)~~ ✅ **Tur 37**
3. ~~Hilt'e geç (`RepositoryProvider` silinir)~~ ✅ **Tur 37**
4. ~~Gerçek login + session diske + outbox + WorkManager~~ ✅ **Tur 38**
5. Buyer/approval uçlarını gerçek backend'e bağla → **KISMEN Tur 38**: approval
   onay/ret/gönderim bağlandı; **buyer OKUMA uçları (`/me/debts`, `/me/transactions`,
   `/me/balances`) hâlâ çağrılmıyor** — okuma yolunun tamamı Tur 39'a ait (§F).
6. **Pull ekseni** → **Tur 39**, bkz. §F.

**Ucuz olan taraf:** backend seed'i zaten app-mobile'ın seed'inden türetildi
([faz5-backend-plan.md §0.1](faz5-backend-plan.md)) — `u_market`, `m1`/`o1`, `p1`/`p2` sunucuda
aynı id'lerle var. Yani ekranlar bugünküyle aynı veriyi görecek.

### ~~B.3 `upsertUser` deseni app-mobile'a da lazım~~  ✅ KAPANDI (Tur 38)
Tuzağa düşülmeden yapıldı: `OfflineFirstRepository.mirrorUser` sunucunun user id'sini
**aynen** yazıyor (`LocalSource.upsertUser`). `UserDao`'ya `@Upsert` eklendi — mevcut `insert`
`IGNORE` olduğu için sunucu profili güncelleyemezdi.

### ~~B.4 `DataModule` şimdilik `RoomRepository` bağlıyor~~  ✅ KAPANDI (Tur 38)
`provideRepository` artık `OfflineFirstRepository` döndürüyor. Değişiklik **tek fonksiyon**
oldu ve 8 ViewModel'in hiçbiri etkilenmedi — binding'i arayüzün arkasına koymanın bedelini
tam olarak burada geri ödedi.

### ~~B.5 `:core-data`'da `LocalSource` ayrışması yok~~  ✅ KAPANDI (Tur 38)
`RoomRepository` → `local/RoomLocalDataSource`, ve artık `LocalSource` arayüzünü uyguluyor.

### ~~B.7 `requestApproval` sunucu yanıtına bakmıyor~~  ✅ KAPANDI (Tur 38b)
Artık **sunucunun kararını uyguluyor**: 201 (approvalId) → lokal onay kartı, 200
(transactionId) → lokal ledger yazımı, ağ hatası → offline dal + `QueuedOffline`.
`ApprovalApi.send` 200 dalını temsil edemiyordu (tek tip `ApprovalDto`); yeni
`ApprovalSendResultDto` iki şekli de taşıyor.

**Kalan borç aynı:** offline dalda lokal satır sunucudan ayrışabilir ve **bunu uzlaştıran
hiçbir şey yok** — pull yolu (§F) gelene kadar. Fark şu ki artık kullanıcı bunu **görüyor**
("İnternet yok — cihaza kaydedildi, bağlanınca gönderilecek"), sessizce başarı denmiyor.

### B.6 `OtpService` artık ÖLÜ KOD (login tarafında)
**Nerede:** [`OtpService.kt`](../app-mobile/app/src/main/java/com/example/app_mobile/data/OtpService.kt)

Tur 38 login'i gerçek backend'e bağlayınca bu mock'un login'deki tek çağıranı kalktı; şu an
**hiçbir yerden çağrılmıyor**. Silinmedi çünkü `ApprovalService`'in SMS-OTP dalı için hâlâ
referans desen. İlk temizlik turunda ya silinmeli ya da o dala taşınmalı.

---

## C. ~~Backend — `backend/` klasörü BOŞ~~  ✅ YAZILDI (Tur 29–34)

FastAPI + PostgreSQL 16 + Docker Compose. **Bölüm A'nın tamamı canlı: 22 uç**, `future`
etiketli hiçbir uç yazılmadı (canlı `/openapi.json` contract'la programla karşılaştırıldı).
**137 pytest / 0 fail.** Çalıştırma ve kararlar: [`backend/README.md`](../backend/README.md),
[faz5-backend-plan.md](faz5-backend-plan.md).

**Yedi kuralın hepsi uygulandı ve test edildi:**

| # | Kural | Nerede |
|---|---|---|
| 1 | `Idempotency-Key == transaction_id`; 201/200/**409** | `routers/ledger.py` |
| 2 | Bakiye **türetilir, saklanmaz** (`balanceOf` ile aynı) | `ledger.py` |
| 3 | Append-only — **DB trigger'ı** ile zorlanıyor | migration `0001` |
| 4 | `seller_id` **token'dan**, gövdeden asla | `deps.py` + `ledger.py` |
| 5 | Enum'lar string, bilinmeyen değer düşürülebilir | `schemas.py` |
| 6 | Telefon E.164, `PhoneFormat.toStored` ile birebir | `phone.py` |
| 7 | `verify` **auto-register YAPMAZ** → `404 user_not_found` | `routers/auth.py` |

**3 numara plandan saptı:** `REVOKE UPDATE, DELETE` yerine **trigger**. Uygulama şemanın
sahibi olarak bağlanıyor ve owner kendi yetkisini revoke edemez — REVOKE hiçbir şey yapmaz,
sadece yanlış bir güvenlik hissi verirdi.

### C.1 Şemaya eklenen kolon: `customers.created_by_seller_id` (migration 0002)
Defter üyeliği ledger'dan türetiliyordu ("bu satıcı bu müşteriye satır yazmışsa defterinde").
Ama **yeni eklenmiş, henüz borçlandırılmamış müşterinin hiç ledger satırı yok** →
`POST /customers` 201 dönüyor, `GET /customers` göstermiyordu. Nullable kolon eklendi;
üyelik artık *ledger'da satırı var* **VEYA** *bu satıcı oluşturmuş*. Sahiplik DEĞİL — müşteri
satırı dükkanlar arasında paylaşılmaya devam ediyor. [db-schema.md A.2](db-schema.md)'ye işlendi.

### C.2 OTP hâlâ mock — ama artık sunucuda
Sabit kod `123456`, `backend/app/config.py` → `mock_otp_code`. Gerçek SMS sağlayıcısı ayrı iş;
değişen şey kodun **nerede yargılandığı**.

### C.3 `logout` sunucuda no-op
JWT stateless olduğu için iptal edilecek bir şey yok; kısa access TTL + client'ın `TokenStore`'u
temizlemesi tezgâhtaki gerçek riski karşılıyor. Uç yine de var ve **token istiyor** ki ileride
revocation list eklenirse **client değişmesin**.

### C.4 Cihaz seed'i ile sunucu seed'i ayrı gerçeklikler  ⚠️ borç
app-pos'un `SeedCallback`'i hâlâ cihazda çalışıyor: temiz kurulumdan sonra Room kendi demo
verisini yazıyor, sunucununki ise ayrı. Gerçek veri sunucuda olduğu için cihaz seed'i artık
**yanıltıcı** — mirror turunda ya kaldırılmalı ya da yalnızca sunucusuz geliştirmeye
indirgenmeli.

---

## D. FAZ 8'e bırakılanlar (contract'ta `future` tag'li)

Bunlar **kod iskeleti olarak var** ama hiçbir yere bağlı değil — "imza hazır, gövde sonra" deseni.
**Backend'de de bilinçli olarak YAZILMADI** (plan kararı): sunucuda bu uçların hiçbiri yok,
canlı `/openapi.json` ile contract programla karşılaştırılıp doğrulandı.

| Ne | Client'ta | Backend'de |
|---|---|---|
| `/sync/transactions` (toplu drain) | `SyncApi` + `RemoteDataSource.syncTransactions` | ❌ yazılmadı |
| `/transactions/{id}/settle` (PGW fişi) | `SyncApi.settle` | ❌ yazılmadı |
| `/insights` (ML risk skoru) | `InsightsDto` — KVKK'ya bağlı (FAZ 7) | ❌ yazılmadı |
| `/me/credit-offers` (mikrokredi) | `CreditOfferDto` + `credit_offers` tablosu | ❌ yazılmadı |
| `/devices` (FCM push) | `DeviceCreateDto` + `devices` tablosu | ❌ yazılmadı |
| `fx_rates` (döviz/altın geçmişi) | Room entity + DAO, doldurulmuyor | ❌ tablo yok |
| `audit_log` (denetim izi) | Room entity + DAO — FAZ 7 | ❌ tablo yok |
| PGW settle intent'i (Aşama 0'ın **tersi** yön) | henüz kod yok | — |

**Kural (plan kararı):** ileri-faz tabloları **koda da eklenir**, sadece dokümanda kalmaz —
"şu an gerekeni detaylı, geleceği taslak". Böylece şema bir kez oturur, sonradan migration
gerekmez.

**Ama backend bu kuralı UYGULAMADI, kasıtlı:** Room'da ileri-faz tabloları iskele olarak var,
Postgres'te yok. Sebep: Room'da bedel sıfırdı (destructive migration, gerçek veri yok), oysa
Postgres'te her tablo bir migration dosyası ve geri alma yolu demek — kullanılmayan bir tablo
için ödenecek bedel orada gerçek. Alembic zaten sıralı migration tutuyor, yani tablo gerektiği
gün eklemek ucuz.

---

## E. Cihaz testi — app-mobile iki tur test edildi; 38c bekliyor

**app-mobile 36/37/38 test edildi (2026-08-13)**, iki tur hata çıktı ve düzeltildi:
**Tur 38b** (çift yazım, telefon normalizasyonu, onaysız yazma, sahte başarı) ve
**Tur 38c** (takılan onay, OTP UI, müşteri listesi). Testin değeri iki kez kanıtlandı —
38b'deki çift yazım ledger'ı bozuyordu ve hiçbir birim test onu yakalamamıştı.

**Hâlâ doğrulanmamış:**
- **app-mobile + app-pos Tur 38c** — düzeltmelerin kendisi (5 adımlık liste progress.md'de).
  Şema değişmedi → `adb uninstall` GEREKMİYOR.
- **app-pos Tur 34 (5f)** — hiç cihazda çalıştırılmadı.

**Ortak koşullar:** sunucu ayakta (`docker compose up`) + `adb reverse tcp:4010 tcp:4010`
(her USB bağlantısında yeniden). Host ayarı `gradle.properties` → `mobileApiHost` / `posApiHost`.

**`adb uninstall` ne zaman gerekir:** yalnız **şema değiştiğinde** (Tur 36 DB'yi v2 yaptı).
MIUI'de `pm clear` çalışmadığı için sıfırlama yolu her zaman uninstall
([[miui-adb-restrictions]]).

Test adımları: [progress.md](progress.md) Tur 34 / 36 / 38 / 38b / 38c sonlarında listeli.

**Bağlantı:** `adb reverse tcp:4010 tcp:4010` (USB). `app-pos/gradle.properties` içindeki
`posApiHost=127.0.0.1` bunun için — LAN IP'si her DHCP kirasında değişiyor ve test sırasında
sessizce "sunucuya ulaşılamadı"ya dönüşüyordu (bir oturum içinde `.96` → `.31` değişimi
gözlendi). Wi-Fi'a dönmek gerekirse dosyayı düzenlemeden:
`./gradlew -PposApiHost=$(ipconfig getifaddr en0) :app:installDebug`

**Atlanmaması gereken adım:** ilk kurulumdan önce `adb uninstall com.example.app_pos`
([faz5-backend-plan.md §0.6](faz5-backend-plan.md)) — kuyrukta lokal id'li kayıt kalırsa
sunucu 404 döner, `SyncEngine` 4xx'i kalıcı ret sayıp satırı **siler**.

---

## F. Okuma yolu (pull) — sistemde HİÇ YOK  ⚠️ yapısal boşluk, Tur 39'un konusu

Bu madde bir "erteleme" değil, **kod okumasıyla yeni keşfedilen bir boşluk**. Mirror turu
planlanırken app-pos'un ağ katmanı baştan sona tarandı ve şu çıktı:

**İki client da sunucuya sadece YAZIYOR.** `SyncEngine.drainOutbox` `POST /transactions`
atıyor, auth uçları çağrılıyor, Tur 38'de approval yazma uçları eklendi — hepsi bu.
Sunucudan **okuma** hâlâ sıfır:

| Uç | Durum |
|---|---|
| `GET /customers`, `/customers/{id}`, `/customers/lookup` | `RemoteDataSource`'ta sarmalı, **çağıran yok** |
| `GET /transactions`, `/balances` | sarmalı, **çağıran yok** |
| `GET /approvals` | sarmalı, **çağıran yok** |
| `GET /me/debts`, `/me/transactions`, `/me/balances` | sarmalı, **çağıran yok** (app-mobile'ın buyer ekranları hâlâ lokalden okuyor) |
| `GET /users/me` | sarmalı, **çağıran yok** |
| `POST /sync/transactions` (toplu) | sarmalı, çağıran yok (drain tek tek gönderiyor) |

**Tur 38 neyi değiştirdi:** app-mobile artık `POST /approvals`, `/approvals/{id}/approve`,
`/reject` çağırıyor — yani onay **verme** sunucuya ulaşıyor. Ama onay **görme** hâlâ lokal:
başka bir cihazda açılmış onay bu ekrana asla düşmez.

Dahası **`Repository` arayüzünde pull affordance'ı yok**: `refresh()`, `pull()`, `fetchX()` diye
bir şey hiç tanımlanmamış. Her okuma Room `Flow`'u. Sözleşmenin kendisi "çekmek" fiilini bilmiyor.
Cursor/watermark kolonu da yok, hiçbir Room entity'sinde `updatedAt` yok.

**Neden bugüne kadar sorun olmadı:** tasarım ledger'ın append-only olmasına ve **cihazın kendi
yazdıklarının tek yazarı olması**na yaslanıyordu — hiç geri okumadığı için uzlaştırma da
gerekmiyordu.

**Neden artık sorun:** onay kutusu doğası gereği bir **okuma** yolu.
- app-pos'un `approvals` tablosu var ama `approvalDao()`'yu çağıran tek satır yok (§A.4);
  `RoomLocalDataSource` DAO referansını bile tutmuyor.
- app-mobile'ın Onaylar sekmesi çalışıyor ama **sadece lokal**: satırlar ya `SeedCallback`'in
  `p1`/`p2`'sinden ya da **aynı cihazda** yapılan `requestApproval`'dan geliyor. Başka cihazdaki
  bir POS burada asla satır üretemez.
- Yani **"mobile'dan gelen veresiye onayını POS'ta göster" bugün imkânsız.** Eksik olan tek şey
  client'ın uçları çağırması değil; pull deseninin **tamamı** yok (cursor, merge, zamanlayıcı,
  çekilen varlık için domain tipi).

### F.1 Sunucu tarafı da poll'a hazır değil
- **`GET /approvals` filtresiz.** Ne `status`, ne `since`, ne `cursor`, ne `limit`. Status
  `PENDING`'e, target token'ın user'ına sabitlenmiş → APPROVED/REJECTED geçmişi API'den
  **hiç sorgulanamıyor**.
- **`approvals` tablosunda `updated_at` YOK.** `requested_at` sadece *oluşturma* anını tutuyor,
  `PENDING→APPROVED` geçişi damga bırakmıyor. Yani `?since=` eklenmiş olsa bile **karar verilmiş
  onay poll'a hiç düşmez** → karşı taraf reddettiğinde kart ekranda sonsuza dek kalır
  ("hayalet kart"). Bu yüzden Tur 39'da **migration 0003 ile `updated_at` ekleniyor**
  (kullanıcı kararı: yarım çözüm değil, tam çözüm).
- ETag/`If-None-Match`/`Cache-Control` yok, WebSocket/SSE yok, FCM yok (`channel="APP_PUSH"`
  bir **etiket**, taşıma değil; `POST /devices` contract'ta `future` ve backend'de yazılmadı).

### F.2 `?since` her tabloda aynı işlemiyor — kasıtlı asimetri
| Tablo | `?since` uygun mu | Neden |
|---|---|---|
| `transactions` | ✅ mükemmel | append-only, `created_at` hiç değişmiyor |
| `approvals` | ✅ (0003'ten sonra) | `updated_at` status geçişini de damgalıyor |
| `customers` | ⚠️ **kısmi** | `updated_at` yok **ve bakiye türetiliyor** — müşteri satırı değişmeden bakiyesi değişir. `since` sadece *yeni müşteri satırı* için; bakiye tazeliği `transactions` pull'undan gelir |

Bu sınır koda ve [db-schema.md](db-schema.md)'ye yazılmalı, yoksa "customers pull'u bakiyeyi
güncellemiyor" ileride bug gibi okunur.

### F.3 POS'un "sürekli dinlemesi" yaklaşıklanıyor  ⚠️ kullanıcının açık notu
Hedef davranış: POS arka planda bile sunucuyu dinlesin, mobile'dan gelen onayı hemen göstersin.
**Gerçekleşen:** WorkManager periyodik minimum **15 DAKİKA** — bu "sürekli dinleme" değil.
Tur 39'un çözümü iki katmanlı:
- **Ekran açıkken** (tezgahta normal durum) 15 sn'lik lifecycle-scoped foreground poll → onay
  neredeyse anında görünür.
- **Arka planda** WorkManager 15 dk → gecikmeli ama yetişir.

Gerçek anlık teslim için **FCM** (endüstri standardı; `devices` tablosu iskeleti ve FAZ 8 planı
zaten var) ya da **foreground service** (kalıcı bildirim + Android 14 servis tipi izinleri +
MIUI'nin agresif kill davranışı) gerekir. Karar: şimdilik yaklaşıklama yeterli, çünkü test bir
telefonda yapılıyor — **ama bu değişecek**, ve değiştiğinde `PullEngine` aynı kalır, sadece
tetikleyici değişir.

**app-mobile'da asimetri kasıtlı:** pil kısıtı nedeniyle arka planda **pull YOK**, sadece
ekran açıkken 15 sn. Arka plan işi yalnızca outbox drain yapar.

### F.4 Tur 39'un kapsamı — "sunucu tek gerçeklik" (kararlar kesinleşti)

Tur 38c'nin cihaz testinde çıkan `p1` sorunu bu maddenin **somut kanıtı** oldu: seed'lenmiş
bir onay kartı, sahibi başka bir hesap olduğu halde ekranda duruyordu ve sunucu haklı olarak
403 veriyordu. 38c kartın **düşmesini** sağladı (semptom), ama kartın oraya gelmemesi
gerekiyordu (kök neden). Kök neden = okuma yolunun olmaması.

**Kullanıcı kararları (2026-08-13):**

1. **Okuma yolu sırası:** önce **Onaylar → `GET /approvals`**. Bu uç zaten target'a göre
   filtreli olduğu için `p1` sorununu kökten çözer ve pull deseninin ilk örneği olur.
   Sonra Borçlarım / Müşterilerim / geçmiş. Tüm uçlar `RemoteDataSource`'ta **hazır**.

2. **Cihaz seed'leri kalkar** (`SeedCallback`, iki app'te de) — C.4'ün kapanışı. Ekranlar
   sunucudan beslendiği için demo verisi de sunucuda tek yerde durur.

3. **Reset: HTTP ucu YOK.** ⚠️ Kullanıcının açık kararı: *"bir kullanıcının es kaza veri
   silebilmesi ihtimal olarak bile sıkıntı."* Sadece sunucu tarafından komutla:
   `docker compose exec api python -m app.reset`. `seed.py`'de `is_empty()` + `seed()`
   zaten var, yeniden kullanılır. Token'lı/debug-gated bir uç bile **eklenmeyecek**.

4. **Boş isimli hesap** (`u_0e8a790ce5d9`, telefon = bir dükkanın shopPhone'u): bug DEĞİL.
   İsim opsiyonel, profilden doldurulur — tasarım tercihi. Reset ile temizlenecek.
