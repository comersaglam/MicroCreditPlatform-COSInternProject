# DB Schema — Veresiye Platform

> Aşama 1 çıktısı (bkz. plan): Room (SQLite, cihazda) ve backend (Postgres) için **ortak** tablo
> tasarımı, tam kolon listesiyle. Endpoint'ler için kardeş dosya: [api-endpoints.md](api-endpoints.md).
> Karar kaynakları: [architecture-pos.md](architecture-pos.md) §4, [veresiye-platform-tasarim.md](veresiye-platform-tasarim.md) §7.

## Konvansiyonlar & tip eşlemesi

- **Para:** `*_minor` → INTEGER (kuruş). Float/DECIMAL yok.
- **bool:** SQLite `INTEGER` (0/1), Postgres `BOOLEAN`.
- **timestamp:** SQLite `TEXT` (ISO-8601), Postgres `TIMESTAMPTZ`.
- **Kolon adı** snake_case; domain (Kotlin) camelCase (mapper `:core-data`'da).
- **Append-only:** `transactions` yalnız INSERT — UPDATE/DELETE yok. Bakiye SAKLANMAZ, türetilir.
- **Bölüm A = ŞU AN bağlanan** (aktif). **Bölüm B = ileri-faz** (kod iskeleti yazılır, bağlama
  ertelenir — plan kararı).

---

# BÖLÜM A — ŞU AN bağlanan tablolar

## A.1 `users`
| Kolon | Tip | Null | Anahtar / Not |
|---|---|---|---|
| `user_id` | TEXT | NO | PK (UUID) |
| `phone` | TEXT | NO | UNIQUE — kimlik (E.164) |
| `display_name` | TEXT | NO | boş başlayabilir ("") |
| `is_buyer` | BOOL | NO | herkes true |
| `is_seller` | BOOL | NO | "Satıcı ol" true yapar |
| `email` | TEXT | YES | opsiyonel |
| `shop_name` | TEXT | YES | SellerInfo düzleştirildi |
| `shop_phone` | TEXT | YES | SellerInfo düzleştirildi |
| `created_at` | TIMESTAMP | NO | |

> **SellerInfo düzleştirme:** domain'de nested `SellerInfo?` (invariant'ı tip korur: isSeller ⇔
> sellerInfo!=null); Room entity'de düz `shop_name?`/`shop_phone?`. Mapper: `is_seller &&
> shop_name!=null` → `SellerInfo`. Room `@Embedded(prefix="shop_")` de kullanılabilir (Aşama 3 karar).

```sql
CREATE TABLE users (
  user_id TEXT PRIMARY KEY, phone TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
  is_buyer INTEGER NOT NULL, is_seller INTEGER NOT NULL, email TEXT,
  shop_name TEXT, shop_phone TEXT, created_at TEXT NOT NULL );
```

## A.2 `customers`
| Kolon | Tip | Null | Anahtar / Not |
|---|---|---|---|
| `customer_id` | TEXT | NO | PK (UUID) |
| `display_name` | TEXT | NO | esnafın girdiği isim |
| `phone` | TEXT | NO | kimlik; pratikte hep dolu |
| `claim_status` | TEXT | NO | UNCLAIMED \| CLAIMED |
| `claimed_by_user_id` | TEXT | YES | FK→users; CLAIMED ⇔ not null |
| `created_by_seller_id` | TEXT | YES | FK→users; **defter üyeliği** (Tur 31, migration 0002) |
| `created_at` | TIMESTAMP | NO | |

- Index: `INDEX(phone)` (lookup), `INDEX(created_by_seller_id)`. **Global UNIQUE(phone) açık
  karar** (bkz. api-endpoints.md §1); backend'de 409 **defter bazında** (iki dükkan aynı kişiyi
  tanıyabilir).
- `balance_minor` YOK (ledger'dan türetilir).
- **`seller_id` kolonu YOK ve olmayacak** — müşteri satırı dükkanlar arasında PAYLAŞILIR
  (`c1` ve `m1` aynı kişi, farklı defterler). Defter üyeliği **türetilir**: *ledger'da bu
  satıcının satırı var* **VEYA** *`created_by_seller_id` bu satıcı*.
- **`created_by_seller_id` neden gerekti (Tur 31):** üyelik yalnız ledger'dan türetilseydi,
  yeni eklenmiş ama henüz borçlandırılmamış müşterinin hiç ledger satırı olmadığı için
  `POST /customers` 201 döner ama `GET /customers` onu göstermezdi. Sahiplik değil, **kimin ilk
  yazdığı** — bu yüzden nullable ve paylaşımı engellemiyor.

```sql
CREATE TABLE customers (
  customer_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, phone TEXT NOT NULL,
  claim_status TEXT NOT NULL, claimed_by_user_id TEXT REFERENCES users(user_id),
  created_by_seller_id TEXT REFERENCES users(user_id),
  created_at TEXT NOT NULL );
CREATE INDEX idx_customers_phone ON customers(phone);
CREATE INDEX idx_customers_created_by ON customers(created_by_seller_id);
```

## A.3 `transactions` (append-only ledger)
| Kolon | Tip | Null | Anahtar / Not |
|---|---|---|---|
| `transaction_id` | TEXT | NO | PK (UUID) — idempotency |
| `seller_id` | TEXT | NO | hangi satıcının defteri |
| `customer_id` | TEXT | NO | FK→customers; buyer |
| `amount_minor` | INTEGER | NO | pozitif; işaret `type`'ta |
| `type` | TEXT | NO | DEBT \| PAYMENT |
| `description` | TEXT | NO | "Veresiye" / "Ekmek, süt" |
| `basket_id` | TEXT | YES | FK→baskets; orderBody varsa (para-only null) |
| `settled_via_pgw` | BOOL | NO | PAYMENT PGW'den geçti mi (default false) — FAZ 8 |
| `receipt_no` | TEXT | YES | PGW fiş no (settled ise) — FAZ 8 |
| `created_at` | TIMESTAMP | NO | |

- Index: `INDEX(seller_id, customer_id)` (bakiye/liste), `INDEX(customer_id)` (buyer-scoped).
- Bakiye = (seller, customer) çiftinin toplamı (DEBT +, PAYMENT −).

```sql
CREATE TABLE transactions (
  transaction_id TEXT PRIMARY KEY, seller_id TEXT NOT NULL,
  customer_id TEXT NOT NULL REFERENCES customers(customer_id),
  amount_minor INTEGER NOT NULL, type TEXT NOT NULL, description TEXT NOT NULL,
  basket_id TEXT REFERENCES baskets(basket_id),
  settled_via_pgw INTEGER NOT NULL DEFAULT 0, receipt_no TEXT,
  created_at TEXT NOT NULL );
CREATE INDEX idx_tx_seller_customer ON transactions(seller_id, customer_id);
CREATE INDEX idx_tx_customer ON transactions(customer_id);
```

## A.4 `baskets` (orderBody başlığı)
| Kolon | Tip | Null | Not |
|---|---|---|---|
| `basket_id` | TEXT | NO | PK — PGW basketID (UUID) |
| `create_invoice` | BOOL | NO | |
| `document_type` | INTEGER | NO | |
| `is_void` | BOOL | NO | |
| `created_at` | TIMESTAMP | NO | |

```sql
CREATE TABLE baskets (
  basket_id TEXT PRIMARY KEY, create_invoice INTEGER NOT NULL,
  document_type INTEGER NOT NULL, is_void INTEGER NOT NULL, created_at TEXT NOT NULL );
```

## A.5 `basket_items` (orderBody items[])
| Kolon | Tip | Null | Not |
|---|---|---|---|
| `id` | TEXT | NO | PK (UUID, lokal) |
| `basket_id` | TEXT | NO | FK→baskets |
| `name` | TEXT | NO | |
| `price_minor` | INTEGER | NO | birim fiyat, kuruş |
| `quantity` | INTEGER | NO | ×1000 (1000 = 1 birim) |
| `tax_percent` | INTEGER | NO | ×1000 (1000 = %10) |
| `section_no` | INTEGER | NO | |
| `status` | INTEGER | NO | |
| `type` | INTEGER | NO | |
| `item_limit` | INTEGER | NO | orderBody `limit` (SQL rezerve kelime kaçınması) |

- Index: `INDEX(basket_id)`. Line total = `price_minor × quantity / 1000` (uygulama katmanı;
  Aşama 0 `OrderItem.lineTotalMinor` ile aynı).

```sql
CREATE TABLE basket_items (
  id TEXT PRIMARY KEY, basket_id TEXT NOT NULL REFERENCES baskets(basket_id),
  name TEXT NOT NULL, price_minor INTEGER NOT NULL, quantity INTEGER NOT NULL,
  tax_percent INTEGER NOT NULL, section_no INTEGER NOT NULL, status INTEGER NOT NULL,
  type INTEGER NOT NULL, item_limit INTEGER NOT NULL );
CREATE INDEX idx_basket_items_basket ON basket_items(basket_id);
```

## A.6 `approvals` (üç onay hattı — yön alanlı)
Mevcut `PendingApproval` mock'unun (app-mobile) alanları + ileri üç-hat alanları uzlaştırıldı.
`shop_name` (buyer kartı için denormalize) ve `requested_at` GERÇEK mock'tan; `initiator_*`/
`target_user_id`/`channel`/`status` ileri-hat alanları (mock bugünkü değerleri türetir).

| Kolon | Tip | Null | Not |
|---|---|---|---|
| `approval_id` | TEXT | NO | PK |
| `initiator_user_id` | TEXT | NO | başlatan (üç-hat) |
| `initiator_role` | TEXT | NO | BUYER \| SELLER (hangi hat) |
| `target_user_id` | TEXT | NO | onaylayacak taraf |
| `seller_id` | TEXT | NO | |
| `shop_name` | TEXT | NO | buyer kartında gösterim (denormalize, mock'tan) |
| `customer_id` | TEXT | NO | |
| `amount_minor` | INTEGER | NO | |
| `type` | TEXT | NO | DEBT \| PAYMENT |
| `description` | TEXT | YES | |
| `channel` | TEXT | NO | APP_PUSH \| SMS_OTP |
| `origin` | TEXT | NO | POS \| PHONE (Tur 41, migration 0004) — onaylanınca PGW işi yaratılıp yaratılmayacağına bu karar verir |
| `basket_id` | TEXT | YES | FK→baskets (Tur 42, migration 0005) — isteğin açıldığı sepet; para-only'de null |
| `status` | TEXT | NO | PENDING \| APPROVED \| REJECTED |
| `requested_at` | TIMESTAMP | NO | mock: `requestedAt`. Ne zaman SORULDU — hiç değişmez |
| `updated_at` | TIMESTAMP | NO | Ne zaman DEĞİŞTİ (migration 0003). `requested_at` karara bağlanmış satırı bekleyenden ayıramıyordu; `PENDING→APPROVED/REJECTED` geçişi damga bırakmıyordu |

- Index: `INDEX(target_user_id, status)` (bekleyenler). app-mobile Room'unda **AKTİF** (Aşama 4:
  Onaylar sekmesi bu tablodan okur); app-pos Room'unda İSKELE (entity+DAO var, kullanım = ayrı
  tur — gelen-onay UI dikeyi).
- Not: `PendingApproval` mock bugün TEK YÖN (seller→buyer). Üç-hat alanları eklenir ama
  `requestApproval`/`approvePending` onları bugünkü değerlerle türetir → davranış aynı
  (`initiator_role=SELLER`, `channel=APP_PUSH` — satır yalnızca CLAIMED karşı taraf için açılır;
  app'siz dal doğrudan ledger'a yazar, approval satırı oluşmaz).
- **Karar (Aşama 4):** onaylanan/reddedilen satır SİLİNMEZ, `status` güncellenir. Bekleyen
  sorgusu `status='PENDING'` filtreler → kullanıcıya davranış aynı, ama denetim izi kalır.
- **`customer_id` zorunlu:** istek hangi deftere açıldıysa onay oraya yazılır. Onay anında
  yeniden çözülmez — bir alıcının birden çok kaydı olabilir (dükkan başına bir tane), yeniden
  tahmin yanlış defteri seçebilirdi.
- **`origin` neden gerekti (Tur 41):** onay verilince sunucu, telefonda başlayan satışlar için
  `pgw_jobs`'a bir iş bırakır. Tezgâhta açılan satış bunu istemez — terminal PGW'nin önünde
  duruyor ve kendi intent'ini zaten atıyor, yani iş yaratmak **fişi iki kez** kestirirdi. İstek
  anında kaydedilir çünkü karar saatler sonra verilebilir ve o an bu bilgi kaybolmuş olur.
- **`basket_id` neden gerekti (Tur 42):** PGW'den gelen veresiye `POST /transactions`'a **hiç
  uğramıyor** — onaya gidiyor ve ledger'a sunucu yazıyor. Yani bu kolon olmadan, müşterinin
  onayını bekleyen **her** veresiyenin sepeti kayboluyordu (`baskets` tablosu turlarca 0
  satırdı, bkz. [deferred.md §J](deferred.md)). `origin` ile **aynı gerekçe**: istek anında
  saklanır, çünkü sepeti taşıyan handoff karar verildiğinde çoktan bitmiştir.
- **Reddedilen istek de sepetini korur:** ne İSTENDİĞİ, denetim izi için ne kabul edildiği
  kadar değerli. Nullable kalır — para-only kaydın gerçekten sepeti yoktur.

```sql
CREATE TABLE approvals (
  approval_id TEXT PRIMARY KEY, initiator_user_id TEXT NOT NULL, initiator_role TEXT NOT NULL,
  target_user_id TEXT NOT NULL, seller_id TEXT NOT NULL, shop_name TEXT NOT NULL,
  customer_id TEXT NOT NULL, amount_minor INTEGER NOT NULL, type TEXT NOT NULL, description TEXT,
  channel TEXT NOT NULL, origin TEXT NOT NULL, status TEXT NOT NULL, requested_at TEXT NOT NULL,
  updated_at TEXT NOT NULL, basket_id TEXT REFERENCES baskets(basket_id) );
CREATE INDEX idx_approvals_target ON approvals(target_user_id, status);
```

## A.7 `pgw_jobs` (terminale bırakılan PGW işi — Tur 41, migration 0004)

Sunucunun POS'a **ulaşamaması** yüzünden var. Yol 3, 4, 5 telefonda başlıyor ama PGW'de
bitiyor, ve PGW'ye yalnız terminal ulaşabiliyor; sunucu ise NAT arkasındaki bir cihazı
arayamıyor (FCM de yok). Çözüm: işi yaz, terminal gelip alsın.

| Kolon | Tip | Null | Not |
|---|---|---|---|
| `job_id` | TEXT | NO | PK |
| `seller_id` | TEXT | NO | FK→users; **hangi terminal çekecek** |
| `kind` | TEXT | NO | RECEIPT (fiş kes) \| COLLECT (karttan ödeme al) |
| `transaction_id` | TEXT | YES | eşlik ettiği ledger satırı; COLLECT'te NULL (para henüz alınmadı) |
| `customer_id` | TEXT | NO | |
| `amount_minor` | INTEGER | NO | |
| `order_body` | TEXT | YES | PGW'ye aynen gidecek JSON; COLLECT'te NULL (PGW kendi sepetini kurar) |
| `status` | TEXT | NO | PENDING \| DELIVERED |
| `created_at` | TIMESTAMP | NO | |
| `updated_at` | TIMESTAMP | NO | |

- Index: `INDEX(seller_id, status)` — bu tablonun hizmet ettiği tek sorgu.
- **Neden `approvals`'a bir bayrak değil:** iki tablo **farklı soru** cevaplıyor. Approval
  "kim KARAR verecek", job "kim İLETECEK **ve iletti mi**". İkinci yarı olmadan yeniden
  kurulan bir terminal geçmişteki her fişi tekrar keserdi.
- **Ack idempotent, ve sıra önemli:** terminal önce intent'i atar, sonra ack'ler. Aradaki bir
  çökme işi PENDING bırakır → tekrar denenir → fiş **iki kez** kesilir; can sıkıcı ama
  düzeltilebilir. Ters sıra fişi tamamen kaybeder. Garanti bilinçli olarak **en az bir kez**.
- **`transaction_id`'de FK YOK:** RECEIPT işi eşlik ettiği kayıtla aynı commit'te yazılır ama
  COLLECT NULL taşır; kolon "varsa eşlik ettiği kayıt" olarak okunmalı, satırların yalnız
  yarısının sağlayabileceği bir kısıt olarak değil.
- Karara bağlanan satır **silinmez** (`approvals` ile aynı kural): PGW'ye ne teslim edildiğinin
  izi, eşlik ettiği ledger kadar değerli.

```sql
CREATE TABLE pgw_jobs (
  job_id TEXT PRIMARY KEY, seller_id TEXT NOT NULL REFERENCES users(user_id),
  kind TEXT NOT NULL, transaction_id TEXT, customer_id TEXT NOT NULL,
  amount_minor INTEGER NOT NULL, order_body TEXT, status TEXT NOT NULL,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
CREATE INDEX idx_pgw_jobs_seller ON pgw_jobs(seller_id, status);
```

---

# BÖLÜM B — İLERİ FAZ tabloları (kod iskeleti yazılır, bağlama ertelenir)

Plan kararı: bu tablolar Aşama 3'te Room entity + DAO + Repository interface metodu olarak
YAZILIR (derlenir), ama UI/sync/web-fetch **bağlaması** ertelenir. Desen: `OtpService`/
`claimCustomerForUser` "imza+TODO, faz gelince içi dolar".

## B.1 `outbox` (FAZ 4 — offline sync kuyruğu)
`id TEXT PK, transaction_id TEXT NOT NULL, payload TEXT NOT NULL, created_at TIMESTAMP NOT NULL,
retry_count INTEGER NOT NULL DEFAULT 0`. WorkManager bağlaması FAZ 4.

## B.2 `fx_rates` (döviz kuru geçmişi — YER TUTUCU)
`as_of TIMESTAMP PK, usd_minor INTEGER, eur_minor INTEGER, gold_minor INTEGER`. Her işlem
anındaki USD/EUR/altın kuru; geriye dönük enflasyon/mikrokredi hesabı (tasarim.md son not).
Web-fetch dolumu ileride.

## B.3 `credit_offers` (FAZ 2/8 — mikrokredi)
`offer_id TEXT PK, user_id TEXT NOT NULL, limit_minor INTEGER NOT NULL, apr INTEGER NOT NULL,
term_days INTEGER NOT NULL, status TEXT NOT NULL, created_at TIMESTAMP NOT NULL`. `apr` ×100
(1900 = %19). UI + hesaplama FAZ 2/8 (BDDK lisans — tasarim.md FAZ 7).

## B.4 `audit_log` (FAZ 7 — denetim izi)
`id TEXT PK, actor_user_id TEXT NOT NULL, action TEXT NOT NULL, entity TEXT NOT NULL,
entity_id TEXT NOT NULL, at TIMESTAMP NOT NULL`. Değiştirilemez kim-ne-zaman-ne. Yazma-noktası
enstrümantasyonu FAZ 7.

## B.5 `devices` (FAZ 8 — FCM push)
`device_id TEXT PK, user_id TEXT NOT NULL, fcm_token TEXT NOT NULL, platform TEXT NOT NULL,
updated_at TIMESTAMP NOT NULL`. Push bağlaması FAZ 8.

---

# Üç temsil eşlemesi (Domain ↔ Entity ↔ Dto)

Her değerin üç değişme-sebebi farklı temsili (architecture-pos.md §4 "üç ayrı temsil"):

| Domain (:core-domain, camelCase) | Entity (Room, :core-data) | Dto (JSON / openapi, snake_case) |
|---|---|---|
| `User` (nested `SellerInfo?`) | `UserEntity` (düz shop_name?/shop_phone?) | `User` (`seller_info?` gömülü) |
| `Customer` (balanceMinor türetilir) | `CustomerEntity` (balance YOK) | `Customer` (`balance_minor` sunucu hesap) |
| `Transaction` (+**`basket: OrderBody?`**, Tur 42) | `TransactionEntity` (FK `basket_id?`) | `Transaction` (`basket_id` + `basket`) / `TransactionCreate` (`basket`) |
| `OrderBody` / `OrderItem` | `BasketEntity` / `BasketItemEntity` | `OrderBody` / `OrderItem` |
| `Approval` (yön alanlı) | `ApprovalEntity` | `Approval` |
| `SellerDebt` (repo projeksiyon) | — (DAO join sonucu) | `SellerDebt` |
| `Balance` | — (DAO SUM sonucu) | `Balance` |
| İleri: `FxRate`/`CreditOffer`/`AuditEntry`/`Device` | `FxRateEntity`/… (iskele) | (ileri-faz contract) |

- **Mapper konumu:** Entity↔Domain → `:core-data/mapper/`; Dto↔Domain → `:core-network/mapper/`
  (FAZ 4). Enum'lar üç temsilde de string.
- **Domain `Transaction` neden id değil SEPETİ taşıyor (Tur 42):** sepeti tek başına servis
  eden bir uç yok, yani yalnız `basketId` tutan bir domain nesnesi **çözemeyeceği** bir
  referans tutardı. Depolama tarafı (Entity) FK'yi korur — orada sepet ayrı tabloda ve id
  gerçekten çözülebilir. `settledViaPgw`/`receiptNo` domain'de **hâlâ yok**: onlar geçit
  muhasebesi, defter ekranları sormuyor. Sepet ise ne satıldığı, yani defterin kendisi.
- **Alan adı çevirisi:** Dto'da Moshi `@Json(name="...")` ile snake_case; Room kolon adı `@ColumnInfo`.
