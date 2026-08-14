# Notlar

## Bilgisayar kasması / performans — çalışma ortamı kuralları

Semptom: projede bir süre çalışınca makine kasmaya başlıyor. **Sebep bir memory leak
değil**, 8 GB RAM'de swap thrashing: RAM dolunca macOS sürekli veriyi diske yazıp
okuyor (swap ~6/7 GB dolu, load avg ~17). Disk RAM'den ~100x yavaş olduğu için makine
donuyor. Kasmanın "bir süre sonra" başlamasının sebebi birikme: ağır süreçler üst üste
yığılıyor. Üç ağır JVM daemon'ı tek başına ~6 GB rezerve ediyordu (Gradle 2 GB + Kotlin
2 GB + VS Code Java dil sunucusu 2 GB).

**Kurallar (8 GB makinede zorunlu):**

1. **Aynı anda tek IDE.** Android Studio ve VS Code birlikte açık tutulmamalı — her ikisi
   de kendi Gradle + Kotlin + Java JVM yığınını ayrı çalıştırıp RAM'i ikiye katlıyor.
   Kod yazarken VS Code; build/emülatör gerektiğinde Android Studio. İkisi bir arada değil.
2. **Eski/zombi süreçleri temizle.** VS Code içindeki Claude Code oturumları kapatılmadan
   günlerce arka planda kalabiliyor. Kontrol: `ps aux | grep native-binary/claude`.
   1+ günlük olanları `kill <pid>` ile kapat (aktif oturuma dokunma).
3. **İş bitince Gradle daemon'larını durdur:** her modülde `./gradlew --stop`
   (app-pos / mock-pos / app-mobile ayrı Gradle projeleri, her biri için).

**Opsiyonel kalıcı çözüm:** her modülün `gradle.properties`'ine daemon heap'ini kıs —
8 GB'da 2 GB fazla agresif:

```properties
org.gradle.jvmargs=-Xmx1024m
```

**Hızlı teşhis komutu:** `sysctl vm.swapusage` (used yüksekse thrashing var) +
`top -l 1 -n 0 | grep -E "PhysMem|Load Avg"`.

### Activity Monitor'deki "3 GB java" yanıltıcıdır  ⚠️ (2026-08-13)

Activity Monitor'ün **Memory** sütunu sanal belleği gösteriyor. Swap'e itilmiş bir Gradle
daemon orada **3.2 GB** görünürken gerçek RSS'i ~20 MB olabilir. Yani o sütun "bu process
RAM yiyor" demek DEĞİL; çoğu zaman "bu process zaten swap'lendi" demek.

Doğru göstergeler:
```bash
sysctl vm.swapusage                                    # asil olcut
ps -eo pid,rss,etime,command | grep "[G]radleDaemon"   # gercek RSS + daemon yasi
```

Bir olayda iki Gradle daemon birikmişti, biri **1 gün 5 saatlik**; ikisi de `--stop` ile
kapatılınca swap 11.2 GB → 8.2 GB'a indi. **app-pos / app-mobile / mock-pos ayrı Gradle
projeleri**, her biri kendi daemon'ını açar — `--stop` her birinde ayrı çalıştırılmalı.

### Build/test protokolü — RAM'i zorlamadan doğrulama

Kullanıcının 2026-08-13'te koyduğu kural. Sebebi somut: art arda `assembleDebug` +
`assembleRelease` koşturmak release'i **13.5 dakikaya** çıkardı (CPU değil, swap yüzünden)
ve makineyi durma noktasına getirdi.

| Ne zaman | Komut |
|---|---|
| Ara adımlar | `:modul:compileDebugKotlin` — APK paketleme ve R8 yok, ~20-40 sn |
| Testler | `:modul:testDebugUnitTest`, sadece değişen modülde |
| **Tur sonu, tek sefer** | `assembleDebug` + `assembleRelease` + tüm testler |
| **Her build'den sonra** | `./gradlew --stop` |

Her çağrıya `--max-workers=2` ekle — 8 GB'da paralel worker'lar swap'i tetikleyen asıl şey.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin --max-workers=2 \
  -Dorg.gradle.java.installations.auto-detect=false
```

`compileDebugKotlin` neyi yakalar: tip hataları, eksik override'lar, **Hilt grafiği**
(KSP çalışır). Neyi yakalamaz: manifest merge, R8/ProGuard, resource-link hataları —
bunlar için tur sonundaki `assemble` gerekli.

**JAVA_HOME tuzağı:** Bash tool zshrc yüklemiyor, o yüzden komutun İÇİNDE export edilmeli.
Ayrıca daemon'lar `--stop` ile öldürüldükten sonra Gradle yeni daemon'ı VS Code'un
JRE'siyle başlatabiliyor → **`jlink executable ... does not exist`**. Çözümü transform
cache'ini temizlemek:

```bash
rm -rf ~/.gradle/caches/transforms-4
```

## PhoneFormat.toStored idempotent değildi (Tur 16 — ÇÖZÜLDÜ)

Login ve register sistemini mock DB ile bağlarken bir bug alınmıştı: `PhoneFormat.toStored`
idempotent değildi — E.164 çıktısını ("+905554443322") tekrar girince null veriyordu
(11-hane-0-ile-başlar kontrolüne takılıyor). Bu üç ayrı yerde soruna yol açtı:
1. `login` E.164 numarayı tekrar toStored'dan geçirip null alıyor → session set edilmiyor
   (ama LoginVM yine SUCCESS diyordu → boş dashboard/profil, telefon görünmüyor).
2. `pendingPhoneDisplay` aynı çift-dönüşüm → register onay dialogu açılmıyor.
3. (dolaylı) registerUser'da fallback ile rakam-only saklanıyordu.

**ÇÖZÜLDÜ:** `toStored` idempotent yapıldı (zaten "+90…" olanı olduğu gibi döndürür);
çağrı yerlerinde çift-dönüşüm kaldırıldı; `login` findUserByPhone (digit-normalize) kullanır;
LoginVM `login()` dönüşünü kontrol eder (false→ERROR). Ders: format dönüştüren util'ler
idempotent olmalı.

## Session hardcoded u_owner döndürüyordu (Tur 16 — ÇÖZÜLDÜ)

`observeCurrentUser` / `currentSellerId` her zaman "u_owner" döndürüyordu (tek-user mock
kısayolu). Register ile çok-user gelince kırıldı — yeni numarayla login olsan bile eski
u_owner'ın bilgileri görünüyordu. **ÇÖZÜLDÜ:** `Session`'a `userId` eklendi; login o user'ın
id'sini saklar; observeCurrentUser + currentSellerId session.userId'ye bağlandı. Artık kim
login'se onun profili/ledger'ı görünür (backend JWT subject'ine köprü).
