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
- [docs/architecture-pos.svg](architecture-pos.svg) — katmanlı mimari:
  sistem görünümü (app-pos ↔ Backend ↔ app-mobile, "client çağırır dinlemez",
  auth kararları) + app-pos içi katmanlar (modül etiketleriyle) + veri modelleri
  paneli + bağımlılık yönü.
- [docs/flow-pos.svg](flow-pos.svg) — veresiye ödeme akışı: Flow A (POS,
  esnaf) + Flow B (müşteri, app-mobile), aralarında QR/NFC devir, backend +
  gün sonu reconciliation, hesap logic TODO.
- [docs/architecture-pos.md](architecture-pos.md) — şemaların yazılı
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

### 2026-08-10 — Tur 31: FAZ 5 Aşama 5c — users + customers (ilk seller-scoped okuma)

Sekiz uç canlı: `POST /users`, `GET/PATCH /users/me`, `POST /users/me/become-seller`;
`POST/GET /customers`, `GET /customers/{id}`, `GET /customers/lookup`. Bakiye ilk kez
**sunucuda türetilip** tele gidiyor.

**ŞEMA DEĞİŞİKLİĞİ — `customers.created_by_seller_id` (migration 0002).** Kod yazılırken
çıkan gerçek boşluk: `customers` tablosunda `seller_id` **yok** (doğru karar — müşteri satırı
dükkanlar arasında PAYLAŞILIYOR, `m1`/`c1` aynı kişi). Defter üyeliği ledger'dan türetiliyordu:
"bu satıcı bu müşteriye satır yazmışsa defterindedir". Ama **yeni eklenmiş ama henüz
borçlandırılmamış müşterinin hiç ledger satırı yok** → `POST /customers` 201 dönüyor,
`GET /customers` onu **göstermiyordu**. Kullanıcı kararı: nullable kolon eklendi.
Üyelik artık iki kaynaklı: *ledger'da satırı var* **VEYA** *bu satıcı oluşturmuş*
(`_book_customer_ids()` union'ı). Sahiplik DEĞİL — kimin ilk yazdığı.

**1) `POST /users` telefonla idempotent ve mevcut satırı DEĞİŞTİRMİYOR.** Aynı telefon → 200
+ var olan satır (409 değil): client bu uca `verify`'ın 404'ünden sonra geliyor, cevabı kaybolan
bir retry kullanıcıya yorumlaması gereken hataya dönüşmemeli. **Ama gövdedeki `display_name`/
`is_seller` yok sayılıyor** — bu uç `security: []`, yani kimlik doğrulaması istemiyor; mevcut
satırı güncelleseydi **herkes bir yabancının profilini "kayıt olarak" ezebilirdi.**

**2) `PATCH /users/me` telefonu değiştiremiyor.** Telefon hesabın anahtarı; burada değiştirmek
hesabı kimsenin sahipliğini kanıtlamadığı bir numaraya taşırdı → kendi OTP akışının işi.
`exclude_unset` ile "alan yok" ≠ "alan null geldi" ayrımı korundu, yoksa e-postayı **silmek
ifade edilemezdi**.

**3) `become-seller` bayrağı ve dükkan adını BİRLİKTE yazıyor.** Domain tipi
`is_seller ⇔ seller_info != null` diyor; bayrak var ama `shop_name` yoksa client'ın tipinin
tutamayacağı bir şekil serialize edilirdi.

**4) `GET /customers` tek grouped sorgu.** `ledger.py:balances_by_customer()` (5a'da yazılmış,
çağıranı yoktu) devreye girdi. Müşteri listesi ana ekran — satır başına sorgu tek ekranı
N round-trip'e çevirirdi.

**5) `lookup` başka dükkanın müşterisini BULUYOR ama bakiyesi 0 dönüyor.** Amaç esnafın
sistemin zaten tanıdığı birini yeniden yazmasını önlemek; bakiye seller-scoped kaldığı için
**hiçbir dükkan başka yerdeki borcu öğrenmiyor**. "Benim mi, başkasının mı" ayrımı client'ın
kararı (`CustomerLookup`).

**6) `POST /customers` 409'u DEFTER BAZINDA.** Global unique telefon yanlış olurdu: iki dükkanın
aynı kişiyi tanıması normal durum, her biri kendi satırını tutar. Test bunu açıkça koruyor.

**Öğrenilenler:**
- **Rota sırası sessiz kırılma noktası.** `/customers/lookup` **`/customers/{id}`'den ÖNCE**
  tanımlanmalı, yoksa "lookup" bir id olarak yakalanır ve uç erişilemez olur — derleme hatası
  değil, 404. Ayrı bir test bunu kilitliyor.
- **Şema boşlukları uç yazılırken çıkıyor.** `created_by_seller_id` tasarım turlarında değil,
  `POST` ve `GET`'i yan yana koyunca göründü. Contract'ın yanlış olduğu bir yer değil —
  contract'ın hiç konuşmadığı bir yer.

**Doğrulama:** **76 pytest / 0 fail** (40 önceki + 16 users + 20 customers). Migration 0002
gerçek Postgres'e **artımlı** uygulandı (0001 yeniden koşmadı), sonra `down -v` ile sıfırdan:
users=4 / customers=7 / tx=15, `created_by_seller_id` seed'de doğru dağıldı (c1-c5→u_owner,
m1/o1→u_market). Container'a karşı 8 curl: seller-scoped liste (m1/o1 **sızmıyor**), türetilmiş
bakiyeler (4000/16500/0/2550/21000), `users/me` nested `seller_info`, lookup 200/404,
`POST /customers` 201 → **borçlandırılmadan listede görünüyor** (0002'nin kanıtı), tekrar → 409,
`POST /users` 201→200.

**Sıradaki:** 5d — ledger, asıl iş. `POST /transactions` (+`Idempotency-Key`): aynı key+aynı
gövde → **200**, aynı key+farklı gövde → **409**, `seller_id` **token'dan**; basket varsa
`baskets`+`basket_items` aynı DB transaction'ında. Ayrıca `GET /transactions?customer_id=` ve
`GET /balances?customer_id=`.

### 2026-08-11 — Tur 32: FAZ 5 Aşama 5d — ledger (idempotency, asıl iş)

Üç uç: `POST /transactions` (+`Idempotency-Key`), `GET /transactions?customer_id=`,
`GET /balances?customer_id=`. Offline-first tasarımın dayandığı sözleşme artık **sunucu
tarafında da** var.

**1) Üç yollu idempotency.** Yeni key → **201**; aynı key + **aynı gövde** → **200** ve
orijinal satır **değişmeden** döner; aynı key + **farklı gövde** → **409**, sessiz overwrite
DEĞİL. 409 kritik: ledger append-only, ikinci farklı gövde birincinin üzerine yazsaydı
**bayat bir retry, esnafla müşterinin üzerinde anlaştığı geçmişi yeniden yazardı.**
Client bunu zaten varsayıyordu — `SyncEngine` 2xx görünce kuyruk satırını siliyor
([SyncEngine.kt:70](../app-pos/core-data/src/main/java/com/example/app_pos/data/sync/SyncEngine.kt))
ve 200/201 ayrımı yapmıyor.

**2) `Idempotency-Key` == `transaction_id` zorunlu** (uyuşmazsa 400). Header ikinci bir
bağımsız id değil; farklı olmalarına izin vermek iki farklı kaydın aynı key'i paylaşmasına
ya da tek kaydın iki key altında yazılmasına kapı açardı.

**3) `seller_id` token'dan.** `TransactionCreateDto`'da bu alan zaten yok, ama gövdeye
elle konsa bile yok sayılıyor — test bunu açıkça koruyor. Aksi halde giriş yapmış herkes
başkasının defterine satır ekleyebilirdi.

**4) Bilinmeyen `type` → 400.** DEBT/PAYMENT dışı bir değer bakiye toplamının **hiçbir
tarafına** düşmezdi; kayıt var olur ama temsil ettiği para sessizce kaybolurdu.

**5) Sepet (orderBody) aynı DB transaction'ında.** `baskets` + `basket_items` + ledger satırı
tek `commit`. Ayrı yazılsalar ya sahipsiz sepet ya kırık FK kalırdı. Replay'de sepet
**yeniden yazılmıyor** (item'lar çiftlenmiyor).

**🔴 BULUNAN BUG — timestamp formatı (test yakaladı, iki katmanlı):**

*(a) Mikrosaniye + eksik `Z`.* Client `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")` ile
**LİTERAL** parse ediyor ([TimeFormat.kt:22](../app-pos/app/src/main/java/com/example/app_pos/util/TimeFormat.kt)) —
mikrosaniye de `+00:00` ofseti de parse edilemez. Pydantic'in varsayılanı mikrosaniye
basıyordu, replay yolunda ise `Z` düşüyordu. **Hata sessiz olurdu:** `toDisplayDateTime()`
parse edemeyince ham string'e düşüyor, yani esnaf tarih yerine `2026-08-11T09:15:28.123456Z`
görürdü. Çözüm: `schemas.py`'de `IsoUtc` tipi — **API'nin yaydığı her timestamp** tek yerden
`%Y-%m-%dT%H:%M:%SZ`.

*(b) Naive datetime 3 saat kaydırıyordu.* (a) düzeltilince altından çıktı: SQLite tzinfo'yu
**düşürüyor**, `astimezone(UTC)` naive değeri **sunucunun yerel saati** sayıp UTC+3
makinede 3 saat ileri atıyordu — replay orijinalden 3 saat sonra görünüyordu. Bu §0.2'deki
seed hatasının **tam olarak aynı sınıfı**. Çözüm: naive değer **zaten UTC** kabul ediliyor
(buraya yazılan her şey `datetime.now(UTC)` veya seed literali, yani UTC by construction).
Postgres aware döndürdüğü için üretimde görünmezdi — **SQLite testi kurtardı.**

**Öğrenilenler:**
- **`assert first.json() == second.json()` en ucuz idempotency testi.** "Aynı satır dönüyor
  mu" sorusunu byte düzeyinde soruyor; iki ayrı bug'ı (format + saat kayması) bu tek satır
  yakaladı. Alan alan karşılaştırma ikisini de kaçırırdı.
- **Serileştirme şekli sözleşmenin parçası.** `created_at` "ISO-8601" demek yetmiyor;
  client'ın parser'ı hangi varyantı kabul ediyorsa **o** sözleşme. Tek yerden zorlanmalı,
  yoksa her yeni uç kendi varyantını üretir.

**Doğrulama:** **101 pytest / 0 fail** (76 önceki + 25 ledger). Gerçek Postgres'e karşı
**12 curl senaryosu**: 201→200→409 üçlüsü, **3 POST → DB'de 1 satır**, bakiye 4000+2500=6500
(tek kez sayıldı), geçmiş yeniden-eskiye, `m1` (başka defter) `[]`, sepetli yazım 201 →
replay 200 → **baskets=1/items=2** (çiftlenmedi), key uyuşmazlığı 400, header yok 422.
Replay `created_at` **byte-birebir aynı** (`09:15:28Z`), seed satırları `06:15:00Z`.
Test verisi temizlendi (`down -v`), container temiz seed'le ayakta.

**Sıradaki:** 5e — buyer + approvals. `GET /me/debts|transactions|balances` (app-mobile'ın
`observeDebtsBySeller`'ının sunucu karşılığı) + `POST /approvals`, `GET /approvals`,
`approve`/`reject`. Onay kuralı: **isteği başlatan onaylamaz**; `approve` ledger'a yazan tek
nokta, `reject` hiçbir şey yazmaz (satır silinmez, `status` değişir). §0.3 yetki kontrolü:
`initiator_role == SELLER` ise `seller_id == token.sub` doğrulanacak.

### 2026-08-11 — Tur 33: FAZ 5 Aşama 5e — buyer + approvals (Bölüm A TAMAMLANDI)

Yedi uç: `GET /me/debts|transactions|balances` + `POST /approvals`, `GET /approvals`,
`approve`, `reject`. **Contract'ın Bölüm A'sı bitti — 22 uç canlı, `future` etiketli hiçbir
uç yazılmadı** (canlı `/openapi.json` contract'la program ile karşılaştırıldı).

**1) Buyer okumaları seller'ın SİMETRİĞİ.** Satıcı "bana kim borçlu" sorar, alıcı "kime ne
borçluyum". Aynı ledger ikisini de cevaplıyor. Scope `claimed_by_user_id` üzerinden: bir alıcı
**dükkan başına bir** müşteri kaydı tutuyor (u1 = Ahmet Bakkal'da `c1`, Ayşe Market'te `m1`)
→ `_my_customer_id_with()` "herhangi bir kaydım" fallback'i YAPMIYOR; yanlış kaydı seçmek
başka dükkanın defterinden okumak olurdu.

**2) `/me/debts` sıfır bakiyeli dükkanı ATLIYOR.** 0 satır borç değil; listelemek ekrana
"hiçbir şey borçlu olmadığın dükkanlar"ı koyardı. `shop_name` non-nullable olduğu için
`shop_name → display_name → phone` fallback zinciri var (dükkan adı girmemiş satıcı da
okunabilir bir kart üretmeli).

**3) Onay kuralı: KARŞI TARAF onaylar, başlatan asla.** Yön `initiator_role`'den geliyor:
SELLER başlattıysa onaylayan `customer.claimed_by_user_id`, BUYER başlattıysa `seller_id`.
Sadece müşteri kaydından türetilseydi **alıcının başlattığı ödeme yine alıcıya giderdi** —
app-mobile'da Tur 24b'de bulunan bug'ın ta kendisi.

**4) §0.3 yetki kontrolü uygulandı.** `seller_id` gövdede (buyer da başlatabildiği için
token'dan gelemez) → saldırılabilecek tek alan. `initiator_role == SELLER` ise
`seller_id == token.sub` doğrulanıyor, değilse **403**. Ayrıca BUYER dalında müşteri kaydının
**kendisine ait** olması aranıyor, yoksa bir alıcı başkasının defterine ödeme beyan edebilirdi.

**5) İki dal, tek uç.** CLAIMED karşı taraf → **201** PENDING satır, ledger'a **hiçbir şey
yazılmaz**. UNCLAIMED → kimse "onayla"ya basamaz, SMS-OTP dalı → **200** + Transaction,
anında yazılır. Contract bu iki şekli zaten tanımlıyordu, client dallanıyor.

**6) `approve` ledger'a yazan TEK nokta; yazım + status tek `commit`.** Ayrı olsalar ya
"status değişmemiş kayıt" (tekrar onaylanabilir) ya "kayıtsız APPROVED" (borç tamamen kaybolur)
kalırdı. İkinci onay → **409 `already_decided`** (çift dokunuş / retry koruması).

**7) Karar verilen satır SİLİNMİYOR.** `status` değişiyor, bekleyen sorgusu `PENDING`
filtreliyor → kullanıcıya davranış aynı, denetim izi duruyor (db-schema.md A.6 kararı).
DB'de teyit edildi: `p1 -> APPROVED` satırı yerinde.

**Öğrenilenler:**
- **`transaction_out` paylaşıldı (`serializers.py`).** Önce `ledger.py`'de lokaldi, `buyer.py`
  fonksiyon-içi import ile çekiyordu. Aynı satır iki yönden okunuyor; farklı serialize edilseydi
  client'ın çözemeyeceği bir çelişki olurdu.
- **Uç envanterini programla doğrulamak ucuz.** Canlı `/openapi.json` ile contract'ı
  karşılaştıran ~20 satırlık script "Bölüm A bitti mi, future sızdı mı" sorusunu kesin
  cevapladı. (Not: `{id}` vs `{customer_id}` isim farkı yanlış alarm verdi — parametre adı
  konumsal, tel üzerinde fark yok.)

**Doğrulama:** **137 pytest / 0 fail** (101 önceki + 11 buyer + 25 approvals). Gerçek
Postgres'e karşı **13 curl senaryosu**: `/me/debts` iki dükkan (Ahmet Bakkal 4000 / Ayşe Market
10000 — contract örneğiyle birebir), `/me/transactions` dükkan bazında ayrışıyor (t3,t2,t1 vs
t6,t5,t4), **başlatan onaylayamıyor 403**, hedef onaylıyor → bakiye **4000→9000**, ikinci onay
**409**, UNCLAIMED dalı **200** + bakiye 2550→3550, CLAIMED dalı **201** + bakiye **0'da kaldı**,
§0.3 başka dükkan **403**, `p1 -> APPROVED` DB'de duruyor. Test verisi temizlendi (`down -v`),
container temiz seed'le ayakta (users=4/customers=7/tx=15/approvals=2).

**Sıradaki:** 5f — app-pos'u gerçek backend'e bağla. **İLK ADIM `adb uninstall`** (§0.6: lokal
id'ler sunucuda yok → kuyruktaki kayıtlar 404 alır, `SyncEngine` 4xx'i kalıcı ret sayıp
**siler** = sessiz veri kaybı). Sonra `OfflineFirstRepository.login()` → gerçek
`requestOtp`/`verifyOtp`, `logout()` → `remote.logout()` (§0.5). `NetworkConfig` zaten 4010'a
bakıyor, değişmeyecek.

### 2026-08-12 — Tur 34: FAZ 5 Aşama 5f — app-pos gerçek backend'e bağlandı

`login()` mock'u SİLİNDİ. Oturum artık sunucunun: gerçek JWT, gerçek expiry, gerçek
refresh token. **Cihaz testi yapıldı ve 10/10 geçti** (aşağıdaki tablo); bağlantı Wi-Fi'dan
**USB tüneline** taşındı.

**PLAN SAPMASI — login ekranında OTP alanı YOKTU (kod okumasıyla bulundu).** Plan
"`requestOtp` → `verifyOtp`" diyordu ama giriş ekranı **tek alanlıydı** (telefon) ve
`verifyOtp` kod istiyor. `OtpViewModel` satış akışına ait, giriş akışına değil. Kullanıcı
kararı: **login'e OTP adımı eklendi** — tek ekran, iki adım (telefon → [Kod Gönder] → kod
alanı belirir → [Giriş Yap]). Ayrı ekran değil, çünkü esnaf zaten titreyen telefonu elinde
tutuyor; araya geçiş koymak sadece bakışını kaldıracağı bir an eklerdi.

**İKİNCİ SAPMA — kayıt kararı lokalden sunucuya taşındı.** `LoginViewModel` önce
`repo.findUserByPhone()` (Room) çağırıyordu. §0.6 temiz kurulum gerektirdiği için **Room boş**
olacaktı → **her numara NEEDS_REGISTER'a düşerdi**, hesabı olan esnaf dahil. Artık karar
sunucunun: `verifyOtp` 404 `user_not_found` → NEEDS_REGISTER, 200 → SUCCESS. 5b'nin
"auto-register YAPMAZ" kararının client karşılığı.

**1) `Repository.login(phone): Boolean` → `requestOtp` + `signIn`.** Boolean üç sonucu tek
"tekrar dene"ye indiriyordu; `SignInResult` (yeni, `:core-domain`) ayırıyor: `Success`,
`NeedsRegister`, `InvalidCode`, `Unreachable`, `Failed`. Üçü farklı yere gidiyor — yanlış kod
aynı ekranda tekrarlanır, kayıtsız numara kayıt sorar, ölü ağ kimsenin hatası değil.

**2) `LocalSource.upsertUser` eklendi (bulunan bug).** `mirrorUser` önce `registerUser`
çağırıyordu — o **yeni rastgele UUID üretiyor**. Ama `observeCurrentUser` session'daki
**sunucu** user id'sini lokal tabloyla eşleştiriyor → id'ler asla tutmazdı, esnaf başarıyla
giriş yapıp **boş profil** görürdü. `UserDao`'ya `@Upsert` + `LocalSource.upsertUser`:
sunucunun id'si aynen korunuyor.

**3) `registerUser` artık ÖNCE sunucuya yazıyor.** Sadece lokal yazsaydı sunucunun hiç
duymadığı bir UUID üretilirdi ve ilk girişte üzerine yazılırdı — eski id altına yazılan her
şey sahipsiz kalırdı. Sunucu telefonla idempotent; ulaşılamazsa lokal fallback sürüyor.

**4) `logout()` sunucuya da gidiyor (§0.5), ama lokal temizlik KOŞULSUZ.** Başarısız bir
revoke (sinyal yok, token bitmiş) esnafı hâlâ girişli görünen bir kabukta bırakmamalı.

**5) Debug URL artık sabit değil — `posApiHost` Gradle property'si.** Emülatör `10.0.2.2`
ister, gerçek cihaz makinenin LAN IP'sini. Aynı property **iki yeri** besliyor:
`core-network`'ün `API_BASE_URL`'i ve `:app`'in `debug_api_host` string resource'u
(`resValues = true` gerekti — AGP 9'da kapalı). İkisi ayrışsaydı app, cleartext izni olmayan
bir host'u çağırırdı ve hata **"sunucu ölü" gibi görünürdü**.

**6) `src/debug/res/xml/network_security_config.xml` (YENİ).** LAN cleartext istisnası
**sadece debug'da**; release `main/`deki sıkı sürümü kullanmaya devam ediyor → istisna
release APK'ya giremez. Release APK'da `192.168.*` grep'i **0 sonuç** verdi. XML resource
manifest placeholder alamıyor, ama başka bir resource'u gösterebiliyor — bu yüzden
`@string/debug_api_host`.

**Öğrenilenler:**
- **Bayat Gradle daemon jlink hatasını taşıyordu.** `JAVA_HOME` doğruyken bile build
  VS Code'un JRE'sinden `jlink` arıyordu; `./gradlew --stop` sonrası ilk denemede geçti.
  Ortam değişkeni yeni daemon'a uygulanıyor, çalışana değil.
- **DHCP gerçekten kayıyor.** Doğrulama sırasında makinenin IP'si `.96` → `.31` değişti
  (aynı oturum içinde!). Sabit yazsaydım kullanıcı testte "sunucu ölü" görürdü. Property
  kararı tam da bunun için doğruydu.
- **Session testleri MockWebServer'a taşındı.** Giriş artık ağa çıkıyor; testler
  `login()` çağırıyordu. Kapsam korundu: gate açılır/kapanır, süre biter, process'i aşar,
  bilinmeyen user null döner — artı iki yeni dal (404 → NeedsRegister, 401 → InvalidCode).

**Doğrulama (makinede):** `:app:assembleDebug` ✓, `:app:assembleRelease` ✓,
`:core-domain:build` ✓, **50 unit test / 0 fail** (20 core-data + 8 app + 22 core-network).
`API_BASE_URL` ve `debug_api_host` **aynı IP'yi** taşıyor (generated dosyalardan teyit).
Release APK'da LAN IP yok. Backend LAN IP üzerinden `/health` 200.

**CİHAZ TESTİ YAPILDI (2026-08-12, Xiaomi 23049PCD8G / MIUI, Android 14) — 10/10 GEÇTİ.**

Sonuçlar (sunucu logu + DB sayımıyla doğrulandı):

| # | Test | Sonuç | Kanıt |
|---|---|---|---|
| 3 | Gerçek giriş | ✓ | `otp/request` 202 → `otp/verify` 200 |
| 4 | Kayıtsız numara → kayıt | ✓ | `verify` **404** → `POST /users` **201** → `verify` 200 |
| 5 | Yanlış kod `000000` | ✓ | `verify` **401**, ekranda kaldı |
| 6 | Kapat-aç | ✓ | login sormadı |
| 7 | Çıkış | ✓ | ekrandan doğrulandı (logdaki tek `logout` 401 satırı **uninstall öncesi bayat token** kalıntısı, bu turun denemesi değil) |
| 8 | Profil dolu | ✓ | isim/telefon göründü → `upsertUser` düzeltmesi tuttu |
| 9 | Veresiye yaz | ✓ | `POST /transactions` **201** |
| 10 | Offline yaz → kuyruk | ✓ | uçak modunda yazıldı, çıkınca gönderildi, DB **17 → 18** (tam +1) |

**Wi-Fi yerine USB (bu turun asıl kararı).** Wi-Fi sürekli kopuyordu ve DHCP kirası kayınca
app "sunucu ölü" gibi davranıyordu. `adb reverse tcp:4010 tcp:4010` telefonun `localhost`unu
kablodan makineye bağlıyor → `posApiHost` artık **sabit** `127.0.0.1`, bu yüzden izlenen
`gradle.properties`'e yazıldı (LAN IP'nin aksine makineye özgü değil). Kod değişmedi;
debug `network_security_config.xml` `127.0.0.1`/`localhost`u zaten içeriyordu.
Wi-Fi'a dönüş hâlâ mümkün: `./gradlew -PposApiHost=$(ipconfig getifaddr en0) :app:installDebug`.

**Öğrenilenler:**
- **Port 4010, 8080 değil.** `docker-compose.yml` `4010:8000` publish ediyor (host 4010 →
  container 8000). Tünel host portuna kurulur; 8080'e kurulsa hiçbir şey dinlemez.
- **`adb reverse` USB kopunca silinir ve geri takınca kendiliğinden gelmez.** Bu turda iki kez
  ısırdı. Dahası: APK `-PposApiHost=<LAN IP>` ile kurulduysa tüneli zaten kullanmaz —
  `API_BASE_URL` derleme zamanında gömülü, host değiştirmek **yeniden kurulum** ister.
- **MIUI `INSTALL_FAILED_USER_RESTRICTED`.** Build değil kurulum reddi: Geliştirici
  seçenekleri → **USB ile uygulama yükleme** açık olmalı (MIUI bunu bir süre sonra kendi
  kapatıyor). Ayrıca "USB hata ayıklama (Güvenlik ayarları)".
- **SMS yok, olması da beklenmiyor.** `config.py` `mock_otp_code = "123456"` — sunucu tarafı
  mock, sağlayıcı bağlı değil, kod telefona gitmez. "Kod gönderildi" yazısı mock'un parçası;
  her numara için `123456` girilir.
- **Oto-login bug değil, §6'nın ta kendisi.** `MainActivity` açılışta `isSessionValid()` ile
  start destination seçiyor, `DataStoreTokenStore` oturumu diskte tutuyor, token TTL 3600 sn.
  Test için login ekranını görmek gerekirse: uygulamadan çıkış yap, ya da
  `adb shell run-as com.example.app_pos rm -rf /data/data/com.example.app_pos/files/datastore`
  (MIUI'de `pm clear` çalışmaz).
- **`/auth/logout` 200 değil 204 döner** (`auth.py:104`, `HTTP_204_NO_CONTENT`).

**§10'un kapsamı — dürüst sınır:** doğrulanan şey **offline yazma → kuyruk → tek satır**
zinciri. Ağ ilk denemede başardığı için aynı `Idempotency-Key` ile **ikinci** bir istek hiç
oluşmadı; yani anahtarın mükerreri emmesi burada değil, backend testlerinde kapsanıyor.

**Sıradaki:** 5g (dokümanlar) + app-mobile mirror turu.

### 2026-08-12 — Tur 35: FAZ 5 Aşama 5g — dokümanlar (FAZ 5 KAPANDI)

Kod turu değil; FAZ 5'in bıraktığı izi kayda geçiriyor. Üç dosya güncellendi.

**1) [deferred.md](deferred.md) — kapanan ve açılan borçlar.**
- **A.1 KAPANDI:** `login()` mock'u silindi (Tur 34).
- **A.2 KISMEN:** OTP giriş akışında sunucuya taşındı; **satış akışında hâlâ mock**
  (`OtpService.verifyOtp` her zaman `true`) — sunucu karşılığı `POST /approvals` yazıldı ama
  app-pos bağlanmadı. Ayrım artık açıkça yazılı.
- **C KAPANDI:** "backend klasörü BOŞ" → 22 uç canlı; yedi kuralın hepsi tabloyla eşlendi.
- **YENİ borçlar kaydedildi:** C.1 (`created_by_seller_id` kolonu), C.4 (**cihaz seed'i ile
  sunucu seed'i ayrı gerçeklikler** — gerçek veri sunucuda olduğu için cihaz seed'i artık
  yanıltıcı), B.3 (`upsertUser` tuzağı app-mobile'a da lazım), §E (cihaz testi bekliyor).

**2) İki maddenin ANLAMI değişti, güncellendi:**
- **A.6 (`TokenStore` şifresiz): bahis yükseldi.** Diskteki artık sahte UUID değil, **gerçek
  JWT + 30 günlük refresh token** — sızarsa o defterin yazma yetkisi. Karar hâlâ savunulabilir
  ama gerekçesi "zaten sahte veri" olmaktan çıktı; FAZ 7'ye not düşüldü.
- **A.7 (destructive migration): risk AZALDI ama ters yönde borç doğdu.** Sunucu artık
  kayıtların sahibi, Room önbellek → wipe'ın kalıcı kaybettiği tek şey gönderilmemiş outbox.
  Buna karşılık **Room şeması sunucudan geride** (0002'deki kolon Room'da yok).

**3) `future` uçlar için bilinçli asimetri kayda geçti.** Plan kuralı "ileri-faz tabloları koda
da eklenir" idi; **backend bunu uygulamadı.** Room'da bedel sıfırdı (destructive migration,
gerçek veri yok); Postgres'te her tablo bir migration + geri alma yolu demek — kullanılmayan
tablo için ödenecek gerçek bedel. Alembic sıralı tuttuğu için gerektiği gün eklemek ucuz.

**4) [architecture-pos.md](architecture-pos.md) §8:** FAZ 4 ve FAZ 5 "YAPILDI" olarak işlendi,
sıraya app-mobile mirror eklendi.

**Öğrenilenler:**
- **Doküman güncellemek "bitti" işaretlemek değil.** Asıl iş A.6/A.7 gibi maddelerin
  **anlamının** değiştiğini yakalamaktı: ikisi de hâlâ açık, ama biri artık daha riskli,
  diğeri daha az. Sadece kapananları çizseydim bu iki kayma görünmez olurdu.
- **Kapsam dışı bırakılan da kaydedilmeli.** `future` uçların backend'de yazılmaması bir
  unutma değil karar; gerekçesi yazılmazsa altı ay sonra "eksik" gibi okunur.

**Doğrulama:** Kod değişmedi. Docs bağlantıları ve §0 referansları elle kontrol edildi.

**FAZ 5 KAPANDI.** Kalan tek iş cihaz testi (deferred.md §E).

**Sıradaki:** app-mobile mirror turu — sıra [deferred.md B.2](deferred.md)'de:
(1) ISO timestamp + `substr()` hack'ini sil, (2) `:core-network` kopyala (**Tur 34 hâliyle** —
`requestOtp`/`signIn`, eski `Boolean login()` DEĞİL), (3) Hilt, (4) outbox + WorkManager,
(5) buyer/approval uçlarını gerçek backend'e bağla. Backend seed'i app-mobile'ınkinden
türetildiği için ekranlar bugünküyle aynı veriyi görecek.

### 2026-08-12 — Tur 36: app-mobile mirror 1/4 — ISO timestamp + DB v2  [FAZ 4 (mobile) başladı]

Mirror turunun ilk adımı, ve **kasten ağdan ÖNCE**: app-mobile `"dd.MM.yyyy HH:mm"` yazıyordu,
backend ise ISO-8601 UTC yayınlıyor ve bekliyor. Ağ önce bağlanırsa hata **sessiz** olurdu —
`SimpleDateFormat` literal parse ettiği için kullanıcı tarih yerine ham ISO damgası görür
([deferred.md B.1](deferred.md)).

**1) Üretici formatı (`RoomRepository.nowStamp`):** `("dd.MM.yyyy HH:mm", tr-TR)` →
`("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)` + `timeZone = UTC`. app-pos'un
`RoomLocalDataSource`'undaki `isoFormat()` deseni birebir alındı (çağrı başına yeni formatter —
`SimpleDateFormat` thread-safe değil ve yazımlar hangi dispatcher'dan gelirse gelir).

**2) `substr()` sıralama hack'i SİLİNDİ (`Daos.kt`):** `CREATED_AT_SORT` sabiti ve 3 kullanımı
düz `ORDER BY createdAt DESC` oldu. ISO metin olarak kronolojik sıralanıyor (en anlamlı alan
başta); eski format günü yıldan önce karşılaştırdığı için rebuild gerekiyordu. Üretilen SQL'de
`substr(createdAt` **0 sonuç** ile doğrulandı.

**3) `ApprovalDao.observePendingFor`'a `ORDER BY requestedAt DESC` eklendi.** Bugün ORDER BY
**yoktu** → kartlar rowid sırasında geliyordu. Tur 39'un poll'u tabloyu yeniden yazacağı için
bu, sıralamanın kullanıcının altından kayması demekti — şimdi `GET /approvals` ile aynı sıra.

**4) DB v2** (`version = 1` → `2`). İki format metin olarak karşılaştırılamaz → destructive
rebuild; app-pos aynı sıçramayı aynı gerekçeyle yapmıştı. Şema `2.json` olarak export edildi.

**5) `SeedCallback` 21 damga ISO UTC'ye çevrildi** — ve `backend/app/seed.py` ile **birebir aynı
instant'lar**. Backend seed'i zaten bu seed'den türetilmişti (Istanbul UTC+3 → UTC dönüşümü
orada yazılıydı); o dönüşümün sonucu buraya geri yazıldı. Paylaşılan 13 transaction'ın
damgaları programla diff'lendi: **fark yok** (t14/t15 sadece sunucuda — app-pos'un c2 kaydı,
beklenen).

**6) `util/TimeFormat.kt` (YENİ, app-pos'tan kopya):** ISO → cihazın kendi saat diliminde
`dd.MM.yyyy HH:mm`. Parse edilemeyen değer **ham hâliyle** gösterilir (satır çökmesin,
tarih sessizce boşalmasın). `TransactionAdapter` artık `createdAt.toDisplayDateTime()` çağırıyor —
tek render noktası (onay kartları tarih göstermiyor).

**Öğrenilenler:**
- **Depolama formatı ile gösterim formatı ayrı işler.** app-mobile ikisini birleştirmişti
  (ekranın istediği metni DB'ye yazıyordu); bedeli DAO'daki `substr()` hack'iydi. Depolamayı
  sıralanabilir/karşılaştırılabilir seçince hack kendiliğinden düştü.
- **Sıralaması olmayan sorgu, kaynağı değişene kadar sorun göstermiyor.** `observePendingFor`
  bugün "çalışıyor" gibiydi çünkü satırları hep aynı cihaz ekliyordu. Poll gelince bozulacaktı.
- **Damgaları programla diff'le, gözle değil.** 21 literal elle çevrilse birinde saat kayması
  fark edilmezdi; iki seed'i script'le karşılaştırmak kesinlik verdi.

**Doğrulama:** `:core-domain:build` ✓, `:app:assembleDebug` ✓ **uyarısız**. Şema v2 export
edildi, üretilen DAO SQL'i temiz. **CİHAZ TESTİ BEKLİYOR** (test sırasında cihaz bağlı değildi):
şema değiştiği için `adb uninstall com.example.app_mobile` ŞART (MIUI'de `pm clear` çalışmaz) →
Borçlarım/geçmiş listelerinde tarihler **doğru sırada ve okunabilir**, Onaylar sekmesi p1/p2'yi
gösteriyor.

### 2026-08-12 — Tur 37: app-mobile mirror 2/4 — `:core-network` + Hilt

Altyapı turu: **davranış hiç değişmedi**, ağ yığını ve DI geldi. app-mobile artık app-pos ile
aynı 4 modüle sahip (`:app`, `:core-domain`, `:core-data`, `:core-network`).

**1) `:core-network` bütün olarak kopyalandı** (35 dosya): 7 Retrofit arayüzü, DTO'lar,
mapper'lar, `ApiResult`/`SafeCall`, `TokenStore`/`DataStoreTokenStore`/`AuthInterceptor`/
`TokenAuthenticator`, `NetworkModule` (`@AuthClient`/`@ApiClient` ayrımı — refresh döngüsünü
yapıca imkânsız kılar). Paket adı `com.example.app_pos.network` **korundu** (mock-pos deseni:
ayrı Gradle projeleri kod paylaşmaz, sabitler kopyalanır).

**`OrderBody`/`OrderItem` de `:core-domain`'e kopyalandı** — app-mobile'ın PGW handoff'u YOK,
ama `TransactionDto`/`TransactionMapper` bu tipleri istiyor. `:core-data`'nın `baskets`/
`basket_items` tablolarını zaten aynı gerekçeyle taşıması ("şema app-pos'la aynı kalsın, iki
taraf tek tasarım") emsal oldu.

**2) Sürüm kataloğu**: retrofit 2.11.0, okhttp **4.12.0** (5.x DEĞİL — Retrofit 2.11'in test
edildiği hat), moshi 1.15.2 (+ KSP codegen, `moshi-kotlin` değil → `kotlin-reflect` gelmesin),
datastore 1.1.1, hilt **2.60.1** (2.59 = AGP 9 uyumlu ilk sürüm), androidxHilt 1.4.0,
work 2.11.2. Gerekçe yorumları app-pos'tan taşındı (sürüm numarası tek başına neden'i anlatmaz).

**3) Base URL wiring — üç yer, ayrışırsa hata "sunucu ölü" gibi görünür:**
`core-network/build.gradle.kts` `buildConfigField` → `app/build.gradle.kts` `resValue`
(`resValues = true` gerekti, AGP 9'da kapalı) → `src/debug/res/xml/network_security_config.xml`
`@string/debug_api_host`. Property adı **`mobileApiHost`** (app-pos'un `posApiHost`'unun
kardeşi, ayrı: iki ayrı Gradle build, ve iki-cihazlı testte biri emülatör biri telefon olabilir).
Üretilen dosyalardan teyit edildi: `API_BASE_URL = http://127.0.0.1:4010/` ve
`debug_api_host = 127.0.0.1` **aynı host**.

**4) Hilt'e geçiş — `RepositoryProvider` SİLİNDİ:**
- `di/DataModule.kt` (YENİ): `AppDatabase` + `Repository` binding'i, ikisi de `@Singleton`
  (aynı dosya üzerine ikinci bir AppDatabase = iki yazma kuyruğu, iki cache).
  Şimdilik `RoomRepository`'yi bağlıyor; Tur 38'de offline-first besteye **tek satır** değişecek,
  hiçbir çağıran etkilenmeyecek.
- `di/AppScope.kt` app-pos'tan kopyalandı (`@ApplicationScope` + `@IoDispatcher`) — Tur 38'de
  outbox push'u için gerekecek.
- `App` → `@HiltAndroidApp` (eski `RepositoryProvider.get(this)` çağrısı gitti: grafik artık ilk
  enjeksiyonda kuruluyor, `onCreate` main thread'de DB açmıyor).
- `MainActivity` + **10 fragment** → `@AndroidEntryPoint`. **8 ViewModel** → `@HiltViewModel` +
  `@Inject constructor(repo: Repository)`.
- **İki elle yazılmış factory silindi.** `SellerDetailViewModel`/`CustomerDetailViewModel`
  nav-arg alıyordu; `SavedStateHandle` argümanı graf'taki adıyla zaten tutuyor →
  `by viewModels()` yeterli. **Bonus:** eski factory'nin aksine process death'i de atlatıyor
  (Tur 26'nın dersi, aynen tekrarlandı).
- `DashboardFragment` ViewModel'i yok (bottom-nav menüsünü değiştirmek için user'ı kendi
  izliyor) → repo'yu doğrudan `@Inject lateinit var` ile alıyor.

**Öğrenilenler:**
- **`api(project(":core-network"))` şart, `implementation` değil.** `:app` `TokenStore`
  enjekte edecek ve `NetworkConfig` okuyacak; ayrıca Hilt daha katı — `@Inject`
  constructor'da geçen HER tip, bileşeni üreten modülün compile classpath'inde olmalı
  (app-pos'ta bu ders `Moshi` ile alınmıştı, burada baştan uygulandı).
- **Mekanik dönüşümü script'le yap, ama tek biçimliliği ÖNCE doğrula.** 6 ViewModel'in
  `repo` satırı birebir aynıydı (grep ile teyit edildi) → tek python geçişi. Kalan 2'si
  farklıydı (constructor arg) ve elle yazıldı. Karışık olanı script'lemek sessiz hata üretirdi.
- **Ölü import derlemeyi kırmaz, kod okumasını kırar.** Factory'ler silinince
  `ViewModel`/`ViewModelProvider` import'ları öksüz kaldı; build yine yeşildi. Aynı şekilde
  factory'yi anlatan KDoc'lar da güncellendi (yorum, koddan daha uzun yaşayan yalan).

**Doğrulama:** `:core-domain:build` ✓, `:app:assembleDebug` ✓, `:app:assembleRelease` ✓,
hepsi **uyarısız**. **23 unit test / 0 fail** (22 core-network + 1 örnek) — kopyalanan ağ yığını
app-mobile'ın domain'ine karşı koşuyor. Hilt'in ViewModel modüllerini ürettiği generated
dosyalardan teyit edildi (Dagger grafiği uçtan uca doğrulanmış demek).

**Release güvenliği ayrıca kontrol edildi:** release `BuildConfig` `https://api.veresiye.example/`,
release'de `debug_api_host` **yok**, release APK'da `127.0.0.1`/`10.0.2.2` grep'i **0 sonuç**,
release'e giden `network_security_config.xml` `main/`in sıkı sürümü (debug override'ı görmüyor).

**CİHAZ TESTİ BEKLİYOR** — bu tur davranışı değiştirmedi, yani Tur 36'nın listesi + "hiçbir şey
bozulmadı" kontrolü: giriş (hâlâ mock), Borçlarım, satıcı detayı, Onaylar (Onayla/Reddet),
Profil, "Satıcı ol", Müşterilerim, müşteri detayı, POS eşleştirme, iç-nav geri oku.

**Sıradaki:** Tur 38 — gerçek login (`requestOtp`/`signIn` + `SignInResult`), session diske
(`DataStoreTokenStore`, `prime()` **bloklayıcı**), `upsertUser` tuzağı ([deferred.md B.3](deferred.md)),
outbox + `SyncEngine` + WorkManager, buyer/approval uçlarını gerçek backend'e bağlama.

### 2026-08-13 — Tur 38: app-mobile mirror 3/4 — offline-first beste + gerçek login + outbox

app-mobile'ın ağ katmanı **ilk kez çağrılıyor**. Tur 37 yığını kurmuştu ama hiçbir yerden
kullanılmıyordu; bu tur onu bağladı, mock login'i sildi ve yazma yolunu kuyruğa aldı.

**1) `:core-data` üç parçaya ayrıldı (app-pos'un yapısı):**
- `RoomRepository` → **`local/RoomLocalDataSource`**, ve artık `Repository` değil
  **`LocalSource`** uyguluyor (yeni arayüz: `Repository` + `observeAllUsers` + `upsertUser`
  + outbox metotları). Arayüz olmasının tek somut sebebi test edilebilirlik: oturum
  mantığı Room ayağa kaldırmadan saf JVM'de doğrulanabiliyor ([deferred.md B.5](deferred.md)'in kapanışı).
- `remote/RemoteDataSource` + `sync/SyncEngine` app-pos'tan kopyalandı. SyncEngine'e
  **hiç dokunulmadı** — sadece `LocalSource`/`RemoteDataSource`'a bağlı olduğu için
  app-mobile'da olduğu gibi çalıştı.
- **`OfflineFirstRepository` (YENİ)** — turun kalbi. `DataModule` artık bunu bağlıyor;
  Tur 37'de yazdığım "tek satır değişecek" sözü aynen böyle kapandı, **hiçbir çağıran
  değişmedi**.

**2) Gerçek login — mock silindi:**
- `Repository.login(phone): Boolean` → **`requestOtp(phone)` + `signIn(phone, code)`**,
  dönüş `SignInResult` (Success / NeedsRegister / InvalidCode / Unreachable / Failed).
- **`findUserByPhone` ön-kontrolü KALDIRILDI** — app-pos'un Tur 34'te yediği tuzak:
  temiz kurulumda Room boş olduğu için hesabı OLAN kullanıcıyı bile kayıt ekranına
  düşürüyordu. Kararı artık sunucu veriyor (`404 user_not_found` → NeedsRegister).
- **`upsertUser` tuzağı** ([deferred.md B.3](deferred.md)) baştan doğru yapıldı:
  `mirrorUser` sunucunun user id'sini **aynen** yazıyor. `registerUser` yeni UUID üretirdi,
  session sunucunun id'sini taşıdığı için `observeCurrentUser` eşleşmez ve kullanıcı
  **boş profil** görürdü.
- Login ekranı **tek ekran, iki adım** oldu: telefon → kod. Ayrı destination değil, çünkü
  gate back-stack'e girer ve sistem geri tuşu iki adımın ARASINA düşerdi.
  `LoginState`'e `CODE_SENT` eklendi; `codeRejected` ayrı bir bayrak, yoksa "kod gönderildi"
  ile "kod yanlış" ekranda **birebir aynı** görünüyordu.

**3) Outbox + WorkManager:**
- `addTransactionQueued(transaction, sendPayload)` — ledger yazımı + kuyruk **tek
  `db.withTransaction`**. Ayrı yazılsa araya giren process ölümü ya sunucunun hiç duymayacağı
  bir kayıt ya da hiç yazılmamış bir kayda ait gönderim bırakırdı; ikisi de sonradan tespit
  edilemez.
- **`OutboxDao.insert` `IGNORE` yapıldı** (varsayılan `ABORT`'tu). Satır id'si = transaction
  id olduğu için tekrar kuyruğa alma no-op olmalı; ABORT ile kuyruk **tam iş yaparken**
  patlardı. `observeCount()` + `recordFailure()` de eklendi (DAO'da yoktu).
- `UserDao.upsert` eklendi (`@Upsert`) — `insert` IGNORE olduğu için sunucu profili
  güncelleyemezdi.
- `SyncWorker` + `SyncScheduler` kopyalandı, App.kt `Configuration.Provider` oldu,
  manifest'e WorkManager initializer **kaldırma** node'u eklendi (bu node **wiring'in
  kendisi**, optimizasyon değil — olmadan @HiltWorker runtime'da kurulamıyor).
- `tokenStore.prime()` **runBlocking** — app-pos'un dersi aynen: MainActivity
  startDestination'ı `isSessionValid()` ile onCreate'te seçiyor, arka planda primelamak
  yarış yaratır ve giriş yapmış kullanıcı **aralıklı olarak** login ekranı görür.

**4) Approval'lar gerçek backend'e bağlandı (kısmen):**
- `approvePending`/`rejectPending` artık **sunucuya gidiyor**, ve bilinçli olarak
  offline-toleranslı DEĞİL: onay karşı tarafın beklediği bir karar, sadece bu cihaza
  yazmak "onaylandı" gösterirken karşı tarafa hâlâ bekleyen kart gösterirdi. Ulaşılamazsa
  lokal satır **duruyor**, kullanıcı tekrar deneyebiliyor.
- Onay sonrası gelen Transaction **kuyruğa alınmadan** yazılıyor (`addTransaction`,
  `addTransactionQueued` değil) — sunucu zaten yazdı, kuyruğa alsak ikinci kopya giderdi.
- `requestApproval` sunucuya gönderiyor ama **yanıta bakmadan** lokali de yazıyor;
  ikisinin ayrışabildiği yer burası ve [deferred.md](deferred.md)'de dürüstçe duruyor.

**Öğrenilenler:**
- **Bir arayüzün arkasındaki tek binding, turu ucuzlatan şeydir.** Tur 37'de `DataModule`
  `RoomLocalDataSource` bağlıyordu; bu turda `OfflineFirstRepository`'ye çevirmek **tek
  fonksiyon** oldu ve 8 ViewModel'in hiçbiri değişmedi.
- **Kopyalanan kod, bağlı olduğu soyutlama kadar taşınır.** `SyncEngine` sıfır değişiklikle
  çalıştı çünkü sadece iki arayüze bakıyor; `FakeSyncRepository` ise app-mobile'ın daha
  geniş `Repository`'sine göre elle genişletilmek zorunda kaldı.
- **`@Insert` varsayılanı `ABORT`'tur** ve bu kuyruk tablolarında sessiz bir bomba: hata
  ancak yeniden deneme anında, yani en kötü anda patlar.

**Doğrulama:** `:app:assembleDebug` ✓ `:app:assembleRelease` ✓ (tek uyarı: `@ApplicationContext`
anotasyon hedefi — app-pos'ta da var, davranışa etkisi yok). **30 unit test / 0 fail**
(22 core-network + 8 app: SyncWorker karar tablosu dahil). Release güvenliği: APK'da
loopback IP grep'i **0**, `API_BASE_URL = https://api.veresiye.example/`.

**CİHAZDA DOĞRULANDI** ✓ (2026-08-13, Tur 36-37-38 birlikte) — tarih formatı ve giriş akışı
doğru çalıştı; yazma yolunda çıkan hatalar Tur 38b/38c'de düzeltildi. Şema v2 olduğu için
**`adb uninstall com.example.app_mobile` gerekmişti**. Test edilen sıra:
giriş (telefon → kod `123456`) → Borçlarım →
Onaylar (Onayla/Reddet artık sunucuya gidiyor) → Profil → çıkış → tekrar giriş
(**session diskte kalmalı, tekrar kod sormamalı**).

**Sıradaki:** Tur 39 — pull ekseni ([deferred.md §F](deferred.md)): backend'e
`approvals.updated_at` (migration 0003) + `GET /approvals` filtreleri, client'a `PullEngine`,
ön planda 15 sn poll. Bu olmadan POS, app-mobile'dan gelen onayı **göremiyor**.

### 2026-08-13 — Tur 38b: cihaz testinde bulunan yazma-yolu hataları  [ilk gerçek cihaz testi]

Tur 36/37/38 **cihazda test edildi**. Tarih formatı ve giriş akışı doğru çalıştı; yazma
yolunda dört kullanıcı-görünür hata çıktı. Kod incelemesi bunların **altı kök nedene**
indiğini gösterdi — üçü Tur 38'de yazdığım kodda.

**1) ÇİFT YAZIM — 75 TL onayı ledger'a iki kez düştü (veri bozan)**
`OfflineFirstRepository.approvePending` sunucudan dönen transaction'ı yazıyor, sonra
`local.approvePending` çağırıyordu — ve o metot **kendi içinde ikinci bir `addTransaction`**
yapıyor, üstelik yeni bir `UUID` ile. Farklı id'ler `insert`'ün `IGNORE`'unu atlatıyor →
iki satır da kalıyor, bakiye şişiyor.
→ `LocalSource.markApprovalDecided(approvalId, status)` eklendi: sadece durumu değiştirir,
ledger'a dokunmaz. Ledger'a yazan tek yer artık sunucunun döndürdüğü kayıt.

**2) TELEFON NORMALİZASYONU üç yerde üç farklı (diğer hataların besleyicisi)**
Kotlin `digits()` → `05554443322`; SQL `REPLACE` → `905554443322`. `UserDao` **substring**
(`LIKE '%..%'`) karşılaştırdığı için **login çalışıyordu**, ama `CustomerDao.claimByPhoneDigits`
**tam eşitlik** kullandığı için **claim sessizce hiçbir satırı bulmuyordu** (dönen `Int` de
atılıyordu). Müşteri UNCLAIMED kalıyor → onaysız-yazma dalına düşüyordu.
→ `PhoneFormat` **`:core-domain`'e taşındı** (veri katmanı erişebilsin diye), SQL'den
`REPLACE`/`LIKE` **tamamen kalktı**, sorgular düz `WHERE phone = :stored`. Tek kanonik form:
E.164 — backend'in de formatı, ve artık index kullanılabiliyor.

**3) UNCLAIMED kararını CLIENT veriyordu**
`claimedByUserId == null` ise onaya gitmeden, üstelik **kuyruksuz** `addTransaction`.
→ Karar **sunucuya bırakıldı**. `POST /approvals` iki şekil döndürüyor (201 Approval /
200 Transaction) ama `ApprovalApi.send` tek tip (`ApprovalDto`) yazılmıştı — 200 dalını
temsil **edemiyordu**. Yeni `ApprovalSendResultDto` iki şekli de taşıyor, `approvalId` /
`transactionId` alanıyla ayırıyor (status code `apiCall` içinde kayboluyor).

**4) `initiatePayment` sunucuya HİÇ gitmiyordu** — düz `local.initiatePayment` delegasyonu.
→ Artık `requestApproval` üzerinden geçiyor; customerId çözümü lokal kalıyor.

**5) SESSİZ BAŞARISIZLIKLAR** — `requestApproval` `Unit`, `initiatePayment` **koşulsuz
`true`** dönüyordu; `CustomerDetailFragment` Toast'ı sonucu beklemeden `isClaimed.value`'dan
seçiyordu (`WhileSubscribed` initial `false` → claimed müşteride bile yanlış mesaj).
→ `ApprovalOutcome` sealed tipi (`SentForApproval` / `WrittenImmediately` /
`NoCustomerRecord` / `QueuedOffline` / `Failed`) + tek mesaj eşlemesi
(`util/ApprovalMessage.kt`). `Failed` sunucunun kendi açıklamasını taşıyor.

**6) `shopPhone` ekranda yanıltıcıydı** — satıcı detayında **etiketsiz** numara duruyordu;
test sırasında "ödeme buraya gider" sanılıp başka ekrana girildi ve "kullanıcı bulunamadı"
alındı. `+902123334455` Ayşe Market'in **dükkan telefonu**, hesabın numarası `+905553334455`.
Veri hatası değil, **etiket hatası**.
→ "Dükkan telefonu: %s" etiketi + **tıklayınca arama** (`ACTION_DIAL`). `UserDao`'ya
shopPhone araması eklemek reddedildi: hesap kişiye aittir, dükkana değil.

**Öğrenilenler:**
- **İki metot aynı işi yapıyorsa, biri "de" yapıyordur.** `approvePending` iki katmanda da
  vardı ve ikisi de ledger'a yazıyordu; besteleyen sınıf ikisini de çağırınca hata çıktı.
  Kural: composing katman, alt katmanın **yan etkisiz** varyantını çağırmalı.
- **Aynı veriyi iki farklı şekilde karşılaştıran iki sorgu = zaman bombası.** Biri gevşek
  (`LIKE`), biri katı (`=`) olunca gevşek olan "çalışıyor" görüntüsü verip katı olanın
  sessiz başarısızlığını gizledi. Normalizasyon **tek yerde**, sorgu **düz eşitlik**.
- **Sunucu iki şekil dönüyorsa, client tipi de iki şekil taşımalı.** `ApprovalDto` 200
  dalını temsil edemediği için client kendi kararını vermek zorunda kaldı — asıl hata buydu.
- **`Boolean` dönüş "ne oldu"yu anlatamaz.** `true` hem "onaya gitti" hem "deftere yazıldı"
  demekti; ekran ikisini ayırt edemeyince kullanıcıya yanlış şey söyledi.

**Doğrulama:** `:app:assembleDebug` ✓ `:app:assembleRelease` ✓ uyarısız.
**42 unit test / 0 fail** (30 → 42; yeni 12'si bu turun düzeltmelerini kilitliyor):
6 `ApprovalWritePathTest` (MockWebServer ile **gerçek** 201/200 ayrımı) + 6 `PhoneFormatTest`.

**Mutasyon kontrolü yapıldı:** çift yazım kasten geri konuldu → `approving books the entry
exactly once` **kırmızıya döndü**; düzeltme geri alınınca yeşil. Yani test gerçekten koruyor,
yanlışlıkla yeşil kalan bir test değil.

**CİHAZDA DOĞRULANDI** ✓ (2026-08-13) — çift yazım gitti: sunucu verisiyle teyit edildi (p2
`APPROVED`, ledger'da mükerrer kayıt yok). Test edilen adımlar:
1. Onaylar → 75 TL'yi onayla → ledger'a **bir kez** düşmeli
2. Borçlarım → Ayşe Market → Öde → "Onaya gönderildi" (sunucuya gitmeli)
3. Müşterilerim → CLAIMED müşteri → Ödeme Al → onaya gider, ledger'a hemen yazmaz
4. UNCLAIMED müşteri (Fatma/Hasan) → sunucu 200 → "Deftere yazıldı"
5. Geçmişi olmayan satıcıya ödeme → "kaydınız yok", sahte başarı YOK
6. Uçak modu → "İnternet yok — cihaza kaydedildi"
7. Ayşe detayında numara → "Dükkan telefonu: …", tıklayınca arama açılır

Sunucu tarafı teyidi (çift yazım için kesin kanıt):
`docker exec backend-db-1 psql -U postgres -d veresiye -c "SELECT transaction_id, amount_minor FROM transactions ORDER BY created_at DESC LIMIT 5;"`

**Sıradaki:** Tur 39 — pull ekseni ([deferred.md §F](deferred.md)).

### 2026-08-13 — Tur 38c: cihaz testi 2. tur — takılan onay, OTP UI, müşteri listesi

Tur 38b'nin düzeltmeleri cihazda **doğrulandı**: sunucu verisiyle bakıldı, p2 (75 TL)
`APPROVED` ve ledger'da mükerrer kayıt **yok** — çift yazım gitti. İkinci turda üç iş çıktı.

**1) `p1` onayı takılı kalıyordu — sistem aslında DOĞRU davranıyordu**

Teşhis sunucu verisinden geldi: `p1.target_user_id = u1`, giriş ise `u_owner` ile yapılmıştı.
Sunucu `_decide` ([`approvals.py:189`](../backend/app/routers/approvals.py)) **403 forbidden**
döndürüyor. Yani onaylanamaması doğru — **hata o kartın ekranda olması**.

Kök neden: cihaz seed'i **her iki onayı da** yazıyor, kim giriş yaparsa yapsın; Room tablosu
sunucudan bağımsız. `ApprovalDao.observePendingFor` doğru filtreliyor ama filtrelediği veri
yanlış.

- `DecisionOutcome` (YENİ, `:core-domain`): `Applied` / `NotYours` / `AlreadyDecided` /
  `Unreachable` / `Failed`. `approvePending`/`rejectPending` artık bunu döndürüyor.
- **403 ve 409 → kart DÜŞÜRÜLÜYOR** (`ApprovalDao.delete`). Bu iki cevap tekrar denemekle
  değişmez; satırı bekletmek onu ekranda sonsuza dek bırakır.
- **Silmek, durum yazmak değil** — cihaz gerçek kararı BİLMİYOR. `APPROVED`/`REJECTED`
  yazmak olmayan bir kararı uydurmak olurdu; o iz zaten sunucuda duruyor.
- **Ağ hatası kartı KORUYOR** — orada tekrar denemek doğru hamle. Bu ayrım, "403'te sil"
  kuralının kurtarılabilir hataları yutmasını engelliyor.
- `ApprovalsViewModel.approve/reject` **fire-and-forget'ti**, Toast sonucu beklemeden
  gösteriliyordu (38b'de düzelttiğim hatanın aynısı, başka ekranda) → sonuç bağlandı.

**2) OTP girişi "yeni sayfa" gibi hissettiriyordu**

İkisi de tek fragment; fark tek satırdaydı:

| | app-pos | app-mobile (öncesi) |
|---|---|---|
| Telefon alanı | ekranda **kalır**, `isEnabled = false` | **`GONE`** olurdu |
| Kod alanı | telefonun **altına** eklenir | **yerine** geçerdi |

Kullanıcının az önce yazdığı numara kaybolunca adım, "aynı formun devamı" değil "sayfa
değişti" gibi okunuyordu. `LoginFragment.kt` + `fragment_login.xml` app-pos desenine
çevrildi (`codeLayout` artık `phoneLayout`'un altında).

**3) Müşteri seçmede liste boştu — BİLİNÇLİ bir karar tersine çevrildi**

Liste ve adapter zaten vardı (`CustomerSelectFragment` dashboard'ın `CustomerAdapter`'ını
kullanıyor); boşluk tek satırdan geliyordu: `if (q.isEmpty()) emptyList()`.

Bu **kasıtlıydı** ve kodda gerekçesi yazılıydı: *"tam liste göstermek, bu ekranın önlemek
için yazıldığı karışıklığın ta kendisi"* — kasada yanlış satıra dokunma riski.

**Kullanıcı bilerek tersine çevirdi**, ve gerekçesi sağlam: mock-pos handoff'undan gelince
ekran boş kalıyor, esnaf ismi **ezberden** yazmak zorunda kalıyordu — yazımını doğrulama
imkânı olmadan. İsimleri görmek yanlış kişiyi seçmeyi asıl **engelleyen** şey.
→ `if (q.isEmpty()) all`, ve **eski gerekçe yorumları güncellendi** (ViewModel KDoc,
Fragment KDoc). `search_prompt` string'i ölü kaldı → `no_customers_yet` ile değiştirildi
("henüz müşteriniz yok"), çünkü boş liste artık "arama yapmadın" değil "defter boş" demek.

**Öğrenilenler:**
- **Her başarısızlık "tekrar dene" değildir.** 403/409 ile ağ hatasını aynı kefeye koymak,
  hiç düzelmeyecek bir kartı sonsuza dek ekranda tutuyordu. Ayrımı yapan tip
  (`DecisionOutcome`) davranışı da belgeliyor.
- **Bilmediğin bir kararı uydurma.** Stale kartı `REJECTED` işaretlemek kolay olurdu ama
  cihaz gerçekte ne olduğunu bilmiyor; silmek dürüst olan.
- **Bir tasarım kararını tersine çevirirken yorumları da çevir.** `q.isEmpty()` dalının
  gerekçesi kodda yazılıydı; sadece kodu değiştirmek, altı ay sonra "yorum niye kodla
  çelişiyor?" sorusunu üretirdi.
- **`stateIn(WhileSubscribed)` testte `first()` ile okunmaz** — başlangıç değerini alır ve
  test HER durumda yeşil kalır. Toplayıcı + `advanceUntilIdle` gerekiyor; bu tuzağa düşüp
  mutasyon kontrolüyle yakaladım.

**Doğrulama:** her iki projede `assembleDebug` ✓ `assembleRelease` ✓ uyarısız.
**56 unit test / 0 fail** (app-mobile 45 + app-pos 11; 42+11'den 45+11'e).
Yeni: 3 `ApprovalWritePathTest` (403/409/ağ) + 3 `CustomerSelectViewModelTest`.

**Mutasyon kontrolü (iki ayrı):** (a) `if (q.isEmpty()) all` → `emptyList()` yapıldı, test
**kırmızıya döndü**; (b) 38b'nin çift yazım testi hâlâ koruyor. Testler gerçekten kilitliyor.

**CİHAZDA DOĞRULANDI** ✓ (2026-08-13; şema değişmediği için `adb uninstall` gerekmedi):
1. Onaylar → p1 → kart **düşüyor** ✓
2. Giriş: telefon alanı **ekranda kalıyor**, kod altına açılıyor ✓
3. "Numarayı değiştir" ✓
4. mock-pos → VERESİYE → müşteri **listesi dolu geliyor**, arama daraltıyor ✓
5. Seçim → onay → OTP akışı bozulmadı ✓

**Sıradaki:** Tur 39 — pull ekseni + "sunucu tek gerçeklik" ([deferred.md §F](deferred.md)).

### 2026-08-14 — Tur 39: pull ekseni — "sunucu tek gerçeklik" (Onaylar hattı)

**Sistemdeki yapısal boşluk kapandı:** iki client da sunucuya sadece YAZIYORDU.
`RemoteDataSource`'ta **9 okuma sarmalı** vardı ve **hiçbirinin çağıranı yoktu**;
`Repository` arayüzü "çekmek" fiilini hiç tanımlamıyordu. Sonuç: app-mobile'dan gelen
veresiye onayını POS **göremiyordu**. Artık görebiliyor.

**Kapsam kararı:** sadece Onaylar ekseni. Desen bir kez kurulup kanıtlanıyor;
Borçlarım/Müşterilerim/geçmiş pull'u aynı deseni izleyerek Tur 40'a kaldı.

**1) `since` yerine TAM LİSTE SENKRONU — plandan bilinçli sapma**

§F.1 `?since=` öneriyordu. Kod okumasında iki somut sorun çıktı ve karar değişti:
- **Sunucunun ISO formatı saniye hassasiyetinde** (`schemas.py` `IsoUtc` →
  `%Y-%m-%dT%H:%M:%SZ`, mikrosaniye kırpılıyor çünkü client düz `SimpleDateFormat` ile
  ayrıştırıyor). Dışlayıcı bir `since` **aynı saniyedeki satırları sessizce düşürür**.
- **Karar verilmiş satır `since`'e hiç düşmez** → kart ekranda kalır. Yani çözmeye
  çalıştığımız "hayalet kart" sorununun ta kendisi geri gelirdi.

Tam liste **otoriter**: cevapta olmayan satır lokalde de silinir → hayalet kart yapısal
olarak imkânsız. Bekleyen onay sayısı küçük, yani ucuz. `updated_at` yine de eklendi
(denetim izi + ileride FCM/cursor için) ama **pull onu kullanmıyor**.

**2) Backend: migration 0003 + `GET /approvals` filtreleri**

`approvals.updated_at` eklendi (nullable → `requested_at`'ten backfill → NOT NULL, çünkü
tablo dolu). `server_default` kullanılmadı: bu kod tabanında **zaman damgalarının sahibi
Python**, her yazma noktası açıkça `datetime.now(UTC)` çağırıyor — `onupdate` hiç
kullanılmıyor. Üç yazma noktası damgalanıyor (oluşturma / approve / reject).

`GET /approvals`: `status` (varsayılan PENDING — **bugünkü davranış korunuyor**, `ALL`
geçmişi açar) + `limit` (varsayılan 100). Poll edilen bir uçta sınırsız liste kabul edilemez.
`_approval_out` 13 alanı tek tek saydığı için kolonu wire'a çıkarmak ayrıca gerekti.

**3) `app/reset.py` — HTTP ucu YOK**

`docker compose exec api python -m app.reset`. Kullanıcının açık kararı: token'lı/debug-gated
bir uç bile eklenmeyecek. **Teknik nokta:** `transactions` üstünde append-only trigger var
(`BEFORE UPDATE OR DELETE`), düz `DELETE` patlar → `TRUNCATE ... RESTART IDENTITY CASCADE`
(tablo-seviyesi işlem, satır trigger'ı tetiklenmez; append-only garantisi bozulmuyor).

**4) İki client: `PullEngine` + `refreshApprovals()`**

`SyncEngine`'in ikizi — `@Singleton` + constructor injection + `mutex`, **DI modülü
gerektirmedi**. Üç kural:
- Sunucunun listesi **otoriter** (gelmeyen satır silinir)
- **`Unreachable` boş liste DEĞİLDİR** → lokale hiç dokunulmaz. Bunu karıştırmak,
  sinyalsiz bir telefonun kendi kutusunu silmesi demekti.
- Okunamayan satır **düşürülür, tahmin edilmez** (tip işareti taşıyor)

`LocalSource.syncApprovals` tek Room transaction'ında sil+yaz — ikisi ayrı gözlemlenirse
liste 15 saniyede bir boşalıp dolar (glitch).

**Mevcut `PendingApproval.toEntity` KULLANILMADI:** `channel`'ı APP_PUSH sabitliyor,
`status`'ü PENDING varsayıyor, `initiatorRole`'ü türetiyor — yani sunucunun **az önce
söylediği üç şeyi atıyor**. Doğrudan `ApprovalDto → ApprovalEntity` mapper'ı yazıldı.

**5) app-pos: Onaylar kutusu (§A.4 kapandı) — turun asıl işi**

app-pos çok geride başladı: `PendingApproval`/`DecisionOutcome` domain tipleri **yoktu**,
`ApprovalDao`'nun **hiçbir çağıranı yoktu**, ekran yoktu. Hepsi eklendi (app-mobile'ın
simetriği; tek fark **rol bölümü yok** — POS her zaman dükkan tarafı).

**Keşifte bulunan iki tuzak:**
- `ApprovalDao.observePendingFor`'da **`ORDER BY` YOKTU**. app-mobile'da Tur 36'da
  düzeltilmişti ve gerekçesi tam olarak bu tur: poll tabloyu yeniden yazınca **sıra
  kullanıcının altından kayardı**. Tek cihaz yazarken görünmeyen, poll gelince patlayan bir bug.
- `delete(id)` yoktu → 403/409'da kartı düşürmek imkânsızdı.

**6) §F.3'ün iki katmanı**: ekran açıkken **15 saniye** (`repeatOnLifecycle(STARTED)`,
fragment durunca iptal), app-pos'ta ayrıca **`PullWorker`** (15 dk, arka plan).
**app-mobile'da arka plan pull'u YOK** — pil kararı korundu; asimetri kasıtlı ve belgeli.

**7) A.9 telefon normalizasyonu app-pos'ta düzeltildi**

`PhoneFormat` `:app` → **`:core-domain`**; `UserDao` `LIKE '%..%'` ve `CustomerDao`
`REPLACE(...)` → düz `WHERE phone = :stored`. Bu turda yapıldı çünkü pull sunucudan
**kanonik E.164** yazıyor; gevşek eşleştirme o veriyle çakışırdı.

**8) Cihaz seed'leri kalktı** (iki app'te de, C.4 kapandı). Demo verisi artık tek yerde:
`backend/app/seed.py`. Ekranlar sunucudan besleniyor.

**Öğrenilenler:**
- **Bir zaman damgası "ne zaman oldu" demez, "neyin zamanı" der.** `requested_at` sorulma
  anıydı; karar anını tutan alan olmadığı için karara bağlanmış satır bekleyenden zamanla
  ayırt edilemiyordu. İki farklı olay = iki farklı kolon.
- **Başarısızlığı boşlukla karıştırma.** "Sunucu bir şey döndürmedi" ile "sunucuya
  ulaşılamadı" aynı `if` dalına düşerse, sinyalsiz cihaz kendi verisini siler. Tip bunu
  ayırmak zorunda (`Unreachable` ≠ `Refreshed(0)`).
- **Sunucudan gelen veriyi lokal kurallarla yeniden türetmek, sormanın amacını yok eder.**
  `toEntity` üç alanı "hesaplıyordu"; pull'un tüm anlamı o üç alanı sunucudan almak.
- **Görünmeyen bug, koşul değişince patlar.** app-pos'un eksik `ORDER BY`'ı tek yazar
  varken zararsızdı; poll gelir gelmez kullanıcının altından sıra kaydıracaktı.

**Doğrulama:** iki projede de `assembleDebug` ✓ `assembleRelease` ✓ (tek uyarı:
`@ApplicationContext` anotasyon hedefi — eskiden beri var, davranışa etkisi yok).
Release güvenliği: **iki APK'da da loopback IP grep'i 0**.

- **Backend: 146 pytest / 0 fail** (137 → 146; yeni 9'u filtreleri + `updated_at`'i kilitliyor)
- **app-mobile: 52 test / 0 fail** (45 → 52; yeni 7'si `PullPathTest`)
- **app-pos: 43 test / 0 fail** (11 → 43; yeni `PullWorkerTest` 6 + `PhoneFormatTest` 6 + mevcut suite)

**Mutasyon kontrolü (üç ayrı):**
1. `approve`'dan `updated_at` damgası kaldırıldı → `test_approving_moves_updated_at...`
   **kırmızı**
2. `PullEngine`'de `NetworkError` dalı lokali temizleyecek şekilde bozuldu →
   `an unreachable server leaves local rows untouched` **kırmızı** (en kritik kural)
3. `PullWorker`'da `Failed → Result.failure()` yapıldı →
   `a refusal never abandons the schedule` **kırmızı**

**CİHAZ TESTİ BEKLİYOR — turun asıl sınavı, İKİ CİHAZ gerekiyor.**
Şema değişti (app-pos'a approval yüzeyi) → **`adb uninstall` ŞART** (MIUI'de `pm clear`
çalışmıyor). Koşullar: `docker compose up` + `adb reverse tcp:4010 tcp:4010`.
1. `docker compose exec api python -m app.reset` → temiz sunucu
2. Cihaz seed'i kalktığı için ekranlar **sunucudan** dolmalı (boş değil)
3. **app-mobile'da ödeme onaya gönder → app-pos'un Onaylar ekranında ~15 sn içinde GÖRÜNMELİ**
   ← §F'nin "bugün imkânsız" dediği şey
4. app-pos'ta Onayla → ledger'a **bir kez** düşsün (38b'nin çift yazım guard'ı)
5. app-mobile'da kart **kendiliğinden düşsün** (poll senkronu)
6. Reddet: yazma yok, karşı taraftan kart düşsün
7. Uçak modu → kutu **boşalmasın** (`Unreachable`)
8. `p1` hayalet kartı artık hiç görünmemeli (kök neden kapandı)

**DOĞRULANMADI (Docker daemon kapalıydı):** `alembic upgrade head` ve `python -m app.reset`
**gerçek Postgres'te koşulmadı**. pytest SQLite üstünde ve şemayı `models.py`'den kuruyor →
**migration 0003 test suite tarafından doğrulanmıyor**. `TRUNCATE ... CASCADE` de
Postgres'e özgü. Reset'in tablo listesi ve seed döngüsü SQLite'ta ayrıca sınandı (tablo
listesi metadata ile birebir örtüşüyor), ama **cihaz testinden önce Docker'la doğrulanmalı.**

**Not:** app-pos daha önce **hiç cihazda çalıştırılmadı** (§E). app-mobile'da 38b/38c diye
iki düzeltme turu çıktığı düşünülürse, burada da bir Tur 39b beklemek gerçekçi.

**Sıradaki:** Tur 39b (cihaz testi düzeltmeleri) → Tur 40: pull'un kalan ekseni
(Borçlarım / Müşterilerim / geçmiş), aynı desen.

### 2026-08-14 — Tur 40: pull'un kalan ekseni — defter de sunucudan geliyor  [PLANLAMA HATASI DÜZELTMESİ]

**Neden bu tur zorunlu hâle geldi.** Tur 39 cihaz testinin ilk denemesinde her iki app'te de
ekranlar **boştu**: app-mobile'da iki farklı numarayla girildi, Borçlarım 0,00 TL; app-pos'ta
Ahmet Bakkal'ın defteri görünmüyordu.

Sebep bir bug değil, **sıralama hatasıydı — benim planlama hatam.** Tur 39 iki şeyi aynı
anda yaptı: (a) cihaz seed'lerini sildi, (b) **sadece Onaylar** için pull yazdı. Yani
Borçlarım/Müşterilerim/geçmiş ekranlarının okuduğu Room tabloları hem yerel olarak
doldurulmuyor, hem de sunucudan çekilmiyordu — boş bir veritabanını doğru şekilde
gösteriyorlardı.

Doğru sıra "önce tüm pull yolu, sonra seed'i kaldır" olmalıydı. Tur 40 o boşluğu kapatıyor:
**okuma ekseninin tamamı**.

**1) `PullEngine`'e ikinci metot (iki app'te de, farklı isimle çünkü farklı yön):**

- app-mobile → `pullMyLedger(userId)`: `GET /me/debts` ile hangi dükkâna borçlu olduğunu
  öğrenir, `storeShopNames(...)` ile dükkân adı/telefonunu yazar, sonra **her dükkân için**
  `GET /me/transactions?seller_id=…` çağırır ve `storeBuyerLedger(...)` ile ekler.
- app-pos → `pullBook()`: `GET /customers` + her müşteri için `GET /transactions`;
  `storeCustomers(...)` + `storeLedger(...)`.

**Silme kuralı ONAYLARDAN FARKLI, ve bu ayrım tipin kendisinde duruyor.** Bir onay cevapta
yoksa **karar verilmiştir → silinir**. Bir ledger satırı cevapta yoksa **geri alınmamıştır**
— defter append-only; cevap sadece kısmi gelmiştir. Bu yüzden ledger pull'u hiçbir koşulda
`delete` çağırmaz; iki yolu tek metotta birleştirmek bu farkı derleme zamanından çalışma
zamanına indirirdi. Test: `an empty debt list never deletes stored entries`.

**2) `Repository`'ye ikinci fiil:** `refreshMyLedger()` (mobile) / `refreshBook()` (pos).
Tetikleyiciler:

| Yer | app-mobile | app-pos |
|---|---|---|
| Açılış | `App.kt:78` `refreshMyLedger()` | `App.kt:78` `refreshBook()` |
| Ön plan poll | `DebtsViewModel` 30 sn | `CustomersViewModel` (ekran açılışı) |
| Arka plan | yok (pil kararı) | `PullWorker` 15 dk → `refreshBook()` + `refreshApprovals()` |

**3) `storeCustomers` sunucunun bakiyesini ATAR.** Sunucu `balance_minor` gönderiyor ama
yerel model bakiyeyi `SUM(transactions)` ile **türetiyor** — iki kaynak yazmak, ledger ile
bakiyenin ayrışabildiği bir durum yaratırdı. Bakiye tek yerden gelir: defterin kendisinden.

**4) `storeLedger` / `storeBuyerLedger` insert-IGNORE.** Aynı satır hem outbox drain'inden
hem pull'dan gelebilir; `transactionId` birincil anahtar olduğu için ikinci geliş sessizce
düşer. Çift yazım yapısal olarak imkânsız.

**Sonuç:** ekranların tamamı artık sunucudan besleniyor; cihaz seed'inin kaldırılması
(Tur 39, C.4) ancak bu turla birlikte tutarlı hâle geldi.

**Öğrenilen:** **Bir kaynağı kapatmadan önce yerine geçecek kaynağın tamamı bağlanmış
olmalı.** "Seed'i kaldır" ve "pull'u yaz" aynı turda ama yarım yapıldığında, sistem hatasız
şekilde yanlış çalışır — hiçbir yerde exception yok, sadece her yer boş.

### 2026-08-14 — Tur 40b: cihaz testi 3. tur — 4 UI/veri hatası + bir build hatası

Kullanıcı ekran-ekran (8 ss) hata raporu verdi. Bulgular ve kök nedenleri:

**0) ASIL KÖK NEDEN — kurulu APK Tur 40'ı içermiyordu (benim hatam).** Tur 40 sonrası
app-pos için sadece `:app:compileDebugKotlin` koştum, **`assembleDebug` koşmadım** → telefona
kurulu APK 12:35'teki Tur 39 build'iydi. Raporlanan "posta müşteri görünmüyor" semptomlarının
çoğu buradan geliyordu.

**Kanıt sunucu loglarındaydı:** `GET /me/debts` → 34 çağrı, `GET /customers` → **0 çağrı**.
app-pos hiç sormamıştı, çünkü soracak kod APK'da yoktu.

**Yeni alışkanlık:** kurulumdan sonra APK'nın içeriği doğrulanıyor —
`unzip -p app-debug.apk classes*.dex | strings | grep -c pullBook`. "Derlendi" ile
"telefonda çalışıyor" arasındaki farkı komutla kapatan tek şey bu.

**1) ss3 — buyer'ın stub müşteri satırları POS'un "Müşterilerim" listesine sızıyordu.**
`storeBuyerLedger` bir satır yazarken karşı taraf için müşteri kaydı türetiyor
(`claimedByUserId = buyer`). Ama `CustomerDao.observeForSeller` bunları da döndürüyordu →
listede isimsiz/numarasız satırlar. Düzeltme sorgunun kendisinde:
`AND (claimedByUserId IS NULL OR claimedByUserId <> :sellerId)` — kendi hesabına ait stub
satır, o hesabın müşteri listesine giremez.

**2) ss1 — Ayşe Market'in telefonu görünmüyordu.** `GET /me/debts` dükkân **adını**
gönderiyordu ama **numarasını** göndermiyordu; ekranın gösterecek verisi yoktu. Uçtan uca
eklendi: `backend/app/routers/buyer.py` (`shop_phone=(seller.shop_phone or seller.phone)`)
→ `schemas.SellerDebt.shop_phone` → `SellerDebtDto` → `PullEngine` → `storeShopNames`.

Yerel yazımda kural: `existing.shopPhone ?: shopPhone` — kullanıcının kendi girdiği numara
sunucudan gelenle **ezilmez**.

**3) ss6 — "Geçersiz numara" hatası, oysa numara kayıtlı.** Kullanıcının kendi teşhisi
doğruydu: *"uygulamanın server ile bağlantısı kopunca diyor bunu, arada usb koptuğu için."*
`requestOtp` **`Boolean`** dönüyordu → "sunucu reddetti" ile "sunucuya ulaşılamadı" tek
`false`'ta birleşiyordu. Ağ kopması kullanıcıya *kendi hatası* gibi gösteriliyordu.

Düzeltme: `OtpRequestResult` = `Sent` / `Refused` / `Unreachable` (iki app'te de), +
`LoginState.UNREACHABLE`. Tur 39'un `PullOutcome` dersinin aynısı, bu sefer login yolunda.

**4) ss2 — alt navigasyonda hiçbir sekme seçili görünmüyordu** (detay ekranından çıkınca).
`setupWithNavController` yalnızca **sekme olan** hedefi vurguluyor; detay ekranı sekme
olmadığı için bar tamamen sönüyordu ve geri dönünce de yanmıyordu (zaten "ayrılmış" sayılan
sekmeye dönmek seçim değişikliği üretmiyor). `keepTabSelectedOnSubScreens` her alt ekranı
ebeveyn sekmesine eşliyor.

⚠️ Burada bir tuzak var: `selectedItemId = …` atamak sekmeye **tıklanmış gibi** davranır ve
kullanıcıyı açtığı detay ekranından geri fırlatır. Doğrusu `item.isChecked = true` — sadece
vurguyu boyar.

**Sunucu hiçbir ödemeyi kaybetmemişti.** psql ile doğrulandı: 252525 TL ve 2000 TL kayıtlı;
"görünmeyen" 1000 TL ise `348571dc…` no'lu approval ve hâlâ `PENDING` — çünkü hedefi
**u_market (Ayşe Market)**, app-pos'un hesabı değil. Yani doğru davranış.

**Doğrulama:** backend **146 test**, app-mobile **55 test**, app-pos **43 test** — 0 fail.
Bu sefer iki app'te de `assembleDebug` koşuldu ve **APK içeriği grep ile doğrulandı**:
`app-pos: pull metodu 6 / OtpRequestResult 24`, `app-mobile: pull metodu 5 /
OtpRequestResult 24`. Sunucu `python -m app.reset` ile sıfırlandı, telefondan
`/health` → **200**.

**AÇIK KALAN (kullanıcı raporundan, bu turda yapılmadı):**
- **ss3 kalanı:** müşterinin adı yoksa satır boş görünüyor. İstenen: **en azından telefon
  numarası gösterilsin, ad yoksa "isim girilmemiş" densin.** POS'ta her kayıtta ad zorunlu
  olduğu için bu satırların adsız olması ayrıca incelenmeli →
  [deferred.md §G.1](deferred.md).
- **ss4:** buyer'ın dükkân detayında üstteki "alacak/verecek" başlığı bu ekranda anlamsız →
  [deferred.md §G.2](deferred.md).
- **Onay yolları (kullanıcının açık ertelemesi):** *"posta veresiye ödemesi alırken vs onaya
  atmıyor… bazı onaylar gidicek, bazı onayların yeri değişecek, bazıları eklenecek. Yazıcam
  sonraki turda."* → **bu turda hiçbir onay yönlendirmesi değiştirilmedi**;
  [deferred.md §H](deferred.md).

**Test kılavuzu:** [cihaz-testi-komutlari.md](cihaz-testi-komutlari.md) (Tur 39/40/40b
adımları, A/B/C blokları hâlinde).

**Sıradaki:** Tur 41 — kullanıcının yazacağı onay-yolu tanımı + yukarıdaki iki açık UI
maddesi.

### 2026-08-14 — Tur 40c: cihaz testi 4. tur — bildirilen 5 hatanın hiçbiri sandığı yerde değildi

Kullanıcı Tur 40b'nin test adımlarını koşarken beş hata bildirdi: onay kutusunda yanlış
kişi, çift işlem satırları, isim yerine `u_market` id'si, dükkân telefonunun hâlâ
görünmemesi, ve iki hesap arasında telefon görünürlüğünün tutarsız olması.

**Önce ne YANLIŞ değildi.** Kullanıcının ilk şüphesi seed'di (*"seed bozuk da olabilir"*).
Postgres'e bakıldı: Ayşe Korkmaz kaydı **var**, telefonlar **dolu**, çift satır **yok**.
Seed ve backend verisi baştan beri doğruydu. Beş hatanın hepsi **istemci/ortam** tarafında
çıktı — ve ikisi zaten Tur 40b'de düzeltilmiş koddu.

**Kök neden 1 — çalışan container koddan eskiydi.** `docker exec ... grep shop_phone`:
container'daki `schemas.SellerDebt`'te alan **yok**, yerel dosyada **var**. Canlı
`/me/debts` çağrısı doğruladı — yanıtta `shop_phone` hiç gelmiyordu. Tur 40b'nin
uçtan-uca eklediği alan doğruydu; imaj `--build` almadığı için sunucuda çalışmıyordu.

Bu tek başına iki bildirimi açıklıyor: ss4'ün telefonsuz dükkânı, ve *"u_owner'ın
numarası görünüyor ama u_market'inki görünmüyor"* tutarsızlığı. İkincisi tutarsızlık
değildi: `u_owner` satırı cihaza **kendi girişinden** (tam profil), `u_market` satırı
ise yalnız `/me/debts`'ten (telefonsuz) yazılmıştı. Cihaz DB'si bunu birebir gösterdi.

**Kök neden 2 — cihazlarda eski yerel seed'in VERİSİ duruyordu.** Tur 39 `SeedCallback`
**kodunu** kaldırmıştı; cihazdaki satırlar kalmıştı. Eski seed `t4`–`t10`, sunucu aynı
içeriği `t11`–`t15` ile yolluyor. insert-IGNORE **PK'ya** bakar, içeriğe değil:

```
t4 |u_owner|c2|12000|DEBT|Market alışverişi|2026-07-18   ← eski yerel seed
t14|u_owner|c2|12000|DEBT|Market alışverişi|2026-07-18   ← sunucudan
```

ss3'ün çift satırları bu. c4 ve c5'te de aynısı vardı. Ayrıca ss1'i de açıklıyor: eski
seed'de `t4/t5` u_owner→c2 satırlarıydı, app-mobile'ın seed'inde aynı id'ler u_market→m1.
Cihazın app-pos DB'sinde `u_market` kullanıcısı ve `m1/o1` müşterileri **hiç yoktu** —
Ayşe Korkmaz'ın görünmemesinin sebebi buydu. Onay kartında dükkân adının ("Ayşe Market")
yazması ise tasarım gereği doğru; yanlış olan o dükkânın sahibinin cihazda bulunmamasıydı.

⚠️ **Ders:** seed'i yerelden sunucuya taşırken **id'ler değişti** (`t4`→`t14`). Aynı içeriği
farklı PK ile yollamak, insert-IGNORE'un tüm çarpışma korumasını **sessizce** devre dışı
bırakır. Seed id'lerini sabit tutmak bu sınıf hatayı baştan keser.

**Bu turda düzeltilen iki gerçek kod hatası** (ikisi de teşhis sırasında bulundu,
bildirilenler arasında değildi):

**1) `SellerDetailViewModel.shopPhone` tek-atışlık okumaydı** — `flow { emit(...) }`, Flow
değil. Numara ledger pull'uyla yazılıyor ve bu ekran çoğu zaman pull'dan **önce** açılıyor;
o anda okunan null bir daha yenilenmiyordu, saniyeler sonra numara gelse bile satır tüm
ziyaret boyunca gizli kalıyordu. `Repository.observeShopPhone` eklendi (Room'un mevcut
`observeById`'si üzerinden), ekran artık gözlüyor. **Backend düzeltilse bile bu hata tek
başına ss4'ü tekrar üretirdi.**

**2) Borçlar listesi ham `u_market` id'sini gösteriyordu.** Bu pencere gerçek: liste
**ayrı yazılan iki tablonun** JOIN'i (`storeBuyerLedger` girişleri, `storeShopNames`
adları), ve iki yazım arasında satırın bakiyesi var adı yok. Eski fallback (`shopName ?:
sellerId`) o aralıkta kullanıcıya iç anahtar gösteriyordu — hiçbir şey dememekten kötü,
çünkü **veri gibi görünüyor**. Nötr "Dükkan" etiketiyle değiştirildi; boş string de yok
sayılıyor (boş satır, bekleyen satır değil, bozuk satır gibi okunur).

**Yol üstünde bulunan bir hata:** `seed.py`'de `select_from` → `selecunt_from` yazım hatası
(kullanıcının düzenlemesinden kalma). Bu haliyle backend açılışta seed guard'ında
`AttributeError` ile patlardı. Geri alındı.

**Doğrulama:** `compileDebugKotlin` + `testDebugUnitTest` yeşil, `assembleDebug` koşuldu ve
APK dex'i doğrulandı: `observeShopPhone` 18 kez, `Dükkan` 1 kez. ⚠️ `strings` UTF-8
çoklu-baytı böldüğü için `Dükkan`ı yakalayamadı — dex byte olarak arandı. Grep'in
bulamaması "yok" demek değil.

**Kullanıcıya bırakılan iki adım** (biri imajı derliyor, diğeri veriyi siliyor):

```
docker compose -f backend/docker-compose.yml up -d --build api   # önce bu
docker compose -f backend/docker-compose.yml exec api python -m app.reset
```

Sıra önemli. Ardından iki APK da uninstall + yeniden kurulmalı: `app.reset` **sunucuyu**
sıfırlar, cihazdaki Room'a dokunmaz — ve buyer ledger pull'u **additive** olduğu için
cihaz eski kopyayı kendiliğinden bırakmaz.

ℹ️ **Kullanıcının 1000/2000 TL'lik test ödemeleri sunucuda duruyor** ve uninstall sonrası
geri geliyor. Bu doğru davranış: sunucu tek gerçeklik, cihaz onun aynası. Silmek için
`app.reset` gerekiyor.

**Cihazda ÇALIŞIRKEN doğrulanmadı:** yalnız derleme, testler ve dex içeriği doğrulandı.
Backend güncellenip cihazlar sıfırlandıktan sonra ss4 tekrar denenmeli; dükkân telefonunun
görünmesi asıl kanıt.

**Sıradaki:** Tur 41 — değişmedi: kullanıcının yazacağı onay-yolu tanımı ([deferred.md
§H](deferred.md)) + Tur 40b'den açık kalan iki UI maddesi (§G.1, §G.2).

### 2026-08-15 — Tur 40d: yazma yolu sunucuya bağlandı + hayalet veri durduruldu

Kullanıcı 4. cihaz testinde yeni hatalar bildirdi ve doğru soruyu sordu: *"3 seferdir debug
edemiyoruz. sistematik bir hata mı var? her tuş roomdan istemeli, room da sürekli server
ile sync olmalı diye biliyordum ben."* Beklenti doğruydu; **kod ona uymuyordu**.

**Kök neden 1 — okuma sunucudan, yazma yerelde.** `OfflineFirstRepository` bunu kendi
yorumunda yazıyordu:

```kotlin
// --- everything else is local; the outbox (phase 8) is what will involve remote ---
```

Yalnız `addTransaction` sunucuya gidiyordu. `addCustomer`, `setSeller`, `updateShopName`,
`updateDisplayName`, `updateEmail` sadece Room'a yazıyordu — backend uçları **ve** istemci
API'leri hazır olduğu hâlde. `claimCustomerForUser` daha kötüydü: sunucuda **hiç karşılığı
yoktu**, yani seed dışında hiçbir kayıt gerçekten CLAIMED olamıyordu.

⚠️ **Sessiz veri kaybı (kullanıcının gördüğünden ciddi).** `addCustomer` yerel UUID
üretiyordu. app-pos müşteriyi açıp hemen ona veresiye yazar (`OtpViewModel` →
`SaleViewModel`), yani zincir şuydu:

1. yerel UUID → sunucu bilmiyor
2. veresiye o id ile outbox'a girer
3. sunucu **404 customer_not_found**
4. `isRetryable()` false → `SyncEngine` kaydı **outbox'tan siler**, yerel ledger'da bırakır

Sonuç: veresiye ekranda görünür, sunucuda **yoktur**. Kullanıcının "ödeme al deyince böyle
bir kullanıcı yok diyor" raporunun altındaki asıl risk buydu.

**Kök neden 2 — `allowBackup="true"`.** Android uninstall'da veriyi yedekleyip yeniden
kurulumda geri yüklüyordu; üç turdur yapılan `adb uninstall`'ların hiçbiri gerçekten
silmemişti. Kanıt: cihazda `t4` = `u_owner→c2`, sunucuda `t4` = `u_market→m1` — aynı id,
farklı satır, hiçbir pull'un üretemeyeceği bir durum. Kurulu APK cihazdan çekilip dex'i
arandı: seed kodu yok, yani veri uninstall'dan sağ çıkıyordu. **Testlerin üç turdur
hayalet veriyle koştuğu buradan anlaşıldı.**

**Yapılanlar:**

1. **`allowBackup="false"`** (iki manifest). Bunsuz hiçbir düzeltme güvenilir test
   edilemezdi, o yüzden ilk madde. ⚠️ XML yorumunda `--` geçersiz — manifest bir kez bu
   yüzden parse edilemedi.
2. **`addCustomer` sunucu-önce.** Dönüş tipi `String` → `CustomerCreateOutcome`
   (`Created` / `AlreadyExists` / `Unreachable` / `Failed`). Id'yi hep sunucu üretir; **409
   hata değil**, mevcut kayda uzlaşma demektir (uçta idempotency-key yok, kayıp 201'in
   retry'si de 409 görünür). `Unreachable`'da **yerele hiçbir şey yazılmaz** ve satış durur.
   Bedel bilinçli: yalnız YENİ kayıt açmak sinyal ister, mevcut müşteriye veresiye yazmak
   offline çalışmaya devam eder.
3. **Profil yazmaları** sunucuya + aynalama. Bunlar offline-tolerant KALDI: hiçbir şey
   display name ile anahtarlanmıyor, yani senkronlanmamış düzenleme bayat bir alan, sonradan
   reddedilecek bir id değil. ⚠️ `updateShopName` mevcut `shopPhone`'u **önce okuyup**
   gönderir — become-seller iki alanı da atadığı için tek başına ad göndermek numarayı
   sessizce silerdi.
4. **`POST /users/me/claim`** (yeni). Telefon **token'dan** gelir, gövdeden değil: claim
   "bu kayıtlar benim" demektir, numarayı istekte taşımak herkesin başkasının borcunu
   sahiplenmesine (ve `/me/debts` üzerinden geçmişini okumasına) izin verirdi. Idempotent;
   başkasının tuttuğu kaydı **asla** devretmez.
5. **`createdBySellerId` Room'a** (şema v3) ve `observeForSeller` artık ledger üyeliği ile
   **birleşim** alıyor. Sunucu bu kolonu "işlemi olmayan müşteri hiçbir deftere ait
   görünmüyor" diye eklemişti; istemciler almamıştı — kullanıcının "eklediğim müşteri
   listede yok" raporunun kalan yarısı. ⚠️ app-mobile'ın sorgusunda buyer-stub dışlaması
   var; `OR` eklerken **parantez şart**, yoksa `A OR B AND C` bağlanması Tur 40b'de
   kapatılan sızıntıyı geri getirirdi.

**Doğrulama.** 154 backend testi (146→154; claim için 6, `created_by_seller_id` için 2
yeni), iki app'in unit testleri, `assembleDebug`, dex sayımı (`CustomerCreateOutcome` 22,
`claimMyRecords` app-mobile'da 9 / app-pos'ta 0) ve `aapt2` ile manifest'te
`allowBackup=false`. Uçtan uca canlı backend'e karşı:

- yeni müşteri → id `c_a44d38b30285` (UUID değil), **işlemi yokken listede görünüyor**
- seed'deki `05552223344`'ü "aysemsi" adıyla eklemeye çalışmak → **409**, istemci `c2`'ye
  (Ayşe Demir, 165 TL) uzlaşıyor, **ikinci satır oluşmuyor**
- Ayşe kaydolup giriyor → claim öncesi `/me/debts` **boş**, claim sonrası **165,00 TL**,
  dükkân adı ve telefonuyla

**Cihazda ÇALIŞIRKEN doğrulanmadı** — kurulum kullanıcıda. Sıra: `up -d --build api` →
`app.reset` → iki APK uninstall + kur. Artık `allowBackup=false` olduğu için uninstall
gerçekten siliyor; **ama bu APK'lar kurulduktan SONRA** geçerli.

**Sıradaki:** Tur 41 — değişmedi (onay-yolu tanımı §H + §G.1, §G.2).

### 2026-08-17 — Tur 40e: app-mobile kendi defterini HİÇ çekmiyormuş  [4 turluk semptomun kökü]

Kullanıcının sorusu: *"posta müşteriler tam görünüyor ancak mobile de liste ya boş ya
da yarım... kaçırdığımız nokta neymiş?"* Cevap: bir bug değil, **hiç yazılmamış kod**.

**Kök neden.** app-mobile **iki rollü** (hem alıcı hem satıcı) ama pull'un yalnız
**alıcı yarısı** yazılmıştı. `GET /customers`'ın bu app'te **sıfır çağıranı** vardı —
`RemoteDataSource.customers()` ölü koddu — ve `LocalSource`'ta **`storeLedger` hiç
yoktu**. Yani "Müşterilerim", sunucu tarafından hiçbir şeyin yazmadığı bir Room
tablosunu okuyordu. İçindeki tek satırlar **bu kurulumun kendi yazdıkları**: temiz
cihazda boş, sonra yarım. Borçlarım baştan beri çalışıyordu çünkü onun ikizi
(`pullMyLedger`) **vardı**.

**Bu Tur 40'ın planlama hatasının tekrarı.** Tur 40 "pull ekseni tamam" derken her app'e
**bir** metot ekledi: app-pos'a `pullBook`, app-mobile'a `pullMyLedger`. Tek rollü bir
app için bu işin tamamı; iki rollü olan için **yarısı**. Tur 40d'de eklenen
`createdBySellerId` de bu yüzden işe yaramadı: kolon geldi, onu dolduracak pull yok.

**Yapılanlar (hepsi app-mobile; app-pos ve backend'e DOKUNULMADI):**

1. **`LocalSource.storeLedger`** — `storeBuyerLedger`'dan **ayrı** metot, bayrak değil.
   O metot her satır için **boş müşteri satırı TÜRETİYOR** (alıcıya kaydın adı hiç
   söylenmez); burada satırlar `GET /customers`'tan **dolu** geliyor, tekrar türetmek
   gerçek isim/numarayı boşla ezerdi.
2. **`PullEngine.pullBook()`** — app-pos'un ikizi. `pullApprovals`'ın aksine
   **additive**: cevapta olmayan ledger satırı geri alınmamıştır, cevap kısmidir.
3. **`Repository.refreshBook()`** + tetikleyiciler: `App.kt` açılış, `CustomersViewModel`
   ekran-açıkken 30 sn poll (`DebtsViewModel`'in deseni). **Arka plan `PullWorker`
   EKLENMEDİ** — pil kararı kasıtlı ve belgeli.
4. **403 `not_a_seller` sessiz dal.** Alıcı-only hesap uca sorduğunda sunucu reddediyor;
   bu **`Refreshed(0)`**, `Failed` değil. Sistemin normal cevabını kullanıcıya kırmızı
   hata olarak göstermek 40b'nin `OtpRequestResult` dersinin tekrarı olurdu. ⚠️ Yerel
   `isSeller` bayrağına bakmak daha kötü: bayat bayrak, yeni satıcı olan hesaba bir
   sonraki girişe kadar boş liste gösterirdi.
5. **`observeForSeller`'ın stub dışlaması düzeltildi.** Eskisi
   `claimedByUserId <> :sellerId` idi — **başka bir soru** soruyor ve gerçek satırları
   atıyordu: bir dükkân sahibi başka yerde müşteriyse, kendi hesabına claim'li **dolu**
   bir kaydı var ve kendi listesinden **siliniyordu** (seed'de `o1`/u_owner tam bu).
   Üstelik **kararsızdı**: `storeCustomers` (dünkü FK guard'ı) `claimedByUserId`'yi
   ancak yerel user satırı varsa koruyor → bir satırın görünürlüğü **alakasız veriye**
   bağlıydı. Yeni şart stub'ın **gerçek imzasını** soruyor: boş ad + boş numara +
   `createdBySellerId IS NULL`. 40b'nin çift-rol sızıntısını hâlâ yakalıyor.
   ⚠️ Parantezler korundu (`A OR B AND C` tuzağı).

**Öğrenilen:** **"Deseni kurduk" ile "her rol için kurduk" aynı şey değil.** Simetrik
görünen iki app'ten biri iki rollüyse, tek metot eklemek işin yarısıdır — ve eksik yarı
hata vermez, sadece **sessizce boş** çalışır. Tur 40'ın kendi dersi ("kaynağı kaldırmadan
önce yerine geçeni bağla") burada bir kez daha, bu sefer rol ekseninde tekrarlandı.

**Doğrulama.** **61 unit test / 0 fail** (55 → 61; 6 yeni `pullBook` testi: dolu defter,
403 sessiz dal, alakasız 403, boş defter silmiyor, unreachable dokunmuyor, yarıda kalan
geçmiş müşterileri bırakıyor). `assembleDebug` koşuldu ve **APK dex'i grep'lendi**:
`pullBook` 3, `refreshBook` 5, `storeLedger` 8, `not_a_seller` 1, yeni SQL şartı 1,
**eski `claimedByUserId <> ` şartı 0**. Room üretilen SQL'i de okundu (parantezler doğru).

**CİHAZDA DOĞRULANMADI — kurulum kullanıcıda.** Backend değişmedi, `--build` gerekmez:
```
docker compose -f backend/docker-compose.yml exec api python -m app.reset
adb uninstall <app-mobile paketi>   # allowBackup=false artık gerçekten siliyor
```
u_owner (+905554443322) ile gir → **Müşterilerim'de 5 satır** (app-pos'takinin aynısı):
Ahmet Yılmaz 40,00 / Ayşe Demir 165,00 / Mehmet Kaya 0,00 / Fatma Şahin 25,50 /
Hasan Öztürk 210,00. Sunucu loglarında `GET /customers` **görünmeli** (40b'de 0'dı).
⚠️ u_owner hem satıcı hem alıcı (`o1` ile Ayşe Market'e 60,00 TL borçlu) — 5. maddenin
asıl sınavı: Borçlarım'da Ayşe Market, Müşterilerim'de 5 müşteri, ikisi birbirini
kirletmemeli.

**Sıradaki:** Tur 41 — değişmedi (onay-yolu tanımı §H + §G.1, §G.2).

### 2026-08-19 — Tur 41: onay yollarının yeniden tanımı (§H) + iki UI maddesi

Kullanıcının beş yol tanımı geldi ve uygulandı. **Kod okuması dokümandan daha sert bir
tablo çıkardı:** iki ayrı yazma mimarisi vardı ve birbirinden habersizdi.

| Yol | Onay (Tur 40e'de) | Uç |
|---|---|---|
| POS'ta veresiye yazma (satıcının **ANA** yolu) | ❌ yok | `POST /transactions` |
| POS'ta tahsilat alma | ❌ yok | `POST /transactions` |
| app-mobile satıcı → CLAIMED müşteri | ✅ var | `POST /approvals` |
| app-mobile alıcı → ödeme beyanı | ✅ var | `POST /approvals` |
| İki app, UNCLAIMED karşı taraf | ❌ yok (anında yazılır) | `POST /approvals` → 200 |

Üç somut bulgu:

1. **`OtpService.verifyOtp` dekoratifti** — gövdesi `delay(300); return true`. Boş kod bile
   geçiyordu, `hasApp` parametresi hiçbir dalı değiştirmiyordu. POS'taki OTP ekranı bir kapı
   değil, bir animasyondu.
2. **app-pos onay İSTEYEMİYORDU.** `Repository`'de `requestApproval` yoktu; sadece
   `approvePending`/`rejectPending` vardı. `RemoteDataSource.sendForApproval` **yazılmıştı
   ama çağıranı yoktu** (ölü kod).
3. **Backend'de kapı yoktu.** `ledger.py`'de `Approval` kelimesi hiç geçmiyordu.

Yani *"satıcı tek taraflı borç yazamaz"* ilkesi, satıcının **ana aracında** hiç
uygulanmamıştı. Kullanıcının *"posta veresiye ödemesi alırken onaya atmıyor"* gözlemi
buydu; kökü UI değil, mimariydi.

**Yol üstünde bulunan güvenlik açığı:** `POST /transactions` `customer_id`'yi yalnız "var
mı" diye kontrol ediyordu. `seller_id` token'dan geldiği için **başkasının defterine**
yazmak engelliydi, ama **aynanın diğer yüzü** açıktı: A satıcısı, B'nin müşterisinin
id'siyle **kendi defterine** kayıt açabiliyordu. O satır, o kişinin `/me/debts`'inde hiç
gitmediği bir dükkâna borç olarak görünürdü. Defter üyeliği kontrolü eklendi (403
`not_in_book`), sorgu `app/ledger.py`'ye taşındı — defter ekranı ile yazma yolu aynı
soruya farklı cevap veremesin.

**Yeni kavram: `pgw_jobs` (migration 0004).** Yol 3, 4, 5 telefonda başlıyor ama **PGW'de
bitiyor**, ve PGW'ye yalnız POS ulaşabiliyor. Sunucu POS'u arayamaz (NAT, FCM yok) → işi
yazıp bırakıyor, terminal gelip alıyor. Onay tablosuna sıkıştırılmadı çünkü **soru farklı**:
approval "kim karar verecek", job "kim iletecek **ve iletti mi**". İkinci yarı olmadan
yeniden kurulan bir terminal geçmişteki her fişi tekrar keserdi. Ack **idempotent**:
terminal önce intent'i atıyor, sonra ack'liyor — kayıp cevap, gerçekten teslim edilmiş bir
işin ack'ini tekrarlamakla sonuçlanır, ve bunu hata saymak işi sonsuza dek PENDING
bırakırdı. Garanti bilinçli olarak **en az bir kez**: iki kez kesilen fiş can sıkıcı ve
düzeltilebilir, hiç kesilmeyen fiş değil.

**`approvals.origin` (POS | PHONE)** — onay verilince PGW işi yaratılıp yaratılmayacağına
bu karar veriyor. Olmadan, tezgâhta açılan bir satış onaylandığında **fiş iki kez**
kesilirdi (terminal kendi intent'ini zaten atmış oluyor). Mevcut satırlar **POS**'a
backfill edildi: teknik olarak hepsi telefon kaynaklıydı, ama değer **ne kaydettiğine değil
ne YAPTIĞINA** göre seçildi — POS hiçbir iş yaratmayan dal, ve haftalar önce tamamlanmış
bir satış için migration sonrası fiş kesilmemeli.

**`GET /approvals/{id}` (yeni)** — gelen kutusu "sana ne soruldu"yu listeler ve **isteği
açan taraf asla karar veren taraf değildir**, yani satışı açık tutan tezgâh cevabı oradan
öğrenemezdi.

**Yol 1'in dönüşü.** `setResult` kodda **hiç yoktu**; `finishCreditHandoff` sadece
`finish()` çağırıyordu. Artık sonuç taşıyor. Bu, app-pos'u **`singleTask`'tan
`singleTop`'a** taşımayı zorladı: `singleTask` kendi task'ında koşar ve Android onu
**anında `RESULT_CANCELED`** ile cevaplar — kabul edilen ve reddedilen her satış PGW'ye
birebir aynı görünürdü. `taskAffinity=""` launcher'dan açılışı kendi task'ında tutuyor
(singleTask'ın buradaki asıl işi buydu).

**Onay yolu bilinçli olarak offline-tolerant DEĞİL** — app-pos'un her yerde uyduğu
offline-first kuralından tek sapma. Sinyal yokken kimse sorulamaz, ve kaydı yine de yazmak
kapının önlemek için var olduğu **tam olarak o tek taraflı yazma** olurdu. Satış duruyor.

**mock-pos artık taklit ettiği PGW.** `applicationId` = `com.tokeninc.sardis.paymentgateway`,
gerçek bileşen adını Kotlin sınıfına bağlayan bir `activity-alias` ile — böylece app-pos
gerçek terminalde kullanacağı adresi taşıyor, geliştirmeye özel bir adı değil. Fiş
kesemediği için **gelen orderBody ile gerçek PGW'nin ne yapacağını** ekranda söylüyor
(kullanıcının açık isteği). ⚠️ app-pos'a `<queries>` bloğu eklendi: Android 11+ altında
onsuz PGW **kurulu olsa bile** görünmez ve `startActivity` sessizce patlar.

**Yol 4 bilinçli olarak onaysız.** Kapı, bir tarafın diğerine tek taraflı kayıt açmasını
engeller; kendi tezgâhında **para tahsil etmek** bunun tersidir ve müşteri kartı uzatarak
onaylar. Ledger'a da yazılmıyor: kimse henüz ödemedi. Ekran "kasaya **iletildi**" diyor,
"ödendi" demiyor.

**§G.1 (adsız satır).** Boş satır, tek bir alan eksikken **verinin kayıp olduğunu**
düşündürüyordu. Telefon başlık oldu, ikincil satır "isim girilmemiş" diyor. Arama da
telefonu eşleştiriyor — listede tanınması en zor satır, aranamayan satırdı da.
**Kaynak sorusu kapandı:** buyer stub'ları SQL'de zaten ayıklanıyor
([Daos.kt:128-134](../app-mobile/core-data/src/main/java/com/example/app_pos/data/db/dao/Daos.kt));
kalan kaynak gerçek → kayıt `displayName = ""` gönderiyor (`LoginViewModel`). Yani bunlar
gerçek hesaplar, kozmetik düzeltme veri boşluğu örtmüyor.

**§G.2 (alacak/verecek).** Etiket zaten doğruydu ("Bu satıcıya borcum"); **değer** iki yönlü
çiziliyordu. Satıcının kırmızı/yeşil şeması kopyalanmıştı, yani fazla ödemede **negatif
borç** çıkıyordu ve **sıfır bakiye "ödeme alındı" yeşiline** düşüp olmamış bir olayı
duyuruyordu. Artık **etiket işaretle birlikte** değişiyor, tutar hep pozitif, sıfır nötr.
Aynı sıfır hatası Borçlarım listesinde de düzeltildi.

**Doğrulama.** 175 backend testi (154 → 175), iki app'in unit testleri, üç APK.
Canlı backend'e karşı uçtan uca:

- PHONE kaynaklı onay → onaylanınca **RECEIPT işi**, `orderBody` içinde `"type":17`
- ack **iki kez 200**, iş kuyruktan düşüyor
- aynı satış `origin=POS` ile onaylandığında **hiç iş yok**
- yol 4 → `COLLECT` işi, `order_body: null` (PGW kendi sepetini kurar)
- başka dükkânın müşterisine yazma **403**, kendi müşterisine **201**

APK dex'i grep'lendi (`requestApproval` 9, `PgwJobRunner` 22, `paymentgateway` 2,
`collectAtTerminal` 9, `titleFor` 5) ve manifest'ler `aapt2` ile okundu: mock-pos'un paket
adı + alias, app-pos'un `launchMode=1` (singleTop) + `queries`.

**CİHAZDA DOĞRULANMADI — kurulum kullanıcıda.** Sıra:
```
docker compose -f backend/docker-compose.yml up -d --build api
docker compose -f backend/docker-compose.yml exec api python -m app.reset
adb uninstall com.example.app_pos
adb uninstall com.example.app_mobile
adb uninstall com.example.mock_pos            # ⚠️ ESKİ paket adı — yeni APK farklı id ile kurulur
```
⚠️ mock-pos'un `applicationId`'si değişti, yani eskisi **ayrı bir uygulama olarak kalır**;
elle kaldırılmazsa iki ödeme uygulaması yan yana durur.

**Öğrenilen:** **"Desen kurulu" ile "her giriş noktasında kurulu" aynı şey değil.** Onay
kapısı Tur 38'den beri vardı ve doğru çalışıyordu — ama satıcının ana aracında hiç yoktu,
ve önündeki OTP ekranı kapı **varmış gibi görünüyordu**. 40e'nin dersi (rol ekseninde eksik
yarı) burada **cihaz ekseninde** tekrarlandı: eksik yarı yine hata vermedi, sadece sessizce
kapıyı atladı.

**Sıradaki:** OTP'nin gerçekleştirilmesi (kullanıcı kararı: sunuma kadar mock), UNCLAIMED
için SMS-OTP onayı (§H'de tartışılacak madde), PGW handshake, yol 1 timeout, FCM.

---

### 2026-08-20 — Tur 42: yol 2 kısaldı — POS keypad'i ve OTP ekranı kaldırıldı

Kullanıcının isteği: *"pos'tan ödeme al (tutar gir ve pgw'ye yönlendir) — pgw'ye ilet fiş
için, aynı zamanda server'a gönder db'ye kaydetsin diye."*

**Yol 2, dört ekran uzunluğundaydı.** Müşteri detayı → `[Ödeme Al]` → POS'un kendi keypad
ekranı → Confirm → OTP → ancak ondan sonra PGW. Artık: müşteri detayı → tutar diyaloğu →
yaz + PGW. Üç ekran gitti.

| | Önce | Sonra |
|---|---|---|
| Tutar | `KeypadFragment` (tam ekran) | tek alanlı diyalog |
| Özet | `ConfirmFragment` | — |
| Onay | `OtpFragment` (kod gir) | — |
| PGW | en sonda | tutar onaylanır onaylanmaz |

**Üç ekranın ikisi zaten iş yapmıyordu.** OTP ekranı Tur 41'de tespit edilen sorunun
kalıntısıydı: `OtpService.verifyOtp` gövdesi `delay(300); return true` — yani kapı değil,
animasyon. Yol 2'de bunun *doğru* olması ise ayrı bir konu: bu yolda onay zaten **olmamalı**.
Tur 41 tablosundaki gerekçe aynen geçerli — kapı bir tarafın diğerine **tek taraflı kayıt
açmasını** engeller, tahsilat bunun tersidir (para dükkâna geliyor) ve müşteri **kartını
PGW'ye uzatarak** onaylar. Tezgâhta ikinci bir onay istemek, aynı kişiden aynı işlem için
iki kez izin istemekti.

**app-mobile bu deseni zaten kullanıyordu.** Satıcı tarafında yazma Tur 19'dan beri
"[Ödeme Al] → tutar popup'ı", keypad/saleFlow yok
([architecture-pos.md, Flow B md.8](architecture-pos.md)). Yani POS bu turda **yeni bir
desen icat etmedi**, iki app arasındaki tutarsızlığı kapattı — aynı iş, aynı roldeki
kullanıcı için iki farklı uzunlukta akıştı.

**Yeni giriş noktası** `CustomerDetailFragment.showCollectAmountDialog`. Tutar **lira**
girilip kuruşa burada çevriliyor; `BigDecimal`, `Double` değil — `55,55 * 100` ikili
kayan noktada **5554** kuruş verir. Hem virgül hem nokta kabul ediliyor: terminalin
`numberDecimal` klavyesi nokta sunuyor, Türkçe virgül yazıyor, ve kullanıcının **fiilen
yazdığı** ayracı reddetmek uydurma bir hata olurdu.

**`SharedFlow`, `StateFlow` değil** — ve bu, kopyalanmaması gereken bir fark.
`OtpViewModel.collectAtGateway` bir `StateFlow<Long?>`; orada sorun çıkarmıyor çünkü ekran
hemen kapanıyor. Burada ekran **açık kalıyor**: `StateFlow` olsaydı, kullanıcı PGW'den geri
döndüğünde son değer yeniden yayılır ve **kart ikinci kez çekilirdi**. Bu bir durum değil,
bir **olay**. `extraBufferCapacity = 1`, henüz kimse dinlemiyorken `tryEmit`'in olayı
düşürmesini engelliyor.

**Sıra: önce yaz, sonra PGW.** `repo.addTransaction` → `syncScheduler.syncNow()` →
`collectAtGateway`. Yerel yazma önce geliyor, yani sinyalsiz tezgâh da tahsilatı kaydediyor
(offline-first korunuyor); sunucuya iletim WorkManager'ın işi, çünkü o hem bu ViewModel'den
hem de uygulamanın kapatılmasından uzun yaşıyor. Intent'i **Fragment** atıyor: activity
başlatmak Context ister, veri katmanı Context tutmamalı — `OtpFragment`'taki mevcut ayrımın
aynısı.

**Silinmeyen ölü kod (bilinçli).** `saleFlow`'un PAYMENT yarısı — `KeypadFragment`'ın
PAYMENT dalı, `ConfirmFragment`, `OtpViewModel.collectPayment`, `nav_graph`'taki
`action_global_pay` ve `payCustomer*` argümanları — artık **ulaşılamaz**, ama duruyor.
Demo öncesi geniş silme riskli ve istenen şey akışın değişmesiydi, mimarinin sökülmesi
değil. [KeypadFragment.kt:61](../app-pos/app/src/main/java/com/example/app_pos/ui/sale/KeypadFragment.kt)
üzerine neden orada durduğu ve **topluca** silinmesi gerektiği yazıldı — parça parça
silinirse yol 1 de kırılır, çünkü aynı grafiği paylaşıyorlar.

`CustomerDetailFragment.phone` alanı ise silindi: yalnızca yazılıyor, hiç okunmuyordu.
Telefonu isteyen taraf OTP ekranıydı. Ekrandaki gösterim (`binding.detailPhone`) duruyor.

**Yol 1'e dokunulmadı.** Veresiye hâlâ `saleFlow`'dan geçiyor, hâlâ müşteri onayını
bekliyor, `setResult` ile PGW'ye cevap veriyor. Değişen **yalnız** yol 2'nin giriş noktası.

**Cihaz testi: gerçek PGW isteği REDDETTİ — orderBody şeması yanlışmış.** İlk kurulumda
gerçek terminal *"sepet tutarı 0 olamaz"* dedi, ve kullanıcının ikinci gözlemi daha da
belirleyiciydi: gönderdiğimiz body ile PGW **doğrudan fiş yazıyordu**, oysa istenen **ödeme
ekranına geçmesi**. İki hata tek kökten:

```
GÖNDERDİĞİMİZ  {"basketID":…,"documentType":9002,"paymentItems":[{"amount":N,"type":1}]}
OLMASI GEREKEN {"basketID":…,"documentType":9002,"customerInfo":{…},
                "infoReceiptInfo":{…},"taxFreeAmount":N}
```

1. **`paymentItems`'ın VARLIĞI fişi bastırıyor.** Kalemler gateway için "şunu bas"
   talimatı; ödeme ekranı istiyorsak hiç gönderilmemeli. `type:1` ("ordinary payment")
   sandığımız şey yanlış katmandaydı — belge tipi zaten `documentType`.
2. **Tutar hiçbir yerde taşınmıyordu.** `paymentItems` çıkınca tutarı taşıyan tek alan
   `taxFreeAmount`, ve o hiç gönderilmiyordu → gateway'in gördüğü sepet **sıfırdı**.

`taxFreeAmount`, `customerInfo`, `infoReceiptInfo`, `documentNo`, `documentDate`, `taxID`
alanlarının **hiçbiri** kod tabanında yoktu (repo geneli grep ile doğrulandı).

**Tek düzeltme, üç yol.** `PgwBridge.collectPayment` yol **2, 4 ve 5**'in ortak fonksiyonu
(kullanıcı: *"yol 4 ve 5'te de tutar girip ödeme alıyoruz, bu kodu kullansınlar"*), yani
şema düzeltmesi üçünü birden kapsadı. **Fiş yolu dokunulmadı** — `printReceipt` /
`receiptOrderBody` / backend `receipt_order_body` hâlâ `paymentItems` + `type:17` gönderiyor
ve doğru çalışıyor; fişi bastıran zaten o.

**Müşteri bilgisi iki yoldan geliyor, ikisi de tuzaklıydı:**

- **Yol 2** — Fragment'ta `viewModel.phone.value` okumak **yarış** açıyordu: `phone`,
  `stateIn(initialValue = "")` ile asenkron bir suspend DB okumasından besleniyor, popup
  hızlıca onaylanırsa telefon hâlâ boş olurdu ve `customerInfo` **sessizce** eksik giderdi.
  Bunun yerine ViewModel müşteriyi `collectPayment()` içinde **taze** okuyup olay yüküne
  koyuyor (`GatewayCollect`).
- **Yol 4/5** — sunucu job ile yalnız `customer_id` gönderiyor. Kullanıcı kararı: backend'e
  dokunma, terminal kendi Room'undan baksın. `PgwJobRunner`'a `Repository` enjekte edildi;
  `PgwDispatcher` ile **aynı** `OfflineFirstRepository` singleton'ı olduğu için döngüsel
  bağımlılık yok. Müşteri henüz senkron değilse `null` → `customerInfo` hiç eklenmez, ödeme
  yine gider. Risk `deferred.md §I.1`'de.

`customerInfo` **ya tam ya hiç**: adı olup kimliği olmayan bir blok fişte tamamlanmış gibi
görünür, eksik olduğu belli olmaz.

**İki bilinçli uydurma** (`deferred.md §I.2`, `§I.3`): `taxID` alanına **telefon** yazılıyor
(vergi/TC no elimizde yok, sistem müşteriyi telefonla tanıyor) ve `documentNo` `GIB<yıl>
<epoch>` olarak üretiliyor — gerçek GİB numarası değil. Sayaç saklanmıyor: ardışıklık
gerekmiyordu, kalıcı sayaç ise yeniden kurulumu ve terminaller arası tekilliği çözmek
zorunda kalırdı.

**Doğrulama.** `assembleDebug` yeşil. Üretilen JSON **çalıştırılarak** doğrulandı (kodu
okuyarak değil): `paymentItems` yok, `taxFreeAmount` dolu, bilgi eksikken `customerInfo`
hiç eklenmiyor. APK dex'i grep'lendi — `taxFreeAmount`, `customerInfo`, `infoReceiptInfo`,
`documentNo`, `taxID` ve `GIB` var; `paymentItems` **1 kez** (fiş yolu, doğru).

**CİHAZDA DOĞRULANMADI:** asıl test gerçek PGW'de — hatayı veren o, mock-pos şema kontrolü
yapmıyor. Beklenen: "sepet tutarı 0 olamaz" yok, fiş basılmıyor, **ödeme ekranı** geliyor.

**Yan iş — `~/.zshrc`.** İki cihaza paralel kurulum için build fonksiyonları elden geçti:
bütün `adb` çağrıları artık `-s <serial>` taşıyor (iki cihaz bağlıyken `adb install`
*"more than one device"* ile patlıyordu, daha kötüsü `adb reverse` sessizce yanlış cihaza
kurulabiliyordu), ve `--wifi` artık **doğrulanıyor**: backend LAN adresinden cevap veriyor
mu, telefonun `wlan0` adresi var mı, ve telefonun **içinden** `curl` ile o adrese TCP
açılıyor mu. Üçüncüsü **AP isolation'ı** yakalayan tek testtir (ilk ikisi geçerken o
patlar). Biri başarısızsa Gradle hiç çalışmıyor — yanlış host'u gömülü bir APK kurulmuyor.

**Öğrenilen:** **Bir ekranın var olması, bir şeyi doğruladığı anlamına gelmez.** OTP ekranı
yol 2'de iki kez yanlıştı: hem hiçbir şeyi doğrulamıyordu (Tur 41), hem de doğrulasaydı
bile **yanlış sorunun kapısı** olurdu. Tur 41 kapının **eksik olduğu** yeri bulmuştu; bu tur
kapının **fazladan durduğu** yeri kaldırdı. İkisi aynı hatanın iki yüzü: kapıların nereye
ait olduğunu akışın kendisi değil, **kimin neye rıza verdiği** belirler.
