# Tur 39 — cihaz testi komutları

Sunucu doğrulaması için hazır komutlar. Hepsi repo kökünden çalışır.

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

### Canlı istek akışını izle (poll'u GÖRMEK için)
```bash
docker compose -f backend/docker-compose.yml logs -f api | grep "GET /approvals"
```
Onaylar ekranı açıkken **15 saniyede bir** satır akmalı. Ekrandan çıkınca **durmalı**.

### Cihaz logları
```bash
adb logcat -c    # temizle
adb logcat | grep -E "app_pos|app_mobile|PullEngine|WorkManager"
```

## Test adımları

| # | Ne yapılır | Beklenen |
|---|---|---|
| 1 | İki app'e de giriş | Ekranlar **sunucudan** dolu (cihaz seed'i yok artık) |
| 2 | **app-mobile** → Borçlarım → Ahmet Bakkal → Öde | "Onaya gönderildi" |
| 3 | **app-pos** → Onaylar (ekranı AÇIK tut) | **~15 sn içinde kart DÜŞER** ← turun sınavı |
| 4 | app-pos → Onayla | "Onaylandı" + ledger'da **BİR** satır |
| 5 | app-mobile → Onaylar | **~15 sn içinde kart KENDİLİĞİNDEN kaybolur** |
| 6 | app-mobile → Onaylar → `p1` → Onayla | Borçlarım'da 50 TL |
| 7 | **Uçak modu**, kart varken | **Kart EKRANDA KALIR** ← en kritik kural |
| 8 | app-pos → Onaylar'da `p1` var mı? | **Yok** (u1'e ait — hayalet kart gitti) |
| 9 | app-pos'ta müşteri ekle `05551112233` → o numarayla app-mobile girişi | Borç görünür (claim çalıştı, A.9) |

**Adım 7 neden en kritik:** "sunucuya ulaşılamadı" ile "sunucuda kayıt yok" aynı dala düşerse,
sinyalsiz cihaz kendi onay kutusunu siler. `PullOutcome.Unreachable` ≠ `Refreshed(0)` ayrımı
tam olarak bunu engelliyor; birim testi de bu kuralı mutasyonla doğrulandı.

## Poll davranışı — ne beklemeli

| | app-pos | app-mobile |
|---|---|---|
| Açılışta bir kez | ✓ | ✓ |
| **Onaylar ekranı açıkken** | **15 sn** | **15 sn** |
| **Arka planda** | `PullWorker` **15 dk** | **YOK** (pil kararı, kasıtlı) |

Poll `repeatOnLifecycle(STARTED)`'a bağlı: ekrandan çıkınca **iptal**, dönünce **yeniden
başlar ve hemen bir istek atar**. Yani "sekmeye geri gel" = anında tazeleme.

15 dakikalık `PullWorker`'ı test etmek pratikte zor (WorkManager kendi zamanlamasını yapar);
elle tetiklemek için:
```bash
adb shell am broadcast -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" \
  -p com.example.app_pos
adb logcat -d | grep -i "WM-" | tail -20
```
