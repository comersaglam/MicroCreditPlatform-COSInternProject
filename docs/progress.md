# Progress — Veresiye Platform

Bu dosya her adımda güncellenir: ne yaptık, neden, sıradaki ne. Amaç: unutmamak.

> **Yarım bırakılanlar için: [deferred.md](deferred.md)** — bilinçli olarak ertelenen her şey
> (mock kalan login/OTP, app-mobile'ın FAZ 4 eksiği, `future` uçlar) gerekçesi ve kod
> konumuyla orada. Bu dosya *ne yaptığımızı*, o dosya *neyi bıraktığımızı* anlatır.

Sıra: **app-pos + shared-contracts → app-mobile → backend.**
Yöntem: kısa parçalar, her adımda açıklama + onay, XML views, Clean Architecture,
overengineering yok. Emülatör: medium-size, Google Play imajı (iki cihaz için de).

---

## Genel kararlar (kesinleşti)

- **Repo:** tek monorepo. `app-pos/`, `app-mobile/`, `backend/`, `shared-contracts/`, `docs/`.
- **UI:** XML views (Compose değil).
- **Modül yapısı (app-pos):** çok modüllü Gradle — `:app`, `:core-domain`,
  `:core-data`, `:core-network`. Katman sınırlarını derleyici zorlar.
- **Para tipi:** `amountMinor: Long` (kuruş). Float yok.
- **Ledger:** append-only. Bakiye = hareketlerin toplamı.
- **Sync:** offline-first (Room + outbox + WorkManager). POS = normal telefon
  varsayımı (sürekli prizde ama şimdilik foreground service yok) → WorkManager +
  açılışta sync; FCM ileride.
- **Auth:** esnaf = cihaz kaydı + token yenileme; müşteri = telefon + OTP.
- **Müşteri modeli:** `Customer` + `claimStatus` (UNCLAIMED/CLAIMED) — app'siz
  müşteri desteği (TODO, detay docs/architecture-pos.md §7).

---

## Adım günlüğü

### 2026-07-22 — Tur 1: app-pos mimari tasarımı & folder structure (dokümantasyon)

**Yapılanlar (kod yok, sadece docs):**
- [docs/architecture-pos.svg](docs/architecture-pos.svg) — katmanlı mimari:
  sistem görünümü (app-pos ↔ Backend ↔ app-mobile, "client çağırır dinlemez",
  auth kararları) + app-pos içi katmanlar (modül etiketleriyle) + veri modelleri
  paneli + bağımlılık yönü.
- [docs/flow-pos.svg](docs/flow-pos.svg) — veresiye ödeme akışı: Flow A (POS,
  esnaf) + Flow B (müşteri, app-mobile), aralarında QR/NFC devir, backend +
  gün sonu reconciliation, hesap logic TODO.
- [docs/architecture-pos.md](docs/architecture-pos.md) — şemaların yazılı
  karşılığı: katman rolleri, kavram sözlüğü (ViewModel/Repository/DAO/Retrofit/
  Interceptor/WorkManager), auth kararları, çekirdek 3'lü data class, TODO listesi.
- Mevcut durum tespiti: app-pos & app-mobile Android Studio **Compose**
  template'iyle kurulu (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, minSdk 24,
  targetSdk 36, tek `:app` modülü). `backend/` ve `shared-contracts/` boş.

### 2026-07-22 — Tur 2: Compose → XML dönüşümü (app-pos + app-mobile)

Her iki proje de Android Studio Compose template'iyle gelmişti; XML views'a
çevirdik. İkisine de aynı 6 değişiklik uygulandı (namespace/tema adı farklı):

- `gradle/libs.versions.toml` — Compose (bom, activity-compose, material3, ui,
  compose-plugin) çıkarıldı; XML için `appcompat`, `material`, `constraintlayout`
  eklendi. Plugin `kotlin-compose` → `kotlin-android`.
- `build.gradle.kts` (root) — `kotlin.compose` plugin'i → `kotlin.android`.
- `app/build.gradle.kts` — `buildFeatures { compose }` → `viewBinding = true`;
  `kotlinOptions { jvmTarget = "11" }` eklendi (Compose plugin'i bunu içeride
  hallediyordu); bağımlılıklar XML setine değişti.
- `MainActivity.kt` — `ComponentActivity` + `setContent { Compose }` →
  `AppCompatActivity` + `setContentView(binding.root)` (ViewBinding).
- `res/layout/activity_main.xml` — **yeni**: ConstraintLayout + ortada TextView
  (`@string/pos_hello` / `@string/mobile_hello`).
- `res/values/themes.xml` — `android:Theme.Material.*` → `Theme.Material3.DayNight.
  NoActionBar` (AppCompatActivity uyumlu). `strings.xml`'e hello string eklendi.
- Silindi: `ui/theme/` (Color.kt, Theme.kt, Type.kt — Compose teması).
- Test dosyaları (ExampleUnitTest / ExampleInstrumentedTest) Compose'suz, dokunulmadı.
- Doğrulama: `grep compose` → temiz. Gerçek build kullanıcının terminalinde
  (`./gradlew :app:assembleDebug` + emülatörde run).

**Öğrenilenler (kavram):** ViewBinding = XML'deki id'li view'lar için üretilen
tip-güvenli sınıf (findViewById yerine). AppCompatActivity XML dünyasının Activity
temeli, AppCompat/Material tabanlı tema ister.

### 2026-07-22 — Tur 2 düzeltmeleri: build hatası + SVG temizliği

**Build hatası çözümü (önemli AGP 9 dersi):**
- Hata: `Cannot add extension with name 'kotlin'`. Sebep: **AGP 9.x Kotlin'i
  built-in getiriyor** — `com.android.application` uygulanınca `kotlin` extension'ı
  zaten kayıtlı oluyor. Ayrıca `org.jetbrains.kotlin.android` eklemek çakışma yaratır.
- Çözüm: `kotlin-android` plugin'ini her yerden kaldırdık (root + app build.gradle.kts
  + libs.versions.toml). AGP 9'da XML projesi için `plugins { alias(android.application) }`
  yeterli; ayrı Kotlin plugin'i YOK. `kotlinOptions { jvmTarget }` bloğu da kaldırıldı
  (built-in Kotlin ile gerekmiyor; compileOptions VERSION_11 yeterli). İkisine de uygulandı.
- Not: Bu haliyle yapı AGP 9'un XML template'iyle birebir aynı davranışta.

**SVG düzeltmeleri:** dikey/binen yazılar yatay yapıldı, belirsiz oklar net
kenarlara bağlandı (architecture-pos.svg: ok etiketleri; flow-pos.svg: var/kurulum/
FCM/sync/devir okları).

**Doğrulandı:** app-pos gerçek cihazda (Xiaomi, wireless debugging) çalıştı.
Not: MIUI'de "USB üzerinden yükle" ayarı açılmadan kurulum
INSTALL_FAILED_USER_RESTRICTED verir (kablosuz bağlıyken bile).
Ayrıca AndroidX sürüm dersi: core-ktx 1.19.0 compileSdk 37 istiyordu ->
1.15.0'a düşürüldü (lifecycle 2.11.0 -> 2.8.7 de aynı sebeple).

---

### 2026-07-23 — Tur 3: Yol haritası güncellendi (tasarım txt)

- `veresiye-platform-tasarim.txt` **bölüm 6** yeniden yazıldı: "contract-first"
  yerine **9 fazlı yol haritası** (FAZ 0 temel ... FAZ 8 ileri özellikler).
- **FAZ 7 = Regülasyon & uyumluluk** — ileri özelliklerden ÖNCE. Gerekçe:
  bankalarla iletişimde olan bir üründe KVKK/ödeme regülasyonu ekstra özellik
  değil, canlıya çıkmanın ön koşulu. (KVKK, tokenizasyon/PCI-DSS, audit log,
  veri ikametgahı, faiz-vade için BDDK lisans sorusu.)
- Yeni **bölüm 7 "ALINAN KARARLAR"**: açık kalan kararlar kapatıldı — modül
  yapısı, para tipi, ledger, claimStatus, auth (esnaf/müşteri), POS donanımı
  (açık karar -> normal telefon), geliştirme yöntemi.
- Sıra kararı: **UI+ViewModel (sahte veri) -> contract -> Room -> ağ.** Contract
  backend'e bağlanılacağı an için doğru kural; ekranların neye ihtiyacı olduğu
  bilinmeden yazılan şema sonradan değişir.

---

### 2026-07-23 — Tur 4: FAZ 1 — app-pos UI + MVVM iskeleti (sahte veri)

Üç ekran, hepsi ViewModel + StateFlow ile, veri kaynağı sahte.

**Model + veri (`:app` içinde, FAZ 2'de `:core-domain`'e taşınacak):**
- `model/Customer.kt` — `Customer` + `ClaimStatus` (UNCLAIMED/CLAIMED)
- `model/Transaction.kt` — `Transaction` + `TransactionType` (DEBT/PAYMENT)
- `data/FakeRepository.kt` — 5 müşteri, 10 hareket. **Bakiye saklanmıyor,
  hareketlerden hesaplanıyor** (append-only kuralı sahte veride de geçerli).
- `util/MoneyFormat.kt` — `5000L.toTlString()` -> "50,00 TL"

**Ekran 1 — `ui/payment/`** (launcher activity; MainActivity silindi)
- POS tuş takımı: rakamlar sağdan kayar (`amountMinor * 10 + digit`), ondalık
  nokta yok -> hiç float/parse yok, model ile aynı Long.
- Kart/Yemek Kartı/Nakit -> mock Toast; VERESİYE -> Ekran 2.

**Ekran 2 — `ui/customers/`**
- RecyclerView + `ListAdapter` (DiffUtil ile otomatik fark hesabı)
- Üstte toplam alacak, arama (`doAfterTextChanged`), filtre çipleri (Tümü/Borçlular)
- Borcu olan kırmızı, borcu bitmiş gri.

**Ekran 3 — `ui/detail/`**
- Müşterinin ledger geçmişi + güncel bakiye (yine hareketlerden hesaplanıyor)
- Filtre çipleri: Tümü / Borçlar / Ödemeler. DEBT "+" kırmızı, PAYMENT "-" yeşil.

**Gradle:** `lifecycle-viewmodel-ktx`, `activity-ktx`, `recyclerview` eklendi.

**Öğrenilenler:** `by viewModels()` (ekran döndürmede state korunur);
`StateFlow` + `repeatOnLifecycle(STARTED)` (arka planda toplama durur, sızıntı
yok); `ListAdapter`/`DiffUtil`; constructor parametreli ViewModel için
`ViewModelProvider.Factory`; `companion object`'te `createIntent()` (extra
anahtarları ekrana özel kalır); `styles.xml` ile 12 butonun tek yerden stili;
`tools:` namespace'i sadece preview'da görünür.

---

### 2026-07-23 — Tur 5: Activity'ler -> tek Activity + Fragment + Navigation

Kullanıcının sorusu üzerine yapıldı ("her sayfa ayrı Activity yerine Fragment
daha optimize olmaz mı?") — haklıydı, modern Android standardı tek Activity.

**Yeni yapı:**
- `MainActivity` tek Activity; `activity_main.xml` sadece bir
  `FragmentContainerView` (NavHostFragment) içerir.
- `res/navigation/nav_graph.xml` — ekranlar ve geçişler. **Nested graph**
  (`saleFlow`) kullanıldı: satış akışının ekranları bir grup altında.
- Üç Activity -> Fragment: `PaymentFragment`, `CustomerListFragment`,
  `CustomerDetailFragment`. Layout'lar `activity_*` -> `fragment_*`.
- `ui/sale/SaleViewModel` — akışın **paylaşılan state**'i (tutar).
  `by navGraphViewModels(R.id.saleFlow)` ile erişilir: akıştaki tüm fragment'lar
  aynı örneği görür, akıştan çıkınca ViewModel ölür -> yeni satış sıfırdan başlar.
  Intent extra ile tutar taşıma kalktı.
- Ekranlar arası geçiş: `findNavController().navigate(...)`, argümanlar
  **Safe Args** ile tip güvenli (`CustomerListFragmentDirections`, `by navArgs()`).
- Tema `NoActionBar` -> `Theme.Material3.DayNight`; ekran başlığı ve geri oku
  `setupActionBarWithNavController` ile nav_graph label'larından geliyor.

**Gradle:** `fragment-ktx`, `navigation-fragment-ktx`, `navigation-ui-ktx` +
`androidx.navigation.safeargs.kotlin` plugin'i (root + app).

**Öğrenilenler:**
- Fragment'ın view'ı fragment'tan **önce ölür** -> `_binding` nullable, view
  `onDestroyView`'da temizlenir (yoksa bellek sızıntısı).
- `viewLifecycleOwner.lifecycleScope` kullanılır, `lifecycleScope` değil — flow
  toplama view'ın ömrüne bağlanır.
- Nested graph = hem ekran grubu hem **ViewModel scope**. İleride login akışı /
  bottom navigation gelince kardeş nested grafikler olarak eklenir.
- `viewModelScope` ayrı bir şey: ViewModel içindeki coroutine scope'u (FAZ 3'te
  Room/Retrofit çağrılarında kullanılacak).

**Sıradaki:** Uçtan uca test (tutar gir -> VERESİYE -> liste -> müşteri detayı,
geri oku çalışıyor mu). Sonra FAZ 2: `:core-domain` modülü +
`shared-contracts/openapi.yaml`.

---

### 2026-07-27 — Tur 6: Ödeme ayrımı (mock-pos) + app-pos iki giriş noktalı

Plan değişikliği: ödeme (keypad + Kart/Yemek Kartı/Nakit) app-pos'un parçası
DEĞİL — Token'ın POS ödeme app'ine ait. Yeni `mock-pos/` (ayrı Gradle projesi/APK)
onu taklit eder; VERESİYE -> app-pos'u intent ile açar.

**mock-pos (yeni):** ödeme ekranı (keypad + 4 yöntem), `PaymentViewModel` (keypad
state), `MoneyFormat` (app-pos'tan kopya — ayrı APK'lar import edemez), ViewBinding.
VERESİYE -> `Intent("com.example.app_pos.action.CREDIT")` + `setPackage` +
`amount_minor` (Long kuruş) extra. Manifest'e `<queries>` (Android 11+ package
visibility — olmadan hedef app sessizce bulunamaz).

**app-pos:** ödeme ekranı SİLİNDİ (PaymentFragment, keypad layout/style/strings).
`SaleViewModel` keypad'i çıktı, `setAmount` geldi (tutar intent'ten). nav_graph
start = **dashboard**; saleFlow customerSelect'ten başlar, tutarı `amountMinor`
nav-arg'ından alır. `MainActivity`: intent CREDIT ise saleFlow'a tutarla geç +
handoff modu; değilse dashboard. Manifest'e CREDIT intent-filter (MAIN/LAUNCHER
korundu -> **iki giriş noktası**: bağımsız launcher + veresiye handoff).

**Öğrenilenler / dersler:**
- App-to-app handoff standart deseni: tek Activity + iki intent-filter + onCreate'te
  intent'e göre başlangıç ekranı seçme. Custom action + extra; client'lar kod
  paylaşmaz, sabitler kopyalanır (ileride shared-contracts).
- **Build ortamı dersi:** VSCode'un getirdiği JRE'de `jlink` yok -> `JdkImageTransform`
  patlıyor. Çözüm: `JAVA_HOME`'u Android Studio JBR'sine ayarla + transform cache'i
  temizle. Terminal build: `export JAVA_HOME=".../Android Studio.app/Contents/jbr/
  Contents/Home"` + `-Dorg.gradle.java.installations.auto-detect=false`.
- mock-pos AndroidX sürümleri app-pos ile hizalandı (yine core-ktx 1.19->1.15 vb.
  compileSdk 37 sorunu; Tur 2 dersinin tekrarı).

### 2026-07-27 — Tur 7: FAZ 1 kapatma — onay ekranı + ledger yazımı  [FAZ 1 BİTTİ]

Flow A'nın yarım kalan halkası tamamlandı: müşteri seçilince artık gerçekten
ledger'a yazılıyor.

**FakeRepository observable oldu:** ledger `MutableStateFlow<List<Transaction>>`;
`addTransaction` (append-only), `observeCustomers/observeTransactions/
observeTotalReceivableMinor` Flow döner. Yazınca liste/detay/toplam CANLI güncellenir.
Room DAO Flow'ları aynı davranacağı için ViewModel'ler Faz 3'te değişmeyecek.

**ViewModel'ler Flow'a bağlandı:** `CustomersViewModel`/`CustomerDetailViewModel`/
`CustomerSelectViewModel` artık `combine(repoFlow, query/filter)` + `stateIn
(viewModelScope, WhileSubscribed)`. Bir kez snapshot yerine reaktif. Detay bakiyesi
tek `Long` yerine `StateFlow<Long>`, fragment collect ediyor.

**Onay ekranı (yeni):** `ui/sale/ConfirmFragment` + `ConfirmViewModel` +
`fragment_confirm.xml`. Müşteri, tutar, mevcut + işlem sonrası bakiye gösterir;
[Onayla ve Yaz] -> `addTransaction(DEBT, UUID transactionId)`. Bakiye repo'dan
TAZE okunur (SaleViewModel'deki stale kopya değil). Onay LOKAL — müşteri bildirimi
FAZ 8 (client dinlemez, çağırır). saleFlow: customerSelect -> **confirm** -> yazım
-> handoff'ta `finish`, bağımsız modda dashboard'a.

**Öğrenilenler:**
- `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5s), initial)` — cold
  Flow'u hot StateFlow'a çevirir; aboneli değilken durur (pil), 5sn tolerans ekran
  dönmesinde yeniden başlatmaz.
- `combine` ile arama/filtre reaktif kaynağın üstüne bindirilir (ayrı MutableStateFlow
  snapshot'ı yerine).
- minSdk 24: `java.time.LocalDateTime` API 26 — desugaring yoksa `SimpleDateFormat`
  kullan (MoneyFormat'ın Locale desenine uyumlu).

**Sıradaki:** Uçtan uca test (mock-pos tutar -> VERESİYE -> müşteri seç -> onay ->
yaz -> mock-pos'a dön; app-pos bağımsız aç -> detayda yeni hareket + artan bakiye).
Sonra **FAZ 2**: User modeli (isBuyer/isSeller flag), `:core-domain` modülü,
mock DB formatı, `shared-contracts/openapi.yaml`. Sonra auth (OTP mock), en son
app-mobile UI (model hazırken).

### 2026-07-27 — Tur 8: Handoff düzeltmeleri + yeni müşteri (UNCLAIMED) oluşturma

Fiziksel cihaz testinde çıkan üç iş. (Test sırasında bir yanlış anlama netleşti:
handoff DOĞRU çalışıyordu — VERESİYE app-pos'u açıyor; recent-apps'te tek "mock-pos"
kartı görünmesi Android'in TASK davranışıydı, veri hep app-pos'tan geliyordu.)

**1) Keypad sıfırlama (mock-pos):** `MainActivity.onResume()` -> `viewModel.onClear()`.
Veresiye yazıp dönünce tutar artık "0,00 TL" (önceki satış kalmıyor).

**2) Ayrı task (mock-pos):** `onCreditSelected` intent'ine `FLAG_ACTIVITY_NEW_TASK`.
app-pos KENDİ task'ında açılır -> recent-apps'te iki ayrı kart. Gerekçe: mock-pos
Token'ın gerçek POS ödeme app'inin TAKLİDİ; app-pos onun yaşam döngüsüne bağımlı
olmamalı (hem mimari doğruluk hem debug'da ayrılabilirlik). İki app kod paylaşmaz;
tek sözleşme action string + `amount_minor` extra.

**3) Yeni müşteri oluşturma (UNCLAIMED):** Müşteri seç ekranında, aranan isimde
tam eşleşme YOKSA inline buton ("'<isim>' adıyla yeni müşteri ekle") çıkar; tıkla
-> `ClaimStatus.UNCLAIMED` Customer oluşur (app'siz müşteri; "not-registered" flag'i
zaten tasarımda vardı) -> onay ekranına geçer. Tam eşleşmede buton gizli (aynı isme
izin yok -> esnaf listeden seçer).
- `FakeRepository`: `customers` da MutableStateFlow (observable) oldu; `addCustomer`
  (UNCLAIMED, UUID id) + `customerNameExists` (case-insensitive). observeCustomers/
  observeTotalReceivable artık iki Flow'u `combine` ediyor.
- `CustomerSelectViewModel`: `canCreate: StateFlow<Boolean>` (query dolu + isim yok)
  + `createCustomer()` (id döner / çakışmada null).
- `CustomerSelectFragment` + `fragment_customer_select.xml`: inline TonalButton.

**Öğrenilenler:**
- **Task modeli:** `startActivity` varsayılan olarak çağıranın task'ına ekler;
  başka app'i AYRI recents kartı/yaşam döngüsüyle açmak için `FLAG_ACTIVITY_NEW_TASK`.
- **Customer vs User:** yeni müşteri `Customer.claimStatus=UNCLAIMED` ile açılır,
  Faz 2'nin User/buyer-seller modelini beklemez (farklı kavramlar).
- Kaynak observable'sa (`MutableStateFlow`), yazma metodu yeni liste emit eder ->
  tüm ekranlar canlı; iki kaynağı `combine` ile birleştir.

**Sıradaki:** Fiziksel cihazda doğrula (keypad reset, iki recents kartı, yeni
UNCLAIMED müşteri akışı). Sonra **FAZ 2** (User modeli + core-domain + openapi).

### 2026-07-28 — Tur 9: Telefon-kimlikli akış + OTP onay pipeline + ödeme + nav fix

Büyük mimari karar: sistem **telefon numarası** ekseninde çalışır, ve **her
veresiye/ödeme OTP onayından** geçer (satıcı keyfî borç yazamasın). Backend yok →
OTP mock (`verifyOtp` hep true), ama gerçek ekran + fonksiyon imzaları var;
backend gelince (FAZ 4/5) sadece içleri değişir.

**Kimlik = telefon:** seed müşterilere numara eklendi (hepsi dolu). `addCustomer`
artık `(name, phone)`; `customerNameExists` → `customerPhoneExists` (rakam-normalize,
aynı numara iki kez olamaz; isim serbest). `findCustomerByPhone/ById` eklendi.
UNCLAIMED artık sadece "app yok" demek (numara her kayıtta var).

**OtpService (mock, backend-ready):** `requestOtp(phone, hasApp)` + `verifyOtp(phone,
code, hasApp)`, ikisi suspend, şimdilik true. hasApp dalı (app-push vs SMS) kodda
ayrık, ikisi de mock. Belirgin TODO(FAZ 4/5).

**saleFlow ortak DEBT/PAYMENT akışı:** `SaleViewModel.txType` taşınır. Keypad
mantığı (onDigit/onClear/onBackspace, mock-pos'tan) SaleViewModel'e geri geldi.
Yeni ekranlar: KeypadFragment (PAYMENT girişi), PhoneFragment (yeni müşteri
numara), OtpFragment (kod). Confirm artık YAZMAZ → OTP'ye devreder; **yazma tek
yerde** (OtpViewModel.verifyAndWrite: yeni müşteriyse addCustomer, sonra
addTransaction, UUID idempotency). DEBT: mock-pos → customerSelect. PAYMENT: müşteri
detayı → global action → keypad. Keypad app-pos'a geri (styles.xml + key strings
yeniden; silinmişti).

**İki nav bug'ı:**
- 7a (detaydan listeye dönüş): kök neden — detay İÇ nav host'ta, action-bar up sadece
  DIŞ controller'ı biliyordu. Fix: iç host `defaultNavHost=true` (sistem geri) +
  MainActivity.onSupportNavigateUp önce iç controller'ı dener (DashboardFragment
  `innerNavControllerOrNull` expose eder).
- 7b (satışta yanlış geri = kayıp): `installCancelGuard()` (ortak extension,
  OnBackPressedCallback + AlertDialog "iptal edilsin mi?") her sale ekranında.

**Öğrenilenler:**
- Nested NavHost'ta `defaultNavHost=true` iç back stack'i sistem geri tuşuna bağlar;
  action-bar up ayrı köprü ister (onSupportNavigateUp iç controller'ı önce dener).
- Global action + safeargs: `NavGraphDirections.actionGlobalPay(...)`; iç graph'tan
  dış graph'a geçiş `Navigation.findNavController(activity, R.id.navHostFragment)`.
- Yazmayı tek noktada (OTP sonrası) tutmak: onay ekranı sadece özet, gate OTP.
- Mimari gerilim (NOT): OTP zorunlu ↔ offline-first. Şimdilik mock bypass; backend
  gelince offline'da onay politikası (PENDING durumu?) yeniden konuşulacak (FAZ 4/5).

**Sıradaki:** Cihazda uçtan uca doğrula (yeni müşteri+numara+OTP, kayıtlı, çakışma,
ödeme akışı, iki nav bug). Sonra **FAZ 2** (User buyer/seller modeli + core-domain
+ openapi), sonra auth, en son app-mobile UI.

### 2026-07-28 — Tur 10: Tur 9 hata düzeltmeleri (crash, handoff, geri, numara)

Cihaz testinde çıkan 4 hata düzeltildi. Kök nedenler + çözümler:

**1a) "Ödeme Al" → mock-pos'a gitti:** `isCreditHandoff` bayrağı bir kez true olunca
sıfırlanmıyordu + `launchMode` tanımsız → mock-pos'un NEW_TASK'ıyla açılan app-pos
hayalet task olarak kalıp eski (handoff=true) instance geri geliyordu, ödeme akışı
finish() ile yanlışlıkla mock-pos'a dönüyordu.
- Fix: Manifest `MainActivity launchMode=singleTask` (tek instance) + `onNewIntent`
  override + bayrağı **her intent'te** yeniden değerlendir (`isCreditHandoff =
  intent.action == ACTION_CREDIT`; launcher gelince false).

**1b) Ödeme keypad girişi crash (sürekli durma):** `action_global_pay` iç graph'tan
dış keypadFragment'a girerken zorunlu (default'suz) argümanlar eşleşmiyordu →
IllegalArgument → crash-restart döngüsü.
- Fix: keypad arg'larına `android:defaultValue=""` (crash yerine boş güvenlik ağı) +
  action'a `launchSingleTop`.

**2) Detaydan geri dönülemiyor:** İki nested `defaultNavHost=true` çakışıyordu (dış
host sistem geri tuşunu önce yakalıyor).
- Fix: iç host'tan `defaultNavHost` kaldırıldı; DashboardFragment kendi
  `OnBackPressedCallback`'ini kurar — iç back-stack'te detay varsa
  (`previousBackStackEntry != null`) geri onu pop eder (detay→liste), yoksa callback
  disabled (dış host çalışır). Action-bar up köprüsü (onSupportNavigateUp) korundu.

**3+4) Numara görünmüyor / Ahmet1-Ahmet2 ayrımı:** Detay + liste satırında telefon
alanı yoktu. Seçim zaten customerId (UUID) ile doğruydu; numara görününce satıcı
ayırt edebiliyor.
- Fix: `fragment_customer_detail.xml`'e `detailPhone`; CustomerDetailFragment
  `findCustomerById(...).phone` (lazy, tek okuma, pay butonuyla paylaşılır).
  `CustomerAdapter` statü satırına numarayı ekler ("Uygulaması yok · +90 555…").

**Öğrenilenler:**
- `launchMode=singleTask` + `onNewIntent`: app-to-app handoff yapan Activity'de
  tek-instance garantisi; her açılış intent'i onNewIntent'e gelir, state oradan
  tazelenir. Bayrağı intent'e bağlamak (bir kez set edip bırakmak yerine) stale
  state'i önler.
- Nav argümanı default'suz = zorunlu; verilmezse crash. Deep hedefe (nested graph
  içi) girişte default güvenlik ağı işe yarar.
- İki nested defaultNavHost = geri tuşu çakışması; iç host'u elle OnBackPressedCallback
  ile yönet.

**Sıradaki:** Cihazda 4 hatayı da doğrula (app-pos'u yeniden kur!). Sonra FAZ 2.

### 2026-07-28 — Tur 11: Ödeme akışı crash — gerçek kök neden (nav mimarisi)

Tur 10'daki 1a/1b tam çözmemişti; cihazda crash sürüyordu. Logcat (crash buffer)
ile kesin trace alındı, kök neden bulundu ve nav_graph mimarisi düzeltildi.

**Debug yöntemi (kalıcı):** `adb logcat -b crash` = crash buffer (Java stack trace).
`level:error` filtresi sistem-seviyesi crash'i KAÇIRIYOR — crash için hep `-b crash`.
Kurulu kod güncel mi? `dumpsys package ... | grep lastUpdateTime`. Kısayollar
`~/.zshrc`'ye eklendi: **posbuild** (app-pos derle+kur), **mockbuild**, **poscrash**.
Önemli ders: AS "Run" bazen "up-to-date" deyip eski APK'yı bırakıyor; kesinlik için
`adb install -r` (posbuild bunu yapar) + lastUpdateTime kontrolü.

**Gerçek kök neden:** `keypadFragment` `saleFlow` nested graph'ının İÇİNDEydi ama
`startDestination` değildi (`customerSelectFragment`'tı). Navigation kuralı: bir
nested graph'a DIŞARIDAN sadece `startDestination`'ına girilebilir; iç bir node'a
doğrudan `navigate` → IllegalArgumentException ("cannot be found from current
destination"). Global action da, Bundle ile doğrudan navigate de aynı duvara
çarpıyordu — hepsi "içeri doğrudan gir" demeye çalışıyordu.

**Çözüm — keypad'i saleFlow'un start'ı yap, iki akışı kapıda ayır:**
- `nav_graph.xml`: `saleFlow app:startDestination=@id/keypadFragment`. Keypad ortak
  giriş kapısı. `action_global_pay` artık `saleFlow`'a (grafiğe) gider, keypad'e değil.
- `KeypadFragment.routeByEntry()`: `amountMinor > 0` (mock-pos'tan gelen DEBT) →
  tutarı yükle + `action_keypad_to_customerSelect` (popUpTo keypad inclusive, keypad
  geçmişten silinsin) → müşteri seçmeye geçer, keypad atlanır. `amountMinor == 0`
  (detaydan gelen PAYMENT) → keypad'de kal, müşteri zaten belli, tutar burada girilir.
- `CustomerSelectFragment`: artık amountMinor argümanı yok (keypad'e taşındı),
  `setupAmount`/navArgs kaldırıldı; amount/txType keypad'de set edilir.

**Handoff'u akış türüne bağla (Tur 10-1a'yı tamamlar):** `finishCreditHandoff(isHandoffFlow)`
— sadece DEBT + isCreditHandoff'ta finish (mock-pos'a dön). PAYMENT hep dashboard'a
döner, ASLA mock-pos'a gitmez/crash etmez. Çağrı yerleri (OtpFragment, SaleFlowCancel)
txType'a göre isHandoffFlow geçer.

**Detay geri oku (Tur 10-2'yi tamamlar):** iç host action-bar'a bağlı olmadığından
geri oku HİÇ görünmüyordu. DashboardFragment iç destination değişiminde
`supportActionBar.setDisplayHomeAsUpEnabled(canGoUp)` — detayda ok görünür, listede
gizli. Hem üst ok hem sistem geri tuşu detay→liste yapar. ✓ cihazda doğrulandı.

**Öğrenilenler (nav — intuitive):** nav_graph = metro haritası; `<navigation>` = kapalı
istasyon grubu; `app:startDestination` = grubun TEK giriş kapısı; dışarıdan sadece
kapıya girilir, iç node'a değil. `app:` = kütüphane (Navigation) attribute'u,
`android:` = çekirdek. `popUpTo`+`popUpToInclusive` = geçmişten ekran sil (atlanan
ekrana geri dönülmesin). "Girilemez" kuralını kütüphane runtime'da uygular (crash
mesajıyla belli eder), kodda yazmaz.

**Durum:** app-pos akışları cihazda ÇALIŞIYOR — veresiye (mock-pos→app-pos→onay→OTP→
mock-pos'a dön), ödeme (detay→Ödeme Al→keypad→onay→OTP→dashboard), yeni müşteri+numara,
detaydan geri. **Sıradaki: FAZ 2** (User buyer/seller modeli + core-domain + openapi),
sonra auth (OTP'yi gerçek backend'e bağlama dahil), en son app-mobile UI.

### 2026-07-28 — Tur 12: FAZ 2 ilk adım — `:core-domain` saf-Kotlin modülü  [FAZ 2 başladı]

Çok modüllü mimarinin ilk taşı. Hedef yapı `:app → :core-data → {:core-domain,
:core-network}`; bu tur `:core-domain`'i kurup saf domain modellerini oraya taşıdık.
Küçük, öğretici adım — davranış hiç değişmedi ("modül ekledik, kod aynen derlendi").

**Kapsam kararı (bilinçli dar tutuldu — overengineering yok):**
- TAŞINDI → `:core-domain`: `model/Customer.kt` (ClaimStatus + Customer),
  `model/Transaction.kt` (TransactionType + Transaction). Saf enum + immutable data
  class, sıfır dış bağımlılık.
- KALDI → `:app`: `FakeRepository` (Flow/observable/seed = data katmanı; FAZ 3'te
  `:core-data`'ya), `OtpService`, `util/MoneyFormat` + `util/PhoneFormat` (UI-yakını
  formatlama). `balanceOf` (DEBT +, PAYMENT −) şimdilik FakeRepository'de private —
  tek çağıranı var, erken soyutlama YAGNI; FAZ 3'te Room dönüşümüyle domain'e çıkar.

**Yapılanlar:**
- Yeni `core-domain/build.gradle.kts`: `plugins { id("org.jetbrains.kotlin.jvm") }`
  (VERSİYONSUZ — aşağıdaki ders), `kotlin { jvmToolchain(11) }` (:app Java 11 ile
  uyumlu bytecode), dependencies BOŞ (saflık). `repositories {}` YOK
  (`FAIL_ON_PROJECT_REPOS` — modül repo tanımlarsa build patlar). `android {}` YOK.
- `settings.gradle.kts`: `include(":core-domain")`.
- `app/build.gradle.kts`: `implementation(project(":core-domain"))`.
- **Paket adı KORUNDU** (`com.example.app_pos.model`) — sadece fiziksel yer değişti.
  → `:app`'teki hiçbir `import` satırı değişmedi (ViewModel/adapter/FakeRepository
  olduğu gibi derlendi). Minimum risk, net diff. Dosyalar `mv` ile taşındı
  (`git mv` untracked dosyada çalışmaz — henüz commit'lenmemişlerdi).

**Öğrenilenler (kritik AGP 9 dersi):**
- İlk denemede `alias(libs.plugins.kotlin.jvm)` (version.ref="kotlin") çakıştı:
  *"plugin is already on the classpath with an unknown version"*. Kök neden: AGP 9
  Kotlin Gradle Plugin'i TÜM build classpath'ine (sürümsüz) koyuyor — sadece Android
  modüllerini değil. Ayrı bir versiyonlu istem sürüm çakışması sayılıyor. Çözüm:
  `:core-domain`'de plugin'i VERSİYONSUZ uygula (`id("org.jetbrains.kotlin.jvm")`);
  classpath'te hazır olanı kullanır. Kullanılmayan catalog alias'ı geri alındı.
- **Modül saflığı derleme çıktısından okunur:** `:core-domain:build` sadece
  `compileKotlin`/`jar` çalıştırdı, hiç Android task'ı yok → gerçekten saf JVM.

**Doğrulama:** `./gradlew :core-domain:build` ✓ (izole, Android'siz), `:app:assembleDebug`
✓ (taşınan modeller `:core-domain`'den çözüldü; tek uyarı: OtpViewModel'deki eski
`Locale` deprecated — bu turla ilgisiz). **Cihaz testi BEKLİYOR** — build sırasında
telefon bağlı değildi (`adb: no devices`). Cihaz bağlanınca `posbuild` + akışları elle
doğrula (davranış aynı olmalı).

**Sıradaki:** (1) Cihazda doğrula. (2) `docs/architecture-pos.md`'ye Customer≠User /
tek app-mobile iki rol modelini ekle (Tur bu session'da netleşti: müşteri telefon+OTP
hızlı giriş → oto-kayıt → ödeme geçmişi + profil; "Satıcı ol" → registration + POS
eşleme; esnafın eklediği UNCLAIMED kayıt henüz User değil, claim ile bağlanır).
(3) User modeli (`:core-domain`, isBuyer/isSeller) + mock DB düzeni. (4) openapi.yaml.

### 2026-07-29 — Tur 13: User modeli + mock DB (full-app-ready) + yol haritası yeniden sıralandı

FAZ 2'nin asıl içeriği. İki kavramsal karar netleşti ve **faz sırası değişti**.

**YOL HARİTASI YENİDEN SIRALANDI (kullanıcı kararı):** app-mobile öne alındı.
Yeni sıra: **User modeli (bu tur) → app-mobile UI (mock üstünde) → shared-contracts
→ Room → backend.** Gerekçe: iki client mock'la çalışınca backend contract'ı gerçek
ekran ihtiyacına göre yazılır (tasarımın "ihtiyaç bilinmeden yazılan şema değişir"
ilkesi) + gösterilebilir somut demo. app-mobile `:core-domain`'i KOPYALAYACAK (ayrı
Gradle projesi; mock-pos deseni), gerçek paylaşım backend fazında.

**CUSTOMER ≠ USER (tasarımın kalbi — karıştırma):**
- Customer = SATICININ DEFTER KAYDI (app-pos'un bildiği). UNCLAIMED = arkasında hesap
  OLMAYAN isim+telefon.
- User = APP-MOBILE HESABI (telefon+OTP giriş). Tek model, rol iki BOOL: isBuyer
  (herkes böyle başlar) + isSeller ("Satıcı ol" ile). İki rol aynı anda aktif olabilir.
- Köprü = CLAIM: User telefonuyla girince o numaralı UNCLAIMED Customer CLAIMED olur,
  `Customer.claimedByUserId` ile bağlanır. İlişki VERİDE (telefon eşleşmesine güvenme).

**Yapılanlar:**
- `:core-domain` yeni `User.kt`: User (userId + phone non-null + isBuyer/isSeller +
  email? + sellerInfo? + createdAt) + `SellerInfo` (shopName, shopPhone?). SellerInfo
  AYRI class → "isSeller=true ⇔ sellerInfo!=null" kuralını DERLEYİCİ korur.
- `Customer.kt`: `claimedByUserId: String?` eklendi (CLAIMED ⇔ !=null). Yanlış yorum
  düzeltildi ("null=UNCLAIMED" → telefon pratikte hep dolu).
- `FakeRepository`: `RawCustomer`+seed'e claimedByUserId (c1→u1, c3→u3, diğerleri null);
  Customer construct eden **3 nokta** güncellendi (unutulan = derleme hatası, iyi koruma).
  `_users` seed: `u_owner` (esnaf, isBuyer+isSeller, SellerInfo "Ahmet Bakkal" —
  Customer'ı YOK, "tek User iki rol" kanıtı) + u1/u3 (CLAIMED customer'ların hesabı).
- Metodlar (backend-ready, OtpService deseni): **çalışır** — findUserByPhone,
  observeCurrentUser (mock: owner), registerUser (oto-kayıt: yoksa oluştur/varsa dön),
  setSeller (isSeller=true + SellerInfo). **imza+TODO** — claimCustomerForUser
  (app-mobile turunda). observeUser/observeMyTransactions: sadece dokümante (cross-merchant
  backend ile gelir).
- Dokümanlar senkronlandı (iki dosya User'da ayrışmıştı): `docs/architecture-pos.md`
  §4'e Customer≠User + User/SellerInfo kod bloğu + "gerçek DB ne zaman" (Room=FAZ 3 cihazda
  / Docker sunucu-DB=backend fazı), §7 Customer bloğu + §8 yeni sıra; `veresiye-platform-
  tasarim.md` ALINAN KARARLAR'a CUSTOMER!=USER + FAZ sırası güncellemesi + FAZ 8 satıcı notu.

**Öğrenilenler:**
- **Data class'a alan eklemek = tüm construct noktalarını güncelle** (Customer 3 yerde
  kuruluyordu). Unutulan biri derleme hatası verir — sessiz bug değil, iyi koruma.
- **Invariant'ı tiple koru:** "satıcıysa bilgi dolu" / "claimed'se user id dolu" —
  nullable ALT-NESNE (SellerInfo?) veya nullable FK (claimedByUserId?) ile ifade edilince
  imkânsız durumlar derlemede yakalanır (düz nullable alanlar kaçırırdı).
- **Locale:** yeni kodda `Locale.forLanguageTag("tr-TR")` (deprecated değil); kod
  tabanının eski yerleri `Locale("tr","TR")` kullanıyor (ileride topluca güncellenebilir).

**Doğrulama:** `:core-domain:build` ✓ (User/SellerInfo saf JVM), `:app:assembleDebug` ✓
uyarısız. Mevcut veresiye/ödeme akışları değişmedi (sadece FakeRepository iç satırları +
Customer alanı). **Cihaz testi:** telefon bağlıysa `posbuild` + akışları dolaş (aynı
davranış). Build sırasında cihaz bağlı değildi.

**Sıradaki:** app-mobile UI (mock üstünde) — telefon+OTP giriş (oto-kayıt) → ödeme
geçmişi + profil → "Satıcı ol". POS asset'lerinden türer, `:core-domain`'i kopyalar.

### 2026-07-29 — Tur 14: app-pos login-gate + profil (User canlı) + mock eşleştirme

User modelinin İLK gerçek tüketicisi. app-pos'a esnaf oturumu (login-gate) + profil
ekranı (User bilgilerini gösterir) + POS↔hesap eşleştirmesi eklendi.

**En kritik karar — login GERÇEK açılış-gate (mock içi), profil-içi buton DEĞİL:**
Kullanıcı "gerçek login gelince mimari değişmesin, sadece içi dolsun" istedi. Bu yüzden
gate deseni: `nav_graph startDestination = loginFragment`. Gerçek login gelince SADECE
`FakeRepository.login()` + `LoginViewModel.login()` + login UI içi dolar; gate/nav/
handoff mimarisi SABİT kalır.

**CREDIT handoff'u bozmama (en hassas nokta):** `MainActivity.handleIntent` DAVRANIŞÇA
DEĞİŞMEDİ (byte-uyumlu). CREDIT dalı `isLoggedIn`'e BAKMAZ → handoff gate'i ATLAR (POS
terminali fiziksel esnafın; kasada müşteri bloklanmaz; `login()` çağırmaz → session'a
dokunmaz). Tüm yeni davranış startDestination flip'inden gelir. Pending-intent YOK →
`savedInstanceState` guard'ı değişmez. Docs auth kararına (Square/SumUp: bir kez giriş,
sonra sessiz) uyar — gate nadir görünür, handoff'u pratikte kesmez.

**Yapılanlar:**
- `FakeRepository`: `isLoggedIn`/`isPairedWithApp` StateFlow (mock, false başlar —
  akışları görmek için) + `login(phone?)`/`logout()`/`pairWithApp()` (backend-ready
  imzalar). `observeCurrentUser` login'e bağlandı (`combine(_users, _isLoggedIn)` →
  logout'ta null). `asStateFlow` import.
- Login (yeni `ui/login/`): `fragment_login.xml` (başlık + opsiyonel telefon + buton),
  `LoginViewModel` (LoginState IDLE/SUBMITTING/SUCCESS/ERROR — OtpViewModel deseni;
  gerçek OTP için yer), `LoginFragment` (SUCCESS → `action_global_dashboard_after_login`).
- `nav_graph.xml`: `loginFragment` destination + `action_global_dashboard_after_login`
  (popUpTo login inclusive — giriş sonrası gate'i sil) + `action_global_login` (popUpTo
  nav_graph inclusive — logout: dashboard'ı sil). **startDestination dashboard→login FLIP.**
- `MainActivity`: `navigateToLogin()` helper (logout için DIŞ controller — login dış
  grafta; `(activity as? MainActivity)` deseni). handleIntent değişmedi.
- Profil (`ui/dashboard/profile/`): `ProfileUiState` sealed (NotPaired/Ready — NotLoggedIn
  YOK, gate hallediyor), `ProfileViewModel` (`combine(observeCurrentUser, isPaired)`),
  `ProfileFragment` (placeholder→ViewBinding; salt-okunur kart: displayName/phone/email
  null→GONE/roller joinToString/shopName null→GONE + eşleştir kartı + logout),
  `fragment_profile.xml` (MaterialCardView + NestedScrollView).
- Eşleştirme: `PairingViewModel` (PairingStatus; confirmPairing delay(300)→pairWithApp→
  DONE) + `fragment_pairing.xml` (OtpFragment deseni) + `PairingFragment`
  (**installCancelGuard KULLANMADI** — saleFlow scope'una bağlı, crash ederdi;
  DONE→Toast+navigateUp). `dashboard_graph.xml`: pairingFragment + action_profile_to_pairing.
- `strings.xml`: login/profil/eşleştirme string'leri; `profile_placeholder` kaldırıldı.

**Öğrenilenler:**
- **Gate'i startDestination'a koy, MainActivity redirect ETME.** `AppBarConfiguration
  (navController.graph)` start'a up-arrow koymaz → login bedavaya up-arrow'suz. Redirect
  deseni (dashboard start + onCreate'te kaç) ilk-frame flash + manuel AppBarConfiguration ister.
- **Kırılgan koda dokunmamanın değeri:** handleIntent'i byte-uyumlu bırakıp tüm davranışı
  deklaratif nav'dan (startDestination + popUpTo action'lar) almak = en güvenli refactor.
  handoff testi bozulursa suçlu tek satır (flip), yeni ekranlar değil.
- **Adım sırası riski izole eder:** additive (1-5, app hâlâ dashboard açar) → flip (6) →
  handoff (7) → profil (8). Her adım derlenip test edilir; en riskli tek satır tek başına.
- **Cross-graph nav = DIŞ controller.** Profil iç grafta, login dış grafta →
  `findNavController()` (iç) login'i bulamaz/crash; activity üzerinden dış controller.
- **String kaldırma sırası:** `profile_placeholder`'ı önce sildim, eski layout hâlâ
  referans veriyordu → resource-link hatası. Ders: string'i onu KULLANAN son dosyayla
  birlikte kaldır (geçici geri-ekleme + build-yeşil-tut ile çözdüm).

**Doğrulama:** `:app:assembleDebug` ✓ uyarısız (8 adım, her biri ayrı derlendi).
**Cihaz testi BEKLİYOR** (telefon bağlı değildi). Regresyon+yeni test listesi plan
dosyasında (Grup A: launcher/handoff/iç-nav bozulmamalı; Grup B: login gate, logout,
eşleştirme). `posbuild` ile doğrulanacak.

**Sıradaki:** app-mobile UI — bu login + profil ekranları desen olacak (User kopyalanarak).

### 2026-07-29 — Tur 15: Login gerçek credential + mock token/session + handoff gate + profil düzenleme

Tur 14'ün mock login'i gerçeğe yaklaştırıldı; kullanıcı isteğiyle 4 iş.

**Session/token (User'a DEĞİL, ayrı Session):** `FakeRepository` içinde `private data
class Session(token, loggedInAt, expiresAt)` + `_session: MutableStateFlow<Session?>`.
`isSessionValid()` = token var + `expiresAt > now`. Gerekçe: User = kimlik (domain);
token = oturum-state. Backend JWT DataStore/Room'da tutulacak, User'da değil. **MOCK
sınırı:** token RAM'de → app tamamen kapanınca sıfırlanır (kalıcılık FAZ 3/Room); "7
gün" mantığı kodda gerçek ama restart'ta hatırlamaz.

**Login credential:** `login(phone): Boolean` — sadece kayıtlı numara kabul (Tur 15'te
sabit "05554443322"; Tur 16'da findUserByPhone'a genişledi). LoginViewModel SUCCESS/ERROR.

**Dinamik startDestination (flash'sız):** `MainActivity.onCreate`'te grafiği inflate
edip `setStartDestination(isSessionValid ? dashboard : login)`. Token geçerliyse login
hiç çizilmez. `savedInstanceState==null` guard korunur.

**Handoff + login + pending amount:** CREDIT geldiğinde login değilse `pendingHandoffAmount`
(MainActivity field + Bundle save/restore) saklanır, login sonrası `onLoginSucceeded()`
saleFlow'a (pending ile) götürür (`action_global_saleflow_after_login`, popUpTo login
inclusive). `isCreditHandoff` flag'i korunur → OTP sonrası mock-pos'a döner. handleIntent
CREDIT dalı `isSessionValid` kontrolü eklendi (login'liyse eskisi gibi direkt saleFlow).

**Profil düzenlenebilir + u_owner boş başlar:** seed u_owner `displayName=""`,
`sellerInfo=null`, `isSeller=true`. Profil inline edit: isim + dükkan yanında "Güncelle"
butonu → MaterialAlertDialog + input → `updateDisplayName`/`updateShopName` (setSeller'ın
shopPhone-ezmesini önler). Boşken "eklenmemiş" placeholder.

### 2026-07-29 — Tur 16: Satıcı sahipliği (Transaction.sellerId) + login/register ayrımı

Cihaz testinde iki eksik görüldü: (1) müşteriler/transaction'lar hiçbir satıcıya bağlı
değildi (herkese aynı liste), (2) login/register ayrımı yoktu. Kullanıcının mimari
düzeltmesi: sahiplik **Customer'da DEĞİL Transaction'da** ("bir müşteri farklı
satıcılardan alışveriş yapabilir").

**A — Sahiplik:**
- `Transaction`e `sellerId: String` eklendi (customerId = buyer). Bakiye artık
  **(seller, customer) çifti** toplamı. Seed t1-t10 hepsi `sellerId="u_owner"` (bakiyeler
  aynı kaldı — regresyon güvencesi).
- `observeCustomers(sellerId)` = o satıcının ledger'ında transaction'ı olan müşteriler
  (SQL: `DISTINCT customer_id WHERE seller_id=?`). `observeTransactions(sellerId,
  customerId)`, `observeTotalReceivableMinor(sellerId)`, `balanceOf(sellerId, customerId,
  ledger)` hepsi seller-scoped. `RawCustomer.toCustomer(sellerId, ledger)` helper (3
  yerdeki tekrarı tek noktaya aldı).
- `currentSellerId(): String?` senkron helper (OtpViewModel yazarken; Flow'dan .value
  alınamaz). OtpViewModel.verifyAndWrite artık `sellerId` yazıyor.
- 3 reader VM (Customers/CustomerSelect/CustomerDetail) + ConfirmViewModel:
  `observeCurrentUser().flatMapLatest { observe*(user.userId) }` deseni (@OptIn
  ExperimentalCoroutinesApi). findCustomerById/ByPhone'a sellerId parametresi.

**B — Login/Register:**
- `login`: kayıtlı numara (`findUserByPhone`) → login; yeni → `NEEDS_REGISTER`.
  LoginViewModel: NEEDS_REGISTER + `register()` (registerUser(phone, "", isSeller=true) +
  login) + `cancelRegister()`. LoginFragment: MaterialAlertDialog onayı. app-pos'tan
  register = **satıcı** (isSeller=true default param; app-mobile'ı bozmaz).

**Cihaz testinde çıkan 3 BUG (çözüldü):**
1. **Login sonrası boş dashboard/profil, telefon yok** — kök neden: `PhoneFormat.toStored`
   idempotent değil; `login` E.164 numarayı TEKRAR toStored'dan geçirince null → session
   set edilmiyordu (ama LoginVM SUCCESS diyordu). Debug log ile teşhis edildi. Fix:
   `login` toStored yapmıyor (findUserByPhone digit-normalize zaten her formatı kabul
   eder); `toStored` **idempotent** yapıldı (zaten +90 ile başlayanı olduğu gibi döndürür);
   LoginVM `login()` dönüşünü kontrol ediyor (false→ERROR).
2. **Register dialog açılmıyordu** — aynı çift-toStored: `pendingPhoneDisplay` E.164'ü
   tekrar toStored → null → dialog return. Fix: pendingPhone zaten E.164, direkt döndür.
3. **Register/login farklı user'da hep u_owner gösteriyordu** — `observeCurrentUser`
   HARDCODED "u_owner" döndürüyordu (session kimin diye bakmıyordu). Fix: `Session`'a
   `userId` eklendi; login o user'ın id'sini saklar; observeCurrentUser + currentSellerId
   session.userId'ye bağlı. Artık kim login'se onun profili/ledger'ı görünür.

**Öğrenilenler:**
- **Idempotent olmayan dönüşüm = sinsi bug.** `toStored(toStored(x))` null veriyordu;
  fonksiyonu idempotent yapmak sınıfın tüm bug'larını kökten çözdü. Format dönüştüren
  util'ler idempotent olmalı.
- **Session "kim" bilgisini taşımalı.** Tek-user mock kısayolu (hep u_owner) çok-user
  (register) gelince kırıldı; session.userId gerçek çözüm — backend JWT subject'ine köprü.
- **Debug teşhisi:** sessiz mantık hatası (crash değil) → geçici `Log.d` + logcat; kök
  neden anında görünür. MIUI logcat gürültüsü (avc denied, OnBackInvokedCallback,
  AutofillManager) uygulama hatası DEĞİL, filtrelenmeli.

**Doğrulama:** `:app:assembleDebug` ✓. Cihazda: login (05554443322) ✓, farklı numara →
register onayı → yeni satıcı olarak giriş ✓, doğru profil/dashboard ✓. Tek uyarı:
OtpViewModel'deki eski `Locale("tr","TR")` (bu turla ilgisiz).

**Sıradaki:** app-mobile UI (mock üstünde) — login + profil + register ekranları desen
olacak, User modeli kopyalanarak. Sonra shared-contracts/openapi.yaml (seller_id +
customer_id ledger + POST /users{is_seller} + POST /auth/login{phone}).

### 2026-07-29 — Tur 17: FAZ 6 — app-mobile MÜŞTERİ (buyer) dikeyi, mock üstünde  [FAZ 6 başladı]

Yeni client'ın ilk turu. app-mobile'ın **alıcı (buyer)** tarafı baştan sona kuruldu;
app-pos ekranları desen alındı, `:core-domain` KOPYALANDI (ayrı Gradle projesi, mock-pos
deseni). Hepsi mock (`FakeRepository` RAM). Cihazda derleniyor (`:app:assembleDebug` ✓).

**Bu turun kararları (kullanıcı onayı):**
- **Polling: sadece foreground (mock).** Onay ekranı açıkken repo StateFlow'u reaktif →
  "bekleyen onay" canlı görünür. Background/WorkManager = imza+TODO(FAZ 4). app-mobile
  **caller**, dinleyici değil; **FCM YOK** (Google servis güvenilmezliği).
- **App'li müşteri onay UX'i: in-app Onayla/Reddet kartı** (OTP kodu DEĞİL). Docs'taki
  "app'li müşteride onay app-push, app'siz'de SMS OTP" ayrımına uyar → düşük friction.
- **Kapsam: buyer.** Seller ("Satıcı ol" + müşteri recyclerview/detay) AYRI tura ertelendi
  (profildeki buton görünür ama şimdilik Toast placeholder).

**Mimari yön — buyer = seller'ın SİMETRİĞİ:** app-pos seller-scoped
(`observeCustomers(sellerId)` = "müşterilerim"); app-mobile buyer bunun tersini ister:
müşteri borcunu **tüm satıcılar boyunca** görür. Aynı append-only ledger, farklı okuma yönü
(`WHERE customer_id=?`, satıcıya göre grupla). Yeni buyer-scoped repo metodları:
`observeMyDebtsBySeller` (→ `SellerDebt(sellerId, shopName, balanceMinor)` projeksiyonu),
`observeMyTransactions(userId, sellerId)`, `observeMyBalanceWithSeller`, `observeMyTotalDebtMinor`.
`claimCustomerForUser` bu turda GERÇEKTEN kodlandı (app-pos'ta `TODO`'ydu): login'de o
numaralı UNCLAIMED Customer'lar CLAIMED + `claimedByUserId` bağlanır (eski borç devralınır).

**Bekleyen onay — mock sınırı dürüst:** app-mobile ayrı APK, app-pos'un FakeRepository'sini
GÖREMEZ + backend yok → "POS istek attı" durumu app-mobile'ın KENDİ repo'sunda simüle
edildi: `PendingApproval(id, sellerId, shopName, buyerUserId, amountMinor, type, ...)` +
`observePendingApprovals` + `approvePending`/`rejectPending`. Seed'de 1 bekleyen onay
(demo'da kart görünsün). Onaylanınca → `addTransaction` (tek yazma noktası, app-pos'un
"OTP sonrası tek yerde yaz" deseninin buyer karşılığı) + pending kaldırılır. Reddedilince
→ pending kaldırılır, yazma yok.

**Ödeme başlatma (buyer initiator):** müşteri detayında **[Ödeme Yap]** → tutar dialog'u →
`initiatePayment` → PAYMENT yazılır (bakiye canlı düşer). Docs "hem POS hem müşteri
başlatabilir"e uyar; backend gelince "POS'a onay gider" olacak (TODO FAZ 4/5). app-pos'un
keypad+saleFlow'u yerine basit dialog — buyer ödemesi için yeterli, overengineering yok.

**Yapılanlar (dosya seviyesinde):**
- **Gradle:** `libs.versions.toml`'a navigation+safeargs+recyclerview+fragment/activity/
  viewmodel-ktx eklendi; `app/build.gradle.kts` viewBinding zaten açıktı, safeargs plugin +
  `implementation(project(":core-domain"))` eklendi; `settings.gradle.kts` include(":core-domain").
  app-mobile zaten XML durumundaydı (Compose değil) — dönüşüm gerekmedi.
- **`:core-domain` KOPYASI:** app-pos/core-domain → app-mobile/core-domain (build.gradle.kts
  dahil). **Paket `com.example.app_pos.model` KORUNDU** → app kodu `com.example.app_mobile.*`
  ama modelleri `app_pos.model`'den import eder (mock-pos deseni; import satırı değişmez).
  `:core-domain:build` izole ✓ (saf JVM).
- **data:** `FakeRepository` (buyer-scoped, iki satıcılı seed: u_owner "Ahmet Bakkal" +
  u_market "Ayşe Market", u1 signed-in buyer iki dükkana borçlu), `OtpService` (sign-in OTP
  mock), `util/{PhoneFormat,MoneyFormat}` kopya. `SellerDebt`/`PendingApproval` = buyer-tarafı
  read projeksiyonları (repo yanında, domain değil).
- **UI (hepsi app-pos idiomu):** `MainActivity` session-gate startDestination (CREDIT
  handoff makinesi ÇIKARILDI — POS'a özel) + inner-nav up routing; `ui/login` (register =
  ALICI, isSeller=false; login → claim); `ui/dashboard/DashboardFragment` iç NavHost +
  bottom-nav (Borçlarım/Onaylar/Profil); `ui/debts` (SellerDebt liste); `ui/sellerdetail`
  (geçmiş+filtre+Factory VM+ödeme dialog); `ui/approvals` (Onayla/Reddet kart); `ui/profile`
  (isim/telefon/email/roller + "Satıcı ol" placeholder + logout). nav_graph + dashboard_graph
  + bottom_nav_menu + 3 vektör ikon + tema (Material3 DayNight, teal — POS'un fixed-dark'ından
  farklı, tüketici app'i).

**Öğrenilenler:**
- **Buyer okuması = seller okumasının simetriği.** Aynı ledger'ı iki client iki yönden
  okuyor; contract yazılınca (sonraki adım) bu iki gerçek ihtiyaç (seller-scoped +
  buyer-scoped endpoint) görülmüş olacak — sıralamanın (app-mobile önce) amacı buydu.
- **Ayrı APK sınırını dürüst modelle.** "POS'tan gelen onay" gerçekte backend'den gelir;
  backend yokken bunu app-mobile'ın kendi mock'unda `PendingApproval` seed'iyle taklit etmek,
  gerçek mimariyi (poll → onayla → tek yazma) bozmadan gösterilebilir demo verir.
- **Paketi koruyarak kopyalamak = sıfır import düzenlemesi.** `:core-domain` `app_pos.model`
  paketinde kaldı; app kodu farklı pakette ama modelleri sorunsuz import etti.

**Doğrulama:** `:core-domain:build` ✓, `:app:assembleDebug` ✓ (tek uyarı: MoneyFormat'taki
kopyalanmış `Locale("tr","TR")` deprecation — app-pos'la aynı, bu turla ilgisiz). **Cihaz
testi:** APK kuruldu ama MIUI "USB'den yükle" kısıtı `INSTALL_FAILED_USER_RESTRICTED` verdi
(Tur 2 dersi — build sorunu değil, cihaz ayarı). Kullanıcı cihazda izin verince uçtan uca:
register → borçlarım (2 satıcı) → satıcı detayı → Ödeme Yap → Onaylar (seed) → onayla →
profil (roller, email düzenle) → logout.

**Sıradaki:** (opsiyonel) seller dikeyi app-mobile'da; sonra **shared-contracts/openapi.yaml**
— iki client'ın gerçek ihtiyacı görüldü (seller-scoped + buyer-scoped ledger okuma, pending
approval endpoint, user/auth). Sonra FAZ 3 (Room).

### 2026-07-29 — Tur 18: app-mobile Token mavi palet + görsel iyileştirme + demo seed düzeltmesi

Cihaz testi geri bildirimi üzerine üç iş (kullanıcı: "çok iyi olmuş").

**1) Token mavi, sabit-koyu palet:** app-mobile'a Tur 17'de teal palet konmuştu; Token
şirketinin mavi tonlarına (`primary #4C8BFF`, app-pos'ta zaten var) taşındı. Karar:
app-mobile app-pos'un **sabit-koyu** fintech paletini kullansın (iki app görsel olarak
birebir tutarlı). `colors.xml` app-pos'unkiyle değiştirildi (aynı isimler → layout
referansları kırılmadı), `themes.xml` `Theme.Material3.DayNight` → `Theme.Material3.Dark`
+ app-pos'un color-token eşlemesi + `ThemeOverlay.Appmobile.ActionBar` +
`Widget.Appmobile.BottomNav` (+ActiveIndicator), `res/color/bottom_nav_item.xml` kopyalandı.
Layout'lar tema attribute'ları (`?attr/colorOnSurface` vb.) kullandığı için otomatik uydu.

**2) app-pos profil kartı görseli (fonksiyon değişmedi):** app-mobile'ın beğenilen kart
düzeni (label üstte + değer altta + hairline `ProfileDivider`) app-pos `fragment_profile.xml`e
taşındı. **Tüm view id'leri korundu** → ProfileFragment/ViewModel HİÇ değişmedi (id değişse
ViewBinding derleme hatası verirdi — güvenlik ağı). `ProfileDivider` stili app-pos
`styles.xml`e + `profile_name_label` string'i eklendi. **Login layout'ları zaten birebir
aynıydı** (diff sadece yorum/tools:text) → app-pos login'de değişiklik gerekmedi; kullanıcının
gördüğü fark palet+tema kaynaklıydı.

**3) Demo seed düzeltmesi (kök neden seed, kod değil):** bekleyen onay yalnız `u1`'e bağlıydı;
herkesin bildiği `05554443322` = `u_owner` (satıcı+alıcı) ile girişte onay/borç görünmüyordu.
`observePendingApprovals` filtresi DOĞRU çalışıyordu — seed yanlış hesaba bağlıydı. Fix:
`u_owner`'a Ayşe Market'te bir alıcı customer kaydı (`o1`) + ledger (borç 60 TL) + kendi
bekleyen onayı (`p2`, Ayşe Market 75 TL) eklendi. `u1` korundu. Artık **iki numarayla da**
girişte borç + onay görünür. `observeMyDebtsBySeller`/`observeMyTransactions` zaten
`claimedByUserId == userId` ile çalıştığından kod değişmedi.

**Kısayol:** `~/.zshrc`'ye `mobilebuild` eklendi (posbuild/mockbuild deseni: app-mobile
derle + `adb install -r`).

**Öğrenilenler:**
- **Boş liste bug'ı = önce seed'i şüphelen.** Filtre kodu doğruyken "hiç görünmüyor" çoğu
  kez veri-senaryo uyuşmazlığıdır; herkesin test ettiği hesabın (05554443322) seed'de
  ilgili verisi yoksa "çalışmıyor" görünür.
- **Görsel refactor'da id koru = fonksiyon dokunulmaz.** Layout'u tümden değiştirip tüm
  id'leri sabit tutmak, Fragment/VM'e hiç dokunmadan yeni görünüm verir; kırılırsa derleme
  hatası (sessiz değil).

**Doğrulama:** `mobilebuild`/`posbuild` ile cihazda (kullanıcı çalıştırdı) — mavi palet ✓,
app-pos profil yeni görünüm ✓, 05554443322 girişte borç+onay ✓. Regresyon yok (buyer akışları
+ app-pos veresiye/ödeme değişmedi).

**Sıradaki:** Tur 19 — app-mobile SATICI dikeyi ("Satıcı ol" + müşteri listesi/detay +
onaya-gönder ApprovalService + pairing + dinamik sekme).

### 2026-07-29 — Tur 19: app-mobile SATICI (seller) dikeyi + ApprovalService (onaya-gönder)

app-mobile artık tek app, iki rol: buyer (varsayılan) + "Satıcı ol" ile seller. FAZ 6'nın
seller yarısı. Tümü mock, buyer tarafı bozulmadan ADDITIVE.

**Kullanıcı kararları:** satıcı defteri = kendi userId'si (sellerId = userId); ekranlar =
Müşterilerim (recyclerview + toplam alacak + arama/filtre) + müşteri detayı; veresiye/ödeme
yazma = **popup** (keypad/saleFlow YOK, buyer'daki gibi aynı asset); sekme **DİNAMİK**
(satıcı olunca "Müşterilerim" eklenir); pairing DAHİL (numara-eksenli mock); server onay
mock'u EKLENDİ.

**En önemli parça — ApprovalService (onaya-gönder, tek yazma korunur):** önceden
`initiatePayment`/`approvePending` DOĞRUDAN ledger'a yazıyordu (sadece TODO yorumu vardı).
Artık gerçek bir mock onay yolu var: yeni `data/ApprovalService.kt` (OtpService deseni,
suspend `requestApproval`). Repo'da `requestApproval(fromUserId, sellerId, customerId,
amount, type, description)`: hedef müşteri **CLAIMED** ise (app'li) o kullanıcının
`buyerUserId`'sine `PendingApproval` DÜŞER (Onaylar sekmesi); **UNCLAIMED** ise (app'siz)
OtpService mock true → **anında yazılır** (docs'un app-push/SMS ayrımı). Böylece satıcı
veresiye yazınca müşteri onayından geçer (docs: her DEBT/PAYMENT onaydan geçer). **Buyer
`initiatePayment` de bu yola taşındı** → iki yön simetrik, tek yazma noktası (approvePending
sonrası veya app'siz anında). `initiatePayment`/`pay` artık suspend → viewModelScope.launch.

**Repo seller yüzeyi (app-pos'tan port):** `observeCustomers(sellerId)`,
`observeTransactions(sellerId, customerId)`, `observeTotalReceivableMinor(sellerId)`,
`addCustomer`, `findCustomerById/ByPhone`, `customerPhoneExists`, `setSeller`,
`updateShopName`, `pairWithApp()` + `isPairedWithApp: StateFlow`. **`RawCustomer.toCustomer`
gerçek bakiye türetir oldu** (`balanceOf`; eskiden hardcoded 0 — buyer claim'i için nullable
sellerId dalı korundu). Seed: `u_owner` defterine (sellerId="u_owner") c3 (app'li) + c4/c5
(UNCLAIMED, app'siz onay dalı için) müşteri + t9-t13 ledger. `logout` pairing'i sıfırlar.

**Seller ekranları (app-pos idiomu, paket app_mobile):** `ui/customers/` (Fragment+VM+
Adapter; VM `observeCurrentUser().flatMapLatest { observeCustomers(user.userId) }`),
`ui/customerdetail/` (Fragment+VM; Factory ile customerId; **[Veresiye Yaz]/[Ödeme Al] =
popup** → `requestApproval`; feedback claim'e göre "Onaya gönderildi"/"Deftere yazıldı").
Buyer'ın `sellerdetail/TransactionAdapter`'ı yeniden kullanıldı. Layout'lar app-pos'tan
kopya (`fragment_customers`, `item_customer`, `fragment_customer_detail`; `item_transaction`
zaten vardı).

**Profil + pairing + dinamik sekme:** `ProfileViewModel` `combine(observeCurrentUser,
isPairedWithApp)` → `ProfileUiState(user, isPaired)`. "Satıcı ol" Toast yerine dükkan-ismi
dialog → `setSeller` → `isSeller=true`; satıcı olunca profilde dükkan satırı + pairing kartı
(NotPaired → eşleştir → Ready) belirir, "Satıcı ol" butonu gizlenir. `PairingFragment/VM`
(app-pos deseni, tek-onay mock). `DashboardFragment` `observeCurrentUser` collect edip
`isSeller` olunca bottom-nav menüsünü `bottom_nav_menu_seller.xml`e (4 sekme: Borçlarım/
Müşterilerim/Onaylar/Profil) çevirir + setupWithNavController'ı tekrar bağlar (isSellerMenu
guard ile tek seferlik). `dashboard_graph`e customersFragment/customerDetailFragment/
pairingFragment + action'lar eklendi.

**Öğrenilenler:**
- **Tek app iki rol = tek repo iki okuma yönü.** Buyer (`observeMyDebtsBySeller`) ve seller
  (`observeCustomers`) aynı ledger'ı iki yönden okur; her ikisi de ADDITIVE, çakışmaz.
- **Onay yolu mimariyi taşır.** "Doğrudan yaz"ı `requestApproval`a çevirmek, backend gelince
  sadece ApprovalService gövdesinin değişeceği doğru şekli verir; CLAIMED/UNCLAIMED dalı
  app-push/SMS ayrımının mock'u. Tek yazma noktası korunur.
- **Dinamik bottom-nav = menüyü değiştir + setupWithNavController'ı tekrar çağır.** Guard
  olmadan her re-emit seçili sekmeyi sıfırlar.

**Doğrulama:** kod yazıldı; **cihaz build'i kullanıcıda** (`mobilebuild`). Beklenen: 05554443322
(satıcı) → Müşterilerim sekmesi → liste + toplam alacak → müşteri detayı → [Veresiye Yaz]
popup → c3 (app'li) için onaya gider / c4-c5 (app'siz) anında yazılır → pairing kartı →
eşleştir → Ready. Yeni numarayla register → buyer → "Satıcı ol" → dükkan ismi → sekme belirir.
Buyer regresyon: Borçlarım/Onaylar/Profil aynı.

**Sıradaki:** kullanıcı feedback'i sonrası düzeltmeler; sonra shared-contracts/openapi.yaml
(iki client + iki rol ihtiyacı netleşti) → FAZ 3 (Room).

### 2026-07-30 — Tur 20: Token orderBody handoff (mock-pos=PGW taklidi) — Aşama 0

Handoff'u Token Sardis paymentgateway'in GERÇEK `orderBody` JSON formatına taşıdık. Eskiden
mock-pos app-pos'a basit `amount_minor: Long` extra gönderiyordu; artık PGW'nin bize attığı
`orderBody` (basketID + items[]) şeklini kullanıyor. **Default para-only**, ama items[] taşınıp
ileride ürün-bazlı veresiye/ödeme için veri hazır.

**Gerçek akış / karar:** sepet app'i barkod üretip sepeti PGW'ye → PGW bize (`orderBody`) devreder.
Bizde sepet app'i YOK → **mock-pos = PGW taklidi**, orderBody'yi doğrudan app-pos'a atar. mock-pos'a
mock sepet konuldu (istenirse sepetten, istenirse elle tutar). items ayrı Basket+BasketItem
tablolarında saklanacak (Aşama 3/Room); Transaction'a `basketId?` (para-only'de null).

**Yapılanlar:**
- `:core-domain` yeni `OrderBody.kt`: `OrderBody(basketId, createInvoice, documentType, isVoid,
  items)` + `OrderItem(name, price, quantity, taxPercent, sectionNo, status, type, limit)`. Saf
  (JSON'suz). **Ölçek tek yerde:** `price`=kuruş/birim, `quantity` ve `taxPercent` ×1000;
  `lineTotalMinor = price × quantity / 1000`, `totalMinor = Σ lineTotal`.
- app-pos `data/OrderBodyParser.kt`: org.json ile JSON→OrderBody (Android built-in, ekstra bağımlılık
  yok). Bozuk/eksik JSON → null (başka app'ten gelen kötü girdide crash yerine güvenli fallback).
  Wire format (`basketID`, alan adları) TEK yerde.
- mock-pos `MockBasket.kt` (PGW taklidi): `moneyOnly(amount)` = tek sentetik kalemli orderBody
  (toplam = girilen tutar → davranış birebir eski); `SAMPLES` = demo sepetleri; `toJson` PGW şekli.
- mock-pos `MainActivity`: VERESİYE **tıkla** = para-only (bugünkü akış); **uzun-bas** = mock sepet
  seç (AlertDialog). Extra `amount_minor` → `orderBody` (JSON). `FLAG_ACTIVITY_NEW_TASK` korundu.
- app-pos `MainActivity`: extra `orderBody` okunur → parse → `totalMinor()` = amountMinor →
  mevcut saleFlow yolu (`bundleOf("amountMinor" to ...)`) **aynen**. Login-bekleyen handoff artık
  JSON string saklar (items login sonrası da hayatta). `EXTRA_ORDER_BODY`/`KEY_PENDING_ORDER_BODY`.
- app-pos `SaleViewModel`: opsiyonel `orderBody: OrderBody?` alanı (yazım anında sepeti saklamak
  için; tam plumbing Aşama 3/Room'da write consume edince).

**Kritik hassasiyet korundu:** `isCreditHandoff`/`singleTask`/`onNewIntent`/saleFlow nested-graph
giriş kapısı DAVRANIŞÇA DEĞİŞMEDİ — sadece extra okuma satırı JSON'a döndü (Tur 10-11 dersleri).

**Öğrenilenler:**
- **Ölçekleri tek noktada gizle:** ×1000 quantity/taxPercent yalnızca `lineTotalMinor` + parser'da;
  gerisi typed OrderBody ile çalışır (float yok, para hep Long kuruş).
- **Ayrı APK = kod paylaşımı yok:** JSON *üreten* MockBasket mock-pos'ta, *parse eden* OrderBodyParser
  app-pos'ta; sabitler iki tarafta kopya (ileride shared-contracts `OrderBody` şemasına bağlanacak).
- **Handoff kırılganlığına dokunma:** pending değeri Long→JSON'a çevrilirken bile nav/handoff yolu
  bit-uyumlu bırakıldı; suçlu tek nokta (extra okuma) kalır.

**Doğrulama:** `:core-domain:build` ✓, app-pos `:app:compileDebugKotlin` ✓, mock-pos
`:app:compileDebugKotlin` ✓ (hepsi offline, uyarısız). **Cihaz testi BEKLİYOR** (kullanıcı):
`mockbuild` + `posbuild`; sonra (a) tutar gir → VERESİYE (para-only) → app-pos doğru toplam →
müşteri seç → onay → OTP → mock-pos'a dön; (b) VERESİYE'ye **uzun bas** → "Market sepeti" →
app-pos'ta toplam 107,00 TL görünmeli. Doğrudan `adb shell am start` ile de orderBody test edilebilir.

**Sıradaki:** Aşama 1 — `docs/api-and-schema-design.md` (endpoint + SQL tablo tasarımı, ONAY noktası)
→ Aşama 2 openapi.yaml + Prism → Aşama 3 Room (app-pos) → Aşama 4 Room (app-mobile).

### 2026-07-30 — Tur 21: Aşama 1 — API & DB tasarım dokümanları (ONAY noktası, kod yok)

openapi.yaml + Room yazılmadan ÖNCE tüm endpoint (tam body) + tablo (tam kolon) tasarımı. Kullanıcı
feedback'iyle iki dosyaya bölündü ve genişletildi:
- **`docs/api-endpoints.md`** — her endpoint'in alan-seviyesi request/response JSON'u (özet değil).
  Bölüm A (ŞU AN): auth/otp, user/profil, customer (seller-scoped), transactions (Idempotency-Key +
  opsiyonel basket), buyer-scoped (me/debts…), **approvals (ÜÇ HAT, yön alanlı)**. Bölüm B (ileri-faz):
  sync, PGW settle, insights, micro-credit, fx-rates, audit-log, devices.
- **`docs/db-schema.md`** — her tablonun tam kolon listesi + DDL (SQLite) + üç-temsil eşleme matrisi.
  Bölüm A: users, customers, transactions (+`basket_id?`, `settled_via_pgw`, `receipt_no?`), baskets,
  basket_items, **approvals** (approval_id, initiator_role, target_user_id, channel, status…).
  Bölüm B (kod iskeleti yazılacak, bağlama ertelenecek): outbox, fx_rates, credit_offers, audit_log, devices.
- Eski `api-and-schema-design.md` → iki yeni dosyaya yönlendirme (tek kaynak).

**Kullanıcı feedback'iyle netleşen kararlar (plan + memory'e işlendi):**
- **Üç onay hattı, tek `approvals` şeması:** (1) buyer-mobile→seller-POS, (2) seller-POS→buyer-mobile,
  (3) seller-mobile→buyer-mobile. Yön alanları: `initiator_role`, `target_user_id`, `channel`.
  Approval **app-pos'ta da** var (önceki taslakta yoktu).
- **Onay sonrası PGW = SADECE PAYMENT:** DEBT onayla biter (defter); PAYMENT onay sonrası POS→PGW
  (nakit/kart→fiş). Şema alanları (`settled_via_pgw`/`receipt_no?`) + endpoint (`/settle`) modellendi;
  gerçek `am start paymentgateway` intent'i (Aşama 0'ın TERSİ) FAZ 8.
- **İleri-faz tabloları KODA da eklenecek:** sadece dokümanda değil, Aşama 3'te Room entity+DAO+
  interface iskelesi olarak (sadece bağlama ertelenir). "Şu an gerekeni detaylı + geleceği taslak."
- **fx_rates = döviz kuru** (USD/EUR/altın, geriye dönük enflasyon/mikrokredi hesabı — tasarim.md son not).

**Doğrulama:** kod yok (tasarım turu); **kullanıcı onayı BEKLİYOR** (endpoint body'leri + tablolar).
Onaylanınca Aşama 2 (openapi.yaml + Prism).

**Sıradaki:** Aşama 2 — `shared-contracts/openapi.yaml` (bu iki dokümandan) + Prism mock → Aşama 3
Room (app-pos, ileri-faz iskele dahil) → Aşama 4 Room (app-mobile).

### 2026-07-30 — Tur 23: Aşama 3 — app-pos `:core-data` (Room) — FakeRepository → kalıcı Room (FAZ 3)

`FakeRepository` (RAM) → Room tabanlı kalıcı `RoomRepository`. UI/ViewModel mimarisi korundu
(MVVM ödülü): DAO Flow'ları FakeRepository StateFlow'larıyla aynı davrandığından ViewModel'lerin
İÇ MANTIĞI değişmedi — sadece `FakeRepository.x` → `repo.x` (repo = Repository interface).

**Modül + altyapı (AGP 9 + KSP + Room):**
- Yeni `:core-data` (`com.android.library`) modülü: Room 2.7.1 + KSP `2.2.10-2.0.2` (Kotlin'e
  kilitli) + coroutines. Bağımlılık: `:app → :core-data → :core-domain`. schemaLocation export.
- **İki AGP 9 tökezlemesi çözüldü** (memory'e kaydedildi): (1) `android.disallowKotlinSourceSets=
  false` (built-in Kotlin, KSP'nin kotlin.sourceSets kullanımını yasaklıyordu); (2) KSP versiyonu
  `<kotlin>-<ksp>` formatında olmalı. Root'a android-library + ksp plugin (apply false).

**Room katmanı (:core-data):**
- Entity'ler: `UserEntity` (SellerInfo düz shop_* kolonları), `CustomerEntity`, `TransactionEntity`
  (+basketId?/settledViaPgw/receiptNo?), `BasketEntity`, `BasketItemEntity`, `ApprovalEntity` (üç-hat
  alanlı). + İLERİ FAZ iskele (aynı dosyada yorum bloğu): Outbox/FxRate/CreditOffer/AuditLog/Device.
- DAO'lar: FakeRepository'nin her observe/find karşılığı, Flow döner. Append-only: sadece @Insert
  (IGNORE = idempotency), balance = SQL SUM (saklanmaz). Telefon digit-normalize SQL'de (REPLACE).
- `AppDatabase` (v1, 11 entity), `Mappers.kt` (Entity↔Domain, SellerInfo topla/düz, OrderBody→Basket),
  `RoomRepository : Repository`, `RepositoryProvider` (singleton, DI yok), `SeedCallback` (ilk açılışta
  u_owner + c1-c5 + t1-t10 seed → bakiyeler FakeRepository ile birebir aynı).
- `:core-domain`: `Repository` interface (iki impl'in ortak kontratı) + `balanceOf` saf fonksiyon
  (domain'e taşındı) + coroutines-core (Flow tipi için; hâlâ saf JVM, Android importu yok).

**:app bağlama (13 dosya):**
- Yeni `App : Application` → `RepositoryProvider.get(this)` (Room'u bir kez kurar); manifest `.App`.
  ViewModel'ler `RepositoryProvider.instance` (no-arg accessor) ile eriştir. `:app` → `:core-data` dep.
- **Senkron→suspend gerginliği çözüldü** (kullanıcı kararı: "provider + interface, gerekli yerde
  suspend"): DB yazan metodlar suspend (çoğu zaten viewModelScope.launch içinde). Senkron kalan
  session (`isSessionValid`/`currentSellerId`) RAM'de → interface'te sync kaldı. Fragment'taki iki
  senkron okuma reaktife çevrildi: `PhoneFragment.customerPhoneExists` (launch), `OtpFragment.hasApp`
  (cache + resolveHasApp suspend), `CustomerDetailFragment.phone` (VM'e StateFlow olarak taşındı).
- `OtpViewModel.verifyAndWrite` (tek yazma noktası) `orderBody` parametresi aldı → sepet handoff'unda
  basket+items da yazılır (Aşama 0'ın Room karşılığı; para-only'de null).
- `FakeRepository.kt` SİLİNDİ (artık ölü kod; git'te duruyor). RoomRepository tek impl.

**Öğrenilenler:**
- **MVVM ödülü gerçek:** reader VM'lerde SADECE `FakeRepository.` → `repo.` (Flow imzaları aynı);
  iç mantık/StateFlow zinciri değişmedi. Senkron→suspend sadece yazan/tekil-okuyan yerlerde iş çıkardı.
- **Session RAM'de kalmalı:** `isSessionValid` DB I/O değil (mock token); interface'te sync tutmak
  MainActivity.onCreate'in graf-öncesi start-destination seçimini bozmadan bıraktı.
- **Room DAO'da digit-normalize:** telefon eşleşmesi SQL `REPLACE(...)` ile (FakeRepository'nin
  Kotlin filter'ının karşılığı) — lookup her formatta çalışır.

**Doğrulama:** `:core-domain:build` ✓, `:core-data:assembleDebug` ✓ (Room codegen — tüm @Query SQL +
entity ilişkileri geçerli), `:app:compileDebugKotlin` ✓ (13 bağlanan dosya + App). **NOT:**
`:app:assembleDebug` sandbox'ta `jlink`/JdkImageTransform ortam hatası veriyor (VSCode Red Hat Java
JRE'sinde jlink yok — KOD BUGI DEĞİL; library modül tetiklemez, application modül tetikler; memory'de).
Tam APK = kullanıcının `posbuild`'i (Android Studio JBR). **Cihaz testi (kullanıcı):** `posbuild`;
app KAPANIP AÇILINCA veri KALICI (RAM sıfırlanmıyor — FAZ 3'ün asıl kazanımı); mevcut akışlar (login,
veresiye mock-pos→onay→OTP→yaz, ödeme, yeni müşteri, detay, profil) aynı davranmalı; orderBody sepet
handoff'unda basket_items dolmalı. Bir bug: build sonucunu grep ile doğrula (`| tail` pipe exit code'u
gizler — memory'de).

**Sıradaki:** Aşama 4 — app-mobile `:core-data` (Room kopyası) + buyer/seller okumalar + claim +
`PendingApproval` üç-hat alanlarıyla genişletme (davranış aynı, mock değerleri türetir).

### 2026-07-30 — Tur 22: Aşama 2 — shared-contracts/openapi.yaml + Prism mock (A tamamlandı)

Contract yazıldı, lint temiz, Prism mock seed-hizalı yanıt veriyor. **Kritik: app-mobile
FakeRepository'si TAM okundu** (önceki turlarda docs'tan planlamıştım; kullanıcı "iki repo farklı"
diye uyardı — doğruydu, iki seed ve approval yüzeyi ayrışıyor).

**Yapılanlar:**
- `shared-contracts/openapi.yaml` (OpenAPI 3.0.3): tüm ŞU AN endpoint'leri (auth/otp, user, customer,
  transactions+Idempotency-Key+opsiyonel basket, buyer-scoped, approvals) + `future` tag'li ileri-faz
  (sync, settle, insights, credit-offers, devices). Şemalar: OrderBody/OrderItem, Transaction
  (+settled_via_pgw/receipt_no), Customer, User (+SellerInfo), Balance, Approval, SellerDebt, enum'lar.
- `shared-contracts/README.md`: Prism kullanımı + seed açıklaması + lint + codegen notu.
- **Example'lar TEK BİRLEŞİK seed'e göre** (iki repo kapsanır): schema-level (canonical) +
  response-level (customers→c1-c5, me/debts→Ayşe 100+Ahmet 40, approvals→p1/p2, transactions→c1 geçmişi).
  Değerler FakeRepository kodundan alındı.
- **Approval şeması uzlaştırıldı:** app-mobile'ın GERÇEK `PendingApproval` alanları (`shop_name`,
  `requested_at`) + ileri üç-hat alanları (`initiator_role`/`target_user_id`/`channel`). db-schema.md
  + api-endpoints.md senkronlandı. (Üç-hat davranışı + app-pos Onaylar UI = AYRI tur, Tur 21 kararı.)

**Öğrenilenler:**
- **Prism modu TERS SEZGİLİ:** `mock openapi.yaml` (flag YOK) = static/example (yaml example'larını
  döner). `-d`/`--dynamic` = şemadan RASTGELE üretir, example'ları YOKSAYAR. Örnekleri görmek için
  `-d` KOYMA. (İlk denemede `-d` ile gibberish geldi; flag'siz seed-hizalı çıktı.)
- **Example = spec'in bedava yan ürünü (kullanıcı önerisi):** ayrı altyapı değil; zaten yazılan
  spec'e örnek eklemek Prism'i gerçekçi demo'ya çevirir (rastgele yerine seed). Codegen'de de örnek olur.
- **Docs'tan planlama ≠ koddan planlama:** app-mobile seed'i (u_market, m1/o1, t1-t13, u1@Ayşe=100)
  app-pos'tan (c1-c5 tek satıcı) farklıydı; contract/Room için GERÇEK repo okunmalı. `nullable`+`allOf`
  OpenAPI 3.0'da `type: object` ister (Redocly nullable-type-sibling); flow-YAML'da parantez/virgül
  description'ı bozar → block style.

**Doğrulama:** `npx @redocly/cli lint` → 0 error (55 warning stil). Prism (default mode) →
`/customers` 5 müşteri doğru bakiyeli, `/me/debts` iki dükkan, `/balances?customer_id=c1`=4000 ✓.
**Kullanıcı testi:** `npx @stoplight/prism-cli mock shared-contracts/openapi.yaml` + curl'ler
(README'de). Retrofit bağlantısı = FAZ 4 (bu turda değil).

**Sıradaki:** Aşama 3 — app-pos `:core-data` (Room): entity/DAO/mapper/RoomRepository (ŞU AN
bağlanan: users/customers/transactions/baskets/basket_items/approvals-iskele) + ileri-faz iskele
(outbox/fx_rates/credit_offers/audit_log/devices — entity+DAO+interface, bağlama yok). Sonra Aşama 4.

### 2026-08-05 — Tur 24: Aşama 4 — app-mobile `:core-data` (Room) + müşteri ekleme (3-dal) + sıralama bug'ı

app-mobile `FakeRepository` (RAM) → kalıcı Room. app-pos Tur 23'ün deseni birebir kopyalandı;
üç bilinçli fark: **approvals AKTİF** (app-pos'ta iskele), **buyer-scoped okumalar** var,
**OrderBody/basket yok** (bu tarafta PGW handoff yok — tablolar yine de şema eşliği için duruyor).

**Modül + domain:**
- Yeni `:core-data` (`com.android.library` + KSP). `libs.versions.toml`'a room/ksp/coroutines +
  **`android-library` plugin alias'ı** (app-mobile'da yoktu). `gradle.properties`'e
  **`android.disallowKotlinSourceSets=false`** (AGP 9 + KSP dersi; app-mobile'da eksikti).
- `:core-domain` (artık coroutines-core'a bağlı — `Flow` kontrat için): yeni `Repository`
  interface, `Ledger.kt` (`balanceOf` — iki projede ayrışmıştı, kapandı), `CustomerLookup`
  sealed tip, ve `FakeRepository`'den TAŞINAN `SellerDebt` + `PendingApproval`.
- İsim farkı bilinçli: `currentUserId()` (app-pos'ta `currentSellerId()`) — burada kullanıcı
  iki rolde olabilir, 5 çağıran zaten bu adı kullanıyordu.

**Room katmanı:** 11 entity (app-pos'la aynı), DAO'lar + app-mobile'a özel sorgular:
`observeDebtsBySeller` (**LEFT JOIN users** ile shopName + GROUP BY SUM — buyer ana ekranı,
tek round-trip), `observeForBuyerSeller`, `observeBuyerTotalDebt/BalanceWithSeller`,
`customerIdForBuyerSeller` (fallback YOK — geçen turun düzeltmesi korundu), `claimedBy`.
`SeedCallback`: 4 user / 6 customer (u1'in İKİ kaydı: c1+m1 — çok-dükkan vakası) / 13 tx /
2 approval. `ApprovalService` `:app`'ten `:core-data`'ya taşındı (bağımlılık yönü zorunluluğu).

**:app bağlama:** yeni `App.kt` + manifest `android:name=".App"` (**yoktu** — olmadan
`RepositoryProvider.instance` ilk ViewModel'de patlar). 8 ViewModel'e `repo` alanı; suspend
yazmalar `viewModelScope.launch` ile sarıldı (ApprovalsVM approve/reject, ProfileVM 6 nokta,
LoginVM `signIn` → `suspend`). `MainActivity` login gate'i **hiç değişmedi** (session RAM'de →
`isSessionValid()` sync kaldı). Tek yapısal değişiklik: `CustomerDetailFragment`'ın
`by lazy { findCustomerById }` bloğu → VM'de `phone`/`isClaimed` StateFlow (suspend barındıramaz).
`ProfileViewModel.currentUserId()` artık `repo.currentUserId()` okuyor — `uiState.value`
`WhileSubscribed` yüzünden null olabiliyordu (latent bug, Room'la büyürdü).

**Müşteri ekleme (b·3, YENİ):** Müşterilerim'e FAB → isim+telefon dialogu → `lookupCustomerForSeller`
3 dalı: **New** → kayıt açılır → detaya git; **KnownToOtherSeller** → "sistemde X adına kayıtlı"
onayı → mevcut kayıt kullanılır → detaya git; **AlreadyMine** → inline hata, dialog açık kalır.
İlk veresiye detaydaki mevcut popup'la yazılır — o an listede belirir (sahiplik ledger'da).

**GERÇEK BUG — tarih sıralaması (her iki projede düzeltildi):** `createdAt` = `"dd.MM.yyyy HH:mm"`;
`ORDER BY createdAt` lexicographic → **önce GÜNE** bakıyor. `05.08.2026` , `20.07.2026`'nın
ALTINA düşüyordu. Tüm seed Temmuz olduğu için görünmüyordu. Çözüm (DAO-only, şema değişmez):
`ORDER BY substr(createdAt,7,4)||substr(createdAt,4,2)||substr(createdAt,1,2)||substr(createdAt,12)`.
**app-pos'a da geri taşındı** (kullanıcı kararı) — iki proje ayrışmasın.

**Öğrenilenler:**
- **Silinecek dosyanın barındırdığı public tipleri ÖNCE taşı.** `SellerDebt`/`PendingApproval`
  `FakeRepository.kt` içinde top-level'dı; 4 UI dosyası import ediyordu. Domain'e taşımadan
  silmek cleanup adımını kırardı.
- **Yorumlarda sınıf adı geçiyorsa toplu sed onları da bozar.** `FakeRepository.` → `repo.`
  dönüşümü `OtpService` KDoc'unu bozdu; derleyici yakalamaz, elle kontrol gerekti.
- **Onay satırını silme, status'ünü değiştir.** Bekleyen sorgusu zaten filtreliyor → kullanıcıya
  aynı, denetim izi bedava. `PendingApproval.customerId` (geçen tur eklendi) burada şemaya oturdu.
- **`stateIn(WhileSubscribed)` senkron okuma için güvenilmez:** kimse collect etmiyorken `null`.
  Session gibi RAM state'i doğrudan repo'dan okumak doğrusu.

**Doğrulama:** app-mobile `:core-domain:build` ✓ + `:core-data:assembleDebug` ✓ (Room KSP tüm
@Query SQL'ini — join/subquery/substr dahil — derleme zamanında doğruladı) + `:app:compileDebugKotlin` ✓.
app-pos regresyon ✓ (üç modül). Tek uyarı: `MoneyFormat`'ta eski `Locale` (bu turla ilgisiz).
**Cihaz testi BEKLİYOR** (`mobilebuild`): (1) kalıcılık — force-stop sonrası oturum gider ama
ledger/profil kalır; (2) buyer u1 → Borçlarım iki satır (Ayşe 100 + Ahmet 40 = 140); (3) onay →
bakiye 90'a çıkar; (4) seller u_owner → Müşterilerim 3 kişi, toplam 235,50; Fatma (app'siz) anında
yazılır / Mehmet (app'li) onaya gider; (5) FAB 3 dalı; (6) cihaz tarihini 05.08.2026 yapıp yeni
kayıt → geçmişte EN ÜSTTE görünmeli (sıralama fix'i).

**Sıradaki:** FAZ 4 — `:core-network` (Retrofit + DTO + AuthInterceptor) + outbox/WorkManager sync
(tablo hazır, bağlama yok) + Prism'e karşı uçtan uca test. DI kararı (Hilt vs Factory) burada
gerekli olacak — bağımlılık grafiği büyüyor.

### 2026-08-05 — Tur 24b: Onay YÖNÜ bug'ı (cihaz testinde bulundu)

**Bug (kullanıcı buldu):** `0555 444 3322` ile Ayşe Market'e ödeme yapılınca onay **kendi
kutusuna** düştü. Kök neden: `requestApproval` onaylayanı her zaman `row.claimedByUserId`
(müşteri kaydının sahibi) olarak seçiyordu. Satıcı→alıcı yönünde doğru, ama **alıcı ödeme
başlattığında** `customerId` zaten alıcının kendi kaydı → onay başlatana geri dönüyordu.

**Düzeltme:** onaylayan yöne göre belirleniyor —
`if (fromUserId == sellerId) row.claimedByUserId else sellerId`. Yani kural artık kodda açık:
**isteği başlatan onaylamaz, karşı taraf onaylar.**

**Yan düzeltmeler (aynı kök nedenin izleri):**
- `PendingApproval.buyerUserId` → **`approverUserId`**. Eski ad "alıcı onaylar" varsayımını
  taşıyordu; artık her iki taraf da onaylayan olabilir.
- `PendingApproval.shopName` → **`counterpartyName`**, ve `requestApproval` onu yöne göre
  dolduruyor (satıcı başlattıysa dükkan adı, alıcı başlattıysa müşteri adı). Aksi halde
  satıcının kutusundaki kartta **kendi dükkan adı** yazıyordu — kimin ödediği belirsizdi.

**Öğrenilenler:**
- **Yön alanını veriden türetme, açıkça sor.** "Onaylayan = müşteri kaydının sahibi" tek yön
  için doğruydu; iki yönlü akışta sessizce yanlış oldu. `initiator == seller?` karşılaştırması
  kuralı okunur kılıyor. Şema (`db-schema.md` A.6) `initiator_role` ile bunu zaten öngörmüştü.
- **Alan adı yanlış varsayımı taşır:** `buyerUserId` adı, kodun onu "onaylayan" olarak
  kullandığını gizliyordu. Yeniden adlandırma bug'ın tekrarını zorlaştırır.

**Doğrulama:** üç modül ✓. **Cihaz testi:** `docs/test-hesaplari.md` → "Onay YÖNÜ" bölümü
(alıcı ödeme yapar → onay SATICIYA düşer, kendine değil).

**Ayrıca:** `docs/test-hesaplari.md` yazıldı — seed hesapları, hangi numara ne işe yarar,
senaryo→numara eşlemesi, iki app'in ayrı seed'i, checklist.

### 2026-08-05 — Tur 25: app-mobile UI/UX okunabilirlik + iki format bug'ı

Cihaz testi sonrası kullanıcı geri bildirimi: ekranlarda kim alıcı kim satıcı belli olmuyor,
Onaylar'da hangi rolde olduğun ve paranın yönü okunmuyor, seed'de kişi adı = dükkan adı.

**İSİM AYRIMI — kod suçsuzdu, sorun seed'deydi.** `User` zaten `displayName` (kişi) +
`sellerInfo.shopName` (dükkan) ayırıyor ve HER ekran doğru alanı çekiyordu; seed'de
`u_owner` için ikisi de "Ahmet Bakkal" olduğu için yanlış alanı gösteren bir ekran fark
edilemezdi. Seed ayrıştırıldı (kişi "Ahmet Demirtaş" / dükkan "Ahmet Bakkal", "Ayşe Korkmaz" /
"Ayşe Market"), `o1` müşteri kaydı kişi adına çevrildi. **Üretim kodu değişmedi.**

**İKİ FORMAT BUG'I (ekran görüntüsünde görünüyordu):**
- `toTlString()` negatifte çift eksi basıyordu (`-756.228,-50 TL`). Kotlin'de `/` ve `%` sıfıra
  doğru kırptığı için kalan negatif oluyor. İşaret baştan ayrılıp `abs` üzerinden formatlandı.
  Fazla ödemede bakiye negatife düştüğü için ULAŞILABİLİR bir yol. **İki app'te de** düzeltildi
  (MoneyFormat gerçek kopya).
- `Locale("tr","TR")` deprecated → `Locale.forLanguageTag("tr-TR")` (derleme uyarısı gitti).

**RENK SİSTEMİ — dört ton (kullanıcı kararı):** yön = **cep testi** (değer bana geliyorsa
yeşil, çıkıyorsa kırmızı); yoğunluk = **veresiye soluk** (defter kaydı, para hareket etmedi) /
**ödeme doygun** (gerçek para). `colors.xml`e error/accent'in 8 alfa varyantı (yeni renk tonu
YOK), `styles.xml`e `Widget.Appmobile.DirectionCard` + `.Debt`/`.Credit`.

> Kullanıcının ilk ifadesi ("satıcı olarak veresiye yazıyorsam kırmızı") cep testiyle
> çelişiyordu — soruldu, cep testi seçildi: satıcı için veresiye yazmak alacağın artması.

**Ekran rol rengi:** Borçlarım/Müşterilerim'in üst toplam bloğu MaterialCardView'a alındı
(kırmızı/yeşil kenarlık + hafif tint). ViewBinding id'leri korunduğu için **Fragment'larda
sıfır Kotlin değişikliği**.

**ONAYLAR YENİDEN YAZILDI — monorepo'nun İLK çok-tipli adapter'ı:**
- Yeni `ApprovalListItem` sealed (Header/Card) + `ApprovalTone` enum (4 ton).
- `ApprovalsViewModel` → `StateFlow<List<ApprovalListItem>>`: `partition { sellerId == userId }`
  ile iki bölüm, ton + karşı taraf telefonu VM'de çözülür (adapter saf renderer).
- `ApprovalAdapter` → `getItemViewType` + iki VH. ConcatAdapter ELENDİ: iki bölüm için 4
  adapter + fragment'ta imperatif boş-bölüm mantığı gerekirdi; sealed'da tek `submitList`,
  DiffUtil header'ları da yönetir.
- Kart telefonu: **şema değişmeden** VM lookup (`findCustomerById` / yeni `shopPhoneOf`).

**Dükkan telefonu (satıcı detayı):** `Repository.shopPhoneOf` eklendi (yeni nav arg DEĞİL —
`CustomerDetailViewModel.phone` deseni: suspend lookup → StateFlow). Adım 6 ve 9 aynı metodu
paylaşıyor.

**Öğrenilenler:**
- **Seed'de iki alan aynıysa hangi alanın gösterildiği test edilemez.** Demo verisini bilinçli
  ayrıştırmak, yanlış-alan bug'ını görünür kılar. Kod düzeltmesi gerekmedi — teşhis seed'di.
- **Geri dönüştürülen view'da renk her dalda set edilmeli.** Enum üzerinde exhaustive `when`
  bunu derleyiciye zorlattırıyor; `if/else` olsaydı eksik dal sessiz bug olurdu.
- **`%02d` negatif sayıda işareti tekrarlar.** Para formatlamada işaret her zaman baştan
  ayrılmalı.
- **VM'de `R.string` id'si (Int) tutmak Context sızıntısı değil** — fragment çözer; başlığı
  veri olarak taşımak boş bölümün kendiliğinden kaybolmasını sağlıyor.

**Doğrulama:** app-mobile üç modül ✓ (`:app:assembleDebug` dahil), app-pos ✓, **uyarısız**.
**Cihaz testi:** `adb shell pm clear com.example.app_mobile` ŞART (yeni seed isimleri için) →
`mobilebuild`. Test listesi `docs/test-hesaplari.md` → "Renk ve okunabilirlik (Tur 25)"
bölümünde; dört tonun tamamını üreten senaryolar orada.

**Bilinen, bu turda çözülmeyen:** detay ekranı başlıkları donmuş nav arg'dan geliyor — dükkan
adı ekran açıkken değişirse başlık tazelenmiyor (FAZ 4'te reaktif hale gelebilir).

**Sıradaki:** FAZ 4 — `:core-network` (Retrofit + DTO + AuthInterceptor) + outbox/WorkManager.

### 2026-08-05 — Tur 25b: Onay renk kuralı revize + bölüm başlığı kutulandı

**Renk kuralı değişti (kullanıcı geri bildirimi):** Tur 25'te ton yalnızca ROLE bakıyordu
(satıcı hep yeşil, müşteri hep kırmızı). Kullanıcı işlem türünün de rengi belirlemesini istedi:

| Rolüm | İşlem | Eski | YENİ |
|---|---|---|---|
| Satıcı | veresiye veriyorum | soluk yeşil | **soluk kırmızı** |
| Satıcı | ödeme alıyorum | doygun yeşil | canlı yeşil (aynı) |
| Müşteri | veresiye alıyorum | soluk kırmızı | **soluk yeşil** |
| Müşteri | ödeme yapıyorum | doygun kırmızı | canlı kırmızı (aynı) |

Yeni kural: **veresiyede hareket eden mal/kredi** (satıcı verir → out, müşteri alır → in),
**ödemede hareket eden para** (satıcıya gelir → in, müşteriden çıkar → out). Yoğunluk aynı
kaldı (veresiye soluk = henüz nakit yok, ödeme doygun = gerçek para). Değişiklik tek `when`
bloğunda (`ApprovalsViewModel.toCard`) + KDoc'lar; renk kaynakları ve enum dokunulmadı.

**Bölüm başlıkları kutulandı:** düz metin arka planda kayboluyordu →
`item_approval_header.xml` artık `bg_section_label` (surface_elevated + outline stroke,
8dp radius) üzerinde pill etiket.

**Doğrulama:** `:app:assembleDebug` ✓ uyarısız. `docs/test-hesaplari.md` renk tablosu +
"dört tonu üretme" adımları güncellendi (her ton ONAYLAYANIN ekranında görünür — başlatan
kendi rengini görmez).

### 2026-08-06 — Tur 26: FAZ 4 Aşama 7 — `:core-network` bağlandı + Hilt + session diske  [FAZ 4 yarısı]

`:core-network` (Aşama 0-6'da yazılmıştı) artık **çağrılıyor**: `:core-data` ona bağlı, `:app` Hilt
kullanıyor, ve session RAM'den **diske** taşındı. Kullanıcıya dönük tek görünür kazanç: **oturum
açıkken uygulamayı kapatıp açınca login sormuyor.**

**Adımlar (7a-7g, her biri ayrı derlendi):**
- **7a** `:core-data` → `implementation(project(":core-network"))` + hilt plugin. `RoomRepository.kt`
  → `local/RoomLocalDataSource.kt` (davranış aynı, sadece isim/yer).
- **7b** `remote/RemoteDataSource.kt` — **7 API'nin hepsi** sarmalandı (kullanıcı kararı: app-mobile
  düz kopyalasın). Her metot `apiCall` → `ApiResult` döner, DTO sızdırmaz (istisna: approval/sync —
  app-pos'ta domain tipleri yok). **Bu turda çağıranı YOK.**
- **7c** `OfflineFirstRepository : Repository` — `LocalSource` + `RemoteDataSource` + `TokenStore`
  besteler. `isSessionValid()`/`currentSellerId()` **senkron KALDI** (TokenStore RAM cache'ten).
  `login()` hâlâ **lokal doğruluyor** ama ürettiği session `SessionDto` olarak `tokenStore.save()`
  ile **diske** yazılıyor. **10 yeni unit test.**
- **7d+7e BİRLEŞTİRİLDİ** (plan sapması, aşağıda) — Hilt `:app`'e: `@HiltAndroidApp App`,
  `@AndroidEntryPoint MainActivity` + 10 fragment, **7 VM** `@HiltViewModel`.
- **7f** `di/DataModule.kt` (AppDatabase + LocalSource + Repository binding); `RepositoryProvider.kt`
  **SİLİNDİ**.
- **7g** `network_security_config.xml` (cleartext SADECE 10.0.2.2/127.0.0.1/localhost) + manifest'e
  `INTERNET` izni. **`usesCleartextTraffic="true"` KONMADI** — release manifest'te grep ile doğrulandı.

**PLAN SAPMASI — 7d ve 7e ayrılamadı (kod okumasıyla bulundu):** Plan "önce sadece MainActivity'yi
inject et, VM'ler eski provider'da kalsın" diyordu. Ama `LoginViewModel.login()` provider'ın
**RAM** session'ına yazarken `MainActivity.isSessionValid()` **diskteki**ni okuyacaktı → giriş
başarılı görünür, gate hiç açılmazdı. İki taraf aynı session'ı görmek zorunda → tek adımda taşındı.

**DEVIR dokümanı bayattı (kod okuması düzeltti):** "CREDIT dalı login gate'i ATLAR" artık YANLIŞ —
`MainActivity` evrilmiş, `pendingHandoffOrderBody` + `onLoginSucceeded()` ile oturum yoksa login'e
gidip akışı kaldığı yerden sürdürüyor. Yani `isSessionValid()` senkronluğu daha da kritik.

**İki VM Hilt DIŞINDA kaldı (kasıtlı):** `ConfirmViewModel` — `customerId`'yi nav arg'dan değil
**başka VM'in runtime state'inden** alıyor, `SavedStateHandle` çözemez → factory'si korundu,
`ConfirmFragment` repo'yu inject edip factory'ye geçiriyor. `SaleViewModel` — repo'ya hiç dokunmuyor.
`CustomerDetailViewModel` ise nav arg aldığı için `SavedStateHandle` ile temizce geçti (elle yazılmış
factory silindi — bonus: artık process death'i de atlatıyor).

**Öğrenilenler:**
- **`implementation(project(...))` Hilt için yetmez.** `RemoteDataSource` constructor'ında `Moshi`
  vardı ama `:core-network` onu `implementation` ile tutuyordu → KSP "could not be resolved" dedi.
  Constructor'da geçen HER tip modülün kendi compile classpath'inde olmalı. (`:core-network`'ü de
  `api(...)` ile açtık: `:app` `TokenStore` inject ediyor.)
- **Test edilebilirlik arayüzden gelir.** `OfflineFirstRepository` somut `RoomLocalDataSource` alsaydı
  session testleri Room ayağa kaldırmak zorundaydı. Yeni `LocalSource` arayüzü sayesinde 10 test saf
  JVM'de koşuyor — test edilen şey (gate) veritabanına hiç bağlı değil.
- **Sahte saat gerçek saatten başlamalı.** `FakeTokenStore(now = 1_000_000L)` ile expiry testi patladı:
  repo `System.currentTimeMillis()` ile expiry üretiyor, sahte epoch'a göre session zaten yıllar önce
  bitmiş görünüyordu. `now`'ı gerçek saatle başlatınca düzeldi.
- **`prime()` BLOKLAYICI olmalı.** `MainActivity.onCreate` gate'i senkron okuyor; `prime()`'ı arka plan
  coroutine'ine atmak yarış yaratırdı → yarışı kaybedince giriş yapmış esnaf login ekranı görürdü
  (**aralıklı** ortaya çıkan, en kötü türden bug). `runBlocking` maliyeti: süreç zaten açtığı bir
  dosyadan tek küçük okuma, henüz hiçbir frame çizilmemişken.

**Doğrulama:** `:app:assembleDebug` ✓ (Dagger grafiği uçtan uca doğrulandı), `:app:assembleRelease` ✓,
`:core-domain:build` ✓, **32 unit test / 0 fail / 0 skip** (22 network + 10 yeni session). Uyarı yok.
NOT: bu turda `:app:assembleDebug` sandbox'ta jlink hatası VERMEDİ (önceki turların ortam sorunu).

**CİHAZ TESTİ BEKLİYOR** (`posbuild`) — DB şeması değişmedi, uninstall gerekmez:
- *Grup A (bozulmamalı):* (1) launcher → login gate; (2) `05554443322` → dashboard, flash yok;
  (3) mock-pos VERESİYE → onay → OTP → mock-pos'a döner; (4) **oturum kapalıyken** VERESİYE → login →
  giriş → satış akışı kaldığı yerden devam eder; (5) çıkış → login, dashboard geçmişten silinir;
  (6) müşteri detayı → geri oku listeye döner; (7) ödeme akışı dashboard'a döner, mock-pos'a GİTMEZ.
- *Grup B (Aşama 7'nin kazancı):* (8) **oturum açıkken app'i tamamen kapat-aç → login SORMAZ**;
  (9) çıkış yap → kapat-aç → login SORAR.

**Sıradaki:** Aşama 8 — outbox yazımı (`addTransaction` içinde `db.withTransaction` ile ledger+outbox
atomik), `OutboxDao.insert`'e `onConflict = IGNORE` (şu an default ABORT → retry'da patlar),
`SyncEngine.drainOutbox()` `isRetryable()` kuralıyla. WorkManager ayrı tur. Sonra app-mobile'a
`:core-network` kopyası + Aşama 0 timestamp geçişi.

### 2026-08-06 — Tur 27: FAZ 4 Aşama 8 — outbox yazımı + SyncEngine (ağ ilk kez yazma yolunda)

Aşama 7 yolu açmıştı (`RemoteDataSource` hazır, Hilt enjekte ediyor); bu tur o yoldan **ilk gerçek
trafik** geçiyor. Veresiye yazınca artık sunucuya da gidiyor — ama esnaf ASLA ağı beklemiyor.

**1) Atomik yazma (`RoomLocalDataSource.addTransactionQueued`):** ledger INSERT + outbox INSERT
**tek `db.withTransaction` içinde**. Ayrı yazılsalar, aradaki process ölümü ya "sunucunun asla
duymayacağı kayıt" ya da "olmayan kayıt için gönderim kuyruğu" bırakırdı — ikisi de sonradan
tespit edilemez. Kuyruk satırı **hazır JSON gövde** taşır (ledger satırına referans değil):
sunucuya onay anında mutabık kalınan şey gitmeli.

**2) `OutboxDao.insert` → `onConflict = IGNORE` (plandaki latent bug):** default `ABORT`'tu.
Satır id'si = transaction id olduğu için, tekrar kuyruğa girme denemesi (retry, devam eden handoff)
**exception fırlatacaktı** — yani kuyruk tam işini yaparken patlayacaktı. DAO'ya ayrıca
`observeCount()` (senkronlanmamış sayısı) + `recordFailure()` (retryCount++) eklendi.

**3) `SyncEngine.drainOutbox()`** — üç kural:
- **Sıra:** eski→yeni, ve **retryable hata RUN'I DURDURUR**. Ağ yoksa 2. kayıt da aynı sebeple
  düşecek; gerisini denemek sadece retryCount şişirir ve pil yakar.
- **Ne yapılacağı `isRetryable()`'dan gelir** (Aşama 4'te `ApiResult`'a yazılmıştı, tüketicisi şimdi
  geldi): ağ hatası/5xx → satır KALIR; 4xx/409 → satır SİLİNİR (kalıcı ret sonsuza dek denenmez).
- **Silmek güvenli çünkü gönderim idempotent:** `Idempotency-Key` = transaction id, cevap kaybolsa
  bile resend orijinali tekrar oynatır, ikinci veresiye açmaz.
- Parse edilemeyen payload → **istek atmadan** düşer (kuyruk başını sonsuza dek tıkamasın).
- `Mutex` ile tek-uçuş: iki eş zamanlı drain aynı satırları iki kez gönderirdi.

**4) Tetikleyiciler:** `App.onCreate` (dün offline olan terminal, sinyalli açılınca yetişir) ve
**OTP sonrası** yazının hemen ardından. İkincisi `@ApplicationScope` ile **ekranı aşan** scope'ta —
`onWritten()` satış akışını kapatıp `viewModelScope`'u iptal ediyor, orada başlatılan push istek
ortasında ölürdü. Yeni `data/di/AppScope.kt`: `@ApplicationScope CoroutineScope` + `@IoDispatcher`.

**5) `Repository` arayüzüne `syncNow()` + `observeUnsentCount()`** eklendi. `addTransaction` KDoc'u
"lokale yazar, ağı BEKLEMEZ" sözleşmesini artık açıkça yazıyor.

**Öğrenilenler:**
- **Serileştirme depolamanın işi değil.** Önce `RoomLocalDataSource`'a `TransactionCreateDto` +
  Moshi import ettim — katman ihlali. Doğrusu: payload'ı repository üretir, local kaynak string'i
  ne olduğunu bilmeden saklar.
- **`viewModelScope` "yazdıktan sonra" işi için yanlış scope.** Ekran kapanınca iptal olur. Yazmanın
  KENDİSİ ekrana bağlı (kullanıcı bekliyor), ama gönderimi değil.
- **Test fake'i gerçek DAO'nun sırasını taklit etmeli.** `pendingOutbox()` önce insertion order
  dönüyordu; DAO `ORDER BY createdAt`. Fake'i `sortedBy` yapmasam "sıra" testi yalancı yeşil olurdu.
- **Drain asla throw etmemeli.** Patlarsa kuyruk temelli tıkanır; bu yüzden parse hatası bile
  `runCatching` ile değere çevrilip "kalıcı bozuk" muamelesi görüyor.

**Doğrulama:** `:core-domain:build` ✓, `:app:assembleDebug` ✓, `:app:assembleRelease` ✓,
**42 unit test / 0 fail / 0 skip** (22 network + 10 session + **10 yeni SyncEngine**). Uyarı yok.
SyncEngine testleri **MockWebServer** ile gerçek OkHttp/Retrofit yığınına karşı koşuyor (stub değil):
idempotency header'ı, sıra, kopan soket, 500/400/409 ayrımı, bozuk payload.
**DB versiyonu 2'de KALDI** — `outbox` tablosu zaten iskelet olarak vardı, migration/uninstall YOK.

**CİHAZ TESTİ:** Aşama 7'nin listesi aynen geçerli (hepsi bozulmamalı). Aşama 8'e özel:
- Prism KAPALIYKEN veresiye yaz → **normal çalışmalı**, ekran beklememeli (kuyrukta birikir).
- `npx @stoplight/prism-cli mock shared-contracts/openapi.yaml` açıp app'i yeniden başlat →
  birikenler gitmeli (Prism loglarında `POST /transactions` + `Idempotency-Key`).
- Prism AÇIKKEN veresiye yaz → anında POST görünmeli.

**Sıradaki:** WorkManager (arka plan periyodik drain — `WorkerFactory` + Hilt entegrasyonu, ayrı tur;
şu an sadece açılış + yazma sonrası tetikleniyor). Sonra app-mobile'a `:core-network` kopyası +
Aşama 0 timestamp geçişi. `login()`'in gerçek `AuthApi.verifyOtp`'ye bağlanması da bekliyor (FAZ 4b).

### 2026-08-06 — Tur 28: FAZ 4 Aşama 9 — WorkManager ile arka plan senkronizasyonu

Aşama 8'de kuyruk ve `SyncEngine` vardı ama **sadece iki anda** boşalıyordu: uygulama açılışı ve
OTP sonrası. Boşluk: uygulama kapalıyken sinyal gelirse hiçbir şey olmuyordu — sinyalsiz tezgâhta
gün boyu yazılan veresiyeler, ertesi gün uygulama açılana kadar sunucuya gitmiyordu. Bu tur drain'i
işletim sistemine emanet ediyor.

**1) `SyncWorker` (`:app/sync/`, `@HiltWorker`):** karar tablosu —
- `isSessionValid()` false → **`Result.retry()`, kuyruğa DOKUNMAZ.**
  **Bu guard'ın olmaması doğrudan veri kaybıydı:** token yokken sunucu 401 döner, `SyncEngine`
  401'i 4xx sayıp "kalıcı ret" olarak kaydı **SİLER**. Yani çıkış yapmış esnafın gönderilmemiş
  veresiyeleri arka planda sessizce yok olurdu.
- `outcome.retryable > 0` → `retry()` (WorkManager kendi exponential backoff'unu uygular)
- exception → `retry()`, **asla `failure()`**: kalıcı failure işi düşürür, kuyruk temelli tıkanır.

**Worker `:app`'te, `:core-data`'da DEĞİL:** `:core-data` depolamayı ve ağı bilir, **ne zaman**
senkron olunacağını bilmez — zamanlama uygulama kararı. Kütüphane modülünü WorkManager'a bağlamak
zamanlama politikasını veri katmanına gömerdi.

**2) Manifest'te `WorkManagerInitializer` KALDIRILDI (`tools:node="remove"`):** WorkManager kendini
`androidx.startup` ile `App.onCreate`'ten ÖNCE başlatıyor ve stok worker factory'yi kullanıyor —
o da constructor parametreli `@HiltWorker`'ı kuramaz. Sonuç **derleme hatası değil, runtime'da
"could not instantiate SyncWorker"**. Bu satır optimizasyon değil, WIRING'in kendisi.
Merged manifest'te debug+release için `grep -c` = 0 ile doğrulandı.

**3) `SyncScheduler` (`:app`, WorkManager'ı bilen TEK yer):**
- Periyodik: **15 dk** (WorkManager'ın tabanı; altı sessizce yükseltilir) + `NetworkType.CONNECTED`
  (sinyalsizken hiç uyanmaz) + **`ExistingPeriodicWorkPolicy.KEEP`**. KEEP kritik: `UPDATE` olsaydı
  her açılışta periyot sıfırlanır, sık açılan bir POS'ta iş **hiç çalışmazdı**.
- Tek-sefer: satış sonrası, `ExistingWorkPolicy.KEEP` (arka arkaya satışlar tek koşuda birleşir).

**4) `OtpViewModel`'de `appScope.launch { repo.syncNow() }` → `syncScheduler.syncNow()`:**
`appScope` process'e bağlıydı; esnaf satıştan sonra uygulamayı recents'ten atarsa push yarıda
kalıyordu. Work request process'i aşar, ayrıca ağ kısıtı sayesinde offline'da boşuna tur atmaz.

**5) `SyncOutcome` `:core-data` → `:core-domain`'e TAŞINDI, `Repository.syncNow()` artık onu
DÖNÜYOR** (eskiden `Unit`'ti + `OfflineFirstRepository.drainAndReport()` vardı). Sebep: Worker
"tekrar denemeli miyim" sorusunu cevaplamak zorunda; `Unit` dönseydi Worker somut
`OfflineFirstRepository`'ye bağlanacaktı. Tek metot, arayüz üzerinden, `drainAndReport` silindi.

**Öğrenilenler:**
- **`WorkerParameters`'ın public constructor'ı yok** → JVM testinde `SyncWorker` kurulamıyor;
  gerçeğini koşturmak Robolectric + scheduler demekti. Karar mantığı `companion object`'te saf
  `suspend fun decide(repository)` olarak ayrıldı → 4 dal unit-test maliyetine doğrulanıyor.
  `doWork()` tek satır delegasyon.
- **`:app`'te `BuildConfig` YOK** (`buildConfig` feature'ı sadece `:core-network`'te açık).
  Mevcut `NetworkConfig.isDebug` kullanıldı — sırf aynı bilgi için `:app`'e feature açmaya gerek yok.
- **Ölü catalog alias'ı temizlendi:** `androidx-hilt-navigation-fragment` Aşama 7'de eklenmiş ama
  hiç kullanılmamıştı; `androidxHilt` 1.2.0 → **1.4.0** (hilt-work için) çekilirken silindi.

**Doğrulama:** `:core-domain:build` ✓, `:app:assembleDebug` ✓, `:app:assembleRelease` ✓,
**50 unit test / 0 fail / 0 skip** (22 network + 10 session + 10 SyncEngine + **7 SyncWorker** + 1 örnek).
Uyarı yok. Hilt'in `SyncWorker_AssistedFactory_Impl` ürettiği doğrulandı.
Sürümler: WorkManager **2.11.2**, androidx.hilt **1.4.0** (Google Maven'dan teyit; 2.12.x beta).
**DB versiyonu 2'de KALDI**, migration/uninstall YOK.

**CİHAZ TESTİ:** Aşama 7+8 listeleri bozulmamalı. Aşama 9'a özel:
1. **Prism kapalı** → 2-3 veresiye yaz → normal çalışmalı (ekran beklemez).
2. Uygulamayı **tamamen kapat** (recents'ten at), Prism'i aç.
3. **Uygulamayı AÇMADAN** bekle → 15 dk içinde kuyruk boşalmalı (Prism'de `POST /transactions`).
   Hızlandırma: `adb shell dumpsys jobscheduler | grep app_pos` ile jobId bul →
   `adb shell cmd jobscheduler run -f com.example.app_pos <jobId>`.
4. **Oturumsuz senaryo (asıl risk):** kuyrukta kayıt varken **çıkış yap**, kapat, Prism açık bekle
   → `POST /transactions` **GELMEMELİ**, kayıtlar durmalı. Giriş yap → gitmeli.
5. Prism **açıkken** veresiye yaz → anında POST (tek-sefer iş).

**Sıradaki:** FAZ 4b — `login()` gerçek `AuthApi.requestOtp/verifyOtp`'ye bağlanır (şu an lokal
doğruluyor ama session gerçek `SessionDto` olarak diske yazılıyor → sadece token'ın kaynağı
değişecek). Sonra app-mobile'a `:core-network` + outbox + WorkManager kopyası.
Küçük opsiyon: `Repository.observeUnsentCount()` **var ama hiçbir ekran kullanmıyor** —
dashboard'a "N kayıt gönderilmedi" rozeti tek fragment değişikliği.

### 2026-08-10 — Tur 29: FAZ 5 Aşama 5a — backend iskeleti + Docker + şema + seed

`backend/` artık boş değil. FastAPI + PostgreSQL 16 + Docker Compose ayakta, altı tablo
kurulu, seed atılmış. Henüz **hiçbir uç yok** (sadece `/health`) — bu tur temeli atıyor.

**Plan yazılırken kod okundu, 8 ayrışma bulundu** ([faz5-backend-plan.md](faz5-backend-plan.md) §0).
En önemlisi 5a'nın kapsamını değiştirdi:

**1) İKİ FARKLI SEED VARDI (§0.1).** app-pos ve app-mobile aynı id'lere farklı satırlar
yüklüyordu: `t4`/`t5` app-pos'ta `u_owner`→`c2`, app-mobile'da `u_market`→`m1`. Tek DB
olduğu için biri kazanmalıydı. **app-mobile'ınki seçildi**, çünkü üst küme: ikinci satıcı
(`u_market`), çapraz defterler (`m1`/`o1`), approval satırları onda var. app-pos seed'iyle
gidilseydi **`/me/debts` boş liste dönerdi** → 5e hiç test edilemezdi. app-pos'un tek
fazlalığı `c2` (UNCLAIMED müşteri, claim akışı için gerekli) korundu → 15 transaction.

**2) Contract örnekleri 3 saat bayattı (§0.2).** `openapi.yaml`'da `t1` için
`2026-07-20T09:15:00Z` yazıyordu; bu Istanbul yerel saatine `Z` eklenmiş hali. app-pos
Aşama 0'da aynı anı `06:15:00Z` olarak yazmıştı. **8 örnek düzeltildi** (−3 saat), ayrıca
`User` örneğindeki `display_name: Ahmet Bakkal` → `Ahmet Demirtaş` (kişi adı ≠ dükkan adı;
seed'in kasıtlı ayrımı contract'ta bozulmuştu). Redocly lint temiz kaldı.

**3) Append-only DB seviyesinde zorlanıyor.** Plan "REVOKE UPDATE, DELETE" diyordu ama
uygulama şemanın **sahibi** olarak bağlanıyor ve owner kendi yetkisini revoke edemez →
**trigger** kullanıldı. `UPDATE`/`DELETE` denemesi
`transactions is append-only: UPDATE is not permitted` ile patlıyor (ikisi de denendi).

**4) Seed idempotent (§0.7).** Alembic her container açılışında koşuyor; koşulsuz seed
bakiyeleri katlardı. `users` boşsa seed eder — app-pos'un `SeedCallback.isEmpty()` deseni.
`docker compose restart` sonrası satır sayıları sabit kaldı (users=4, tx=15).

**5) Port 4010 seçildi** — app-pos'un debug build'i zaten `http://10.0.2.2:4010/` (eski
Prism portu) adresine bakıyor. Compose onu 4010'a map ediyor → **Android tarafında tek satır
bile değişmiyor** (5f'nin işini azaltır).

**Öğrenilenler:**
- **SQLAlchemy insert'leri tabloya göre gruplar, FK sırasını bilmez.** İlk `compose up`
  `ForeignKeyViolation` ile patladı: `customers.claimed_by_user_id` → `users` FK'sı varken
  customers önce flush edildi. Çözüm: users'tan sonra ve customers'tan sonra açık
  `db.flush()`. Sıra umut edilmez, **söylenir**.
- **Trigger, REVOKE'tan daha doğru araç.** Kural "kimse UPDATE edemez" değil, "bu tablo
  append-only" — rol yetkisi değil, tablo özelliği. Owner-connection senaryosunda REVOKE
  hiçbir şey yapmazdı, yani yanlış bir güvenlik hissi verirdi.
- **`jti` şart.** JWT'ye unique id koyulmasaydı aynı saniyede aynı user için üretilen iki
  token **aynı string** olurdu; `TokenAuthenticator` eski/yeni bearer'ı karşılaştırarak
  "başka thread yeniledi mi" diye bakıyor → aynı string onu yanlış dala sokardı.

**Doğrulama:** `docker compose up -d --build` ✓, `alembic upgrade head` ✓, `/health` 200 ✓,
**23 pytest / 0 fail** (telefon normalizasyonu + seed bakiyeleri + ledger sorguları).
Seed bakiyeleri **SQL ile teyit edildi**, hepsi plandaki değerlerle birebir:
c1 40,00 / c2 165,00 / c3 0 / c4 25,50 / c5 210,00 / m1 100,00 / o1 60,00.
Redocly lint: valid (56 uyarı, hepsi önceden var olan `operation-4xx-response` stil notu).

**Sıradaki:** 5b — auth uçları (`/auth/otp/request|verify|refresh|logout`). OTP mock kalır
ama **sunucuda** (sabit `123456`); `verify` auto-register YAPMAZ → kayıtsız numara
`404 user_not_found`. `refresh_token` her zaman üretilir (§0.8: contract'ta nullable ama
`TokenAuthenticator` onsuz pes edip login gate'ine düşüyor).

### 2026-08-10 — Tur 30: FAZ 5 Aşama 5b — auth uçları (ilk gerçek token)

Dört uç canlı: `/auth/otp/request|verify|refresh|logout`. Sunucu artık **gerçek JWT
üretiyor** — app-pos'un `login()`'i hâlâ lokal mock ama karşı taraf hazır (5f'de bağlanacak).

**1) OTP mock KALDI ama SUNUCUYA taşındı.** Sabit kod `123456`, `config.py`'de tek sabit.
Değişen şey kodun **nerede yargılandığı**: cihazdaki `OtpService` yerine sunucu. Gerçek SMS
sağlayıcısı gelince sadece bu sabitin kaynağı değişecek, başka hiçbir yer değil.

**2) `verify` auto-register YAPMIYOR** (api-endpoints.md A.1 kesin kararı): kayıtsız numara
`404 user_not_found`, client `POST /users` ile ayrı adımda kaydediyor. `LoginViewModel`'de
NEEDS_REGISTER dalı zaten var, 5f'de bu 404'e bağlanacak.

**3) `otp/request` hesabın var olup olmadığını SIZDIRMIYOR.** İki numara da 202 + `sent:true`
alıyor. Kayıtlı numaraya farklı cevap verilseydi bu uç "hangi numaraların hesabı var"
sorusunun oracle'ı olurdu. Ama `channel` alanı gerçeği yansıtıyor (`APP_PUSH` vs `SMS_OTP`)
çünkü client ekran metnini ona göre seçiyor — sızıntı değil, çünkü kod zaten telefona gidiyor.

**4) `refresh_token` HER ZAMAN üretiliyor (§0.8).** Contract'ta nullable ama
`TokenAuthenticator` onu bulamayınca **pes edip login gate'ine düşüyor** — yani vermeyen bir
sunucu 401→refresh→retry yolunu test edilemez kılardı.

**5) Token tipi kontrol ediliyor.** `typ: access|refresh` claim'i; access token'ı refresh
yerine kullanma denemesi `401 invalid_token`. Olmasaydı bir access token sessizce **bir aylık**
bearer'a dönüşürdü (refresh TTL'i 30 gün).

**6) `logout` sunucuda no-op ama 204 dönüyor + token İSTİYOR.** JWT stateless olduğu için
iptal edilecek bir şey yok; kısa access TTL + client'ın `TokenStore`'u temizlemesi tezgâhtaki
gerçek riski karşılıyor. Uç yine de var ki client'ın çağıracak tek yeri olsun ve ileride
revocation list eklenirse **client değişmesin**. Token istiyor: aksi halde biri sahip
olmadığı oturumu kapatabilirdi.

**Öğrenilenler:**
- **`jti` olmadan iki token aynı string olabiliyor.** Aynı saniyede aynı user için üretilen
  JWT'lerin payload'ı birebir aynı → imza da aynı. `TokenAuthenticator` eski/yeni bearer'ı
  **karşılaştırarak** "başka thread yeniledi mi" diye bakıyor; aynı string onu yanlış dala
  sokup bayat sandığı token'la retry ettirirdi. Test bunu açıkça koruyor.
- **`dependency_overrides` lifespan'i kapsamıyor.** `TestClient(app)` startup'ı tetikliyor,
  o da `SessionLocal`'dan **kendi** session'ını kuruyor → testler gerçek Postgres'e uzanıp
  container'sız makinede patlıyordu. Çözüm: fixture `seed_on_startup`'ı kapatıyor.
- **Hata zarfı tek yerden.** `main.py`'deki handler `{error:{code,message}}` şeklini garanti
  ediyor; FastAPI'nin kendi 401/404'leri de sarmalanıyor, yani hiçbir uç farklı şekilli hata
  sızdıramıyor (`ErrorEnvelopeDto` bunu bekliyor).

**Doğrulama:** **40 pytest / 0 fail** (23 önceki + 17 auth). Ayrıca **gerçek container'a
karşı 9 curl senaryosu**: kayıtsız→404, yanlış kod→401, geçersiz telefon→400, request→202,
token'sız logout→401, token'lı logout→204, refresh→farklı token, access-token-ile-refresh→401,
`seller_info` nested + `created_at: 2026-07-01T06:00:00Z` (§0.2 düzeltmesi uçtan uca tuttu).

**Sıradaki:** 5c — users + customers (`POST /users` telefonla idempotent, `GET/PATCH /users/me`,
`become-seller`; `POST/GET /customers` bakiyeli, `/customers/{id}`, `/customers/lookup`).
`GET /customers` seller-scoped olacak: token'ın kullanıcısının defteri + `SUM` ile türetilmiş
`balance_minor`.
