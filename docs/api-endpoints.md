# API Endpoints — Veresiye Platform

> Aşama 1 çıktısı (bkz. plan): `openapi.yaml` (A) yazılmadan ÖNCE her endpoint'in **tam
> alan-seviyesi** request/response body'si. Tablolar için kardeş dosya: [db-schema.md](db-schema.md).
> Kaynak: iki client'ın (app-pos + app-mobile) `FakeRepository`'de bugün yaptığı gerçek
> okuma/yazma. İlgili karar dokümanları: [architecture-pos.md](architecture-pos.md),
> [veresiye-platform-tasarim.md](veresiye-platform-tasarim.md).

## Konvansiyonlar

- **Auth:** `Authorization: Bearer <token>` her istekte (AuthInterceptor). Token = Session
  (User'a değil — architecture-pos.md §5). Auth endpoint'leri hariç.
- **Para:** tüm `*_minor` alanları **integer, kuruş** (50 TL = 5000). Float YOK.
- **Ölçek:** `quantity`, `tax_percent` ×1000 (1000 = 1 birim / %10) — orderBody item'ları.
- **Alan adı:** JSON `snake_case`; domain (Kotlin) `camelCase` (mapper çevirir).
- **UUID:** `transaction_id`, `user_id`, `customer_id`, `basket_id`, `approval_id`.
- **seller_id gövdeye yazılmaz:** seller-scoped endpoint'lerde token'daki kullanıcıdan gelir
  (`currentSellerId`). Aynı şekilde buyer-scoped'ta buyer = token.
- **Enum'lar:** `type` (DEBT|PAYMENT), `claim_status` (UNCLAIMED|CLAIMED), `approval.status`
  (PENDING|APPROVED|REJECTED), `initiator_role` (BUYER|SELLER), `channel` (APP_PUSH|SMS_OTP).
- **Hata gövdesi (ortak):** `{ "error": { "code": string, "message": string } }`.

---

# BÖLÜM A — ŞU AN gereken endpoint'ler

İki client'ın mevcut mock ekranlarının gerçek ihtiyacı. Bunlar Aşama 2'de openapi.yaml'a ve
Aşama 3/4'te Room Repository'ye birebir eşlenir.

## A.1 Auth / OTP

### `POST /auth/otp/request`
Müşteri/esnaf girişinde OTP ister. (Mock: her numaraya "gönderildi".)
```jsonc
// Request
{ "phone": "+905554443322" }
// Response 202
{ "sent": true, "channel": "SMS_OTP" }   // channel: app'li kullanıcıda APP_PUSH olabilir
```
FakeRepository: `OtpService.requestOtp`.

### `POST /auth/otp/verify`
Kodu doğrular, token + kullanıcıyı döner. **Kayıt=giriş DEĞİL burada (KESİN KARAR):** numara
kayıtlı değilse auto-register YAPMAZ, `404 user_not_found` döner; çağıran `POST /users` ile
ayrı pipeline'da kayıt açar. Gerekçe: verify'ı saf tutmak debug'ı kolaylaştırır ve ileride farklı
bir register akışı (ör. ek doğrulama/KVKK rızası) gerekirse verify'a dokunmadan eklenir.
```jsonc
// Request
{ "phone": "+905554443322", "code": "123456" }
// Response 200
{ "token": "<opaque-session-token>",
  "expires_at": "2026-08-06T09:00:00Z",
  "user": { /* User (A.2) */ } }
// Response 404  -> caller registers via POST /users, then re-verifies/logs in
{ "error": { "code": "user_not_found", "message": "No account for this phone" } }
```
FakeRepository: `OtpService.verifyOtp` + `login`.

### `POST /auth/logout`
```jsonc
// Request: (empty)   Response 204
```
FakeRepository: `logout`.

## A.2 User / Profil

**`User` şeması (response gövdesi):**
```jsonc
{ "user_id": "u_owner",
  "phone": "+905554443322",
  "display_name": "Ahmet Bakkal",
  "is_buyer": true,
  "is_seller": true,
  "email": null,
  "seller_info": { "shop_name": "Ahmet Bakkal", "shop_phone": null },  // null if not a seller
  "created_at": "2026-07-01T09:00:00Z" }
```

### `POST /users`  — kayıt=giriş (register)
Numara yoksa oluşturur, varsa döner (idempotent). `is_seller`: app-pos'tan register = satıcı
(true), app-mobile'dan = alıcı (false).
```jsonc
// Request
{ "phone": "+905554443322", "display_name": "", "is_seller": true }
// Response 201 (created) | 200 (already existed)  -> User
```
FakeRepository: `registerUser`.

### `GET /users/me`
```jsonc
// Response 200 -> User (token'daki kullanıcı)
```
FakeRepository: `observeCurrentUser`.

### `PATCH /users/me`
Profil düzenleme (isim/email). Yalnız gönderilen alanlar güncellenir.
```jsonc
// Request
{ "display_name": "Ahmet Yıldız", "email": "ahmet@example.com" }
// Response 200 -> User
```
FakeRepository: `updateDisplayName` (+ email).

### `POST /users/me/become-seller`
"Satıcı ol" → `is_seller=true` + SellerInfo.
```jsonc
// Request
{ "shop_name": "Ahmet Bakkal", "shop_phone": "+902121112233" }  // shop_phone optional
// Response 200 -> User (seller_info dolu)
```
FakeRepository: `setSeller` / `updateShopName`.

## A.3 Customer (seller-scoped; seller = token)

**`Customer` şeması:**
```jsonc
{ "customer_id": "c1",
  "display_name": "Ahmet Yılmaz",
  "phone": "+905551112233",
  "claim_status": "CLAIMED",              // or UNCLAIMED
  "claimed_by_user_id": "u1",             // null if UNCLAIMED
  "balance_minor": 4000 }                 // DERIVED by server from ledger, not stored
```

### `POST /customers`
Esnaf app'siz müşteriyi isim+telefonla açar (UNCLAIMED). Telefon benzersiz (digit-normalize).
```jsonc
// Request
{ "display_name": "Fatma Şahin", "phone": "+905556667788" }
// Response 201 -> Customer (claim_status=UNCLAIMED, balance_minor=0)
// Response 409 phone_exists
```
FakeRepository: `addCustomer` (+ `customerPhoneExists`).

### `GET /customers`
Satıcının defteri: onunla en az bir ledger kaydı olan müşteriler + bakiye.
```jsonc
// Response 200 -> [Customer]  (SQL: DISTINCT customer_id WHERE seller_id=<token>)
```
FakeRepository: `observeCustomers`.

### `GET /customers/{id}`
```jsonc
// Response 200 -> Customer (balance seller-scoped)  | 404 not_found
```
FakeRepository: `findCustomerById`.

### `GET /customers/lookup?phone=+905556667788`
Yeni müşteri eklerken tekrar girişi atlamak için.
```jsonc
// Response 200 -> Customer | 404 not_found
```
FakeRepository: `findCustomerByPhone` / `customerPhoneExists`.

## A.4 Transactions / Ledger (append-only)

**`Transaction` şeması:**
```jsonc
{ "transaction_id": "t1",
  "seller_id": "u_owner",
  "customer_id": "c1",
  "amount_minor": 5000,          // positive; sign carried by type
  "type": "DEBT",                // DEBT | PAYMENT
  "description": "Ekmek, süt",
  "basket_id": null,             // set when the handoff carried an orderBody 
  "settled_via_pgw": false,      // PAYMENT settled through the payment gateway? (FAZ 8)
  "receipt_no": null,            // PGW receipt when settled (FAZ 8)
  "created_at": "2026-07-20T09:15:00Z" }
```

### `POST /transactions`  — tek yazma noktası
Idempotency zorunlu: `Idempotency-Key` header = `transaction_id`. `basket` verilirse
`baskets` + `basket_items` yazılır ve `basket_id` bağlanır (Aşama 0 orderBody → ledger).
```jsonc
// Headers: Idempotency-Key: <transaction_id>
// Request (TransactionCreate)
{ "transaction_id": "t-uuid",
  "customer_id": "c1",
  "amount_minor": 5000,
  "type": "DEBT",
  "description": "Veresiye",
  "basket": {                    // OPTIONAL — omit for a money-only entry
    "basket_id": "b-uuid",
    "create_invoice": false,
    "document_type": 0,
    "is_void": false,
    "items": [
      { "name": "Ekmek", "price": 1500, "quantity": 2000, "tax_percent": 1000,
        "section_no": 1, "status": 1, "type": 0, "item_limit": 0 }
    ] } }
// Response 201 -> Transaction   (retry with same key -> 200, same Transaction)
// 403 not_in_book — müşteri bu satıcının defterinde değil (Tur 41)
```
FakeRepository: `addTransaction` (+ orderBody Aşama 3'te).

⚠️ **Defter üyeliği doğrulanır (Tur 41).** `seller_id`'nin token'dan gelmesi *başkasının
defterine* yazmayı engelliyordu; bu kontrol **aynanın diğer yüzünü** kapatıyor: başka
dükkânın müşteri id'siyle **kendi defterine** yazmak. O satır, kişinin `/me/debts`'inde hiç
gitmediği bir dükkâna borç olarak görünürdü. Üyelik `customers` ile aynı kuralla türetilir
(*ledger'da satırı var* **VEYA** *`created_by_seller_id` bu satıcı*), tek uygulama
`app/ledger.py`'de.

### `GET /transactions?customer_id=c1`
```jsonc
// Response 200 -> [Transaction]  (seller=token, newest first)
```
FakeRepository: `observeTransactions`.

### `GET /balances?customer_id=c1`
**`Balance` şeması:**
```jsonc
// Response 200
{ "seller_id": "u_owner", "customer_id": "c1", "balance_minor": 4000,
  "as_of": "2026-07-30T12:00:00Z" }
```
FakeRepository: `balanceOf` / `observeTotalReceivableMinor` (toplam için `customer_id` yok).

## A.5 Buyer-scoped (app-mobile — seller-scoped'un simetriği)

**`SellerDebt` şeması:**
```jsonc
{ "seller_id": "u_owner", "shop_name": "Ahmet Bakkal", "balance_minor": 6000 }
```

### `GET /me/debts`
Müşterinin borcu tüm satıcılar boyunca, satıcıya göre gruplu (buyer = token).
```jsonc
// Response 200 -> [SellerDebt]
```
FakeRepository: `observeMyDebtsBySeller`.

### `GET /me/transactions?seller_id=u_owner`
```jsonc
// Response 200 -> [Transaction]
```
FakeRepository: `observeMyTransactions`.

### `GET /me/balances?seller_id=u_owner`
```jsonc
// Response 200 -> Balance
```
FakeRepository: `observeMyBalanceWithSeller`.

## A.6 Approvals — ÜÇ ONAY HATTI (tek şema, yön alanlı)

Üç hat aynı `Approval` şemasıyla modellenir:
1. **buyer-mobile → seller-POS** (müşteri ödeme başlatır, POS onaylar)
2. **seller-POS → buyer-mobile** (esnaf veresiye/ödeme başlatır, müşteri onaylar)
3. **seller-mobile → buyer-mobile** (app-mobile'daki satıcı başlatır, müşteri onaylar)

Ortak desen: **biri başlatır → karşı taraf onaylar → onay sonrası ledger'a yazılır**. PAYMENT
ise onay sonrası ayrıca **POS → PGW** (nakit/kart → fiş; gerçek intent FAZ 8, alanlar hazır).

**`Approval` şeması** (app-mobile'ın gerçek `PendingApproval` alanları + ileri üç-hat alanları):
```jsonc
{ "approval_id": "p1",
  "initiator_user_id": "u_owner",     // [üç-hat] başlatan
  "initiator_role": "SELLER",         // [üç-hat] BUYER | SELLER (hangi hat)
  "target_user_id": "u1",             // [üç-hat] onaylayacak taraf
  "seller_id": "u_owner",
  "shop_name": "Ahmet Bakkal",        // denormalize (buyer kartı) — mock'tan
  "customer_id": "c1",
  "amount_minor": 5000,
  "type": "DEBT",                     // DEBT | PAYMENT
  "description": "Ekmek, süt",
  "channel": "APP_PUSH",              // [üç-hat] APP_PUSH (app'li) | SMS_OTP (app'siz)
  "status": "PENDING",                // PENDING | APPROVED | REJECTED
  "requested_at": "2026-07-25T10:05:00Z",   // ne zaman SORULDU — hiç değişmez
  "updated_at": "2026-07-25T10:05:00Z" }    // ne zaman DEĞİŞTİ — her status geçişinde ilerler
```
> `[üç-hat]` alanları contract'ta var ve Room şemasında tutulur, ama mock BUGÜN bunları türetir
> (initiator_role=SELLER, target_user_id=buyerUserId, channel=CLAIMED?APP_PUSH:SMS_OTP). Tam
> üç-hat davranışı + app-pos "Onaylar" UI = AYRI tur (bkz. progress.md Tur 21).

### `POST /approvals`  — onaya gönder
Hedef müşteri **CLAIMED** ise (app'li) `Approval(PENDING)` oluşur (target'ın Onaylar sekmesine
push kartı düşer). **UNCLAIMED** ise (app'siz) OTP mock true → **anında** `Transaction` yazılır.
```jsonc
// Request
{ "seller_id": "u_owner", "customer_id": "c1", "amount_minor": 5000,
  "type": "DEBT", "description": "Veresiye",
  "initiator_role": "SELLER", "target_user_id": "u1",
  "origin": "POS" }              // POS | PHONE — varsayılan PHONE (Tur 41)
// Response 201 -> Approval (PENDING)          // CLAIMED target
//   OR       -> Transaction (already written) // UNCLAIMED target (immediate)
```
FakeRepository: `requestApproval` (+ `ApprovalService`).

**`origin` (Tur 41)** isteği hangi CİHAZIN açtığını söyler ve onaylanınca `pgw_jobs`'a iş
bırakılıp bırakılmayacağına karar verir. Tezgâh PGW'nin önünde durup kendi intent'ini
attığı için `POS` hiç iş yaratmaz; `PHONE` yaratır. Yanlış değer, fişin **iki kez**
kesilmesi (POS'a PHONE demek) ya da **hiç** kesilmemesi (tersi) demektir.

### `GET /approvals/{id}`  — isteği AÇAN taraf cevabı buradan öğrenir
`GET /approvals` "sana ne soruldu"yu listeler, ve **isteği açan taraf asla karar veren taraf
değildir** — yani satışı açık tutan tezgâh cevabı o listeden öğrenemez. İki uç da okuyabilir
(initiator + target), üçüncü bir hesap okuyamaz.
```jsonc
// Response 200 -> Approval
// 403 forbidden          — ne başlatan ne hedef
// 404 approval_not_found
```
Client: `Repository.approvalStatus` (app-pos, satış beklerken 5 sn poll).

### `GET /approvals`
Target = token. **Client'lar bu ucu POLL EDİYOR** (Tur 39) ve lokal tabloyu cevaba göre
senkronluyor → cevap **otoriter**: listede olmayan satır başka yerde karara bağlanmıştır ve
lokalde de silinmelidir.
```jsonc
// GET /approvals?status=PENDING&limit=100
//   status: PENDING (varsayılan) | APPROVED | REJECTED | ALL   — ALL geçmişi de döndürür
//   limit:  1..500, varsayılan 100
// Response 200 -> [Approval]
// 400 invalid_status — tanınmayan status
```
`?since=` **bilinçli olarak YOK**: wire formatı saniye hassasiyetinde
(`%Y-%m-%dT%H:%M:%SZ`), dışlayıcı bir `since` aynı saniyedeki satırları sessizce düşürürdü.
Tam liste senkronu hem bu riski hem de "hayalet kart"ı ortadan kaldırıyor
([deferred.md §F](deferred.md)).

FakeRepository: `observePendingApprovals`; pull tarafı `refreshApprovals`.

### `POST /approvals/{id}/approve`
Onaylar → ledger'a yazılır (tek yazma noktası). PAYMENT ise `settled_via_pgw` akışı FAZ 8.
```jsonc
// Response 200 -> Transaction (written)
```
FakeRepository: `approvePending`.

### `POST /approvals/{id}/reject`
```jsonc
// Response 204   (yazma yok)
```
FakeRepository: `rejectPending`.

---

## A.7 PGW jobs — sunucunun TERMİNALE bıraktığı iş (Tur 41)

Yol 3, 4 ve 5 telefonda başlıyor ama **PGW'de bitiyor**, ve PGW'ye yalnız POS ulaşabiliyor.
Sunucu ise bir terminali arayamaz (NAT arkası, FCM yok). Bu yüzden işi yazıp bırakıyor,
terminal gelip alıyor. Ayrıntı ve kolonlar: [db-schema.md §A.7](db-schema.md).

### `GET /pgw-jobs`
Token'daki satıcının bekleyen işleri, **en eskisi önce** (onaylar kutusunun tersine: burası
sırayla tüketilen bir kuyruk, fişler satışların sırasını izlemeli).
```jsonc
// GET /pgw-jobs?limit=50        // 1..200, varsayılan 50
// Response 200 -> [PgwJob]
// 403 not_a_seller — alıcı-only hesabın terminali yok
```
Client: `PgwDispatcher.pendingJobs` → `PgwJobRunner` (app-pos, 5 sn poll, ekran açıkken).

### `POST /pgw-jobs/{id}/ack`  — **idempotent**
İş PGW'ye iletildi. Zaten DELIVERED olan bir işi ack'lemek **200** döner, çakışma değil:
terminal önce intent'i atıp sonra ack'liyor, yani kayıp bir cevap onu *gerçekten teslim
edilmiş* bir işin ack'ini tekrarlar hâlde bırakır. Bunu hata saymak işi sonsuza dek PENDING
bırakır ve fiş her poll'de yeniden kesilir.
```jsonc
// Response 200 -> PgwJob (DELIVERED)
// 403 forbidden      — başka dükkânın işi
// 404 job_not_found
```

### `POST /pgw-jobs`  — satıcı KENDİ kasasına iş yollar (yol 4)
Onay **yok** ve bu bir eksiklik değil: kapı bir tarafın diğerine tek taraflı kayıt açmasını
engeller, burada dükkân **kendi tezgâhında** tahsilat istiyor ve müşteri kartı uzatarak
onaylıyor. Ledger'a da **hiçbir şey yazılmaz** — intent'i kuyruğa koymak kimsenin ödediğinin
kanıtı değil.
```jsonc
// Request  { "kind": "COLLECT", "customer_id": "c1", "amount_minor": 2500 }
// Response 201 -> PgwJob (PENDING)
// 400 invalid_kind — yalnız COLLECT; RECEIPT sunucuya özel (eşlik ettiği kayıtla yazılır)
// 403 not_in_book / not_a_seller
```
`seller_id` gövdede **yok**: terminal token'la adreslenir, yani kimse başka dükkânın
kasasına iş koyamaz.

---

# BÖLÜM B — İLERİ FAZ endpoint'leri (premature ama planlı)

Şu an gerçek kullanım yok; contract'ta yer tutucu + Aşama 3'te Room entity+DAO+interface iskeleti
yazılır (sadece BAĞLANMASI ertelenir — plan kararı).

## B.1 Sync (FAZ 4 — offline outbox'un sunucu ucu)
### `POST /sync/transactions`
```jsonc
// Request
{ "entries": [ /* TransactionCreate (A.4), her biri kendi transaction_id'siyle idempotent */ ] }
// Response 200
{ "accepted": ["t-uuid-1", "t-uuid-2"] }
```
Tablo: `outbox`. WorkManager bağlaması FAZ 4.

## B.2 PGW settle (FAZ 8 — PAYMENT → paymentgateway fiş)
### `POST /transactions/{id}/settle`
Onaylanmış bir PAYMENT'ı POS→PGW'ye gönderip fiş sonucunu işler.
```jsonc
// Request
{ "receipt_no": "R-2026-000123", "settled_via_pgw": true }
// Response 200 -> Transaction (settled_via_pgw=true, receipt_no dolu)
```
Gerçek `am start com.tokeninc.sardis.paymentgateway ... --es orderBody {...}` intent'i FAZ 8
(Aşama 0'ın TERS yönü: app-pos → PGW).

## B.3 Insights (FAZ 2 — analytics, KVKK gate)
### `GET /insights`
```jsonc
// Response 200 (şekil TBD)
{ "risk_scores": [ { "customer_id": "c1", "score": 0.42 } ],
  "collection_forecast_minor": 125000,
  "as_of": "2026-07-30T12:00:00Z" }
```
Gövde faz 2; şimdilik yalnız yer tutucu (veresiye-platform-tasarim.md bölüm 5).

## B.4 Micro-credit (FAZ 2/8 — bankadan cömert esnek hesap)
**`CreditOffer` şeması:**
```jsonc
{ "offer_id": "co1", "user_id": "u1", "limit_minor": 500000,
  "apr": 1900, "term_days": 30, "status": "OFFERED" }   // apr ×100 (1900 = %19)
```
### `GET /me/credit-offers` → `[CreditOffer]`
### `POST /credit-offers/{id}/accept` → `CreditOffer` (status=ACCEPTED)
Tablo: `credit_offers`. UI + hesaplama FAZ 2/8 (BDDK lisans sorusu — tasarim.md FAZ 7).

## B.5 FX rates (döviz kuru geçmişi — YER TUTUCU)
Her işlem anındaki USD/EUR/altın kuru; geriye dönük enflasyon/mikrokredi hesabı (tasarim.md
son not). Endpoint (ileride, web-fetch dolumu): `GET /fx-rates?as_of=...`. Bu turda contract'a
girmez; tablo `fx_rates` iskele olarak yazılır (bkz. db-schema.md).

## B.6 Audit log (FAZ 7 — regülasyon/KVKK denetim izi)
### `GET /audit-log?entity=transaction&entity_id=t1` → `[AuditEntry]`
Değiştirilemez kim-ne-zaman-ne kaydı. Tablo `audit_log`.

## B.7 Device / FCM (FAZ 8 — push)
### `POST /devices`
```jsonc
// Request
{ "fcm_token": "<token>", "platform": "android" }
// Response 204
```
Tablo: `devices`. Push bağlaması FAZ 8 (backend "yenilik var, sync et" dürtmesi).

---

## Açık kararlar (Aşama 2'de kapanır)
1. `customers.phone` global unique mi (backend), yoksa uygulama-katmanı kontrol mü? Mock: uygulama.
2. ~~`POST /users` ↔ `/auth/otp/verify`~~ — **KAPANDI:** AYRI. verify auto-register yapmaz,
   404 döner; register ayrı pipeline (debug + ileride farklı register akışı için).
3. Timestamp: mock "dd.MM.yyyy HH:mm" → contract/Room'da ISO-8601 (bu belge ISO gösterir).
4. `amount_minor` şemada `minimum: 0`; işaret `type`'ta.
