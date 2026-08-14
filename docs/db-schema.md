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

```sql
CREATE TABLE approvals (
  approval_id TEXT PRIMARY KEY, initiator_user_id TEXT NOT NULL, initiator_role TEXT NOT NULL,
  target_user_id TEXT NOT NULL, seller_id TEXT NOT NULL, shop_name TEXT NOT NULL,
  customer_id TEXT NOT NULL, amount_minor INTEGER NOT NULL, type TEXT NOT NULL, description TEXT,
  channel TEXT NOT NULL, status TEXT NOT NULL, requested_at TEXT NOT NULL,
  updated_at TEXT NOT NULL );
CREATE INDEX idx_approvals_target ON approvals(target_user_id, status);
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
| `Transaction` (+basketId?, settledViaPgw, receiptNo?) | `TransactionEntity` (FK basket_id?) | `Transaction` / `TransactionCreate` |
| `OrderBody` / `OrderItem` | `BasketEntity` / `BasketItemEntity` | `OrderBody` / `OrderItem` |
| `Approval` (yön alanlı) | `ApprovalEntity` | `Approval` |
| `SellerDebt` (repo projeksiyon) | — (DAO join sonucu) | `SellerDebt` |
| `Balance` | — (DAO SUM sonucu) | `Balance` |
| İleri: `FxRate`/`CreditOffer`/`AuditEntry`/`Device` | `FxRateEntity`/… (iskele) | (ileri-faz contract) |

- **Mapper konumu:** Entity↔Domain → `:core-data/mapper/`; Dto↔Domain → `:core-network/mapper/`
  (FAZ 4). Enum'lar üç temsilde de string.
- **Alan adı çevirisi:** Dto'da Moshi `@Json(name="...")` ile snake_case; Room kolon adı `@ColumnInfo`.
