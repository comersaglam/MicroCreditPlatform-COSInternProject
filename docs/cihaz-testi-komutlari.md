# Cihaz testi — komut kılavuzu

Sunucu doğrulaması için hazır komutlar. Hepsi repo kökünden çalışır.
Kapsam: Tur 39 (onay pull'u) + Tur 40 (defter pull'u) + Tur 40b (düzeltmeler)
+ Tur 40d (yazma yolu, **D bloğu**) + Tur 42 (PGW sepeti, **E bloğu**).

## Kurulum (her testten önce)

```bash
# 1. Sunucuyu YENİDEN DERLEYEREK kaldır — `--build` şart, bkz. aşağıdaki uyarı
cd backend && docker compose up -d --build api && cd ..

# 2. Sunucuyu temizle + seed'le  (HTTP ucu YOK, sadece bu komut)
docker compose -f backend/docker-compose.yml exec -T api python -m app.reset

# 3. Port yönlendirme — HER USB bağlantısında yeniden
adb reverse tcp:4010 tcp:4010

# 4. Şema değiştiyse SIFIRLA (Tur 39'da ŞART; MIUI'de `pm clear` çalışmaz)
adb uninstall com.example.app_pos
adb uninstall com.example.app_mobile
adb uninstall com.tokeninc.sardis.paymentgateway   # mock-pos (Tur 41'den beri bu id)
adb uninstall com.example.mock_pos                 # ⚠️ yalnız ESKİ kurulum varsa

# 5. Kur (ayrı Gradle projeleri, sırayla — 8GB RAM)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
(cd app-pos    && ./gradlew :app:installDebug --max-workers=2 -Dorg.gradle.java.installations.auto-detect=false)
(cd app-pos    && ./gradlew --stop)
(cd app-mobile && ./gradlew :app:installDebug --max-workers=2 -Dorg.gradle.java.installations.auto-detect=false)
(cd app-mobile && ./gradlew --stop)
(cd mock-pos   && ./gradlew :app:installDebug --max-workers=2 -Dorg.gradle.java.installations.auto-detect=false)
(cd mock-pos   && ./gradlew --stop)
```

> 💡 **Kısayol: `~/.zshrc`'deki yardımcılar.** 5. adımın (ve 3. adımdaki `adb reverse`'ün)
> yerine geçer, JAVA_HOME'u ve hedef cihazı kendisi ayarlar:
>
> ```bash
> posbuild --usb        # app-pos  : derle + kur (host'u ayarlar, adb reverse dahil)
> mockbuild             # mock-pos : ağ bayrağı YOK — PGW taklidi backend'e bağlanmaz
> mobilebuild --usb     # app-mobile
> ```
>
> `--wifi` de var. Birden fazla cihaz bağlıysa serial ekle (`posbuild --usb 136dd281`) ya da
> `dev <serial>` ile sabitle. Son crash için `poscrash`.

> ⚠️ **mock-pos'un paket adı DEĞİŞTİ (Tur 41).** Artık taklit ettiği gerçek geçidin id'sini
> taşıyor: `com.tokeninc.sardis.paymentgateway`. Android için bu **ayrı bir uygulama**, yani
> eski kurulum elle kaldırılmazsa **iki ödeme uygulaması yan yana durur** ve yanlışına
> dokunmak "veresiye uygulaması bulunamadı" gibi görünür. Yukarıdaki `adb uninstall
> com.example.mock_pos` bir kez, geçiş için gerekli.
>
> Doğrulama: `adb shell pm list packages | grep -E "paymentgateway|mock_pos"` — yalnız
> `paymentgateway` kalmalı.

> **MIUI/HyperOS notu:** `INSTALL_FAILED_USER_RESTRICTED` alırsanız
> Ayarlar → Ek ayarlar → Geliştirici seçenekleri → **"USB ile yükleme"** açık olmalı.

> ℹ️ **`DELETE_FAILED_INTERNAL_ERROR` çoğu zaman hata DEĞİL.** `adb uninstall` **kurulu
> olmayan** bir paket için de bu jenerik mesajı veriyor — yani "silemedim" değil, "silecek
> bir şey yok" demek olabilir. Önce gerçekten kurulu mu diye bak:
>
> ```bash
> adb shell pm list packages | grep -E "app_pos|app_mobile|paymentgateway|mock_pos"
> ```
>
> Çıktı boşsa 4. adım zaten gereksizdir, doğrudan kuruluma geç. Paket **listede duruyor**
> ama silinmiyorsa o zaman gerçek bir kısıt vardır (iş profili, cihaz yöneticisi).

> ⚠️ **`--build`'i atlamayın (Tur 40c).** `docker compose up -d` çalışan container'ı
> **yeniden derlemez**; backend'de yeni yazdığınız kod sunucuda çalışmaz. Bu, Tur 40c'de
> düzelttiği sanılan bir hatanın (dükkân telefonu) cihazda hâlâ görünmesine yol açtı ve
> saatlerce yanlış yerde arandı. Şüphelendiğinizde container'daki dosyayı doğrudan okuyun:
>
> ```bash
> docker exec backend-api-1 grep -n shop_phone /code/app/schemas.py
> ```
>
> Aynı mantık cihaz için de geçerli: `compileDebugKotlin` **APK üretmez**, kurmadan önce
> `assembleDebug` + dex grep'i gerekir (Tur 40b).

> ⚠️ **Sıra önemli: önce sunucu, sonra cihaz.** `app.reset` yalnız **sunucuyu** sıfırlar.
> Cihazdaki Room'a dokunmaz ve buyer ledger pull'u **additive** olduğu için cihaz eski
> satırları kendiliğinden bırakmaz — 4. adımdaki uninstall bu yüzden reset'ten **sonra**
> gelmeli. Tersi sırada eski satırlar geri gelir.
>
> Bunu atlamak Tur 40c'de "aynı işlem iki kere görünüyor" olarak ortaya çıktı: cihazda eski
> yerel seed'in satırları (`t4`–`t10`) duruyordu, sunucu aynı içeriği farklı id'lerle
> (`t11`–`t15`) yolluyordu. **insert-IGNORE PK'ya bakar, içeriğe değil** — çakışma
> korumasının hiçbiri devreye girmez.

> ℹ️ **Kendi test yazımlarınız sunucuda kalıcıdır.** Uygulamayı silip kursanız da geri
> gelirler; sunucu tek gerçeklik, cihaz onun aynası. Temizlemenin tek yolu 2. adımdaki
> `app.reset`.

### 6. APK GERÇEKTEN yeni mi?  ⚠️ atlanmaz

Tur 40b'nin en pahalı dersi: `compileDebugKotlin` "derlendi" der ama **APK'yı üretmez**.
`installDebug` eski APK'yı kurabilir ve saatlerce yanlış semptom kovalanır.

```bash
for a in app-pos app-mobile; do
  echo "=== $a ==="
  unzip -p $a/app/build/outputs/apk/debug/app-debug.apk classes\*.dex \
    | strings | grep -c "OtpRequestResult"     # 0 ise APK ESKİ
done
```

`app-pos` için ayrıca `pullBook`, `app-mobile` için `pullMyLedger` grep'lenebilir.

### 7. Migration gerçekten koştu mu?  ⚠️ pytest bunu doğrulamaz

`conftest.py` şemayı `models.py`'den kuruyor → **Alembic test suite'inde hiç çalışmıyor.**

```bash
docker compose -f backend/docker-compose.yml exec -T api alembic current   # 0005 (head)
docker exec backend-db-1 psql -U veresiye -d veresiye -c "\d approvals" | grep basket_id
```

> ⚠️ **Tur 42: `alembic upgrade head` bu turda ATLANAMAZ.** Sepet düzeltmesi
> `approvals.basket_id` kolonunu getiriyor (migration **0005**); kolon yoksa onaya gönderme
> anında sunucu patlar. Kurulum adımlarına ekle:
>
> ```bash
> docker compose -f backend/docker-compose.yml exec -T api alembic upgrade head
> ```

## Test hesapları (`backend/app/seed.py`)

| Rol | Telefon | Kim | Not |
|---|---|---|---|
| **app-pos** | `05554443322` | Ahmet Demirtaş / "Ahmet Bakkal" (`u_owner`) | dükkan |
| **app-mobile** | `05551112233` | `u1` | Ahmet Bakkal'a **50 TL borçlu** (`c1`) |
| (diğer dükkan) | `05553334455` | Ayşe Korkmaz / "Ayşe Market" (`u_market`) | `p2`'yi u_owner'a gönderen |

OTP kodu her zaman **`123456`** (`backend/app/config.py` → `mock_otp_code`).

Seed onayları: **`p1`** = u_owner→u1 (50 TL, DEBT) · **`p2`** = u_market→u_owner (75 TL, DEBT)

## Sunucu tarafını izleme

### Token al (tekrar tekrar lazım olur)
```bash
# Dükkan (app-pos tarafı)
TP=$(curl -s -X POST localhost:4010/auth/otp/verify -H 'Content-Type: application/json' \
  -d '{"phone":"+905554443322","code":"123456"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

# Müşteri (app-mobile tarafı)
TB=$(curl -s -X POST localhost:4010/auth/otp/verify -H 'Content-Type: application/json' \
  -d '{"phone":"+905551112233","code":"123456"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
```

### Bekleyen onaylar (client'ın gördüğünün aynısı)
```bash
curl -s localhost:4010/approvals -H "Authorization: Bearer $TP" \
  | python3 -c "import sys,json;[print(r['approval_id'],r['status'],r['amount_minor'],r['shop_name']) for r in json.load(sys.stdin)]"
```

### Geçmiş dahil (Tur 39'da eklendi)
```bash
curl -s "localhost:4010/approvals?status=ALL" -H "Authorization: Bearer $TP" \
  | python3 -c "import sys,json;[print(r['approval_id'],r['status'],'req=',r['requested_at'],'upd=',r['updated_at']) for r in json.load(sys.stdin)]"
```

### ÇİFT YAZMA KONTROLÜ (38b'nin guard'ı — en önemlisi)
```bash
docker exec backend-db-1 psql -U veresiye -d veresiye -c \
  "SELECT transaction_id, seller_id, customer_id, amount_minor, type, created_at
   FROM transactions ORDER BY created_at DESC LIMIT 5;"
```
Bir onay = **bir satır**. Aynı tutar iki kez görünüyorsa çift yazma geri gelmiş.

### Bakiye (türetilmiş, saklanmıyor)
```bash
curl -s "localhost:4010/balances?customer_id=c1" -H "Authorization: Bearer $TP"
```

### Buyer'ın gördüğü defter (Tur 40 — dükkân telefonu dahil)
```bash
curl -s localhost:4010/me/debts -H "Authorization: Bearer $TB" \
  | python3 -m json.tool
```
`shop_phone` **null olmamalı** — Tur 40b'de eklendi, ss1'deki "numara görünmüyor" bunun eksikliğiydi.

### Canlı istek akışını izle (poll'u GÖRMEK için)
```bash
docker compose -f backend/docker-compose.yml logs -f api \
  | grep -E "GET /(approvals|customers|transactions|me/debts)"
```
Onaylar ekranı açıkken **15 saniyede bir** satır akmalı. Ekrandan çıkınca **durmalı**.

**Hangi app'in ne sorduğunu say** — "kod var ama APK eski" hatası burada yakalanır:
```bash
docker compose -f backend/docker-compose.yml logs api \
  | grep -oE "GET /(approvals|customers|me/debts|transactions)" | sort | uniq -c
```
`GET /customers` sayısı **0** ise app-pos defteri hiç sormuyor demektir (Tur 40b'de tam olarak
bu oldu: `/me/debts` 34 çağrı, `/customers` 0 → APK bir tur eskiydi).

### Cihaz logları
```bash
adb logcat -c    # temizle
adb logcat | grep -E "app_pos|app_mobile|PullEngine|WorkManager"
```

## Test adımları

### A — Defter pull'u (Tur 40): ekranlar sunucudan dolmalı

Cihaz seed'i kalktı; **hiçbir ekran yerel veriden dolmuyor.** Boş ekran = pull kırık.

| # | Ne yapılır | Beklenen |
|---|---|---|
| A1 | **app-pos** → giriş `05554443322` → Müşterilerim | Ahmet Yılmaz / Ayşe Demir vb. **dolu** |
| A2 | app-pos → bir müşteri → detay | Geçmiş hareketler **dolu**, bakiye tutuyor |
| A3 | **app-mobile** → giriş `05551112233` → Borçlarım | Ahmet Bakkal **50 TL**, 0,00 TL DEĞİL |
| A4 | app-mobile → Ahmet Bakkal → detay | Hareketler dolu; **dükkân telefonu görünüyor** (Tur 40b) |
| A5 | app-pos → Müşterilerim listesini tara | **İsimsiz/numarasız satır YOK** (buyer stub sızıntısı — Tur 40b) |

> A5 açıklaması: buyer'ın defteri çekilirken karşı taraf için stub müşteri satırı türetiliyor.
> Bunlar POS'un kendi listesine girmemeli. Girerse `observeForSeller` filtresi düşmüş demektir.

### B — Onay pull'u (Tur 39): asıl sınav

| # | Ne yapılır | Beklenen |
|---|---|---|
| B1 | **app-mobile** → Borçlarım → Ahmet Bakkal → Öde | "Onaya gönderildi" |
| B2 | **app-pos** → Onaylar (ekranı AÇIK tut) | **~15 sn içinde kart DÜŞER** ← turun sınavı |
| B3 | app-pos → Onayla | "Onaylandı" + ledger'da **BİR** satır |
| B4 | app-mobile → Onaylar | **~15 sn içinde kart KENDİLİĞİNDEN kaybolur** |
| B5 | app-mobile → Onaylar → `p1` → Onayla | Borçlarım'da 50 TL |
| B6 | **Uçak modu**, kart varken | **Kart EKRANDA KALIR** ← en kritik kural |
| B7 | app-pos → Onaylar'da `p1` var mı? | **Yok** (u1'e ait — hayalet kart gitti) |
| B8 | app-pos'ta müşteri ekle `05551112233` → o numarayla app-mobile girişi | Borç görünür (claim çalıştı, A.9) |

**B6 neden en kritik:** "sunucuya ulaşılamadı" ile "sunucuda kayıt yok" aynı dala düşerse,
sinyalsiz cihaz kendi onay kutusunu siler. `PullOutcome.Unreachable` ≠ `Refreshed(0)` ayrımı
tam olarak bunu engelliyor; birim testi bu kuralı mutasyonla doğrulandı.

### C — Tur 40b düzeltmeleri

| # | Ne yapılır | Beklenen |
|---|---|---|
| C1 | **USB kablosunu çek** → app-mobile'da giriş dene | "Sunucuya ulaşılamadı" — **"Geçersiz numara" DEĞİL** |
| C2 | Kabloyu tak, `adb reverse` tekrar, giriş | Normal çalışır |
| C3 | Müşteriler → bir müşteriye gir → alt bardan başka sekme | Basılan sekme **vurgulu** |
| C4 | Detaydayken alt bara bak | Geldiğin sekme **vurgulu kalıyor** (bar sönmüyor) |

> C1 açıklaması: `requestOtp` eskiden `Boolean` dönüyordu; "sunucu reddetti" ile "ulaşılamadı"
> tek `false`'ta birleşiyor, ağ kopması kullanıcıya kendi hatası gibi gösteriliyordu.
> `OtpRequestResult` bu ikisini ayırıyor.
>
> C3 uyarısı: vurgu `isChecked` ile boyanıyor. `selectedItemId` atansaydı sekmeye **tıklanmış**
> sayılır, kullanıcı açtığı detaydan geri fırlardı — regresyon olarak bunu da izle.

### Açık maddeler (bu turda YAPILMADI, hata olarak raporlamaya gerek yok)

- Adsız müşteri satırı boş görünüyor (istenen: numara + "isim girilmemiş") → [deferred.md §G.1](deferred.md)
- Buyer dükkân detayında gereksiz "alacak/verecek" başlığı → [deferred.md §G.2](deferred.md)
- Onay yollarının yeniden tanımı (kullanıcı yazacak) → [deferred.md §H](deferred.md)

## Poll davranışı — ne beklemeli

| | app-pos | app-mobile |
|---|---|---|
| Açılışta bir kez | onaylar + defter | onaylar + defter |
| **Onaylar ekranı açıkken** | **15 sn** | **15 sn** |
| **Defter ekranı açıkken** | Müşterilerim açılışında | Borçlarım **30 sn** |
| **Arka planda** | `PullWorker` **15 dk** (onay + defter) | **YOK** (pil kararı, kasıtlı) |

Poll `repeatOnLifecycle(STARTED)`'a bağlı: ekrandan çıkınca **iptal**, dönünce **yeniden
başlar ve hemen bir istek atar**. Yani "sekmeye geri gel" = anında tazeleme.

15 dakikalık `PullWorker`'ı test etmek pratikte zor (WorkManager kendi zamanlamasını yapar);
elle tetiklemek için:
```bash
adb shell am broadcast -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" \
  -p com.example.app_pos
adb logcat -d | grep -i "WM-" | tail -20
```

## D bloğu — Tur 40d: yazma yolu sunucuya bağlandı

⚠️ **Bu blok, `allowBackup=false` içeren APK'lar kurulduktan sonra anlamlı.** Önceki
kurulumlar uninstall'dan sağ çıkabilir; bir kez temiz kurulum yapıldığından emin ol.

| # | Yap | Bekle |
|---|-----|-------|
| D1 | app-pos → yeni müşteri ekle (`05559998877`, "Test Ali") | **Hemen listede görünür** (henüz veresiye YOK) |
| D2 | psql ile bak | id **`c_` ile başlar** (UUID değil), `created_by_seller_id=u_owner` |
| D3 | Test Ali'ye veresiye yaz | `transactions`'a düşer, outbox boşalır |
| D4 | `05552223344`'ü (seed'de Ayşe Demir) **başka isimle** eklemeye çalış | Mevcut kayda uzlaşır, **"aysemsi" diye ikinci satır OLUŞMAZ** |
| D5 | **USB'yi çek**, yeni müşteri eklemeyi dene | "Sunucuya ulaşılamadı" — kayıt **açılmaz** (id'yi sunucu üretir) |
| D6 | Uçak modu, **mevcut** müşteriye veresiye yaz | **Çalışır** (offline kuyruk) ← D5'in kısıtı yalnız YENİ kayıt |
| D7 | app-mobile → `05552223344` ile giriş | Ayşe'nin borcu (**165,00 TL**) görünür ← claim |
| D8 | Profil → dükkân adını değiştir | `users.shop_name` güncel **ve `shop_phone` SİLİNMEMİŞ** |

Doğrulama komutları:

```bash
# D2/D4 — tek satır mı, id formatı ne?
docker exec backend-db-1 psql -U veresiye -d veresiye -c \
  "SELECT customer_id, display_name, phone, created_by_seller_id FROM customers ORDER BY customer_id;"

# D7 — claim gerçekten sunucuda mı oldu?
docker exec backend-db-1 psql -U veresiye -d veresiye -c \
  "SELECT customer_id, claim_status, claimed_by_user_id FROM customers WHERE phone='+905552223344';"

# D8 — rename shop_phone'u silmiş mi?
docker exec backend-db-1 psql -U veresiye -d veresiye -c \
  "SELECT user_id, shop_name, shop_phone FROM users WHERE user_id='u_owner';"
```

> **D5 neden böyle:** müşteri id'sini SUNUCU üretir. İstemci yerel bir id uydurursa, ona
> yazılan veresiye sunucuda 404 alır; 404 retry edilebilir değildir, dolayısıyla outbox
> kaydı **atar** ve borç ekranda kalıp sunucuda hiç var olmaz. Tur 40d'ye kadar olan
> davranış buydu. Bu yüzden "offline'da yeni müşteri açılamaz" bilinçli bir karardır.

---

## E bloğu — Tur 42: PGW sepeti ledger'a ulaşıyor mu?

Bu blok `docs/deferred.md` §J'yi doğrulayan bloktur. Ölçüt **ekran değil, DB**: sepet
hiçbir UI'da görünmüyor (henüz onu okuyan ekran yok), dolayısıyla tek kanıt aşağıdaki sorgu.

### ⚠️ Kalemli sepet için: "Veresiye" düğmesine UZUN BAS

mock-pos'ta iki ayrı yol var ve ikisi de aynı düğmede:

| Hareket | Ne gönderilir | DB'de ne görünür |
|---|---|---|
| **Kısa dokunuş** | `MockBasket.moneyOnly` — tutarı tek sentetik kaleme koyar | tek satır, `name = "Veresiye"` |
| **UZUN BASIŞ** | sepet seçici açılır → "Market sepeti (3 kalem)" / "Tek ürün (Yiyecek)" | 3 satır: Ekmek / Süt / Yumurta |

Uzun basışta **tutar girmeye gerek yok** — `showBasketPicker` `requireAmount()` çağırmıyor,
sepetin kendi toplamı geçerli oluyor (Market sepeti = 107,00 TL).

> Bunu bilmeden test edersen zincir çalışsa bile hep tek "Veresiye" kalemi görürsün ve
> ürün-bazlı yolun çalıştığını **doğrulamamış** olursun. Uzun basış UI'da hiçbir yerde
> yazmıyor; kodda `MainActivity.kt` `btnCredit.setOnLongClickListener`.

### Adımlar

| # | Yap | Bekle |
|---|-----|-------|
| E1 | mock-pos → Veresiye'ye **uzun bas** → "Market sepeti (3 kalem)" | app-pos açılır, tutar **107,00 TL** |
| E2 | Müşteri seç → onaya gönder | Tezgâh müşterinin cevabını bekler |
| E3 | app-mobile → `05551112233` ile onayla | app-pos kapanır, mock-pos **"onaylandı"** der |
| E4 | Aşağıdaki sorguyu koştur | **3 satır**, tek `basket_id` altında |
| E5 | Yeniden dene, ama **reddet** | mock-pos **"reddedildi"** der (§K.4 regresyonu) |
| E6 | Tahsilat yap (Veresiye değil, kart) | Geçit **ödeme ekranı** açar, fiş basmaz |

```bash
# E4 — asıl ölçüt. Tur 42'den ÖNCE bu sorgu 0 satır dönüyordu.
docker exec backend-db-1 psql -U veresiye -d veresiye -c "
SELECT t.transaction_id, t.basket_id, i.name, i.price_minor, i.quantity
FROM transactions t LEFT JOIN basket_items i ON i.basket_id = t.basket_id
WHERE t.basket_id IS NOT NULL ORDER BY t.created_at DESC;"
```

Beklenen (Market sepeti): `Ekmek 1500|2000` · `Süt 3200|1000` · `Yumurta (10'lu) 4500|1000`
— üçü de **aynı** `basket_id`. `quantity` ×1000 ölçekli, yani 2000 = **2 adet**;
`price_minor` 1500 = **15,00 TL**, dolayısıyla Ekmek satırı 30,00 TL, sepet toplamı 107,00.

### ⚠️ Tahsilat bu sorguda GÖRÜNMEZ — eksiklik değil

Sorgu `WHERE basket_id IS NOT NULL` diyor, tahsilatın ise sepeti yok (müşteri kart uzatıyor,
satılan ürün listesi yok — geçide giden gövdede de `paymentItems` bulunmuyor, bkz. §K.2).
Ödemeni görmek için filtresiz bak:

```bash
docker exec backend-db-1 psql -U veresiye -d veresiye -c "
SELECT created_at, type, amount_minor, basket_id, description
FROM transactions ORDER BY created_at DESC LIMIT 10;"
```

`PAYMENT` satırlarının `basket_id`'si **NULL olmalı**. Dolu çıkarsa yanlış bir şey var.
