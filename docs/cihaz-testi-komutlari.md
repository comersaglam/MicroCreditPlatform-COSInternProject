# Cihaz testi — komut kılavuzu

Sunucu doğrulaması için hazır komutlar. Hepsi repo kökünden çalışır.
Kapsam: Tur 39 (onay pull'u) + Tur 40 (defter pull'u) + Tur 40b (düzeltmeler).

## Kurulum (her testten önce)

```bash
# 1. Sunucu ayakta mı
cd backend && docker compose up -d && cd ..

# 2. Sunucuyu temizle + seed'le  (HTTP ucu YOK, sadece bu komut)
docker compose -f backend/docker-compose.yml exec -T api python -m app.reset

# 3. Port yönlendirme — HER USB bağlantısında yeniden
adb reverse tcp:4010 tcp:4010

# 4. Şema değiştiyse SIFIRLA (Tur 39'da ŞART; MIUI'de `pm clear` çalışmaz)
adb uninstall com.example.app_pos
adb uninstall com.example.app_mobile

# 5. Kur (ayrı Gradle projeleri, sırayla — 8GB RAM)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
(cd app-pos    && ./gradlew :app:installDebug --max-workers=2 -Dorg.gradle.java.installations.auto-detect=false)
(cd app-pos    && ./gradlew --stop)
(cd app-mobile && ./gradlew :app:installDebug --max-workers=2 -Dorg.gradle.java.installations.auto-detect=false)
(cd app-mobile && ./gradlew --stop)
```

> **MIUI/HyperOS notu:** `INSTALL_FAILED_USER_RESTRICTED` alırsanız
> Ayarlar → Ek ayarlar → Geliştirici seçenekleri → **"USB ile yükleme"** açık olmalı.

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
docker compose -f backend/docker-compose.yml exec -T api alembic current   # 0003 (head)
docker exec backend-db-1 psql -U veresiye -d veresiye -c "\d approvals" | grep updated_at
```

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
