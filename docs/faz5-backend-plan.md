# FAZ 5 — Backend (Python + FastAPI + Postgres + Docker)

> Bu dosya FAZ 5'in **uygulama planı**. Nereye kadar geldiğimiz: [progress.md](progress.md).
> Neyi bilinçli erteledik: [deferred.md](deferred.md). Uygulanacak sözleşme:
> [`shared-contracts/openapi.yaml`](../shared-contracts/openapi.yaml) + [db-schema.md](db-schema.md).
>
> Backend bu ikisini **uygular, yeniden tasarlamaz**. Plan yazılırken kod okundu; contract ile
> kodun ayrıştığı 8 nokta bulundu ve aşağıda karara bağlandı (§0).

---

## Neden şimdi

1. **Mock'a yazılan kod birikiyor.** `login()` hâlâ lokal doğruluyor, `OtpService` sahte. Her
   ertelenen uç, sonradan sökülecek bir mock daha demek.
2. **app-mobile mirror'lanmadan önce yapılmalı.** app-pos'un ağ katmanı app-mobile'a
   kopyalanacak; gerçek sunucuya karşı doğrulanmamış bir katmanı kopyalamak, hatayı da
   kopyalamak olur.
3. **Akışların gerçekten çalıştığını görmek.** Outbox drain'i, idempotency'yi, 401→refresh
   döngüsünü ancak gerçek sunucu kanıtlar. Prism statik example döndürüyor: idempotency
   kontrol etmiyor, bakiye hesaplamıyor, token üretmiyor.

## Alınan kararlar

| Konu | Karar | Not |
|---|---|---|
| Stack | Python 3.12 + FastAPI + PostgreSQL 16 + Docker Compose | Kullanıcının Python deneyimi var |
| Kapsam | Contract'ın BÖLÜM A'sının tamamı (~18 uç) | approval/buyer dahil → app-mobile turunda hazır |
| `future` tag'li uçlar | YAZILMAZ (`/sync/transactions`, `/settle`, `/insights`, `/credit-offers`, `/devices`) | Client'ta da bağlı değil |
| Seed | **app-mobile'ın seed'i taban alınır** (§0.1) | app-pos'unki bunun alt kümesi |

---

## §0 — Plan yazılırken bulunan 8 ayrışma (KARARA BAĞLANDI)

Bunlar planın ilk taslağında yoktu; kod okunurken çıktı. Her biri uygulanmazsa 5f cihaz
testinde ya yanlış alarm ya sessiz veri kaybı üretir.

### 0.1 İKİ FARKLI SEED VAR — backend hangisini alacak? 🔴 en kritik

app-pos ve app-mobile **aynı id'leri farklı anlamlarla** kullanıyor:

| | app-pos `SeedCallback` | app-mobile `SeedCallback` |
|---|---|---|
| `u_owner` displayName | `""` (boş) | `"Ahmet Demirtaş"` |
| `u_owner` shopName | **`null`** | **`"Ahmet Bakkal"`** |
| `u_market` (2. satıcı) | **YOK** | var (`Ayşe Korkmaz` / `Ayşe Market`) |
| `c2` (Ayşe Demir) | var, UNCLAIMED, bakiye 165,00 | **YOK** |
| `m1`, `o1` (çapraz defter) | YOK | var |
| `t4`/`t5` kime ait | `u_owner` → `c2` | **`u_market` → `m1`** ← aynı id, farklı satır |
| timestamp formatı | ISO-8601 (`2026-07-20T06:15:00Z`) | **`dd.MM.yyyy HH:mm`** |
| transaction sayısı | t1–t10 | t1–t13 |

**KARAR: backend seed'i app-mobile'ın seed'ini taban alır**, çünkü:
- Tek DB var; iki client aynı sunucuya bakacak. app-mobile'ınki **üst küme** (çapraz defter
  `m1`/`o1` + ikinci satıcı `u_market` + approval satırları), app-pos'unki alt küme.
- `/me/debts` ve `/approvals` uçları **ancak** ikinci satıcı varsa anlamlı veri döndürür.
  app-pos seed'iyle `/me/debts` boş liste döner → 5e hiç test edilemez.
- Contract'ın kendi örnekleri (`u_market` / `Ayşe Market` / `p1`+`p2`) zaten app-mobile
  seed'inden türetilmiş.

**Sonuç — 5a'da yapılacaklar:**
- Backend seed = app-mobile seed'i, **timestamp'ler ISO-8601'e çevrilerek** (yerel saat
  Istanbul UTC+3 kabul edilir: `20.07.2026 09:15` → `2026-07-20T06:15:00Z`).
- **`t4`/`t5` çakışması:** app-pos'ta bu id'ler `u_owner`→`c2`, app-mobile'da `u_market`→`m1`.
  Backend app-mobile'ınkini alır. app-pos cihazı 5f'de **temiz kurulumla** sunucudan okuyacağı
  için çakışma kalmaz (bkz. §0.6).
- **`c2` (Ayşe Demir) korunur** — app-pos seed'inden gelen tek fazlalık; UNCLAIMED bir
  müşteri örneği olarak değerli (claim akışı testi). `u_owner` defterine eklenir.

### 0.2 openapi.yaml örnekleri BAYAT — 3 saat kaymış

Contract örneği `t1` için `created_at: '2026-07-20T09:15:00Z'` diyor. app-pos Aşama 0'da
ISO'ya geçerken **aynı anı** `2026-07-20T06:15:00Z` olarak yazdı (Istanbul UTC+3 → UTC).
Yani contract örneklerinde yerel saate `Z` eklenmiş, dönüşüm yapılmamış.

**KARAR:** `openapi.yaml`'daki `created_at`/`requested_at` örnekleri **düzeltilir** (−3 saat).
Yoksa planın "seed tutmazsa client-sunucu ayrışması vardır" doğrulama kuralı ilk günden
yanlış alarm verir. Bu 5a'nın parçası, ayrı tur değil.

### 0.3 `POST /approvals` gövdesinde `seller_id` var — yetki kontrolü şart

`ApprovalCreate.required` listesinde `seller_id` var (transactions'ın aksine). Burada
**savunulabilir**, çünkü onayı bir buyer da başlatabilir ve o zaman `seller_id` token'dan
gelemez. Ama kontrolsüz bırakılırsa bir buyer başkasının defterine onay isteği açabilir.

**KARAR (5e):** `initiator_user_id` **her zaman** token'dan alınır (gövdede yok zaten).
Ek olarak: `initiator_role == SELLER` ise `seller_id == token.sub` doğrulanır, değilse
**403**. `initiator_role == BUYER` ise `seller_id` serbest ama customer'ın o seller'ın
defterinde olması aranır.

### 0.4 `PhoneFormat.toStored` planın anlattığı gibi değil

İlk taslak "aksi halde ham rakamlar" diyordu. Gerçek kod
([`PhoneFormat.kt:22-32`](../app-pos/app/src/main/java/com/example/app_pos/util/PhoneFormat.kt))
**`null` döndürüyor** — yani reddediyor. Sunucu "ham rakam" fallback'i uygularsa client'ın
reddettiği numarayı kabul eder → aynı kişi için iki kayıt.

**KARAR:** Sunucu da **reddeder (400 `invalid_phone`)**. Algoritma birebir:
- 12 hane ve `90` ile başlıyor → `+` ekle (idempotent dal)
- 11 hane ve `0` ile başlıyor → baştaki 0'ı at, `+90` ekle
- **aksi halde 400**

(Not: ilk taslak `RoomLocalDataSource.storedPhone`'a atıf yapıyordu; gerçek konum
`app/util/PhoneFormat.kt`.)

### 0.5 `logout()` planda yoktu

[`OfflineFirstRepository.kt:85`](../app-pos/core-data/src/main/java/com/example/app_pos/data/OfflineFirstRepository.kt)
`TODO(phase 4b): remote.logout()`. İlk taslak sadece `login()`'i bağlıyordu.
**KARAR:** 5f kapsamına girer. Kural: revoke başarısız olsa bile lokal temizlik **yapılır** —
başarısız bir revoke kullanıcıyı "giriş yapmış" kabuğunda bırakmamalı.

### 0.6 Geçişte outbox veri kaybı riski 🔴

Cihazda şu an **lokal** üretilmiş session ve lokal id'ler var. Gerçek backend'e geçince
sunucu kendi user_id'sini üretecek. Kuyrukta bekleyen kayıtlar lokal `customer_id` taşıyor →
sunucuda yok → **404** → `SyncEngine` 4xx'i "kalıcı ret" sayıp **satırı siler** (sessiz kayıp).

**KARAR (5f, zorunlu ilk adım):** geçiş öncesi **`adb uninstall` + temiz kurulum**.
MIUI'de `pm clear` çalışmıyor (bkz. memory: MIUI adb kısıtlamaları), uninstall şart.
Bu "DB şeması değişmedi, uninstall gerekmez" notunun **istisnası** — değişen şema değil,
id uzayı.

### 0.7 Seed idempotent olmalı

`alembic upgrade head` her `docker compose up`'ta çalışacak. Seed koşulsuz olursa bakiyeler
katlanır. **KARAR:** app-pos'un `SeedCallback.isEmpty()` deseni birebir — `users` boşsa seed
et, doluysa dokunma.

### 0.8 `refresh_token` nullable ama client onsuz çalışamaz

`SessionDto.refreshToken` contract'ta nullable; ama
[`TokenAuthenticator.kt:38`](../app-pos/core-network/src/main/java/com/example/app_pos/network/auth/TokenAuthenticator.kt)
`?: return null` ile **refresh edemeden pes ediyor** → kullanıcı login ekranına düşer.
**KARAR:** sunucu **her zaman** refresh_token üretir. Contract'ı değiştirmeye gerek yok
(nullable olması "vermeyen sunucu da geçerli" demek), ama biz veriyoruz — 401→refresh→retry
yolu ancak böyle test edilebilir.

---

## Mimari kararlar (gerekçeleriyle)

1. **Bakiye SAKLANMAZ, `SUM` ile türetilir.**
   [`core-domain/Ledger.kt::balanceOf`](../app-pos/core-domain/src/main/java/com/example/app_pos/model/Ledger.kt)
   ile birebir aynı kural: DEBT artı, PAYMENT eksi. Sunucu ve cihaz aynı sayıyı bulmalı;
   saklanan bir kolon ikisinin ayrışabileceği tek yerdir.

2. **`Idempotency-Key == transaction_id`.** Aynı key + aynı gövde → **200** (orijinal kayıt);
   aynı key + farklı gövde → **409**, sessiz overwrite DEĞİL. Bu, client outbox'ının
   "silmek güvenli" varsayımının sunucu tarafındaki karşılığı — yanlış olursa cevabı kaybolan
   bir retry ikinci veresiye açar.

3. **`seller_id` gövdeden ASLA okunmaz, token'dan gelir.** `TransactionCreateDto`'da bu alan
   zaten **yok** (client tarafı uygulanmış). İstisna ve kuralı: §0.3.

4. **Append-only:** `transactions` üzerinde UPDATE/DELETE yok. Düzeltme = ters işaretli yeni
   satır. DB seviyesinde `REVOKE UPDATE, DELETE` ile zorlanır (yorum değil, kural).

5. **Telefon E.164 normalizasyonu sunucuda**, `PhoneFormat.toStored` ile birebir (§0.4).

6. **Yeni enum değeri = breaking change.** Client bilinmeyen `type`'ı düşürüyor
   (`EnumMapping.kt`), yani sunucu yeni bir tür eklerse eski client o satırı hiç görmez.

---

## Adımlar

### 5a — İskelet + Docker + şema + seed

```
backend/
  docker-compose.yml        # api + postgres (+ volume)
  Dockerfile
  requirements.txt
  alembic/                  # migration'lar (şema geçmişi kayıt altında)
  app/
    main.py                 # FastAPI app, router'ları bağlar
    config.py               # env: DATABASE_URL, JWT_SECRET, TOKEN_TTL
    db.py                   # SQLAlchemy engine/session
    models.py               # db-schema.md Bölüm A'nın 6 tablosu
    schemas.py              # Pydantic — openapi.yaml'daki DTO'ların birebir karşılığı
    phone.py                # E.164 normalizasyonu (§0.4)
    seed.py                 # app-mobile seed'i, ISO-8601'e çevrili (§0.1)
    routers/                # auth, users, customers, ledger, buyer, approvals
    deps.py                 # get_db, get_current_user (Bearer)
```

- Postgres tipleri db-schema.md konvansiyonuna göre: `*_minor` → **BIGINT**, bool → BOOLEAN,
  timestamp → **TIMESTAMPTZ** (client ISO-8601 UTC gönderiyor).
- Seed: **§0.1 kararı** — app-mobile tabanlı, `c2` korunarak, ISO-8601.
- Seed idempotent (§0.7).
- **`openapi.yaml` örnek timestamp'leri düzeltilir (§0.2).**
- **Doğrulama:** `docker compose up` → `GET /health` 200; `alembic upgrade head` tabloları
  kurar; seed bakiyeleri: c1 40,00 / c2 165,00 / c3 0 / c4 25,50 / c5 210,00 /
  m1 100,00 / o1 60,00.

### 5b — Auth

`/auth/otp/request`, `/auth/otp/verify`, `/auth/refresh`, `/auth/logout`

- OTP **mock kalır ama sunucuda**: `request` her numaraya `{sent:true}` döner, `verify`
  sabit kodu (`123456`) kabul eder. Gerçek SMS sağlayıcısı ayrı iş; şimdilik kodun **nereden
  geldiği** değişiyor.
- `verify` **auto-register YAPMAZ** → kayıtsız numara **404 `user_not_found`**
  ([api-endpoints.md](api-endpoints.md) A.1'de KESİN KARAR). Client `POST /users` ile kaydeder.
- JWT: `sub = user_id`, `exp`. **`refresh_token` her zaman üretilir (§0.8).**
- **Doğrulama:** `curl` ile verify → token; token'sız `/users/me` → 401.

### 5c — Users + Customers

`POST /users` (telefonla idempotent), `GET /users/me`, `PATCH /users/me`,
`POST /users/me/become-seller`; `POST /customers`, `GET /customers` (bakiyeli),
`GET /customers/{id}`, `GET /customers/lookup?phone=`

- `GET /customers` **seller-scoped**: token kullanıcısının defteri + her müşteri için `SUM`
  ile türetilmiş `balance_minor`.
- `lookup` 404 = "bu numarayı kimse tutmuyor". "Başka dükkanın tanıdığı" ayrımı **client'ın**
  kararı (`CustomerLookup`); sunucu sadece kaydı döner.

### 5d — Ledger (asıl iş)

`POST /transactions` (+ `Idempotency-Key`), `GET /transactions?customer_id=`,
`GET /balances?customer_id=`

`POST` mantığı — **tek DB transaction içinde**:
1. `Idempotency-Key` başlığı zorunlu; yoksa **400**.
2. Aynı `transaction_id` var mı? → gövde aynıysa **200** (orijinali dön), farklıysa **409**.
3. Yoksa: `seller_id`'yi **token'dan** al, satırı INSERT et, **201** dön.
4. Basket varsa `baskets` + `basket_items` da **aynı transaction'da** yazılır.

### 5e — Buyer + Approvals (app-mobile için hazır)

`GET /me/debts`, `GET /me/transactions?seller_id=`, `GET /me/balances?seller_id=`;
`POST /approvals`, `GET /approvals`, `POST /approvals/{id}/approve`,
`POST /approvals/{id}/reject`

- `/me/debts` **dükkan başına gruplu** — app-mobile'ın `observeDebtsBySeller`'ının sunucu
  karşılığı. `shop_name` seed'den geliyor (§0.1 sayesinde dolu).
- **Onay kuralı** (Tur 24b'de bulunan bug'ın sunucu tarafı): **isteği başlatan onaylamaz.**
  `initiator_role == SELLER` ise onaylayan `customer.claimed_by_user_id`, aksi halde
  `seller_id`.
- **Yetki kontrolü: §0.3.**
- `approve` ledger'a yazan **tek nokta** bu hatta; `reject` hiçbir şey yazmaz, satırın
  `status`'ü değişir (denetim izi). Satır **SİLİNMEZ** (db-schema.md A.6 kararı).

### 5f — app-pos'u gerçek backend'e bağla

- **İLK ADIM: `adb uninstall` + temiz kurulum (§0.6).** Atlanırsa kuyruktaki kayıtlar sessizce
  silinir.
- `NetworkConfig` debug URL'i zaten `http://10.0.2.2:4010/` → compose'da **4010'a map edilir**,
  böylece tek satır bile değişmez.
- `OfflineFirstRepository.login()` gerçek `AuthApi`'ye bağlanır (FAZ 4b borcu):
  `requestOtp` → `verifyOtp` → dönen `SessionDto` `tokenStore.save()`. Lokal mock üretimi
  silinir. **404 `user_not_found` → NEEDS_REGISTER** (`LoginViewModel`'de bu dal zaten var).
- **`logout()` de bağlanır (§0.5).**

### 5g — Dokümanlar

- [progress.md](progress.md) → Tur 29.
- [deferred.md](deferred.md) güncellenir: 5b/5f'de kapanan borçlar (OTP mock, `login()`,
  `logout()`) işaretlenir; kalanlar (`observeUnsentCount` rozeti, app-pos gelen-onay UI'si,
  `TokenStore` şifresiz, destructive migration, app-mobile FAZ 4 eksiği) durur.
- [architecture-pos.md](architecture-pos.md) §8'e backend fazının bittiği notu.

---

## Doğrulama

**Ben:**
```bash
cd backend && docker compose up -d && docker compose logs -f api
pytest        # idempotency, 409, bakiye SUM, seller-scope, onay yönü, telefon 400
curl -s localhost:4010/health
```
Ayrıca contract uyumu: `npx @redocly/cli lint shared-contracts/openapi.yaml` temiz kalmalı;
FastAPI'nin `/openapi.json`'ı ile elle karşılaştırma (alan adları snake_case mi, `*_minor`
integer mı).

Android tarafı: `:app:assembleDebug` + `:core-data:testDebugUnitTest` (**50 test bozulmamalı**).

**Sen (cihazda — önce `adb uninstall`, sonra `posbuild` + `docker compose up`):**

1. **Gerçek login:** `05554443322` → OTP ekranı → `123456` → dashboard. (Artık sunucu token'ı.)
2. **Kayıtsız numara** → "kayıt olunsun mu?" dalı → kayıt → giriş.
3. **Veresiye yaz** → Docker loglarında `POST /transactions` + `Idempotency-Key`; **201**.
4. **Idempotency kanıtı:** uçağa al (offline) → veresiye yaz → uçaktan çıkar → aynı kayıt iki
   kez POST edilse bile sunucuda **TEK satır** (ikinci istek **200**, 201 değil).
5. **Bakiye eşitliği:** cihazdaki bakiye = `GET /balances` cevabı. Tutmuyorsa `balanceOf` ile
   sunucu SQL'i ayrışmıştır.
6. **Oturumsuz drain** (Aşama 9 kuralı hâlâ geçerli): kuyrukta kayıt varken çıkış yap →
   arka planda POST **gitmemeli**; giriş yapınca gitmeli.
7. **Token yenileme:** `TOKEN_TTL`'i 60 sn yapıp bekle → istek 401 → sessizce refresh → devam
   (**login ekranı GÖRÜNMEMELİ**).
8. **Geçersiz telefon (§0.4):** `12345` gibi bir numara → client zaten reddediyor; `curl` ile
   doğrudan sunucuya gönder → **400**, kayıt açılmamalı.

---

## Sonraki turlar

1. **app-mobile mirror:** `:core-network` + outbox + WorkManager kopyası + Aşama 0 timestamp
   geçişi. Backend hazır olduğu için approval/buyer uçları gerçek sunucuya bağlanır.
   **Not:** app-mobile'ın seed'i backend seed'inin tabanı olduğu için (§0.1) bu tur daha ucuz.
2. **FAZ 7 (regülasyon):** `audit_log` bağlanır, KVKK veri saklama kararları.
3. **FAZ 8:** FCM (`devices`), PGW settle, `future` uçları.
