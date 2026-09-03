# Test Hesapları — elle test için numara rehberi

> Seed verisi (demo hesaplar) her iki app'te de **ilk açılışta bir kez** yazılır.
> Kaynak: `app-mobile/core-data/.../SeedCallback.kt` ve `app-pos/core-data/.../SeedCallback.kt`.
>
> **Şifre/OTP yok** — giriş yalnızca numarayla (mock). Kayıtlı numara → giriş;
> kayıtsız numara → "kayıt olacaksınız" onayı → yeni hesap.

> ⚠️ **Seed değişti (Tur 25 — isim ayrımı).** Seed yalnızca DB ilk yaratıldığında çalışır,
> yani mevcut kurulumda eski isimler durur. Yeni isimleri görmek için:
> `adb shell pm clear com.example.app_mobile` (şema değişmedi, migration gerekmez).

**İKİ APP'İN SEED'İ FARKLI.** Ayrı APK, ayrı veritabanı (`veresiye.db` her app'in kendi
sandbox'ında). Aynı numara iki app'te farklı şey ifade edebilir — aşağıda ayrı tablolar.

---

# app-mobile (müşteri + satıcı uygulaması)

## Giriş yapabileceğin hesaplar (User = app hesabı)

> **Kişi adı ≠ dükkan adı.** Bir satıcının iki ismi vardır: kendi adı (`displayName`) ve
> dükkanının adı (`shopName`). Alıcı tarafındaki ekranlar (Borçlarım, satıcı detayı) **dükkan
> adını**, satıcı tarafındakiler (Müşterilerim, müşteri detayı) **kişi adını** gösterir.
> Seed'de ikisi bilinçli olarak farklı — aynı olsaydı yanlış alanı gösteren bir ekranı fark
> edemezdik.

| Numara | Kişi adı | Dükkan adı | Roller | Ne için kullanılır |
|---|---|---|---|---|
| **0555 444 3322** | Ahmet Demirtaş | **Ahmet Bakkal** | alıcı **+ satıcı** | **En zengin hesap.** İki rolü birden test etmek için: hem "Müşterilerim" (kendi defteri) hem "Borçlarım" (Ayşe Market'e borcu) var. Bottom-nav'da 4 sekme. Profilde iki isim ayrı satırda görünür. |
| **0555 333 4455** | Ayşe Korkmaz | **Ayşe Market** | alıcı + satıcı | İkinci satıcı. Çapraz dükkan senaryoları için. |
| **0555 111 2233** | Ahmet Yılmaz | — | sadece alıcı | **Saf alıcı testi.** İki dükkana borcu var → Borçlarım iki satır. "Satıcı ol" akışı için de bu. |
| **0555 444 5566** | Mehmet Kaya | — | sadece alıcı | Ahmet Bakkal'ın app'li müşterisi. Onay kutusuna düşen istekleri görmek için. |

## Müşteri kayıtları (Customer = satıcının defter satırı — giriş yapılamaz)

> ⚠️ **Bakiyeler seed'in yazdığı hâlidir — cihazda DAHA YÜKSEK görünür.** Endeksleme
> (Tur 43) bir bakiye ilk okunduğunda tembel olarak işliyor, yani `seed_demo`'nun kur
> serisi varken her hesaba aylık INDEXATION satırları ekleniyor. Aşağıdaki rakamlar
> anaparadır; ekrandaki rakam = anapara + o güne kadar birikmiş enflasyon farkı.
>
> Örnek (Tur 45b sonrası): c1'in anaparası **490,00**, cihazda görünen **665,72** —
> aradaki **175,72** on iki aylık endeksleme. Kart üzerindeki *"Bunun X TL'si enflasyon
> farkı"* satırı tam olarak bunu söylüyor.

| Numara | İsim | Durum | Hangi defterde | Bakiye (anapara) |
|---|---|---|---|---|
| 0555 111 2233 | Ahmet Yılmaz (c1) | app'li (CLAIMED→u1) | Ahmet Bakkal | **490,00** |
| 0555 111 2233 | Ahmet Y. (m1) | app'li (CLAIMED→u1) | Ayşe Market | 100,00 |
| 0555 444 3322 | Ahmet Demirtaş (o1) | app'li (CLAIMED→u_owner) | Ayşe Market | 60,00 |
| 0555 444 5566 | Mehmet Kaya (c3) | app'li (CLAIMED→u3) | Ahmet Bakkal | 0,00 |
| **0555 666 7788** | Fatma Şahin (c4) | **app'siz (UNCLAIMED)** | Ahmet Bakkal | 25,50 |
| **0555 888 9900** | Hasan Öztürk (c5) | **app'siz (UNCLAIMED)** | Ahmet Bakkal | 210,00 |

> **DİKKAT — aynı numara iki satırda:** `0555 111 2233` hem `c1` hem `m1`. Bu bir hata değil,
> tasarımın kalbi: **bir kişi = tek kimlik (telefon), ama her dükkanda ayrı defter kaydı.**
> Bakiye (satıcı, müşteri) çiftine göre hesaplanır. Bu yüzden "yanlış deftere yazma" bug'ları
> hep bu iki kayıtla ortaya çıkar — test ederken buraya bak.

---

## Senaryo → hangi numara

### Kalıcılık (Room'un asıl kazanımı)
**`0555 111 2233`** ile gir → force-stop (Ayarlar'dan zorla durdur) → tekrar aç.
- Beklenen: **oturum kapanmış** (login ekranı — token RAM'de, tasarım gereği)
- Beklenen: giriş yapınca **tüm borçlar/geçmiş yerinde** ← eskiden sıfırlanıyordu

### Alıcı tarafı (Borçlarım)
**`0555 111 2233`** — iki dükkana borcu olan tek hesap.
- Borçlarım: Ayşe Market **100,00** + Ahmet Bakkal **490,00** = toplam **590,00**
  (anapara; endeksleme işledikten sonra cihazda ~105 + ~666 = **~771** görünür)
- Bu ekran doğruysa: JOIN sorgusu + çok-dükkan gruplaması çalışıyor demektir

### Onay kutusu (Onaylar)
**`0555 111 2233`** → bekleyen: Ahmet Bakkal'dan 50,00 veresiye.
- Onayla → Ahmet Bakkal borcu **50,00 ARTMALI** (anapara 490,00 → 540,00)
  ⚠️ Mutlak rakam yerine FARKA bak: endeksleme her ay yeni satır ekliyor, yani
  ekrandaki başlangıç değeri hangi gün baktığına göre değişir.
- Ayşe Market'teki 100,00 **değişmemeli** ← doğru deftere yazıldığının kanıtı

**`0555 444 3322`** → bekleyen: Ayşe Market'ten 75,00.

### Satıcı tarafı (Müşterilerim)
**`0555 444 3322`** ile gir (bu hesap satıcı).
- Müşterilerim: Mehmet 0,00 / Fatma 25,50 / Hasan 210,00 → toplam alacak **235,50**

**İki onay dalını ayırmak için:**
| Müşteri | Durum | [Veresiye Yaz] sonucu |
|---|---|---|
| **Fatma Şahin** (0555 666 7788) | app'siz | **Anında yazılır**, bakiye hemen artar ("Deftere yazıldı") |
| **Mehmet Kaya** (0555 444 5566) | app'li | **Onaya gider**, bakiye 0 kalır ("Onaya gönderildi") |

Mehmet'e yazdıktan sonra çıkıp **`0555 444 5566`** ile girersen isteği Onaylar'da görürsün.

### ⚠️ Onay YÖNÜ — en kritik kural (burada bug çıktı, düzeltildi)

**İsteği başlatan onaylamaz; karşı taraf onaylar.** Onayın kime gideceği, kimin başlattığına bağlı:

| Kim başlatır | Onay kime gider | Kartta hangi isim yazar |
|---|---|---|
| **Satıcı** veresiye/ödeme yazar | **Müşteriye** | Dükkan adı ("Ahmet Bakkal") |
| **Alıcı** ödeme yapar | **Satıcıya** (tahsilatı teyit eder) | Müşteri adı ("Ahmet Bakkal" kaydı) |

> **Bulunan bug (Tur 24 sonrası):** onay her zaman *müşteri kaydının sahibine* gidiyordu.
> `0555 444 3322` ile Ayşe Market'e ödeme yapınca onay **kendi kutuna** düşüyordu — çünkü
> Ayşe Market'teki müşteri kaydı (`o1`) zaten sana ait. Artık yön `fromUserId == sellerId`
> karşılaştırmasıyla belirleniyor.

**Bu düzeltmenin testi (mutlaka yap):**
1. **`0555 444 3322`** ile gir → Borçlarım → **Ayşe Market** → [Ödeme Yap] → 20,00
2. **Kendi Onaylar sekmene BAKMA** — orada **yeni bir şey ÇIKMAMALI** (sadece seed'deki p2 durur)
3. Çık, **`0555 333 4455`** (Ayşe Market) ile gir → Onaylar'da **20,00 ödeme** olmalı
4. Kartta senin müşteri adın ("Ahmet Bakkal") yazmalı, "Ayşe Market" değil
5. Onayla → çık, `0555 444 3322` ile gir → Ayşe Market borcun **20,00 AZALMIŞ** olmalı
   (anapara 60,00 → 40,00; ekranda endeksleme de eklendiği için farka bak, mutlak rakama değil)

### Yeni müşteri ekleme (FAB — üç dal)
`0555 444 3322` (Ahmet Bakkal) ile girip Müşterilerim → sağ alt **+** butonu:

| Girdiğin numara | Beklenen | Neden |
|---|---|---|
| **0555 111 0000** (uydurma) | Yeni kayıt açılır → müşteri detayına gider | Sistemde yok = yeni kişi |
| **0555 666 7788** (Fatma) | ❌ "Bu müşteri zaten defterinizde" | Zaten senin müşterin |
| **0555 333 4455** (Ayşe Market) | ⚠️ "Sistemde 'Ayşe Market' adına kayıtlı" onayı → devam | Var ama **senin** defterinde değil |
| **123** | ❌ "Geçerli bir numara girin" | Format hatası |

> Üçüncü satır bu turun asıl düzeltmesi: **başka dükkanın tanıdığı kişi reddedilmez, yeniden
> kullanılır.** Onayladıktan sonra detayda veresiye yazınca o kişi **iki defterde birden**
> görünür — ama tek `Customer` kaydı, geçmişi bölünmez.

> **NOT:** Yeni eklenen müşteri, ilk veresiye yazılana kadar **listede görünmez.** Bu bilinçli:
> "satıcının müşterisi" = ledger'da ortak kaydı olan kişi. O yüzden ekleme akışı seni doğrudan
> müşteri detayına götürür.

### Çapraz dükkan testi (en öğretici)
1. **`0555 333 4455`** (Ayşe Market) ile gir
2. Müşterilerim → **+** → `0555 666 7788` (Fatma — sadece Ahmet Bakkal'ın defterinde)
3. "Kayıtlı müşteri" onayı çıkmalı, isim **Fatma Şahin** yazmalı
4. Devam → detayda 30,00 veresiye yaz
5. Çık, **`0555 444 3322`** ile gir → Fatma'nın bakiyesi hâlâ **25,50** olmalı (Ayşe'ninki ayrı)

### Kayıt olma (yeni hesap)
Seed'de olmayan herhangi bir numara, ör. **`0555 000 1122`** → "kayıt olacaksınız" onayı →
yeni alıcı hesabı, Borçlarım boş.

### Claim (eski borç devralma) — en etkileyici test
**`0555 888 9900`** (Hasan Öztürk) ile **kayıt ol**.
- Hasan seed'de app'siz bir müşteri (c5), Ahmet Bakkal'a 210,00 borcu var
- Kayıt olur olmaz Borçlarım'da **Ahmet Bakkal 210,00** görünmeli
- Yani: esnafın deftere yazdığı borç, kişi app'e girince hesabına bağlanıyor

### "Satıcı ol" — rol kazanma
**`0555 111 2233`** (Ahmet Yılmaz, saf alıcı) ile gir.
- Bottom-nav'da **3 sekme** olmalı (Borçlarım / Onaylar / Profil) — Müşterilerim YOK
- Profil → **"Satıcı ol"** → dükkan ismi gir (ör. "Ahmet Kuruyemiş")
- **Müşterilerim sekmesi anında belirmeli** (4 sekme) — menü rol değişince yeniden kuruluyor
- Profilde dükkan satırı + **POS eşleştirme** kartı çıkmalı → eşleştir → "Ready"
- Force-stop → tekrar gir → **hâlâ satıcı** (rol kalıcı)

### Yeni satıcı olarak tahsilat (rol zincirinin sonu)
Yukarıdaki hesap satıcı olduktan sonra:
1. Müşterilerim → **+** → `0555 888 9900` (Hasan) → "kayıtlı müşteri" onayı → devam
2. Detayda 50,00 **veresiye yaz** → Hasan app'siz ise anında yazılır
3. Sonra [Ödeme Al] → 20,00 → bakiye 30,00'a düşmeli
4. Çık, `0555 444 3322` ile gir → **Hasan'ın sana olan 210,00 borcu değişmemiş** olmalı

### Tarih sıralaması (bu turda düzeltilen bug)
Cihaz tarihini **5 Ağustos 2026** yap → herhangi bir veresiye yaz → geçmiş listesinde
**en üstte** olmalı. (Düzeltme öncesi Temmuz kayıtlarının altına düşerdi.)

### 🎨 Renk ve okunabilirlik (Tur 25)

**Ekran rolü — üstteki toplam kartı:**
- Borçlarım → **soluk kırmızı** kenarlıklı kart (buradaki param çıkıyor)
- Müşterilerim → **yeşil** kenarlıklı kart (buradan param geliyor)

**İsim ayrımı:**
- `0555 444 3322` ile gir → Profil: Ad Soyad **"Ahmet Demirtaş"**, Dükkan **"Ahmet Bakkal"**
- `0555 111 2233` ile gir → Borçlarım'da **"Ahmet Bakkal"** (dükkan adı, kişi adı değil)
- `0555 333 4455` ile gir → Müşterilerim'de **"Ahmet Demirtaş"** (kişi adı, dükkan adı değil)

**Dükkan telefonu:** Borçlarım → Ahmet Bakkal → başlıkta **`+902121112233`** görünmeli.
(Telefonu olmayan bir dükkanda satır gizlenir — yeni "Satıcı ol" yapan hesapla test edilir.)

**Onaylar — iki bölüm + dört ton.** Kural: **benden ne çıkıyor / bana ne geliyor**.
Veresiyede hareket eden şey mal/kredi (satıcı verir, müşteri alır) → **soluk**, çünkü henüz
para hareketi yok. Ödemede hareket eden para (satıcıya gelir, müşteriden çıkar) → **doygun**.

| Rolüm | İşlem | Renk |
|---|---|---|
| Satıcı | veresiye veriyorum | **soluk kırmızı** |
| Satıcı | ödeme alıyorum | **canlı yeşil** |
| Müşteri | veresiye alıyorum | **soluk yeşil** |
| Müşteri | ödeme yapıyorum | **canlı kırmızı** |

> **Önemli:** onay hep **karşı tarafa** gider, ve renk **kartı gören kişinin** rolüne göredir.
> Yani bir işlemi başlatınca rengini kendi ekranında göremezsin — karşı tarafın hesabına
> girmen gerekir.

**Dört tonu da üretmek** — her ton, isteği **onaylayan** kişinin ekranında görünür:

| Ton | Kim başlatır | Kimin ekranında görünür |
|---|---|---|
| **soluk yeşil** (müşteri, veresiye alıyor) | u_owner → Müşterilerim → Mehmet → [Veresiye Yaz] | Mehmet (`0555 444 5566`) |
| **canlı kırmızı** (müşteri, ödeme yapıyor) | u_owner → Müşterilerim → Mehmet → [Ödeme Al] | Mehmet (`0555 444 5566`) |
| **canlı yeşil** (satıcı, ödeme alıyor) | u_owner → Borçlarım → Ayşe Market → [Ödeme Yap] | Ayşe (`0555 333 4455`) |
| **soluk kırmızı** (satıcı, veresiye veriyor) | Ayşe → Müşterilerim → Ahmet Demirtaş → [Veresiye Yaz] → onaylanır; sonra o kişi kendi müşterisine yazarsa | isteği alan satıcı |

En hızlı yol: ilk üç satırı uygula, sonra `0555 444 3322` ile gir — seed'deki p2 zaten
**soluk yeşil** (müşteri olarak veresiye alıyorsun) olarak duruyor.

**İki bölüm başlığı** artık çerçeveli etiket (pill) olarak görünmeli — arka planla
karışmadan "Satıcı olarak onaylarınız" / "Müşteri olarak onaylarınız" okunabilmeli.

\* Kartı **karşı tarafın** hesabında görürsün — onay hep karşı tarafa gider.

Ayrıca her kartta **karşı tarafın telefonu** yazmalı: dükkan soruyorsa dükkanın numarası,
müşteri soruyorsa müşterinin numarası.

`0555 444 3322` (iki rolü de olan hesap) her iki bölümü birden görebilir → **"Satıcı olarak
onaylarınız"** ve **"Müşteri olarak onaylarınız"** başlıkları. Sadece bir rolde isteğin varsa
o bölümün başlığı hiç çıkmaz.

**Geri dönüştürme kontrolü:** iki bölüm de doluyken listeyi hızlı kaydır → hiçbir kartta
yanlış renk kalmamalı.

**Para formatı:** fazla ödeme yap (borçtan büyük tutar) → bakiye **`-756.228,50 TL`** gibi
**tek eksi** ile görünmeli. (Düzeltme öncesi `-756.228,-50 TL` yazıyordu.)

### Reddetme kalıcılığı
Onaylar'da bir isteği **Reddet** → listeden çıkar → **force-stop → tekrar gir** →
geri gelmemeli. (Satır silinmiyor, `status=REJECTED` oluyor; bekleyen sorgusu filtreliyor.)

---

# app-pos (esnaf POS uygulaması) — AYRI SEED

Tek satıcı var, müşteri listesi farklı.

| Numara | Ne |
|---|---|
| **0555 444 3322** | **Giriş numarası** (esnaf hesabı, u_owner). POS'ta login bunu ister. |
| 0555 111 2233 | Ahmet Yılmaz (c1), app'li — anapara 490,00 (+ endeksleme) |
| 0555 222 3344 | Ayşe Demir (c2), **app'siz** — 165,00 ← *app-mobile'da bu kayıt YOK* |
| 0555 444 5566 | Mehmet Kaya (c3), app'li — 0,00 |
| 0555 666 7788 | Fatma Şahin (c4), app'siz — 25,50 |
| 0555 888 9900 | Hasan Öztürk (c5), app'siz — 210,00 |

Toplam alacak: **440,50**

### POS'ta müşteri ekleme (3 dal — mock-pos'tan veresiye akışında)
mock-pos → tutar → VERESİYE → app-pos → müşteri seç → yeni müşteri → telefon ekranı:

| Numara | Beklenen |
|---|---|
| Uydurma numara | Yeni kayıt → onay → OTP → yazılır |
| **0555 666 7788** (Fatma) | ❌ "zaten defterinizde" |
| **0555 444 5566** (Mehmet) | ❌ "zaten defterinizde" |

> app-pos'ta tek satıcı olduğu için "başka satıcının müşterisi" dalını görmek zor.
> O dalı test etmek istersen: yeni bir numarayla **kayıt ol** (POS'tan kayıt = satıcı),
> sonra o hesapla `0555 111 2233` girmeye çalış → "Kayıtlı müşteri" onayı çıkmalı.

### app-pos'un kendi akışları (regresyon listesi)

| Senaryo | Nasıl | Beklenen |
|---|---|---|
| **Login gate** | Uygulamayı ilk aç | Login ekranı; `0555 444 3322` gir → dashboard |
| **Veresiye (handoff)** | mock-pos → tutar → VERESİYE | app-pos **ayrı task**ta açılır (recents'te iki kart), müşteri seç → onay → OTP → **mock-pos'a döner** |
| **Sepetli handoff** | mock-pos'ta VERESİYE'ye **uzun bas** → "Market sepeti" | app-pos'ta toplam **107,00 TL** görünür |
| **Ödeme (PAYMENT)** | Müşteri detayı → [Ödeme Al] → keypad | Tutar gir → onay → OTP → **dashboard'a döner** (mock-pos'a ASLA gitmez) |
| **Yeni müşteri** | Müşteri seç → isim yaz → yeni | Telefon ekranı → benzersiz numara → onay → OTP |
| **Geri tuşu** | Satış akışında geri | "İptal edilsin mi?" onayı |
| **Detaydan geri** | Müşteri detayı → geri oku | Listeye döner (üst ok + sistem geri, ikisi de) |
| **Kalıcılık** | Force-stop → aç | Login ister (session RAM), ama **ledger duruyor** |

### İki app birlikte (aynı numara, iki ayrı dünya)

> **Önemli:** app-pos ve app-mobile **ayrı veritabanları**. Şu an aralarında senkronizasyon
> **YOK** — backend FAZ 4/5'te gelecek. Yani POS'ta yazdığın veresiye app-mobile'da
> görünmez. Bu bir bug değil, henüz yazılmamış bir katman.
>
> İkisinde de `0555 444 3322` esnaf hesabı; ikisi de kendi seed'ini kullanır. Testleri
> **ayrı ayrı** değerlendir, "POS'ta yazdım mobilde niye yok" diye arama.

---

## Hızlı checklist (kullanıcının doğruladığı senaryolar ✓)

Aşağıdakiler elle test edildi ve çalışıyor:

- [x] Ödeme yapınca onay **karşı tarafa** düşüyor *(bug bulundu → düzeltildi, yukarıdaki
      "Onay YÖNÜ" bölümünü tekrar test et)*
- [x] Satıcıdan alıcıya veresiye/ödeme gönderme + bildirim
- [x] Alıcıdan satıcıya ödeme gönderme + bildirim
- [x] Kayıtlı kişiyi dükkana ekleme (3 dal)
- [x] Eklenen kişiden veresiye alma
- [x] UNCLAIMED kişinin girişte eski kayıtlarını görmesi (claim)
- [x] Girdikten sonra "Satıcı ol"
- [x] Satıcı olup başkasından ödeme alma
- [x] Kalıcılık (force-stop sonrası veri duruyor)

Henüz denenmemiş, denemeye değer:
- [ ] Onay **reddetme** kalıcılığı (force-stop sonrası geri gelmemeli)
- [ ] Tarih sıralaması (cihaz tarihini Ağustos'a al)
- [ ] Aynı numarayla iki farklı dükkanda ayrı bakiye (`0555 111 2233` → c1/m1)
- [ ] Geçersiz girdiler (boş isim, `123` telefon, 0 tutar)
- [ ] Ekran döndürme (satış akışının ortasında state korunuyor mu)
- [ ] Bakiyesi 0'a inen müşteri ("Borçlular" filtresinden düşmeli)

---

## Sıfırdan başlamak istersen (seed'i yeniden yazdırmak)

Seed sadece DB **ilk yaratıldığında** çalışır. Seed değiştiğinde (ör. Tur 25 isim ayrımı)
mevcut kurulumda eski veri durur — temizlemek gerekir.

> ⚠️ **`pm clear` bu cihazda (Xiaomi/MIUI) ÇALIŞMIYOR:**
> `SecurityException: ... does not have permission android.permission.CLEAR_APP_USER_DATA`
> MIUI, adb'nin `shell` kullanıcısından bu izni kaldırıyor. Aynı kısıtlama ailesinden:
> `INSTALL_FAILED_USER_RESTRICTED` (bkz. progress.md Tur 2).

**Çalışan yöntem — kaldır, sonra yeniden kur:**
```bash
adb uninstall com.example.app_mobile   # veya com.example.app_pos
mobilebuild                            # veya posbuild
```

**Alternatifler:**
- **Ayarlar'dan:** Uygulamalar → (uygulama) → Depolama → Verileri temizle
- **Sadece DB dosyası** (debug APK, uygulamayı kaldırmadan — önce force-stop):
  ```bash
  adb shell run-as com.example.app_mobile rm -f databases/veresiye.db*
  ```

Sonraki açılışta `SeedCallback` yeniden çalışır.
