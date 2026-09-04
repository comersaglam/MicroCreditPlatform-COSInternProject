# FAZ 6 — Sunum fazı planı (Tur 43–50)

> **Bu dosya ne işe yarar:** sunum öncesi yapılacak işin tamamı, sırası ve gerekçesi.
> Faz sekiz tur sürüyor, yani birden fazla oturuma yayılıyor — **yeni bir oturuma
> başlarken ilk okunacak dosya budur.** Nerede kaldığımız §0'da işaretli.
>
> Bu dosya *ne yapacağımızı* anlatır. Yaptıklarımız [progress.md](progress.md)'ye,
> bilinçli olarak yarım bıraktıklarımız [deferred.md](deferred.md) §L'ye yazılır.
> Dondurulmuş PGW sözleşmesi: [deferred.md §K](deferred.md#k-cihazda-doğrulanmış-pgw-sözleşmesi---değiştirme) 🔒
> — sepete veya geçide dokunan her tur önce oraya baksın.
>
> Son güncelleme: 2026-09-03. **Mobil taraf BİTTİ (43-47), hepsi cihazda doğrulandı.**
> Kalan: 48-49-50 — admin backend, web panel, chatbot.

---

## §0. Nerede kaldık

| Tur | Konu | Durum |
|-----|------|-------|
| 43 | fx_rates + **endeksleme** + zengin seed | ✅ kapandı (progress.md Tur 43) — cihaz doğrulaması Tur 44'e |
| 44 | **Tema B-a** + T-Fides kimliği — iki app | ✅ kapandı (progress.md Tur 44) — cihazda görüldü (Tur 45b) |
| 45 | Sepet detay ekranı (§J.6 kuyruğu) | ✅ **kapandı ve CİHAZDA DOĞRULANDI** (progress.md Tur 45 + 45b) |
| 46 | Toplam kırılımı + insights | ✅ **kapandı ve CİHAZDA DOĞRULANDI** — insights mock (§L.17), kırılım sheet'i ertelendi |
| 47 | Ödeme seçici + KVKK + profil % | ✅ **kapandı ve CİHAZDA DOĞRULANDI** (progress.md Tur 47 + 47b) |
| 48 | Admin backend | ✅ **kapandı** (progress.md Tur 48) — okumalar gerçek, ban mock (§L.20) |
| 49 | web-admin (React + Vite) | ✅ **kapandı** (progress.md Tur 49) — dokuz ekran, ekranda doğrulandı |
| 50 | Gemini chatbot | ⬜ başlamadı |

*Her tur bitince bu tablo güncellenir: ⬜ → 🔄 (devam) → ✅ (kapandı, progress.md'de Tur N).*

---

## §1. Bu fazın amacı ve sınırı

Tur 42 ile **teknik omurga kapandı**: sepet PGW→app-pos→approval→server→ledger yolunu
gerçekten kat ediyor ([deferred.md §J](deferred.md)), beş onay hattı tanımlı (§H), PGW
intent sözleşmesi donduruldu (§K 🔒). Backend'de 22 uç canlı, 5 migration, ~190 pytest.

Bu fazın işi **tam implementasyon değil, vizyon gösterme**: Veresiye'nin bir defter
uygulamasından **mikrokredi takip sistemine** dönüşebileceğini, çalışan bir önizleme ile
anlatmak.

⚠️ **En önemli kural:** çoğu parça bilinçli olarak mock. Ama mock olduğu **yazılı**
olacak → [deferred.md §L](deferred.md). Altı ay sonra kimse bunları yarım kalmış iş
sanmasın. Bu, §A–§K'nın kurduğu geleneğin devamı.

Sunumda üç yüzey tek ürün gibi görünmeli:

| Yüzey | Sunumdaki rolü | Durum |
|-------|----------------|-------|
| **POS terminali** (app-pos) | Çalışan gerçek akış | Var; sadece tema uyumu |
| **Alıcı/satıcı telefonu** (app-mobile) | Modern hissiyat + kur/enflasyon farkındalığı | Ana iş |
| **Admin paneli** (web-admin) | "Arkada gerçek bir platform var" kanıtı | Sıfırdan |

---

## §2. Alınan kararlar

2026-08-31 tarihli planlama oturumunda kullanıcıyla netleşti. **Bu kararlar tur
başlarken yeniden tartışılmaz** — değişirse burası güncellenir.

| # | Konu | Karar | Gerekçe |
|---|------|-------|---------|
| 2.1 | Admin web stack | **React + Vite**, ayrı `web-admin/`, Recharts | Gerçek komponent mimarisi; panel 6 sekme + chatbot taşıyacak kadar büyük |
| 2.2 | Admin auth | **`.env`'de ayrı admin şifresi** | `users` modeline dokunmamak; iki auth sistemi olması kabul edildi |
| 2.3 | Chatbot | **Gemini API** (öğrenci ücretsiz), function calling | Maliyet sıfır; metin + grafik dönüşü isteniyor |
| 2.4 | Kur/enflasyon | **Gerçek `fx_rates` tablosu** + snapshot hesabı | Tek "gerçek mimari" iş; vizyonun inandırıcılığı buna bağlı |
| 2.5 | Demo verisi | **Zengin seed**, mevcut `seed.py` **`seed_bk.py` olarak yedeklenir** | Sorun çıkarsa geri dönülebilsin (kullanıcı isteği) |
| 2.6 | Trafik sekmesi | **Sadece seed** — canlı middleware YOK | Canlıya alınmadığı için gerçek trafik yok; eksiklik §L.1'e yazılır |
| 2.7 | Admin işlemleri | Ban/askıya alma **gerçek**, ödeme düzeltme **mock** | Ledger append-only; düzeltme mimariyi bozar |
| 2.8 | Profil/KYC | **Yerel-only mock** — TC/foto Room'da kalır | Backend'e TC göndermek demo için gereksiz KVKK riski |
| 2.9 | Tema | **B-a "Derinlik"** (Tur 44'te beş draft turunda seçildi, C değil); iki app da tam | XML views'da yapılabilir; Compose'a dönülmüyor |
| 2.10 | Ödeme mock logoları | **Gerçek logolar**, `res/drawable/`, kendi renk şeridiyle | Token grubu içi sunum |
| 2.11 | Sepet detay | **Her iki app**, işlem satırına tıkla → yeni Fragment | Veri zaten domain'de hazır |
| 2.12 | **Enflasyon** | **Endeksleme** — gösterge değil, borcun parçası | *"ödeme yaparken inflated halini ödemesini istiyoruz"* (Tur 43'te eklendi) |
| 2.13 | Endeksleme yöntemi | **INDEXATION ledger satırı**, aylık + bileşik, tembel tetikleme | Bakiye formülü 10 yerde yazılı; satır eklemek formülü değiştirmiyor |

### Tur 48–49 oturumunda alınan kararlar (2026-09-04)

Sunuma az kaldığı için kapsam bilinçli daraltıldı: **okumalar gerçek, yazmalar mock.**

| # | Karar | Gerekçe / sonucu |
|---|---|---|
| 48.1 | **Altı sekme** + login (planda olduğu gibi) | Beşe indirmek yerine trafik sekmesi mock bırakıldı |
| 48.2 | Ban/askıya alma **mock** — `users.status` ve migration 0008 **yazılmadı** | ⚠️ Karar 2.7'yi geçersiz kılar; §6'nın 8. adımı düştü ([§L.20](deferred.md)) |
| 48.3 | Admin auth **gerçek** (`ADMIN_PASSWORD` + `typ:"admin"` JWT) | Panelin "gerçek platform" iddiasının en ucuz kanıtı; `decode_token` yeniden kullanıldı |
| 48.4 | `POST /admin/reset` **gerçek uç** | ⚠️ deferred §F.4'ün *"asla uç olmasın"* kararının bilinçli geri alınması ([§L.19](deferred.md)) |
| 48.5 | Trafik sekmesi **statik mock**, `audit_log` okunmuyor | Üretilmiş satırı grafiğe dökmek uydurmayı ölçüm gibi giydirirdi ([§L.21](deferred.md)) |
| 48.6 | Chatbot (Tur 50) **kapsam dışı** | Panelde pasif yer tutucu |
| 49.1 | Zemin **kırık metalik siyah** (`#0B0D12` + üç soluk sheen) | Palet `colors.xml`'den değer değer; üç yüzey tek ürün |
| 49.2 | Orbit trail **yalnız KPI kartlarında** | Telefonun *"odak kartı 6 ekranda"* kuralının web karşılığı |
| 49.3 | **Tailwind yok**, tek el yazımı `theme.css` | 8GB + `@property`'nin `@layer` içinde çalışmaması |
| 49.4 | Ödeme düzeltme: **501 uç yerine pasif buton + açıklama** | Kimsenin çağırmadığı 501 ölü kod; okunacak cümle asıl mesaj ([§L.2](deferred.md)) |

### Karar 2.12 nasıl girdi

Plan enflasyonu *gösterilen bilgi* olarak tasarlamıştı. Tur 43 başlarken kullanıcı
düzeltti: enflasyon farkı **borcun kendisi** olacak, ödeme onun üzerinden alınacak.
Satıcının enflasyona yenilmemesi ürünün asıl vizyon argümanı olduğu için kapsam bilinçli
büyütüldü — turun yaklaşık iki katına çıkmasına rağmen.

⚠️ Bunun iki açık ucu var, ikisi de [deferred.md](deferred.md)'de: endeks satırı geri
alınamıyor (§L.7) ve oran/sıklık henüz ürün kararı değil, teknik varsayım (§L.8 — BDDK
sorusu dahil).

### Tema B-a "Derinlik" — uygulanan hali

Beş draft turunda seçildi ([design/](../design/)). Plan başlangıçta "C — neon aksan"
diyordu; draft'lar üzerinde konuşurken B-a'ya dönüldü. Parlama/neon **kullanılmadı**:
ferahlık isteniyordu, ve koyu zeminde parlama kalabalık hissi veriyor.

```
  |o------------------------+   <- sol serit: ustte tam marka,
  |  GUNCEL BAKIYE          |      altta sonumleniyor
  |                         |
  |  2.616,00 TL            |   <- sayarak gelir, sonra gradyan alir
  |  Bunun 66,00 TL'si                enflasyon farki
  |  ....~~^^^~~....        |   <- sparkline (custom View)
  +-------------------------+
      kart: dikey gradyan, ust kenar aydinlik,
            alt kenar zeminle birlesiyor. GOLGE YOK.

zemin  #0B0D12       kart   #1A1E28 -> #14171F -> #0F1219
marka  #2151F3 (dolgu)      #5B87FF (koyu zeminde metin)
```

⚠️ **`#2151F3` metin olarak kullanılmıyor** — koyu zeminde ~3:1, WCAG AA'yı geçmiyor.
Dolgu olarak (buton, çip, şerit) üstüne beyaz metinle 8:1 veriyor.

**Tur 25b'nin dört-ton sistemi korundu** — cep testi (bana gelen yeşil / benden çıkan
kırmızı) hâlâ geçerli. Tur 43'ün `balance_indexation` soluk kırmızısı da yerinde.

⚠️ **Odak kartı yalnız 6 ekranda** (mobile 4 + pos 2). Şerit her yerde olursa vurgu
olmaktan çıkar.

---

## §3. Sıralama mantığı

Turlar üç kurala göre sıralandı. **Sıra değiştirilecekse bu üç kural kontrol edilmeli.**

1. **Bağımlılık** — `fx_rates`, onu okuyan ekranlardan önce. Tema token'ları, o
   token'ları kullanan yeni ekranlardan önce.
2. **Çakışma** — Tema turu 40+ layout'a dokunuyor. Yeni ekranlar (sepet detay, ödeme
   seçici, insights) **temadan sonra** yazılmalı, yoksa iki kez elden geçer.
3. **Risk erken/geç** — Gemini ve React en belirsiz parçalar ama **hiçbir şeyi
   bloklamıyorlar**, o yüzden sonda. Buna karşılık veri katmanı en başta, çünkü hem
   mobil hem web ondan besleniyor.

⚠️ **Kritik:** Tur 43'ten önce hiçbir UI işi başlamamalı. `fx_rates` ve zengin seed
olmadan yazılan her ekran boş veriyle test edilir, sonra yeniden test gerekir.

```
43 (veri) ──┬──> 44 (tema) ──┬──> 45 (sepet detay)
            │                ├──> 46 (kırılım + insights)
            │                └──> 47 (ödeme + KVKK + profil)
            └──> 48 (admin API) ──> 49 (web panel) ──> 50 (chatbot)
```

---

## §4. Turlar

### Tur 43 — Veri temeli: fx_rates + zengin seed

**Neden önce:** hem mobil hem web bundan besleniyor. Bu olmadan yazılan her grafik
boş çıkar.

**Backend — model ve migration**

- `backend/app/models.py` → `FxRate`: `as_of DATE PK, usd_minor, eur_minor,
  gold_minor, cpi_index` (hepsi `BigInteger`). Alan adları Room'daki
  `FxRateEntity`'den kopyalanır (`core-data/.../db/entity/Entities.kt`, iskelet zaten
  var), ama **`cpi_index` yeni** — enflasyon hesabı için şart.
- Alembic `0006_fx_rates.py`. Konvansiyon: elle yazılmış, `revision = "0006"`,
  `down_revision = "0005"`, uzun docstring, `upgrade()` **ve** `downgrade()`.
- `AuditLog` modeli + `0007_audit_log.py`: `id, actor_user_id, action, entity_type,
  entity_id, ip, user_agent, status_code, created_at`.

**Backend — hesap katmanı**

`backend/app/fx.py` (YENİ), tek hesap yeri:

| Fonksiyon | İş |
|---|---|
| `rate_at(db, when)` | O tarihe en yakın (≤) satır |
| `inflation_delta(amount_minor, then, now)` | `amount × (cpi_now/cpi_then − 1)` |
| `in_usd(amount_minor, rate)` | O günkü dolar karşılığı |
| `project_forward(amount_minor, months)` | Son 12 ayın ortalama CPI artışıyla projeksiyon |

⚠️ **Para kuralı:** her zaman `int` kuruş; bölmede `//` değil `round()` —
`ledger.py`'daki `BigInteger` disiplini bozulmasın.

**Backend — uçlar**

- `GET /fx-rates?as_of=`
- `GET /me/debts/breakdown` → `{principal_minor, inflation_minor, fx_at_sale,
  total_paid_minor, projected_3m_minor}`

⚠️ **`ledger.py`'ın mevcut `_SIGNED_AMOUNT`/`_BALANCE` yapı taşları yeniden
kullanılacak**, yeni toplama mantığı yazılmayacak — yoksa cihazdaki hesapla ayrışır.
Bakiyenin ikinci bir tanımı olmamalı.

**Seed**

- `backend/app/seed.py` → **`seed_bk.py` olarak kopyala** (karar 2.5). Orijinal
  `seed.py` **aynen kalır** — ~190 pytest ondan besleniyor.
- `backend/app/seed_demo.py` (YENİ), ayrı giriş noktası `python -m app.seed_demo`:

| Veri | Hacim | Not |
|---|---|---|
| Kullanıcı | ~40 (8 satıcı + 32 alıcı) | |
| Müşteri kaydı | ~120 | Aynı kişi birden çok defterde |
| Transaction | ~1500, 12 aya yayılmış | Mevsimsel dalga (bayram/kış tepeleri) → grafikte trend |
| Sepet + kalem | ~200 | Sepet detay ekranı boş kalmasın |
| fx_rates | 365 gün | USD 32→41, CPI aylık ~%3 |
| audit_log | ~3000 | Sahte geçmiş trafik |

Idempotent olmalı — mevcut `is_empty(db)` deseni izlenir.

⚠️ **Canlı audit middleware YAZILMAYACAK** (karar 2.6). §L.1'e yazılır.

**Doğrulama:** `pytest -q` yeşil (190 test bozulmadı) + yeni fx testleri;
`docker compose exec api python -m app.seed_demo` sonrası
`SELECT count(*) FROM transactions` ≈ 1500.

---

### Tur 44 — Tema C: tasarım sistemi (app-mobile)

**Neden burada:** yeni ekranlar bu token'ları kullanacak. Sonra yapılırsa her ekran
iki kez elden geçer.

**Yeni token dosyaları** (`app-mobile/app/src/main/res/values/`)

| Dosya | İçerik |
|---|---|
| `dimens.xml` (YENİ) | `space_1`…`space_8` (4/8/12/16/24/32/48/64dp), `radius_sm/md/lg`, `stroke_hairline` |
| `type.xml` (YENİ) | `TextAppearance.Veresiye.Display` (34sp) / `.Title` (20sp) / `.Body` (15sp) / `.Caption` (12sp) / `.Money` (tabular figürler) |
| `colors.xml` (genişletilir) | `background #0D0D0F`, `surface #17171B`, `outline #2A2A30`, `glow_primary/accent/error` |
| `drawable/` | `bg_glow_number.xml`, `bg_card_neon.xml` (shape + gradient + stroke) |

⚠️ **Bugün hiçbir tipografi stili yok** — her boyut layout içinde gömülü. `type.xml`
o dağınıklığı toplar; bu turun kalıcı kazancı temadan çok bu.

**Yeni custom View**

- `view/SparklineView.kt` (~60 satır) — `Path` + `LinearGradient`, son 12 ayın bakiye
  eğrisi. Grafik kütüphanesi gerektirmez.
- `util/CountUpAnimator.kt` — `ValueAnimator` ile 0→toplam sayma (~10 satır).

**Layout geçişi**

🔒 **Kritik kural — id'leri koru:** her ViewBinding id korunur, layout tamamen
değişir, Fragment/ViewModel'e **dokunulmaz**. Bir id kırılırsa compile hatası alınır,
sessiz bug değil. Bu kural Tur 25'te kurulmuştu, burada da geçerli.

Öncelik sırası: `fragment_debts` → `fragment_customers` → `fragment_seller_detail` →
`fragment_customer_detail` → `fragment_approvals` → `fragment_profile` →
`fragment_login`.

**app-pos:** bu turda **sadece** token dosyaları kopyalanır, layout'lara dokunulmaz.
İki app tutarlı görünür, POS'un çalışan akışı riske girmez.

**Doğrulama:** `assembleDebug` (tur sonu) + cihazda her ekranın açılması.
`./gradlew --stop` her build sonrası — düşük RAM protokolü.

---

### Tur 45 — Sepet detay ekranı (§J.6 kuyruğu kapanıyor)

**Neden burada:** veri hazır, tema hazır. En yüksek değer/maliyet oranı — §J'nin
başlattığı *"bu veresiyede ne vardı?"* sorusu nihayet ekranda cevaplanıyor. Bugün
cevap yalnızca SQL'den alınabiliyor.

**Yeni:** `TransactionDetailFragment` + `fragment_transaction_detail.xml` +
`BasketItemAdapter` + `item_basket_row.xml`

**Giriş noktaları** — işlem satırına tıklama, üç yerde:

| App | Dosya | Rol |
|---|---|---|
| app-mobile | `ui/sellerdetail/SellerDetailFragment.kt` | Alıcı görünümü |
| app-mobile | `ui/customerdetail/CustomerDetailFragment.kt` | Satıcı görünümü |
| app-pos | `ui/dashboard/detail/CustomerDetailFragment.kt` | Tezgâh |

Nav argümanı `transactionId`. `Transaction.basket` null ise *"bu işlemde sepet
bilgisi yok"* — **para-only handoff meşru bir durum, hata değil**.

**Kur notu** aynı ekranda: *"Alındığı gün 3,2 USD — bugün 2,1 USD"*. `GET /fx-rates`'ten,
read-through cache ile; offline'da veya 404'te sessizce gizlenir.

⚠️ ***"Enflasyon farkı: +34,00 TL"* buradan ÇIKARILDI → Tur 46.** Bir sepetin kendi lira
cinsinden enflasyon farkı yok: `INDEXATION` bakiyeye ait ayrı satırlar, sunucu yazıyor,
sepetsizler. Sepete pay biçmek ledger'da karşılığı olmayan bir rakam üretirdi ve satırlar
toplanınca bakiyeyle tutmazdı. Doğru yeri Tur 46'nın `/breakdown` bottom-sheet'i — orada
rakam **bakiye başına** ve tek kaynaktan. Gerekçe [deferred.md §L.11](deferred.md).

⚠️ **Ölçek kuralları tek yerde:** `OrderItem.lineTotalMinor()`
(`core-domain/.../model/OrderBody.kt`) yeniden kullanılır — ekranda ayrı çarpma
yapılmaz. `quantity` ×1000, `taxPercent` ×1000 tuzakları orada kapalı.

**Doğrulama:** POS'tan long-press ile sepetli veresiye yaz → onayla → her iki app'te
satıra tıkla → üç kalem görünsün. §J.6'nın SQL sorgusu artık UI'da doğrulanabilir.

---

### Tur 46 — Toplam kırılımı + insights ekranı

⚠️ **Tur 46 MOCK olarak uygulandı** (kullanıcı kararı 46.1: *"vizyon göstermek esas, sunum
yakında"*). Altı kart, iki rol, gerçek grafik kütüphanesi — ama her rakam sabit.
Gerekçesi ve gerçek implementasyonun gerektirdikleri [deferred.md §L.17](deferred.md)'de.

⚠️ **Toplam kırılımı BottomSheet'i bu turda YAPILMADI.** `/breakdown` ucu canlı ama insights
enflasyon argümanını zaten taşıyor; sheet'in dört rakamından üçü aynı şeyi ikinci bir
kaynaktan söylerdi — Tur 45'in *"tek rakam iki kaynaktan gelmesin"* kuralı. Yapılırsa
kuralı: sadece `projected_3m_minor` ve fx çiftini telden al, diğer dördünü yerelden türet.
**Neden burada:** fx verisi (43) + tema/sparkline (44) hazır. `ReportsFragment` bugün
tek TextView'lık boş bir placeholder.

**Toplam kırılımı**

`DebtsFragment` ve `CustomersFragment` üstündeki tek rakama tıkla → BottomSheet:
anapara / enflasyon farkı / satış anındaki kur toplamı / toplam ödenen / kalan, artı
*"bugün ödemezsen 3 ay sonra ≈ X TL"* projeksiyonu. `GET /me/debts/breakdown`'dan
beslenir.

⚠️ **Tur 45'ten devredildi:** lira cinsinden enflasyon farkı **burada** gösterilecek,
sepet ekranında değil — rakam bakiye başına ve tek kaynaktan geldiği için burada
tutarlı ([deferred.md §L.11](deferred.md)). Android tarafında `fxRateAt` ve read-through
cache Tur 45'te bağlandı; bu turda eklenen yalnızca `/breakdown` ucu olacak.

**Insights ekranı** — `ReportsFragment` doldurulur, **role göre iki farklı içerik**:

| Rol | İçerik |
|---|---|
| **Alıcı** | Aylık harcama çubukları, satıcı bazında dağılım (donut), ödeme düzenliliği, enflasyona karşı reel yük eğrisi |
| **Satıcı** | Tahsilat vs veresiye eğrisi, en riskli 5 müşteri, ortalama geri ödeme süresi, aylık ciro trendi |

⚠️ **İki uç birden bağlanmalı** — app-mobile iki rollü, tek metot işin yarısıdır.
Bu, Tur 40e'nin kökündeki hatanın tekrarı olur.

Grafik: `SparklineView` (44) + `MPAndroidChart` (XML views ile uyumlu). Kütüphane
eklenirse `libs.versions.toml`'a alias.

**Doğrulama:** demo seed ile 12 aylık trend görünmeli; **boş hesapla açıldığında da
çökmemeli**.

---

### Tur 47 — Ödeme yöntemi seçici + KVKK + profil tamamlama

**Neden birlikte:** üçü de küçük, saf UI, birbirine değmiyor.

**Ödeme yöntemi mock fragment'i**

`SellerDetailFragment.showPayDialog()` → önce `PaymentMethodFragment` (BottomSheet),
sonra mevcut tutar akışı. Dört kart: **TokenFlex / Odero / Yapı Kredi / Normal Ödeme**
— gerçek logolar `res/drawable/logo_*.png`, her biri kendi renk şeridiyle.

⚠️ İlk üçü mock (*"yakında"* kartı). **"Normal Ödeme" mevcut `initiatePayment`
akışını sürdürür — çalışan yol bozulmuyor.**

**KVKK onayı — üç giriş noktası**

| App | Nerede |
|---|---|
| app-mobile | `ui/login/LoginFragment.showRegisterDialog()` |
| app-pos | `ui/login/LoginFragment.showRegisterDialog()` |
| app-mobile | Profil → "Satıcı ol" |

⚠️ **Kapı her giriş noktasında olmalı** — bir yerde çalışıyor olması diğerinde de var
demek değil. Tur 41 bunu pahalı öğretti.

Kaydırılabilir standart aydınlatma metni + *"Okudum, onaylıyorum"* checkbox;
onaylanmadan kayıt butonu pasif. Metin `strings.xml`'de `kvkk_text`. Onay yerel olarak
saklanır, backend'e gitmez.

⚠️ **`TokenStore`'a DEĞİL.** Bu plan öyle diyordu ama uzun fitilli bir bug olurdu:
`DataStoreTokenStore.clear()` çıkışta **tüm dosyayı** siliyor, yani onay her logout'ta
unutulur ve metin sonsuza kadar yeniden çıkardı. Tur 47'de ayrı bir
[`ConsentStore`](../app-mobile/core-data/src/main/java/com/example/app_pos/data/consent/ConsentStore.kt)
yazıldı (`preferencesDataStore(name = "consent")`, `core-data`'da) — ayrı dosya,
`clear()` erişemiyor.

**Kapı tek yerde:** `requireKvkkConsent(...)` Fragment eklentisi, üç çağrı noktası da onu
kullanıyor. Üçünün imzası farklı (`register(code)` / `register()` /
`becomeSeller(shopName)`); fark lambda içinde yakalanıyor, kapıya ulaşmıyor.

**Profil tamamlama**

⚠️ **Tur 47'de VİTRİNE indirildi** (kullanıcı kararı 47.1): alanlar sabit string, girdi
alınmıyor, hiçbir yere yazılmıyor, doluluk çubuğu %83'te sabit. Room'a **dokunulmadı**.
Gerekçe ve gerçek implementasyonun gerektirdikleri [deferred.md §L.16](deferred.md)'da —
özellikle `upsertUser`'ın `@Upsert` olması yüzünden her girişte profili ezeceği tuzağı.

Aşağıdaki plan metni gerçek implementasyon için geçerliliğini koruyor:

`UserEntity` += `tcNo, idPhotoUri, birthDate, address` — **yerel-only**.

* benim notum - buraya bi de yapıkrediye bağlan diye bi mock tuş da koyalım logosuyla beraber.
microcredit sistemi içi banka ile anlaşma vizyonumuzu göstersin

⚠️ Alan eklerken **tüm construct noktaları** güncellenmeli + Room migration gerekir.
Profil ekranında ilerleme çubuğu + eksik alan listesi. Kimlik fotoğrafı: galeri
seçici (`ActivityResultContracts.PickVisualMedia`), **kamera izni yok**. Profilde 3
mock bağlantı butonu (TokenFlex / Odero / Yapı Kredi).

**Doğrulama:** yeni kurulum → KVKK çıkmadan kayıt olunamamalı; profil %'si alan
doldukça artmalı. Cihazda `adb uninstall` + kurulum (`allowBackup=false` sayesinde
gerçekten siliniyor).

---

### Tur 48 — Admin backend

**Neden burada:** web-admin'in yaslanacağı API. Frontend'den önce.

**Auth**

`backend/app/admin_auth.py` — `.env`'den `ADMIN_PASSWORD`; `POST /admin/login` → ayrı
imzalı admin JWT (`typ: "admin"`). Mevcut `security.decode_token`'ın typ kontrolü
yeniden kullanılır — access/refresh ayrımını zaten böyle koruyor. `require_admin`
bağımlılığı `deps.py`'a.

**`backend/app/routers/admin.py` — altı sekmeyi besleyen uçlar**

| Sekme | Uç |
|---|---|
| Alıcılar (bireysel) | `GET /admin/buyers`, `/admin/buyers/{id}` |
| Satıcılar (bireysel) | `GET /admin/sellers`, `/admin/sellers/{id}` |
| Alıcı istatistikleri | `GET /admin/stats/buyers` |
| Satıcı istatistikleri | `GET /admin/stats/sellers` |
| Trafik | ~~`GET /admin/traffic`~~ ⚠️ **YAZILMADI** — sekme tamamen frontend mock ([§L.21](deferred.md)) |
| Admin profili | `GET /admin/me` — oturum, satır sayıları, migration head, **mock/gerçek envanteri** |

⚠️ **Tur 48'de eklenen, planda olmayan:** `GET /admin/overview` yerine `/admin/stats/*`
uçları `breakdown_for(db)`'yi selektörsüz çağırıyor — platform geneli kırılımı bedavaya
geliyor. Ayrıca `ledger.py`'a iki yardımcı eklendi (`receivables_by_seller`,
`debts_by_buyer`); bakiyenin ikinci tanımının router'a sızmaması için.

**Yönetim işlemleri**

| İşlem | Planlanan | **Gerçekleşen (Tur 48)** |
|---|---|---|
| `users.status` → migration `0008` | Gerçek, banlıya 403 | ⚠️ **YAZILMADI** — kapsam daraldı, head **0007**'de kaldı ([deferred.md §L.20](deferred.md)) |
| `POST /admin/users/{id}/suspend` / `/ban` / `/activate` | Gerçek | ⚠️ **YAZILMADI** — butonlar yalnız ekranda, rozetli (§L.20) |
| `POST /admin/reset` → mevcut `reset.py` | Gerçek | ✅ Gerçek, ama **§F.4'ün kararını geri alıyor** ve `seed_demo` + `warm_indexation` da çalıştırıyor ([§L.19](deferred.md)) |
| `POST /admin/transactions/{id}/adjust` | MOCK, 501 | ⚠️ **UÇ YAZILMADI** — açıklama panelde pasif buton olarak ([§L.2](deferred.md)) |

⚠️ ~~**`reset.py`'da bulunan eksik:** `_TABLES` listesinde `pgw_jobs` yok~~ — **zaten
kapatılmış.** `_TABLES` dokuz tablonun hepsini içeriyor (`pgw_jobs`, `fx_rates`,
`audit_log` dahil). Bu satır bayattı, Tur 48'de doğrulandı.

⚠️ **Ödeme düzeltme neden mock:** ledger append-only, düzeltme ters kayıt gerektirir.
Panelde bunu açıklayan bir not gösterilir. §L.2'ye yazılır.

**CORS:** `main.py`'a `CORSMiddleware` (Vite dev server `localhost:5173`). Bugün hiç
yok.

Tüm agregasyonlar `ledger.py`'ın mevcut yapı taşlarını kullanır — bakiye ikinci kez
tanımlanmaz.

**Doğrulama:** `tests/test_admin.py` (YENİ) — yetkisiz erişim 401, ban sonrası login
403, istatistik toplamları `test_seed_balances.py` mantığıyla tutarlı.

---

### Tur 49 — web-admin: React + Vite, altı sekme

**Neden sona yakın:** en büyük tek parça, ama hiçbir şeyi bloklamıyor.

```
web-admin/
  package.json          (react, react-router, recharts, tailwind)
  vite.config.ts        (proxy -> localhost:4010)
  src/
    api/client.ts       (fetch + admin JWT, localStorage)
    theme.css           (Tur 44'un B-a temasi: #0B0D12 -- ASAGIYA BAK)
    components/         (StatCard, NeonChart, DataTable, Sidebar)
    pages/
      Login.tsx
      Buyers.tsx        BuyerDetail.tsx
      Sellers.tsx       SellerDetail.tsx
      BuyerStats.tsx    SellerStats.tsx
      Traffic.tsx
      AdminProfile.tsx
```

⚠️ **Yukarıdaki iki hata Tur 49'da düzeltildi:** seçilen tema **C değil B-a** (§2.9 zaten
böyle diyor) ve zemin **`#0B0D12`**, `#0D0D0F` değil. Neon da kullanılmadı — §2'nin tema
notu *"parlama/neon KULLANILMADI"* diyor. Panelin paleti
[`colors.xml`](../app-mobile/app/src/main/res/values/colors.xml)'den değer değer kopyalandı.

⚠️ **Tailwind KULLANILMADI** (bu plan öneriyordu): 8GB makinede üçüncü toolchain'e
PostCSS+JIT eklemek pahalı, ve orbit trail'in `@property`'si `@layer` **içinde
çalışmıyor**. Tek el yazımı `theme.css` yazıldı.

⚠️ **Tema mobil ile aynı dili konuşur:** aynı zemin/yüzey/aksan renkleri. Sunumda üç yüzey
(POS / telefon / panel) tek ürün gibi görünmeli. Orbit trail **yalnız KPI kartlarında** —
telefonun *"odak kartı yalnız 6 ekranda"* kuralının web karşılığı.

Grafikler Recharts (bar, line, donut, heatmap); renkler `theme.css`'ten, ayrı palet
yok. ⚠️ Ban/askıya alma **mock** (§L.20); ödeme düzeltme pasif buton + açıklama (§L.2).

⚠️ **8GB makine:** `npm run dev` üçüncü bir toolchain. Bu tur boyunca Android Studio
kapalı tutulmalı — kasmanın sebebi leak değil swap thrashing.

**Doğrulama:** `npm run dev` + backend ayakta → altı sekme veri gösterir; panelden
ban et → app-mobile'dan giriş 403.

---

### Tur 50 — Gemini chatbot (admin paneli)

**Neden en sonda:** en belirsiz parça. Tek başına başarısız olsa bile 43–49 sağlam
durur.

`backend/app/routers/chat.py` — `POST /admin/chat`, admin korumalı. Gemini API
(öğrenci ücretsiz katman), **function calling** ile önceden tanımlı araçlar:

| Araç | İş |
|---|---|
| `query_customers(filter, limit)` | Müşteri listesi / arama |
| `aggregate_transactions(group_by, range, metric)` | Zaman serisi / toplam |
| `top_debtors(n)` / `risk_ranking(n)` | Sıralama |
| `traffic_summary(range)` | Trafik özeti |

**Dönüş iki biçimli:** metin cevabı + `chart_spec` JSON (`{type, data, labels}`) →
frontend Recharts'a verir.

🔒 **Güvenlik sınırı:** model **asla ham SQL üretmez**; sadece tanımlı fonksiyonları
parametreyle çağırır. **Yazma işlemi yok** — sorgu araçları salt-okunur.

API anahtarı `.env`'de (`GEMINI_API_KEY`), `.gitignore` kontrolü şart.

⚠️ **İnternetsiz geri düşüş:** önceden tanımlı 6 örnek soru, cache'lenmiş cevaplarla.
Demo internetsiz kalırsa panel sessiz kalmasın.

**Doğrulama:** *"en çok borcu olan 5 müşteri"* → tablo + bar chart; *"son 6 ayda
tahsilat trendi"* → çizgi grafik.

---

## §5. Riskler ve çıkış noktaları

| Risk | Etki | Karşılık |
|---|---|---|
| Tema turu 40+ layout'a dokunuyor | Regresyon | "id'leri koru" → kırılırsa compile hatası. app-pos layout'ları dışarıda. |
| Gemini API kotası/erişimi | Tur 50 çöker | 50 en sonda; cache'lenmiş 6 cevap geri düşüşü |
| 8GB + üçüncü toolchain | Swap thrashing | Tur 49'da Android Studio kapalı; `--stop` disiplini sürer |
| `seed_demo` 1500 kayıt + N+1 pull (§F.5) | Cihazda yavaş çekim | Demo hesapları küçük tutulur; §F.5 hâlâ açık, bu faz kapsamı dışında |

⚠️ **Zaman yetmezse:** **43-44-45-47 çekirdek** — bu dördü biterse mobil vizyon tam
anlatılır. 46 kısaltılabilir. 48-49-50 birlikte düşer (panel ya hep ya hiç).

---

## §6. Faz sonu uçtan uca doğrulama

1. `docker compose up -d --build` → `alembic upgrade head` **0007**'ye kadar koşar
   (0008 yazılmadı, §L.20)
2. `docker compose exec api python -m app.seed_demo` → zengin veri
3. `docker compose exec api python -m app.warm_indexation` → endeksleme **22 → 1222 satır**
   (bu adım olmadan enflasyon kartı boş çıkar, §L.17)
4. `pytest -q` → **291 geçti, 18 atlandı** (atlananlar §L.14'ün bilinçli `skipif`'i)
5. `assembleDebug` her iki app → ⚠️ **dex'i grep'le** (compile APK üretmez),
   `adb uninstall` + kur
6. **Cihaz senaryosu:** POS'ta long-press → sepetli veresiye → telefonda KVKK'lı yeni
   kayıt → onay → sepet detayında üç kalem + kur şeridi → toplam kırılımı → insights
   grafikleri → ödeme yöntemi seçici
7. `cd web-admin && npm run dev` → `localhost:5173`, şifre `admin` → altı sekme veri
   gösterir. ⚠️ Chatbot **yok** (Tur 50); kenar çubuğunda pasif yer tutucu duruyor.
8. ⚠️ ~~Panelden bir kullanıcıyı banla → app-mobile'dan giriş 403~~ — **bu adım
   GÖSTERİLEMEZ.** `users.status` yazılmadı, butonlar mock ([§L.20](deferred.md)).
   Yerine: butona bas → satırın etiketi ve rengi değişir, yanında *"veritabanına
   yazılmıyor"* rozeti çıkar. Panelin dürüstlüğü **Admin profili** sekmesindeki
   mock/gerçek envanterinde toplu olarak görülür.
9. **Ekranda kontrol** (§L.22): KPI kartlarında iz dönüyor mu, tablolarda **yok** mu;
   grafiklerin son noktası kısmi ay mı (öyleyse çizilmemiş ve notu yazılmış olmalı).

---

## §7. Her turda yapılacak doküman işi

- `progress.md` → `### YYYY-MM-DD — Tur N: <başlık>`, gövdede **Doğrulama** /
  **Öğrenilen** / **Sıradaki**
- `deferred.md §L` → o turda bilinçli olarak mock bırakılan ne varsa, gerekçesiyle
- Bu dosyanın **§0 tablosu** güncellenir (⬜ → 🔄 → ✅)
- Her yeşil adımda commit — tur sonunu bekleme
