# Ertelenenler — nereye kadar getirdik, neyi bıraktık

> **Bu dosya ne işe yarar:** Aşağıdakilerin hepsi **bilinçli** karardı, hiçbiri unutulmuş iş
> değil. Ama altı ay sonra koda bakan (sen dahil) "burası neden yarım?" diye soracak. Cevaplar
> burada, gerekçesiyle ve nereye bakması gerektiğiyle.
>
> Son güncelleme: 2026-08-19, Tur 41 (onay yollarının yeniden tanımı) sonrası.
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

**app-mobile mirror turu 4/4 (Tur 36→38c):** ISO timestamp + DB v2, `:core-network` + Hilt,
offline-first beste + gerçek login + outbox + WorkManager. İki cihaz-testi turu (38b: çift
yazım / telefon normalizasyonu / onaysız yazma / sahte başarı; 38c: takılan onay / OTP UI /
müşteri listesi) cihazda doğrulandı.

**Tur 39: okuma yolu AÇILDI (Onaylar hattı).** §F'nin yapısal boşluğu kapandı: `PullEngine` +
`Repository.refreshApprovals()` iki app'te de var, app-pos **Onaylar ekranını kazandı** (§A.4),
cihaz seed'leri kalktı (§C.4), app-pos'un telefon bug'ı düzeltildi (§A.9). Backend'e
`approvals.updated_at` (migration 0003) + `GET /approvals?status=&limit=` eklendi, ve
`python -m app.reset` yazıldı (HTTP ucu YOK).

**Pull deseni: tam liste senkronu, `since` YOK** — bilinçli sapma. Sunucunun wire formatı
saniye hassasiyetinde olduğu için dışlayıcı bir `since` aynı saniyedeki satırları sessizce
düşürürdü; ayrıca karara bağlanmış satır `since`'e hiç düşmediği için "hayalet kart" geri
gelirdi. Tam liste otoriter → gelmeyen satır silinir. Gerekçe: [progress.md](progress.md) Tur 39.

**Tur 40: okuma yolu TAMAMLANDI.** `pullMyLedger` (mobile: `/me/debts` + `/me/transactions`)
ve `pullBook` (pos: `/customers` + `/transactions`) yazıldı; artık **her ekran sunucudan
besleniyor**. Ledger pull'u onaylardan farklı çalışır: onayda gelmeyen satır silinir (karar
verilmiştir), ledger'da **silinmez** (append-only, cevap kısmi olabilir) — bu ayrım iki ayrı
metotla derleme zamanında korunuyor.

**Tur 40b: cihaz testi 3. tur.** Dört hata düzeltildi (buyer stub satırlarının POS müşteri
listesine sızması, `/me/debts`'te eksik `shop_phone`, ağ hatasının "Geçersiz numara" diye
gösterilmesi, alt navigasyonda sönen sekme). Asıl kök neden bir **build hatasıydı**: app-pos
için `assembleDebug` koşulmamıştı, telefondaki APK Tur 39'a aitti.

**Tur 41: onay kapısı tezgâha ulaştı (§H kapandı).** Kullanıcının beş yol tanımı uygulandı.
app-pos artık `requestApproval` ÇAĞIRIYOR (uç yazılıydı, çağıranı yoktu) ve satışı müşterinin
cevabı için bekletip PGW'ye gerçek sonucu döndürüyor. Telefonda başlayıp PGW'de biten yollar
için `pgw_jobs` kuyruğu eklendi — sunucu POS'u arayamadığı için işi yazıp bırakıyor, terminal
gelip alıyor. §G.1 ve §G.2 de kapandı.

**Kalan iş:** aşağıdaki §H.1'de listeli — OTP'nin gerçekleştirilmesi, UNCLAIMED için SMS-OTP,
PGW handshake, yol 1 timeout.

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

### ~~A.3 `RemoteDataSource`'ın okuma sarmalları~~ ✅ KAPANDI (Tur 39 + 40)

**Tur 39** `pendingApprovals()`'ı bağladı, **Tur 40** geri kalanını: `customers()` ve
`transactionHistory()` → `pullBook()` (app-pos), `myDebts()` + `transactionHistory()` →
`pullMyLedger()` (app-mobile). Bakiye sarmalı (`balance()`) **kasıtlı olarak bağlanmadı** —
bakiye yerel olarak `SUM(transactions)` ile türetiliyor ve sunucunun `balance_minor`'ını da
yazmak, defterle bakiyenin ayrışabildiği ikinci bir gerçeklik yaratırdı. `storeCustomers`
sunucudan gelen bakiyeyi bilerek atar.

Aşağıdaki teşhis Tur 39 öncesine ait:

<details><summary>Orijinal teşhis</summary>
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

</details>

### ~~A.4 Gelen onay (approvals) UI'si yok~~  ✅ KAPANDI (Tur 39)
app-pos **Onaylar sekmesini kazandı**: `PendingApproval`/`DecisionOutcome` domain tipleri,
`ApprovalDao` bağlandı (`RoomLocalDataSource` artık `approvalDao()` tutuyor), fragment + VM +
adapter + nav girişi eklendi. Kutuyu dolduran okuma yolu da aynı turda geldi (§F).

app-mobile'ın ekranından tek farkı **rol bölümü olmaması** — POS her zaman dükkan tarafı,
oysa app-mobile tek hesapta iki rol taşıdığı için listeyi role göre bölmek zorunda.

**Bu turda bulunan iki tuzak:** `observePendingFor`'da **`ORDER BY` yoktu** (tek yazar varken
görünmez, poll gelince sırayı kullanıcının altından kaydırırdı — app-mobile'da Tur 36'da
düzeltilmişti) ve **`delete(id)` yoktu** (403/409'da kartı düşürmek imkânsızdı).

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

### ~~A.9 Telefon normalizasyonu bug'ı app-pos'ta HÂLÂ VAR~~  ✅ KAPANDI (Tur 39)
`PhoneFormat` `:app` → **`:core-domain`** taşındı; `UserDao`'nun `LIKE '%..%'`'i ve
`CustomerDao`'nun `REPLACE(...)`'i düz `WHERE phone = :stored` oldu; çağıranlar önce
`toStored`'dan geçiyor. 6 `PhoneFormatTest` app-pos'ta da koşuyor.

**Bu turda yapılmasının sebebi:** pull sunucudan **kanonik E.164** yazıyor — gevşek
eşleştirme o veriyle çakışırdı. Aşağıdaki teşhis kayıt için duruyor:

<details><summary>Orijinal teşhis</summary>
**Nerede:** [`Daos.kt:65-66`](../app-pos/core-data/src/main/java/com/example/app_pos/data/db/dao/Daos.kt) (`LIKE '%..%'`) vs `:104`, `:111` (`=`);
[`PhoneFormat.kt`](../app-pos/app/src/main/java/com/example/app_pos/util/PhoneFormat.kt) hâlâ `:app`'te (veri katmanı erişemiyor)

app-mobile'da Tur 38b ile düzeltilen hata **app-pos'ta aynen duruyor**: `UserDao` substring
(`LIKE`), `CustomerDao` tam eşitlik (`=`), ikisi de SQL'de `REPLACE`'le normalize ediyor ve
ülke kodunu ele almıyor. Sonuç app-mobile'daki ile aynı olmalı: **login çalışır, claim sessizce
hiçbir satırı bulmaz** → müşteri UNCLAIMED kalır → onaysız-yazma dalına düşer.

**Cihazda doğrulanmadı** (app-pos hiç test edilmedi, §E), ama kod birebir aynı desen.

</details>

### A.8 `:app`'te `BuildConfig` kapalı
`buildConfig` feature'ı sadece `:core-network`'te açık. `:app` debug/release ayrımını
`NetworkConfig.isDebug` üzerinden okuyor ([`App.kt`](../app-pos/app/src/main/java/com/example/app_pos/App.kt) — WorkManager log seviyesi). Aynı bilgi için ikinci bir
generated sınıf üretmeye gerek yok. Not düşüldü ki ileride biri "neden `BuildConfig.DEBUG`
yazamıyorum?" diye takılmasın.

---

## B. app-mobile — mirror TAMAM (Tur 36→39)

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
| Okuma yolu (pull) | ✅ **Onaylar (Tur 39)**; kalanı Tur 40 | ✅ **Onaylar (Tur 39)**; kalanı Tur 40 |
| Approvals | ✅ **kutu var (Tur 39)** — gönderiyor VE yanıtlıyor | ✅ kutu var; onay/ret sunucuya gidiyor (Tur 38) |
| Arka plan pull | ✅ `PullWorker` 15 dk | ❌ **kasıtlı yok** (pil) — sadece ön plan 15 sn |

**Kalan asimetri kasıtlı ve Tur 39'da uygulandı:** app-mobile arka planda **pull yapmaz**
(pil), sadece outbox drain eder; ön planda 15 sn poll eder. app-pos ise tezgahta durduğu
için ayrıca `PullWorker` ile 15 dakikada bir arka planda da okur. Bkz. §F.3.

### ~~B.1 Timestamp formatı ayrışması~~  ✅ KAPANDI (Tur 36)
`nowStamp()` artık ISO-8601 UTC yazıyor, `substr()` sıralama hack'i **silindi** (düz
`ORDER BY createdAt`), DB **v2**'ye çıktı, `SeedCallback`'in 21 damgası
`backend/app/seed.py` ile **birebir aynı instant**'lara çevrildi (programla diff'lendi).
Yeni `app/util/TimeFormat.kt` gösterim için ISO'yu cihazın saat dilimine çeviriyor.

**Bonus olarak bulunan gerçek bug:** `ApprovalDao.observePendingFor`'un **ORDER BY'ı yoktu** →
kartlar rowid sırasında geliyordu. Bugün görünmüyordu çünkü satırları hep aynı cihaz ekliyor;
Tur 39'un poll'u tabloyu yeniden yazdığında sıra kullanıcının altından kayacaktı.

### B.2 Mirror sırası — ✅ 4/4 TAMAM
1. ~~Timestamp ISO'ya geçir + `substr()` hack'ini sil~~ ✅ **Tur 36**
2. ~~`:core-network` kopyala (app-pos'un Tur 34 hâliyle)~~ ✅ **Tur 37**
3. ~~Hilt'e geç (`RepositoryProvider` silinir)~~ ✅ **Tur 37**
4. ~~Gerçek login + session diske + outbox + WorkManager~~ ✅ **Tur 38**
5. Buyer/approval uçlarını gerçek backend'e bağla → **KISMEN**: approval yazma (Tur 38) ve
   approval **okuma** (Tur 39) bağlandı; **buyer okuma uçları (`/me/debts`, `/me/transactions`,
   `/me/balances`) hâlâ çağrılmıyor** — Tur 40 (§F).
6. ~~**Pull ekseni**~~ ✅ **Tur 39** — Onaylar hattı; kalan okumalar Tur 40 (§F).

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

### ~~C.4 Cihaz seed'i ile sunucu seed'i ayrı gerçeklikler~~  ✅ KAPANDI (Tur 39)
`SeedCallback` **iki app'ten de silindi**. Demo verisi artık tek yerde: `backend/app/seed.py`.
Ekranlar sunucudan besleniyor, yani cihazdaki her satırın bir kaynağı var.

Bunun somut bedeli 38c'de görülmüştü: cihaz seed'i `p1`'i kim giriş yaparsa yapsın yazıyordu,
sahibi başka hesap olduğu için sunucu haklı olarak 403 veriyordu ve kart ekranda takılı
kalıyordu. 38c kartın **düşmesini** sağladı (semptom); seed'in kalkması kartın oraya hiç
gelmemesini sağlıyor (kök neden).

**Sıfırlama:** `docker compose exec api python -m app.reset` (aşağıda §F.4/3).

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

## E. Cihaz testi — app-mobile doğrulandı; app-pos ilk kez koştu (Tur 40b)

**app-mobile 36/37/38 test edildi (2026-08-13)**, iki tur hata çıktı ve düzeltildi:
**Tur 38b** (çift yazım, telefon normalizasyonu, onaysız yazma, sahte başarı) ve
**Tur 38c** (takılan onay, OTP UI, müşteri listesi). Testin değeri iki kez kanıtlandı —
38b'deki çift yazım ledger'ı bozuyordu ve hiçbir birim test onu yakalamamıştı.

**Tur 38c cihazda doğrulandı (2026-08-13):** takılan onay kartı düşüyor, OTP adımı aynı
ekranda açılıyor, müşteri listesi mock-pos handoff'unda dolu geliyor.

**Tur 39/40 cihazda koşuldu (2026-08-14), bir düzeltme turu çıktı (40b).** Beklenti tuttu:
app-pos'un ilk gerçek cihaz testinden dört hata + bir build hatası çıktı, ayrıntısı
[progress.md](progress.md) Tur 40b'de.

**Backend Docker'da doğrulandı:** `alembic upgrade head` **0002 → 0003** geçişini gerçek
Postgres'te koştu, `approvals.updated_at` **NOT NULL** olarak oluştu; `python -m app.reset`
append-only trigger'a rağmen çalıştı (`TRUNCATE ... CASCADE` doğru seçimdi). §F.1'in
"doğrulanmadı" uyarısı kapandı.

⚠️ **Ama pytest hâlâ Alembic'i çalıştırmıyor:** `conftest.py` şemayı `models.py`'den
`create_all` ile kuruyor. Yani **her yeni migration elle `docker compose exec api alembic
upgrade head` ile doğrulanmalı** — suite bunu yakalamaz. Bu kalıcı bir boşluk, tur bazlı bir
eksiklik değil.

⚠️ **"Derlendi" ≠ "telefonda çalışıyor" (Tur 40b'nin en pahalı dersi).** Ara adımlarda
`compileDebugKotlin` koşmak düşük-RAM protokolünün gereği, ama kurulumdan önce
**`assembleDebug` şart**. Tur 40b'de bu atlandığı için telefondaki app-pos bir tur eskiydi ve
saatler yanlış semptom kovalamakla geçti. Kurulumdan sonra APK içeriği doğrulanmalı:

```bash
unzip -p app-pos/app/build/outputs/apk/debug/app-debug.apk classes\*.dex \
  | strings | grep -c pullBook    # 0 dönerse APK eski
```

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

## F. Okuma yolu (pull) — ✅ KAPANDI (Tur 39: Onaylar, Tur 40: defter)

**Durum:** okuma ekseninin **tamamı** bağlı. `PullEngine` iki app'te de iki metot taşıyor:

| app | onay hattı | defter hattı |
|---|---|---|
| app-mobile | `pullApprovals` | `pullMyLedger` → `/me/debts` + `/me/transactions` |
| app-pos | `pullApprovals` | `pullBook` → `/customers` + `/transactions` |

Tetikleme: ekran açıkken 15 sn (onaylar) / 30 sn (borçlar), açılışta bir kez, app-pos'ta
ayrıca `PullWorker` 15 dk. Artık **app-mobile'da açılan bir onay POS'ta görünüyor** ve
**ekranlar cihaz seed'i olmadan doluyor** — bu maddenin "imkânsız" dediği iki şey.

**İki pull'un silme kuralı FARKLI, ve bu ayrım kasıtlı olarak tipte:**

| | cevapta olmayan satır | neden |
|---|---|---|
| onaylar | **silinir** | karar başka cihazda verilmiştir; kalması "hayalet kart" demek |
| ledger | **silinmez** | defter append-only; satır geri alınmaz, cevap kısmi olabilir |

Tek bir `pull(entity)` metodu bu farkı çalışma-zamanı bayrağına indirirdi. Ayrı metotlar =
derleme zamanında korunan kural. Test: `an empty debt list never deletes stored entries`.

**§F.2'nin customers-bakiye asimetrisi Tur 40'ta şöyle çözüldü:** `storeCustomers` sunucudan
gelen `balance_minor`'ı **atar**. Bakiye tek kaynaktan türetilir (`SUM(transactions)`), yani
tazeliği ledger pull'undan gelir. İki kaynak yazmak, defterle bakiyenin ayrışabildiği bir
durum yaratırdı.

Aşağıdaki teşhis, deseni neden böyle kurduğumuzu açıklamak için duruyor.

<details><summary>Orijinal teşhis (Tur 39 öncesi)</summary>

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

### F.1 Sunucu tarafı da poll'a hazır değil — ✅ ÇÖZÜLDÜ (Tur 39)
- ~~**`GET /approvals` filtresiz.**~~ ✅ `status` (varsayılan PENDING, `ALL` geçmişi açar) +
  `limit` (varsayılan 100) eklendi. `since` **bilinçli olarak eklenmedi** — bkz. §F.4/1.
- ~~**`approvals` tablosunda `updated_at` YOK.**~~ ✅ migration 0003 ile eklendi (üç yazma
  noktası damgalıyor). Not: pull tam-liste senkronu yaptığı için **bunu kullanmıyor**; kolon
  denetim izi ve ileride FCM/cursor için duruyor. Orijinal teşhis: `requested_at` sadece *oluşturma* anını tutuyor,
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

</details>

### F.4 Tur 39'un kapsamı — "sunucu tek gerçeklik" (✅ UYGULANDI)

Tur 38c'nin cihaz testinde çıkan `p1` sorunu bu maddenin **somut kanıtı** oldu: seed'lenmiş
bir onay kartı, sahibi başka bir hesap olduğu halde ekranda duruyordu ve sunucu haklı olarak
403 veriyordu. 38c kartın **düşmesini** sağladı (semptom), ama kartın oraya gelmemesi
gerekiyordu (kök neden). Kök neden = okuma yolunun olmaması.

**Kullanıcı kararları (2026-08-13) ve ne oldukları:**

1. ✅ **Okuma yolu sırası:** önce **Onaylar → `GET /approvals`**. Yapıldı. Sonrası
   (Borçlarım / Müşterilerim / geçmiş) **Tur 40**; uçlar `RemoteDataSource`'ta hazır bekliyor.

   **Uygulamada plandan bir sapma oldu:** §F.1 `?since=` öneriyordu, **tam liste senkronu**
   yapıldı. İki sebep: (a) sunucunun wire formatı **saniye hassasiyetinde**
   (`%Y-%m-%dT%H:%M:%SZ`), dışlayıcı bir `since` aynı saniyedeki satırları sessizce
   düşürürdü; (b) karara bağlanmış satır `since`'e hiç düşmediği için hayalet kart geri
   gelirdi. Tam liste **otoriter** → gelmeyen satır silinir, sorun yapısal olarak yok.
   `updated_at` yine de eklendi (migration 0003) ama **pull kullanmıyor** — denetim izi ve
   ileride FCM/cursor için.

2. ✅ **Cihaz seed'leri kalktı** (`SeedCallback`, iki app'ten de) — C.4 kapandı.

3. ✅ **Reset: HTTP ucu YOK.** `backend/app/reset.py` yazıldı. ⚠️ Kullanıcının açık kararı: *"bir kullanıcının es kaza veri
   silebilmesi ihtimal olarak bile sıkıntı."* Sadece sunucu tarafından komutla:
   `docker compose exec api python -m app.reset`. `seed.py`'nin `is_empty()` + `seed()`'i
   yeniden kullanıldı. Token'lı/debug-gated bir uç **eklenmedi**.

   **Teknik not:** `transactions` üstündeki append-only trigger (`BEFORE UPDATE OR DELETE`)
   düz `DELETE`'i patlatıyor → `TRUNCATE ... RESTART IDENTITY CASCADE` kullanıldı. TRUNCATE
   tablo-seviyesi bir işlem, satır trigger'ı tetiklenmiyor; append-only garantisi bozulmuyor.
   ✅ **Gerçek Postgres'te doğrulandı (Tur 40b):** reset trigger'a takılmadan tamamlandı,
   `alembic upgrade head` 0002 → 0003 geçişini yaptı.

4. **Boş isimli hesap** (`u_0e8a790ce5d9`, telefon = bir dükkanın shopPhone'u): bug DEĞİL.
   İsim opsiyonel, profilden doldurulur — tasarım tercihi. Reset ile temizlenecek.
   **Ama sunum tarafı eksik çıktı** — bkz. §G.1.

---

## G. Cihaz testi 3. turdan kalan UI maddeleri — ✅ KAPANDI (Tur 41)

### ~~G.1 Adsız müşteri satırı boş görünüyor~~  ✅ KAPANDI (Tur 41)

Ad boşsa **telefon başlık** oluyor, ikincil satır *"isim girilmemiş"* diyor. Arama da
telefonu eşleştiriyor — listede tanınması en zor satır, aranamayan satırdı da. İki app'te
de ortak bir `CustomerLabel.kt` (`titleFor` / `matchesQuery`) üzerinden.

**"Asıl soru" cevaplandı:** adsız satırların kaynağı **(a) buyer stub'ları DEĞİL** — onlar
SQL'de zaten ayıklanıyor ([Daos.kt:128-134](../app-mobile/core-data/src/main/java/com/example/app_pos/data/db/dao/Daos.kt)).
Kaynak **(b)**: kayıt akışı `registerUser(phone, displayName = "")` gönderiyor (iki app'in
`LoginViewModel`'i), yani bunlar **gerçek ama adsız hesaplar**. Sunum düzeltmesi bu yüzden
meşru — kozmetik bir yama gerçek bir veri boşluğunu örtmüyor.

### ~~G.2 Buyer'ın dükkân detayında "alacak/verecek" başlığı~~  ✅ KAPANDI (Tur 41)

Teşhis kısmen yanlıştı: **etiket zaten doğruydu** ("Bu satıcıya borcum"). İki yönlü olan
**değerdi**. Satıcı ekranının `if (balance > 0) balance_due else payment_received` şeması
kopyalanmıştı, sonuç:

- fazla ödemede ekran *"Bu satıcıya borcum: −50,00 TL"* diyordu — **negatif borç**
- **sıfır bakiye** "ödeme alındı" yeşiline düşüyor, olmamış bir olayı duyuruyordu

Artık **etiket işaretle birlikte** değişiyor (borcum / alacağım / borcum yok), tutar hep
pozitif (`abs`), sıfır nötr renkte (`balance_settled`). Aynı sıfır hatası **Borçlarım**
listesinde (`SellerDebtAdapter`) da düzeltildi.

---

## H. Onay yollarının yeniden tanımı — ✅ UYGULANDI (Tur 41)

Kullanıcının tanımı geldi ve **beş yol** olarak uygulandı. Kısaltmalar kullanıcının
sözlüğü: `pos` = app-pos, `mb` = müşteri mobile (alıcı rolü), `sb` = satıcı mobile,
`PGW` = ödeme geçidi (bugün mock-pos), `server` = backend + tüm istemcilerin sync'i.

**Yön kuralları (güvenlik sınırı, kullanıcı kararı):**

```
mb  ──uyarır──▶  sb            mb ──✗──▶ pos      (mb POS'a DOKUNAMAZ)
sb  ──ödeme──▶  pos            sb ──✗──▶ PGW      (sb PGW'yi uyaramaz)
pos ──▶ PGW  (açar / fiş keser)     pos ──▶ mb  (onay gönderir)
PGW ──▶ pos  (intent ile çağırır, success/fail BEKLER)
```

| # | Yol | Zincir | Onay |
|---|---|---|---|
| 1 | pos'tan veresiye | PGW → pos → **mb onay** → server → pos `setResult` → PGW fiş | ✅ mb |
| 2 | pos'tan tahsilat | pos (tutar) → PGW intent + server'a kayıt | ❌ |
| 3 | sb'den veresiye | sb → **mb onay** → server → **pos'a iş** → PGW `type 17` fiş | ✅ mb |
| 4 | sb'den tahsilat | sb → **pos'a iş** → pos PGW'yi çağırır | ❌ |
| 5 | mb'den ödeme | mb → **sb onay** → server → **pos'a iş** → pos PGW'yi AÇAR | ✅ sb |

**Yol 2 ve 4'te onay yok, ve bu bir eksiklik değil:** kapı bir tarafın diğerine tek taraflı
kayıt açmasını engeller; ikisi de **tahsilat**, yani parayı dükkân alıyor ve müşteri kartı
uzatarak onaylıyor.

### H.1 Bu turda BİLİNÇLİ olarak yapılmayanlar

**1. OTP hâlâ mock (kullanıcı kararı).** *"OTP kısmı şu anki sistemiyle kalabilir,
muhtemelen sunuma kadar bunu mock tutacağız diğer işlemler bitene kadar."*
[`OtpService.verifyOtp`](../app-pos/app/src/main/java/com/example/app_pos/data/OtpService.kt)
hâlâ koşulsuz `true` döndürüyor. ⚠️ **Ama artık zararsız:** ekranın arkasındaki gerçek kapı
`requestApproval`, ve kararı **mb veriyor**. Tur 40e'de bu ekran tek "kapı"ydı ve hiçbir şey
doğrulamıyordu.

**2. UNCLAIMED karşı taraf için SMS-OTP onayı — AÇIK, tartışılacak.** Kullanıcının notu:
*"müşteri için mobile varsa oradan yoksa otp (şu anda app'i olmayan otp'ler karmaşık olacaksa
atlanabilir, tartışalım)."* Bugün `POST /approvals`'ın UNCLAIMED dalı **sessizce anında
yazıyor** ([approvals.py](../backend/app/routers/approvals.py)) — ne SMS gidiyor, ne kod
doğrulanıyor, ne de denetim izi için approval satırı kalıyor. Yani app'i olmayan müşteri
için kapı **hiç yok**, ve bu gerçek dünyadaki müşterilerin çoğu demek.

**3. PGW handshake yok (kullanıcı kararı).** *"eğer ki biz apppostan pgw ye fiş/ödeme
yoladıysak o ödeme pgw de onaylanmış gibi direkt beklemeden kaydedelim db ye. al ver
handshake'i olmasın o noktada."* Yani pos→PGW yönünde **gönderim = onay** sayılıyor.
Gerçek projede PGW'nin cevabı dinlenecek; kod yorumlarında `TODO(pgw-handshake)`.

**4. Yol 1'de timeout yok (kullanıcı kararı).** Tezgâh müşterinin cevabını **süresiz**
bekliyor; esnaf iptal edebiliyor. Timeout, PGW'ye ne söyleneceği ve açılmış isteğin geri
çekilip çekilmeyeceği kararlarını gerektiriyor — ayrı tur. `TODO(timeout)` düşüldü.

**5. FCM yok.** POS 5 sn poll ediyor (`pgw_jobs` ve bekleyen onay için), onaylar kutusu
15 sn. Gerçek anlık teslim FCM ister; `devices` tablosu iskeleti hazır (§D).

### H.2 Uygulama sırasında bulunan iki şey

**Güvenlik açığı (kapatıldı):** `POST /transactions` `customer_id`'yi yalnız "var mı" diye
kontrol ediyordu. `seller_id` token'dan geldiği için başkasının defterine yazmak engelliydi,
ama **aynanın diğer yüzü** açıktı: A satıcısı, B'nin müşterisinin id'siyle **kendi defterine**
kayıt açabilirdi ve o satır, kişinin `/me/debts`'inde hiç gitmediği bir dükkâna borç olarak
görünürdü. Artık **defter üyeliği** doğrulanıyor (403 `not_in_book`).

**`singleTask` ↔ `startActivityForResult` uyumsuzluğu:** app-pos `singleTask` idi, yani
kendi task'ında koşuyordu ve Android bunu **anında `RESULT_CANCELED`** ile cevaplıyor.
Yol 1'in tamamı bu sonuca dayandığı için `singleTop` + `taskAffinity=""`'ye geçildi.
⚠️ Bu manifest satırı **yol 1'in çalışması için şart**; "sadece bir launchMode" diye
değiştirilirse kabul edilen ve reddedilen her satış PGW'ye aynı görünür.
