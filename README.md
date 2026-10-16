# Butter Bot (Fabric 26.3)

Port Fabric untuk Minecraft **26.3** dari mod Forge 1.20.1 *Butter Bot AI - Rick and Morty* karya **creatoreskim**. Ditulis ulang dari nol dan diubah menjadi **pembantu berwujud orang**. Pembantumu bisa menambang, menggali terowongan, bertani, merapikan peti, membuat item dari isi peti, mengawalmu dari monster, mengikutimu, mengantar barang ke kamu atau ke Bot Base, dan menjalankan program Lua. Bot **naik level** dengan bekerja. Bot memakai **skin Minecraft biasa** dan bisa diberi **telinga dan ekor kucing (neko)**.

Semua AI berjalan lokal dengan sistem prioritas sederhana. Tidak ada LLM dan tidak ada koneksi ke luar.

## Instalasi di TLauncher

1. Butuh profil **Fabric 26.3** dengan **Fabric API 0.162.0+26.3**. Profil `SODIUM` di TLauncher sudah punya Fabric API.
2. Salin `build/libs/butterbot-1.0.0.jar` ke folder `mods` profil tersebut:
   - Profil SODIUM: `%APPDATA%\.minecraft\versions\SODIUM\mods\`
   - Profil Fabric biasa: `%APPDATA%\.minecraft\mods\` (salin juga jar Fabric API ke sana)
3. Jalankan game dari TLauncher. Jar ini sudah berisi interpreter Lua, jadi tidak perlu mod tambahan.

Untuk main bersama teman, mod harus terpasang di client **dan** server.

## Mulai main (survival)

Tidak perlu crafting dan tidak ada baterai. Cukup ketik di chat:

```
/butterbot summon            panggil bot baru di depanmu
/butterbot summon Mochi      panggil bot baru dengan nama "Mochi"
```

- Bot langsung jadi milikmu. Setiap pemain boleh punya maksimal **10 bot** sekaligus.
- **Klik kanan bot** (tangan kosong) atau ketik `/butterbot menu` untuk membuka panel kontrol.
- `/butterbot dismiss` memulangkan bot terdekat. Isi tasnya diberikan ke kamu.

### Bot Base (opsional)

Bot Base adalah peti 27 slot tempat bot menyimpan hasil kerja. Bisa dipakai hopper.

- Dapatkan gratis dengan `/butterbot getbase`, atau craft: 1 Chest di tengah, 4 Iron Ingot di sisi, 4 Redstone di sudut.
- Bot milikmu dalam radius 64 blok otomatis tertaut ke base yang baru ditaruh.
- Klik kanan base untuk mengatur semua bot di sekitarnya (radius 500 blok). Jongkok + klik kanan untuk membuka storage.

### Kontrol cepat

| Aksi | Hasil |
|---|---|
| Klik kanan bot (tangan kosong) | Buka panel kontrol |
| Klik kanan puncak kepala bot (tangan kosong) | Elus kepalanya (lihat [Kedekatan](#kedekatan-elus-dan-camilan)) |
| Klik kanan bot sambil memegang ikan, kue kering, pai labu, beri atau kue | Beri camilan |
| Jongkok + klik kanan bot (tangan kosong) | Kemas bot jadi item. Nama, skin, mode neko dan program ikut tersimpan. Isi tas dan perlengkapannya diberikan ke kamu |
| Klik kanan Bot Base | Panel kontrol untuk semua bot di sekitar |
| Jongkok + klik kanan Bot Base | Buka storage base |
| Pukul bot tanpa jongkok | Tidak berefek (bot aman dari pukulan tak sengaja pemiliknya) |
| Pukul bot sambil jongkok | Benar-benar memukulnya: bot ngambek dan kedekatannya turun |

### Tombol pintas

Semua tombol bisa diganti di **Options → Controls → Key Binds**, bagian **Butter Bot**. Tombol bawaannya dipilih supaya tidak bentrok dengan tombol Minecraft maupun Iris (R, K, O).

| Tombol | Fungsi |
|---|---|
| `B` | Buka panel bot |
| `H` | Panggil **semua** bot ke kamu (mode ikut) |
| `Z` | **Kerjakan yang kamu lihat** (sampai 64 blok, lihat di bawah) |
| `N` | Hentikan semua bot |
| `J` | Semua bot pulang ke Bot Base |
| `Y` | Semua bot menyerahkan barang bawaannya sekarang |
| `V` | Buka tas dan perlengkapan bot terdekat (dalam 8 blok) |
| `U` | Mode pengawal semua bot nyala/mati |
| `I` | Terangi area 24 blok di sekitarmu |
| `M` | Ambilkan satu tumpuk lagi barang yang sedang kamu pegang |
| `]` | Ganti tampilan: daftar bot + penanda bot, hanya daftar bot, atau tidak ada |
| `[` | **Jeda semua job**, atau kalau semua sedang dijeda, suruh semua bot kerja lagi |

`H`, `N` dan `J` hanya berlaku untuk bot dalam 500 blok darimu, dan sekaligus menjeda job bot itu (tekan `[` untuk menyuruh mereka kerja lagi).

`Z` (kerjakan yang kamu lihat) memilih bot terdekat dan menyesuaikan perintah dengan yang kamu lihat:

- **bijih** → tambang 16 bijih itu, mulai dari blok yang kamu lihat;
- **kayu** → tebang pohon itu (32 kayu);
- **peti atau tong** → rapikan peti;
- **tanaman atau farmland** → bertani di situ;
- **Bot Base** → jadikan base bot itu lalu pulang ke sana;
- **monster atau hewan** → semua bot dalam 48 blok menyerangnya (juga saat mode pengawal mati). Penduduk desa, golem, peliharaan dan Warden tidak bisa diserang;
- **bot milikmu** → buka panelnya;
- **blok lain** → bot berjalan ke sana lalu **menunggu di situ** sampai diberi perintah lain.

### Job (pekerjaan tetap)

Setiap bot bisa diberi **job**: pekerjaan tetap yang terus dikerjakan selama kamu tidak memberinya perintah lain. Perintah biasa (tambang, ikuti, ambilkan, dll.) tetap bisa dipakai; setelah selesai, bot otomatis kembali ke job-nya. Atur di panel bot (tombol `B` atau klik kanan bot), tab **Job**.

| Job | Yang dikerjakan |
|---|---|
| **Penambang** | Menggali bijih dan batu: batu bara, besi, tembaga, emas, redstone, lapis, berlian, zamrud, cobblestone, cobbled deepslate, andesit, diorit, granit, tuff, kerikil |
| **Pengumpul** | Mengumpulkan barang permukaan: 9 jenis kayu, pasir, pasir merah, tanah, tebu, kaktus, labu, semangka, bambu, bola salju, biji gandum |
| **Penjaga** | Patroli di sekitar titik pilihanmu (radius 30 blok, bisa 6–64), melawan semua monster di area itu, memungut loot dan XP yang tergeletak, lalu menyimpan loot ke peti di area itu |
| **Petani** | Memanen dan menanam ulang ladang di sekitar titik pilihanmu (radius 16 blok, bisa 6–32) terus-menerus. Hasil panen masuk ke peti pilihanmu (atau Bot Base) |
| **Peternak** | Merawat hewan ternak di sekitar titik pilihanmu (radius 16 blok, bisa 6–32): mengawinkan, mencukur domba, memerah sapi, memungut telur/wol/bulu, mengusir monster, lalu menyimpan hasilnya di peti |
| **Kurir** | Mengosongkan sampai 4 peti sumber ke satu peti tujuan, terus-menerus, dan bisa melebur bijih mentah di jalan |
| **Pengawal** | Mengikuti salah satu bot milikmu ke mana pun (juga lewat portal) dan melawan monster di sekitarnya |

**Penambang dan Pengumpul** bekerja dengan **satu peti khusus** dan **daftar barang**:

1. Pilih job, lalu tekan **Pilih peti (Z)**: panel tertutup, lihat ke peti (atau barrel / Bot Base) lalu tekan `Z`.
2. Klik **+ Tambah** dan pilih barangnya (maksimal 9 barang). Jumlahnya diatur dengan tombol −64 / −16 / +16 / +64.
3. Bot memilih barang pertama yang masih kurang di peti, pergi ke lokasi kerja dan mencarinya, lalu menyetorkannya ke peti. Ini diulang **sampai semua jumlah di daftar ada di peti**. Angka di tiap kotak menunjukkan isi peti / target.
4. Setelah daftar lengkap, bot menunggu di dekat peti. Kalau kamu mengambil barang dari peti sampai kurang dari target, bot langsung berangkat kerja lagi.

**Zona larangan**: Penambang dan Pengumpul **tidak pernah menggali atau menebang dalam 200 blok dari Bot Base** (dari peti job kalau bot belum punya base). Jaraknya bisa diubah per 25 blok (0–1000). Bot memilih lokasi kerja di luar zona itu, sebisa mungkin di biome yang cocok (misalnya hutan birch untuk kayu birch, gurun untuk pasir dan kaktus, gunung untuk zamrud). Lokasi yang ternyata kosong tidak didatangi lagi; kalau 6 lokasi berturut-turut kosong, barang itu dilewati dulu selama 10 menit. Perintah manual (misalnya `Z` ke bijih) tetap boleh dipakai dekat base.

Bot yang punya job **tetap bekerja walaupun kamu jauh**: area kecil di sekitar bot tetap dimuat game, jadi penambang 300 blok dari base tidak berhenti. Lokasi yang jauh didatangi dengan **jalan kaki** (teleport hanya kalau terpaksa, lihat Anti nyangkut), jadi perjalanan ke lokasi kerja di luar zona bisa makan waktu. Di dalam zona larangan bot hanya lewat, tidak menggali. Setelah menyetor, bot kembali tepat ke terowongannya. Bot ber-job bisa diatur dari panel dan perintah dari jarak berapa pun (di dimensi yang sama), dan selalu muncul di HUD.

**Penjaga**: tekan **Di sini** (titik = tempatmu berdiri) atau **Pilih (Z)**. Peti loot bisa dipilih dengan **Peti (Z)**; dengan **Auto**, bot memakai peti terdekat di area patrolinya. Yang dipungut hanya barang yang tidak dibuang pemain (loot monster dan sejenisnya).

**Peternak**: atur titik, radius dan peti seperti Penjaga. Yang dirawat: sapi, mooshroom, domba, babi, ayam, kelinci dan kambing (hewan peliharaan, kuda dan hewan bernama tidak pernah dipotong).

- **Batas per jenis**: tab Job menampilkan 7 kotak hewan dengan **jumlah di area / batas** (misalnya Domba `24/30`). Klik hewannya lalu atur batasnya dengan −10 / −1 / +1 / +10 (2–99, bawaan 10). Yang dihitung hanya hewan di dalam radius peternakan (misalnya 16 atau 32 blok).
- **Kawin**: tiap jenis dikawinkan sampai batasnya. Bot memberi makan sepasang hewan dewasa yang berdekatan, langsung keduanya. Pakan diambil dari peti (sampai 32 per jenis): gandum untuk sapi, domba dan kambing, benih untuk ayam, wortel/kentang/bit untuk babi, wortel/dandelion untuk kelinci. Kalau pakannya tidak ada, bot memberitahumu.
- **Cukur & perah**: domba yang berbulu dicukur (tanpa gunting), sapi diperah kalau ada **ember kosong** di peti (bot membawa sampai 4).
- **Pungut**: telur, wol, bulu, daging dan kulit yang tergeletak di area dipungut. Kandang berpagar tidak masalah: bot masuk lewat **pintu pagar (fence gate)** atau pintu kayu, lalu menutupnya lagi supaya hewan tidak kabur.
- **Potong lebih** (mati kalau tidak dinyalakan): kalau jumlah satu jenis di area melebihi batasnya (misalnya 34 domba dengan batas 30), hewan dewasa dipotong sampai kembali ke batas. Anak hewan, hewan bernama, yang diikat tali, babi berpelana dan yang sedang kawin tidak pernah disentuh, dan setiap jenis selalu disisakan minimal 2 ekor dewasa.
- Malam hari bot melawan monster yang masuk area ternak.

**Kurir**: tekan **Peti (Z)** untuk peti tujuan, lalu **Tambah sumber** untuk tiap peti yang mau dikosongkan (maksimal 4, satu dimensi dengan peti tujuan). Tanda **x** di samping sumber menghapusnya.

- Bot berkeliling peti sumber, mengambil semua isinya (selama tas masih muat), lalu membawanya ke peti tujuan. Ini diulang terus, dan tiap putaran dimulai dari sumber berikutnya supaya semua sumber kebagian. Kalau bot berhenti sebentar untuk melawan monster, ia melanjutkan dari tempat terakhir. Kalau semua sumber kosong, bot menunggu dan memeriksa lagi tiap 30 detik.
- **Lebur bijih di jalan** (nyala dari awal): bot ikut membawa batu bara atau arang dari peti sumber, dan tungku bawaannya melebur besi, emas, tembaga mentah (dan puing kuno) selama perjalanan. Di peti tujuan bot menunggu sampai leburan selesai (paling lama 90 detik), jadi yang masuk peti berupa ingot. Yang dilebur hanya bijih: makanan dan barang lain sampai di peti apa adanya. Kalau dimatikan, tidak ada yang dilebur. Bot menyimpan sampai 32 bahan bakar dan 16 makanan untuk dirinya, dan selalu menyisakan 2 slot tas untuk hasil leburan.
- Kalau peti tujuan penuh atau tidak bisa dicapai, bot memberitahumu lalu mencoba lagi 30 detik kemudian.
- Contoh: penambang mengisi peti di tambang, kurir membawa isinya ke gudang di rumah.

**Pengawal**: pilih bot yang dikawal dengan tombol nama bot di tab Job (saat job dipilih, bot milikmu yang paling dekat dengan pengawal langsung dipilih, bukan pengawal lain). Kalau bot yang dikawal dikemas lalu ditaruh lagi, pengawal menemukannya lagi lewat namanya.

- Pengawal berjalan di dekat bot itu: ikut turun ke terowongannya, ikut lewat portal yang dipakainya, dan berlari kalau tertinggal jauh.
- Monster dalam 12 blok dari bot yang dikawal dilawan, terutama yang sedang menyerangnya. Kalau pengawal masih jauh (lebih dari 20 blok), ia menyusul dulu baru bertarung. Pengawal juga tetap melindungimu seperti mode pengawal biasa.
- Pengawal tidak menggali atau membangun ke dalam kandang atau ladang bot peternak, petani atau penjaga; ia lewat pintu pagar.
- Kalau bot yang dikawal pergi ke dimensi lain tanpa portal yang dikenal (misalnya bot itu teleport darurat), pengawal menunggu 10 detik lalu menyusul sebagai jalan terakhir.
- Kalau bot yang dikawal dikemas atau hancur, pengawal diam di tempat dan memberitahumu.

**Jeda**: tombol **Jeda / Lanjut** di tab Job, `/butterbot job pause|resume`, atau `[` untuk semua bot sekaligus.

### Daftar bot di layar (HUD)

Di pojok kiri atas tampil semua bot milikmu: level (diwarnai sesuai tier-nya), nama, bar darah, apa yang sedang dikerjakan, jaraknya darimu (atau "dimensi lain"), dan isi tas. Daftar disembunyikan otomatis saat layar F3 terbuka. Tekan `]` untuk berganti antara daftar bot plus [penanda bot](#penanda-bot-dan-tombol-tunjukkan), hanya daftar bot, dan tidak menampilkan apa-apa (pilihan ini diingat).

Daftar ini juga langsung memberi tahu kalau ada yang tidak beres:

- **`!` kuning berkedip** menggantikan ikon job saat bot **nyangkut** (tulisannya jadi "Nyangkut!") atau baru saja harus melewati sesuatu, misalnya blok yang tidak bisa dicapai (awal pesannya tampil di baris itu). Saat bot mulai nyangkut terdengar satu bunyi "ting" pelan. Tanda ini hilang sendiri: semenit setelah kejadian terakhir, atau sepuluh detik setelah bot berhasil lolos dengan teleport darurat.
- **Baris berdenyut merah** selama bot diserang (beberapa detik setelah kena pukul) dan selama darahnya di bawah 30%.
- **Bar darah turun halus**, dan bagian yang baru hilang berkedip putih sebentar, jadi pukulan besar langsung kelihatan.
- **Garis progres hijau** di bawah baris: jumlah blok pesanan tambang, panjang terowongan, atau seberapa penuh daftar barang penambang/pengumpul di peti job-nya (menurut hitungan terakhir).

### Penanda bot dan tombol Tunjukkan

Di mode pertama tombol `]`, setiap bot yang lebih jauh dari 20 blok mendapat **penanda** yang melayang di atas kepalanya, terlihat tembus tembok: wajah kecilnya di dalam cincin darah, dengan jaraknya di bawahnya. Kalau bot ada di belakangmu atau di luar layar, penandanya menempel di tepi layar dengan panah kecil yang menunjuk ke arahnya. Bot yang lebih dekat dari 20 blok sudah punya label nama sendiri, jadi penandanya memudar dan hilang. Penanda disembunyikan saat layar F3 atau layar lain (panel, inventaris, chat) terbuka.

Tombol **Tunjukkan** (ikon teropong) ada di kartu bot di panel, di kanan bot 3D, dan di kanan setiap baris daftar **Semua bot**. Panel langsung tertutup, lalu selama **30 detik**:

- bot itu **bersinar tembus tembok** dengan warna rambutnya dan memancarkan **sinar beacon** setinggi langit, sehingga kelihatan dari jauh;
- ia mendapat penanda yang berdenyut, walaupun penanda sedang dimatikan dengan `]` (dari dekat penanda hanya muncul di tepi layar untuk menunjuk arah);
- bot berseru "Di sini, Tuan!" dengan not musik di atas kepalanya (bot neko menyapa dengan suaranya, Butter Bot klasik berbunyi "ting").

Sinar dan cahaya hanya tampil selama bot masih dalam jarak pandang dunia (view distance). Kalau lebih jauh dari itu, penanda di layar tetap menunjukkan arahnya. Telinga dan ekor kucing tidak ikut bersinar.

### Tab panel kontrol

Kartu di kiri panel menampilkan bot dalam **3D**: lengkap dengan telinga, ekor, kalung, baju zirah dan alat yang dipegangnya, dan ia menoleh mengikuti kursor mouse. Bot yang sedang jauh atau di dimensi lain tetap tampil 3D dengan skin dan tingkatnya. Saat memilih skin di tab Skin, pratinjaunya juga 3D.

- **Bot Saya**: daftar bot dan tombol lepaskan kepemilikan (✖).
- **Job**: pilih pekerjaan tetap bot dan atur peti, daftar barang, zona larangan atau area kerjanya (lihat [Job](#job-pekerjaan-tetap)).
- **Kontrol**: pulang ke base, ikuti aku, berhenti, antar sekarang, buka tas (18 slot), laporan status, tujuan antar (aku / base), tautkan base, ganti nama.
- **Tambang**: berlian, besi, emas, tembaga, batu bara, redstone, lapis, zamrud, batu, kayu, pasir. Bot menggali tangga turun ke ketinggian terbaik, lalu membuat cabang terowongan. Bijih lain yang kebetulan tergali ikut disimpan. Untuk kayu, bot menebang **seluruh pohon** sekaligus lalu menanam bibitnya. Tombol **Cari** memberi tahu koordinat blok terdekat (lihat [Cari bijih](#cari-bijih)).
- **Bertani**: tombol **Mulai bertani**, **Rapikan peti** dan **Terangi area** (lihat [Bertani](#bertani), [Rapikan peti](#rapikan-peti) dan [Terangi area](#terangi-area)).
- **Crafting**: ketik nama item (ada saran otomatis, tekan Tab). Bot memeriksa peti, tong dan storage Bot Base dalam 64 blok, mengambil bahan yang pas, membuat item, lalu mengantarnya.
- **Terowongan**: koordinat awal dan akhir (tombol "Posisiku" mengisi otomatis), ukuran 2x2, 3x3, 1x2, 3x2 atau 5x3, obor bisa dinyalakan atau dimatikan. Lava yang ditemui ditutup dengan cobblestone. Jika tas penuh, bot mengantar barang lalu melanjutkan.
- **Program**: editor Lua.
- **Skin**: ganti skin dan nyalakan atau matikan mode neko.
- **Level**: level, bar XP, kemampuan saat ini, XP yang disimpan untukmu, dan tombol pengaturan: Pengawal, Obor, Suapi aku, Isi ulang, Lebur.

Saat menggali, bot memegang beliung, sekop atau kapak sesuai blok dan mengayunkan tangannya. Saat bertarung, bot memegang pedang. Alat itu hanya tampilan: tidak bisa diambil dan tidak jatuh.

### Kemampuan survival

Semua aktif sejak awal dan bisa dimatikan di tab **Level** atau lewat perintah chat.

- **Mode pengawal**: bot melawan monster (zombie, skeleton, creeper, spider, dan lainnya) dalam 10 blok darinya atau 12 blok darimu. Bot tidak pernah memukul pemain, hewan atau peliharaan. Enderman, piglin, zombified piglin dan mob yang diberi nama dibiarkan sampai mereka menyerang. Warden, Creaking, Ender Dragon dan Wither tidak dilawan. Saat HP tinggal 30%, bot makan dari tasnya, atau mundur ke arahmu kalau tidak punya makanan. Setelah pertarungan, bot memungut jarahan monster lalu kembali ke tugasnya. Ini juga berjalan saat bot sedang mengikutimu.
- **Lebur otomatis (tungku bawaan)**: selama ada batu bara atau arang di tas, bot melebur barang bawaannya. Mode *bijih & makanan* (bawaan): logam mentah jadi ingot, ancient debris jadi netherite scrap, makanan mentah dimasak. Mode *semua*: juga pasir jadi kaca, kayu jadi arang, cobblestone jadi batu, dan seterusnya. XP tungku disimpan untukmu.
- **Obor otomatis**: saat menambang di bawah tanah, bot memasang obor di kakinya setiap kali gelap. Obor dari tas dipakai lebih dulu; kalau tidak ada, bot membuatnya sendiri.
- **Tutup lava**: lava di samping blok yang akan digali ditutup dulu dengan cobblestone, deepslate, tanah atau netherrack dari tas, atau cobblestone gratis kalau tasnya kosong. Saat menambang, bot menyimpan sampai 32 blok seperti itu. Bot juga **kebal api dan lava**.
- **XP untukmu**: XP dari bijih, tungku dan monster dikumpulkan bot lalu diberikan saat kamu berada dalam 4 blok darinya, saat bot mengantar barang, atau saat bot dikemas atau dipulangkan.
- **Suapi aku**: kalau bar laparmu tinggal 7 drumstick atau kurang dan kamu dalam 6 blok, bot memberimu makanan terbaik dari tasnya. Makanan dengan efek samping (daging busuk, mata laba-laba, apel emas, chorus fruit) dan sup tidak pernah diberikan.
- **Pulih sendiri**: 5 detik setelah terakhir terluka, HP bot pulih sedikit demi sedikit. Saat HP di bawah 50%, bot juga makan dari tasnya.
- **Tidak merusak ladang**: bot tidak menginjak farmland sampai rusak.
- **Penjaga barang saat kamu mati**: begitu kamu mati, bot terdekat (dalam 500 blok) berlari ke tempatmu mati dan memungut semua barang dan XP yang jatuh sebelum hilang. Koordinat tempatmu mati muncul di chat. Setelah kamu bangkit, bot menyusulmu (juga ke dimensi lain), mengembalikan barangnya, dan langsung memakaikan armor ke slot armor yang kosong. Barang yang dibawanya tidak hilang walaupun dunia disimpan atau bot ikut mati. `/butterbot death` menampilkan lagi tempat terakhir kamu mati.
- **Isi ulang hotbar**: saat bot dalam 8 blok darimu, tumpukan block, obor, makanan, panah, roket kembang api, ender pearl, snowball, bone meal dan wind charge di hotbar yang tinggal seperempat atau kurang diisi lagi. Bot mengambil dulu dari inventory-mu sendiri, lalu dari tasnya, lalu dari Bot Base kalau ia berada dekat base. Tumpukan yang habis sama sekali juga diisi lagi kalau bot punya barangnya.

### Perlengkapan (baju zirah dan alat)

Buka tas bot (tombol **Buka tas** di panel, atau `V`). Baris paling atas adalah slot perlengkapan: **helm, baju zirah, celana zirah, sepatu**, lalu **pedang, beliung, kapak, sekop, cangkul**. Klik item ke slotnya, atau shift-klik dari inventory-mu dan item langsung masuk ke slot yang cocok. Arahkan kursor ke slot kosong untuk melihat gunanya. Di kanan atas tertulis nilai armor bot sekarang.

- **Baju zirah** dipakai dan terlihat di tubuh bot (pas untuk skin lengan lebar maupun ramping), melindunginya seperti pemain, dan enchant-nya berlaku (Protection, Thorns, Feather Falling...). Baju zirah aus saat bot terkena serangan.
- **Alat** dipakai untuk pekerjaan yang cocok: beliung untuk batu dan bijih, kapak untuk kayu dan menebang pohon, sekop untuk tanah, pasir dan kerikil, cangkul untuk panen dan daun, pedang untuk bertarung. Enchant-nya berlaku:
  - **Efficiency** mempercepat menggali; bot tidak pernah lebih lambat dari alat bawaannya.
  - **Fortune**: yang dipakai yang lebih tinggi antara Fortune alat dan Fortune dari level bot.
  - **Silk Touch** berlaku di luar penambangan. Saat menambang, bot tidak memakainya supaya bijih tetap keluar sebagai bijih mentah yang dihitung.
  - **Sharpness, Smite, Fire Aspect, Knockback, Looting** berlaku saat bertarung. Pedang tanpa enchant yang lebih lemah dari pedang bawaan bot tidak dipakai.
- **Ketahanan**: alat aus setiap kali dipakai seperti di tangan pemain (Unbreaking berlaku, tanaman tidak membuat alat aus). Bot memberi tahu saat alat hampir rusak.
  - Alat **ber-enchant tidak pernah dibiarkan rusak**: saat tinggal 10 pemakaian, bot menyimpannya di slot dan memakai alat bawaannya sampai alat itu diperbaiki.
  - Alat tanpa enchant dipakai sampai rusak, lalu bot kembali ke alat bawaannya.
- **Mending**: XP yang dipungut bot dipakai dulu untuk memperbaiki perlengkapan ber-Mending, sisanya disimpan untukmu.
- Slot kosong berarti bot memakai alat bawaannya (besi, lalu berlian dan netherite sesuai level).
- Slot baju zirah hanya menerima baju zirah (bukan labu atau kepala).
- Perlengkapan tetap milikmu: dikembalikan saat bot dikemas atau dipulangkan, dan jatuh utuh di tempat bot hancur (kecuali yang terkena Curse of Vanishing).

### Inti Memori (menghidupkan bot lagi)

Kalau bot milikmu hancur, ia meninggalkan **Inti Memori** yang berkilau. Inti ini menyimpan nama, skin, telinga neko, level, skill, mastery, job, program Lua, kedekatan dan camilan favoritnya, serta semua pengaturannya.

- Inti masuk ke **Bot Base**-nya kalau masih ada tempat, kalau tidak ke **inventory-mu** (juga saat kamu di dimensi lain), asal kamu masih hidup dan ada slot kosong. Kalau dua-duanya tidak bisa, inti jatuh di tempat bot hancur, **bersinar menembus tembok dan tidak pernah hilang**. Chat memberi tahu letaknya, beserta koordinat tempat perlengkapan bot jatuh.
- Inti tahan api, lava, ledakan dan kaktus. Bot yang jatuh ke void meninggalkan intinya di dekat Bot Base (kalau base ada di dimensi yang sama) atau di dekatmu.
- **Rakit Inti Memori dengan 4 Iron Ingot** (tanpa pola) untuk mendapat item Butter Bot lagi. Taruh, dan bot kembali sambil berkata "Aku kembali, Tuan! Aku masih ingat semuanya~".
- Harganya kecil: XP di dalam level yang sedang berjalan hilang (levelnya tetap), dan XP simpanan untukmu sudah jatuh sebagai orb di tempat bot hancur.
- Bot yang sudah dilepas (tanpa pemilik) tidak meninggalkan inti. **Second Wind** (level 25) tetap menyelamatkan bot lebih dulu.

### Mode teleport

Tiap bot punya tombol **Teleport jauh** di tab Kontrol (atau `/butterbot teleport <true|false>`):

- **Nyala** (bawaan): seperti versi awal. Perjalanan lebih dari **64 blok** (ke peti, base, lokasi kerja, ke kamu) langsung teleport, bot menyusulmu kalau tertinggal lebih dari **24 blok**, dan langsung ikut ke dimensi lain. Tempat tujuan yang belum dimuat dimuat dulu, sementara itu bot berjalan.
- **Mati**: bot jalan kaki ke mana pun (juga lewat portal), dan hanya teleport sebagai jalan terakhir (lihat di bawah).

Di kedua mode, bot yang **tidak makin dekat ke tujuannya** selama 15–30 detik dianggap tersangkut, walaupun ia masih bergerak di tempat (menabrak tembok, mengapung di air), lalu teleport darurat.

### Anti nyangkut

Dengan **Teleport jauh** mati, bot **berjalan kaki** ke mana pun, sejauh apa pun, dan tidak teleport hanya karena tujuannya jauh. Di kedua mode, untuk jarak dekat bot selalu berjalan. Area di sekitar bot yang sedang bekerja atau dalam perjalanan tetap dimuat game, jadi perjalanan jauh tidak berhenti di tengah jalan walaupun kamu tidak ada di dekatnya. Kalau bot tidak bisa maju, ia merencanakan rute (pathfinding A*) dan mencoba cara berikut:

1. **Memotong jaring laba-laba dan powder snow** yang menahannya.
2. **Menggali tanah alami** yang menghalangi: batu, deepslate, tanah, pasir, kerikil, netherrack, daun, bijih, dan cobblestone. Bangunan pemain (papan, kaca, pagar, batu bata, pintu, wol, dan lainnya) **tidak pernah digali**.
3. **Membangun jalan** dengan cobblestone, deepslate, tanah atau netherrack dari tasnya, atau cobblestone gratis kalau tasnya kosong:
   - **tangga** ke atas untuk keluar dari lubang, jurang atau gua;
   - **menara (pillar)** lurus ke atas kalau tujuannya tepat di atas, juga untuk keluar dari air atau lava;
   - **jembatan** di atas celah atau jurang.

   Satu perjalanan paling banyak menaruh 64 block. Tumpukan block yang dibuatnya tetap tertinggal. Menara dibuat seperti pemain: bot melompat lalu menaruh block di bawah kakinya.
4. **Membuka pintu pagar dan pintu kayu** yang ada di rute, lalu menutupnya lagi setelah lewat.
5. **Teleport sebagai jalan terakhir** (di mode Teleport jauh mati), dengan pesan "Aku nyangkut, jadi aku lewat jalan pintas!":
   - **tersangkut**: semua cara di atas sudah dicoba dan bot tetap tidak makin dekat ke tujuannya selama 15–30 detik;
   - **genting**: tercebur lava sebelum kebal api, jatuh ke void, atau terjepit block yang tidak boleh digali; bot menyelamatkan diri ke tempat aman terdekat;
   - **penting**: barang yang jatuh saat kamu mati diambil cepat sebelum hilang (lebih dari 64 blok), atau bot harus menyusulmu/pulang ke job di dimensi lain tapi tidak tahu portalnya (setelah menunggu sebentar).

   Pekerjaan peternak (memberi makan, mencukur) dan memasang obor tidak pernah memakai teleport.

Pencegahan lain:

- Saat mengikutimu, bot ikut **pindah dimensi** (Nether atau End) dengan **berjalan masuk ke portal yang kamu pakai**. Bot mengingat portal yang dilewatinya, jadi job atau barang yang harus dibawa pulang tetap bisa ditempuh lewat portal yang sama. Kalau bot tidak tahu portalnya (atau portalnya tidak bisa dicapai), ia bilang, menunggu sebentar, lalu menyusul dengan teleport darurat.
- Bot **tidak pernah masuk portal tanpa sengaja**, dan **tidak pernah terbawa perahu atau minecart**.
- Kalau ada block yang menimpanya (kerikil, pasir), bot memecahkannya, atau terdorong keluar pelan-pelan kalau block itu tidak boleh digali.
- Saat menambang, kalau bot terkurung block yang tidak bisa digali (bedrock, obsidian), ia naik satu block lalu mencari arah lain.
- Kalau kamu offline atau sedang di dimensi lain, bot mengantar barang ke Bot Base, tidak menunggu terus.
- Powder snow tidak membekukannya, dan api serta lava tidak melukainya.
- Mengikutimu dan mengantar barang tetap jalan saat kamu naik perahu atau kuda.

### Kedekatan (elus dan camilan)

Setiap bot punya meter **Kedekatan ♥** (0–100) denganmu. Bot baru, dan bot dari versi lama, mulai dari 20. Kedekatan tidak pernah turun sendiri.

- **Elus**: dengan tangan kosong, arahkan ke **bagian atas kepala** bot (dahinya, sedikit di atas mata) lalu klik kanan. Kuping dan ubun-ubunnya tidak bisa diklik. Bot mendengkur (bot non-neko bersenandung pelan), muncul hati, kupingnya melipat, ekornya bergoyang cepat dan kepalanya menunduk ke tanganmu. Kerjanya tidak terganggu. Klik di bawah mata (wajah atau badan) tetap membuka panel, begitu juga `B` atau `Z`. +2 kedekatan, paling sering sekali per menit (mengelus lagi tetap ada animasinya).
- **Camilan**: klik kanan bot sambil memegang ikan (mentah atau matang), kue kering, pai labu, beri manis, beri bercahaya atau kue. Bot memakannya, sembuh seperti makan dari tasnya, lalu mendengkur. +3 kedekatan (ikan +4), paling sering sekali per 2 menit. Ikan buntal ditolak dan tetap di tanganmu.
- **Camilan favorit**: setiap bot punya satu favorit di antara kue kering, ikan kod matang, salmon matang, beri manis, pai labu dan beri bercahaya. Favoritnya memberi kedekatan dua kali lipat dan bot berseru "Ini favoritku!". Panel baru menampilkannya setelah kamu pernah memberikannya.
- **Waktu bersama**: +1 untuk setiap 10 menit bot berada dalam 16 blok darimu, dan +1 setiap kali bot naik level.
- **Memukul**: memukul bot sambil jongkok membuatnya ngambek (asap marah, ia membuang muka) dan kedekatan turun 5, paling sering sekali per 10 detik.

| Kedekatan | Tingkat | Bonus |
|---|---|---|
| 0–19 | Malu-malu | |
| 20–49 | Akrab | |
| 50–79 | Sayang | Elusan juga memulihkan 2 darah bot. Sesekali bot mendusel kakimu saat kamu diam di dekatnya (dalam 4 blok, 10 detik) |
| 80–100 | Belahan Jiwa | Saat darahmu di bawah 30% dan bot dalam 8 blok, ia memelukmu dan memberi Regeneration (sekali tiap 5 menit) |

Naik tingkat diberitahukan di chat. Kedekatan tampil di kartu bot di panel (♥ di baris darah, arahkan kursor untuk rinciannya), di halaman **Statistik** (bar merah muda, tingkat dan camilan favorit), dan di tooltip item bot yang dikemas maupun Inti Memorinya. Kedekatan dan camilan favorit ikut tersimpan saat bot dikemas jadi item, dan tetap utuh di Inti Memori kalau bot hancur.

### Kehidupan sehari-hari

Saat tidak punya tugas (tanpa perintah, job sedang tidak jalan dan tidak bertarung), bot hidup seperti kucing peliharaan:

- **Duduk santai**: kalau kamu diam 10 detik dalam 6 blok darinya, bot duduk di sebelahmu, ekornya rebahan di tanah, dan sesekali **menggeliat** sambil menguap. Begitu kamu bergerak, ia berdiri lagi.
- **Tidur di samping kasur**: saat kamu tidur, bot dalam 16 blok berjalan ke dekat kasurmu lalu berbaring dengan tulisan "z Z z". Paginya ia bangun, menggeliat, menyapa "Ohayou, Tuan!" dan kadang (30%) membawakan **hadiah kecil** seperti kucing sungguhan.
- **Joget**: kalau ada jukebox yang memutar musik dalam 10 blok, bot berjoget: kepala mengangguk mengikuti irama, tangan terangkat bergantian, ekor bergoyang cepat dan muncul not musik.
- **Kaget petir**: saat petir menyambar di dekatnya, bot melompat kaget dengan tangan terangkat (kadang teriak "Kyaa! Petir!").
- **Mengibas air**: habis kehujanan atau berenang, begitu kering bot mengibaskan badannya dan air memercik.
- **Okaeri!**: kalau kamu pergi lebih dari 3 menit lalu kembali, bot melambai sambil berkata "Okaeri, Tuan!" dengan hati.
- **High-five**: dua bot milikmu yang sedang santai berdekatan sesekali saling tos.

Armor, telinga, ekor dan aksesori ikut bergerak bersama semua gerakan ini.

### Level (1 sampai 30)

Bot mendapat XP kerja dari setiap blok yang digali, tanaman yang dipanen, crafting, item yang dilebur dan monster yang dikalahkan. Kerja yang lebih sulit memberi lebih banyak XP: batu 1, batu bara/tembaga 2, besi/redstone 3, emas/lapis 4, zamrud 8, berlian 10, ancient debris 15; monster sesuai kekuatannya (boss 200). Level tampil di atas kepala bot (`Lv3 Mochi-3F`). Saat naik level ada kembang api dan bot bersorak: melompat sambil berputar, kupingnya tegak dan bergoyang, ekornya berdiri dan bergetar, lalu muncul spiral cahaya berwarna tingkatnya. Untuk level penting muncul judul besar di layarmu.

| Level | Kemampuan baru |
|---|---|
| Setiap level | Lebih cepat menggali, HP dan serangan bertambah, **+1 poin skill** |
| 3 | Menambang seluruh urat bijih sekaligus (sampai 8 blok) |
| 4 | Tas 27 slot |
| 5 | Alat berlian, Fortune I |
| 6 | Urat bijih sampai 24 blok |
| 7 | Tas 36 slot |
| 8 | Fortune II, baris skill ke-2 terbuka |
| 9 | Alat netherite, urat bijih sampai 64 blok |
| 10 | Fortune III |
| 12 | **Kebal Api**: api dan lava tidak melukai |
| 14 | **Kebal Racun**: racun dan wither tidak mempan |
| 16 | **Regenerasi Kuat** (pulih 2x lebih cepat), baris skill ke-3 terbuka |
| 18 | **Tangan Emas**: 10% peluang hasil bijih ganda |
| 20 | **Tahan Ledakan**: ledakan hanya melukai setengahnya |
| 24 | Baris skill ke-4 terbuka |
| 25 | **Hati Baja**: sekali tiap 10 menit, pukulan mematikan menyisakan setengah darah |
| 30 | **Legenda**: +10% darah, serangan dan kecepatan gali, kilau emas |

#### Tingkat dan penampilan

Setiap 5 level bot naik **tingkat**, penampilannya ikut berubah dan ia mendapat aksesori baru. Warna tingkat dipakai untuk lonceng, pita, tulisan `Lv` di atas kepala dan judul saat naik level. Tingkat juga tampil di panel (**Lainnya**), di sebelah level. Tonton semuanya dengan `/butterbot level tour`.

| Level | Tingkat | Yang terlihat |
|---|---|---|
| 1–4 | Anak kucing | Kuping dan ekor berbulu lebat |
| 5–9 | Perunggu | Kalung merah dengan lonceng dan pita di ekor; ekor makin panjang dan tebal |
| 10–14 | Perak | **Pita** di pangkal ekor, kilau putih keperakan sesekali |
| 15–19 | Emas | **Bulu putih** di dalam kuping; lonceng dan pita menyala dalam gelap (dengan shader ikut bersinar), kilau emas |
| 20–24 | Berlian | **Tiara** kecil dengan permata menyala (tidak saat memakai helm); bagian dalam kuping menyala, kilau biru muda |
| 25–29 | Kecubung | Dua **bola arwah** ungu mengorbit bot, kilau ungu, huruf sihir meja enchant melayang ke arah bot |
| 30 | **Nekomata** | Dua ekor berujung api arwah biru, tiga **api arwah** mengorbit, warna pelangi, bintang ★ di depan level |

Saat naik level: bot melompat sambil berputar, kuping tegak dan bergoyang, ekor berdiri dan bergetar, **pilar cahaya** berwarna tingkatnya menjulang ke langit, **cincin cahaya** meluas di tanah, kilau berjatuhan di atas kepala, dan tulisan **"NAIK LEVEL! Lv X"** melayang naik. Saat naik tingkat semuanya lebih besar.

Kuping dan ekor juga menunjukkan **suasana hati**: tegak dan bergoyang riang saat kamu dekat, rebah ke belakang dan ekor mengembang saat takut (baru terluka) atau bertarung, terkulai saat darahnya di bawah 30%. Matanya **berkedip** sesekali dan menyipit senang saat dielus atau makan camilan.

Bot berpakaian sesuai cuaca: **syal** di daerah bersalju, **kacamata hitam** di gurun yang terik, dan **payung** kuning saat kehujanan.

Kilau mengikuti pengaturan Partikel di Minecraft. Ekor, kuping dan aksesori hanya tampil pada bot neko. Kalau bot memakai baju zirah dada, loncengnya dipasang di depan baju zirah. Bot non-neko tetap mendapat warna level, kilau dan efek naik level.

#### Pohon skill

Poin skill dibelanjakan di panel (`B` → **Lainnya** → **Skill**) atau dengan `/butterbot skill <nama>`. Baris skill terbuka di level 1, 8, 16 dan 24, dan setiap skill butuh skill di atasnya minimal 1 tingkat. Poin tidak cukup untuk semuanya, jadi tiap bot bisa punya spesialisasi. **Reset** (klik dua kali) mengembalikan semua poin.

| Cabang | Skill (tingkat maks) |
|---|---|
| Tambang | Gali Cepat (5): +10% kecepatan gali · Mata Bijih (3): radius cari +4 · Urat Raksasa (3): +16 blok urat · Untung Besar (2): +1 Fortune |
| Tempur | Kuat (5): +1,5 serangan · Tangguh (5): +6 darah · Kulit Besi (3): +4 armor · Tebasan (2): pukulan ikut kena monster di sekitar (40%/70%) |
| Tani | Tangan Hijau (3): +15% peluang panen ganda · Tungku Panas (3): +1 lebur sekaligus · Ladang Luas (2): radius bertani +4 · Kantong Ajaib (2): tas +9 slot (maks 54) |
| Gerak | Lincah (3): +8% kecepatan · Magnet (3): item di sekitar masuk ke tas saat bekerja · Perenang (1): bernapas di air · Bulu (1): tanpa damage jatuh |

#### Mastery pekerjaan

Kerja untuk job juga melatih job itu: Pemula → Magang → Terampil → Ahli → Master → Grandmaster (100, 300, 700, 1500, 3000 XP job). Tingkat 3 dan 5 memberi poin skill tambahan.

| Job | Bonus per tingkat |
|---|---|
| Penambang | +5% kecepatan gali; Grandmaster: +1 Fortune |
| Pengumpul | +6% peluang kayu/tanaman ganda |
| Penjaga | +8% serangan, +4 darah (dilatih dengan mengalahkan monster) |
| Petani | +8% peluang panen ganda; Grandmaster: tanaman di sekitarnya tumbuh lebih cepat |
| Peternak | +8% peluang anak kembar saat mengawinkan ternak |

Halaman **Statistik** di panel menampilkan semua angka bot, mastery, kemampuan level dan jumlah total yang pernah digali, dikalahkan, dipanen dan dibuat.

Level, XP, skill, mastery dan pengaturan ikut tersimpan saat bot dikemas jadi item.

### Bertani

Tombol **Mulai bertani** di tab **Bertani**, atau ketik `/butterbot farm`. Area kerjanya 16 blok di sekitar Bot Base, atau di sekitar posisi bot saat perintah diberikan kalau tidak ada base.

- Panen lalu tanam ulang gandum, wortel, kentang, bit, nether wart dan kakao yang sudah matang.
- Memetik sweet berry, memotong tebu (bagian bawahnya disisakan supaya tumbuh lagi), dan mengambil semangka dan labu dari batangnya.
- Menebang pohon yang sudah tumbuh lalu menanam bibitnya. Hanya pohon asli (log yang menyentuh daun alami), jadi rumah dari kayu aman.
- Menabur benih dari tasnya di farmland kosong.
- Hasil panen disimpan di Bot Base. Bot menyimpan sampai 32 benih atau bibit per jenis untuk ditanam lagi. Tanpa base, hasil diantar ke kamu saat tas penuh.
- Bot terus bertani sampai kamu menekan **Berhenti** atau mengetik `/butterbot stop`.

### Menambang di Nether (netherite)

Bawa bot ke Nether (bot dalam mode **ikut** ikut masuk portal bersamamu), lalu pilih **Netherite** di tab **Tambang** (atau `/butterbot mine netherite [jumlah]`, atau lihat puing kuno lalu tekan `Z`).

- Bot menggali tangga turun ke **Y 15**, lalu menambang cabang mencari **puing kuno (ancient debris)**. Puing kuno terlihat dari balik dinding seperti bijih lain.
- Butuh beliung berlian: bot punya sendiri mulai **level 5**, atau beri beliung berlian/netherite di slot perlengkapannya (level berapa pun). Satu blok butuh sekitar 5 detik, lebih cepat dengan Efficiency.
- Lava yang mengalir ke jalur atau ada di bawah langkah berikutnya **ditutup dulu** dengan batu, dan rute galian tidak pernah lewat lava. Bot baru **kebal api di level 12**, jadi taruh makanan di tasnya.
- Hasilnya puing kuno (atau scrap kalau tungku bawaan dinyalakan). Bijih Overworld (berlian, besi, dll.) hanya bisa dipesan di Overworld, netherite hanya di Nether.

### Rapikan peti

Tombol **Rapikan peti** di tab **Bertani**, atau ketik `/butterbot sort`. Bot mendatangi setiap peti dan tong dalam 12 blok dari Bot Base, lalu:

- menumpuk barang yang sejenis;
- mengelompokkan barang per jenis: bijih & mineral, alat & senjata & armor, makanan, kayu, tanaman, jarahan monster, redstone, batu & tanah, blok lain, lain-lain; setiap jenis diberi peti sendiri kalau tempatnya cukup;
- memindahkan isi storage Bot Base ke peti-peti itu.

Tidak ada barang yang hilang: yang tidak muat kembali ke storage base. Peti harta yang belum pernah dibuka tidak disentuh.

### Terangi area

Tombol **Terangi area** di tab **Bertani**, tombol `I`, atau ketik `/butterbot light [radius]` (bawaan 24, maksimal 48). Bot mencari setiap titik gelap tempat monster bisa muncul dalam radius itu di sekitarmu, menghitung posisi obor seminimal mungkin, lalu berjalan memasangnya satu per satu. Setelah itu bot memeriksa lagi (cahaya bisa terhalang dinding) dan menambal titik yang masih gelap. Obor dari tas dipakai dulu; kalau habis, bot membuatnya sendiri. Titik yang tidak bisa dicapai (gua tertutup, atap) dilewati dan dilaporkan, dan bot tidak menggali bangunan untuk mencapainya.

### Ambilkan dan cari barang

- `/butterbot fetch <item> [jumlah]`, atau pegang barangnya lalu tekan `M`. Bot mengambil dari tasnya dulu, lalu mendatangi Bot Base dan peti, tong serta shulker box dalam 16 blok dari base dan dari kamu, lalu mengantarkannya. Tanpa jumlah, bot membawa satu tumpuk.
- `/butterbot where <item>`: menampilkan peti mana saja yang berisi barang itu, berapa banyak, koordinat dan jaraknya, termasuk yang ada di tas bot. Peti-peti itu juga berkilau sebentar supaya mudah ditemukan.

### Cari bijih

`/butterbot find diamond`, atau pilih sumber di tab **Tambang** lalu tekan **Cari**. Bot memberi tahu koordinat blok terdekat sampai 200 blok darimu, seberapa jauh dan seberapa dalam letaknya, serta berapa banyak yang berkumpul di situ. Yang dicari hanya area yang sudah dimuat game (sejauh render distance). Pencarian ini tembus dinding, mirip X-ray.

### Perintah chat

Semua perintah bisa dipakai tanpa cheat. Perintah kerja berlaku untuk bot milikmu yang terdekat (radius 500 blok; bot yang punya job bisa diatur dari jarak berapa pun).

```
/butterbot summon [nama]          panggil bot baru
/butterbot dismiss                pulangkan bot terdekat
/butterbot menu                   buka panel bot terdekat
/butterbot getbase                dapat 1 Bot Base
/butterbot neko <true|false>      telinga & ekor kucing on/off
/butterbot mine <diamond|iron|gold|copper|coal|redstone|lapis|emerald|stone|wood|sand|netherite> [jumlah]
/butterbot find <sumber>          koordinat blok terdekat (200 blok)
/butterbot farm                   mulai bertani di sekitar base
/butterbot sort                   rapikan peti di sekitar base
/butterbot craft <item> [jumlah]
/butterbot tunnel <dariX> <dariZ> <keX> <keZ> [lebar] [tinggi] [obor]
/butterbot guard <true|false>     mode pengawal on/off
/butterbot torches <true|false>   obor otomatis saat menambang on/off
/butterbot feed <true|false>      suapi aku saat lapar on/off
/butterbot smelt <off|ores|all>   tungku bawaan: mati / bijih & makanan / semua
/butterbot level                  level, kemampuan dan XP yang disimpan
/butterbot level set <1-30>       langsung jadikan bot terdekat level itu
/butterbot level tour [1-10]      tur level: bot mulai dari level 1 lalu naik satu level tiap
                                  beberapa detik (bawaan 3) sampai 30, untuk melihat sorakan,
                                  tingkat dan efeknya berganti; skill yang sudah dipelajari aman
/butterbot skill                  daftar skill dan sisa poin
/butterbot skill <nama>           pelajari skill (contoh: quick_dig, magnet)
/butterbot skill reset            lupakan semua skill, poin kembali
/butterbot light [radius]         terangi area di sekitarmu (bawaan 24, maks 48)
/butterbot fetch <item> [jumlah]  ambilkan barang dari peti dan Bot Base
/butterbot where <item>           di peti mana barang itu disimpan
/butterbot refill <true|false>    isi ulang hotbar on/off
/butterbot death                  tempat terakhir kamu mati
/butterbot skin <1-5>             pakai skin neko bawaan nomor 1-5
/butterbot base | follow | come | deliver | stop | status | list
/butterbot job                    info job bot terdekat (isi peti, daftar, area)
/butterbot job set <miner|gatherer|guard|farmer|rancher|courier|bodyguard|none>
                                  (juga: penambang, pengumpul, penjaga, petani, peternak, kurir, pengawal, asisten)
/butterbot job chest [clear]      jadikan peti yang kamu lihat sebagai peti job
/butterbot job need <item> [jumlah]   tambah/ubah barang di daftar (0 = hapus)
/butterbot job remove <item>      hapus barang dari daftar
/butterbot job clear              kosongkan daftar
/butterbot job zone <0-1000>      zona larangan gali dari base (bawaan 200)
/butterbot job center             titik kerja penjaga/petani/peternak = posisimu
/butterbot job radius <6-64>      radius patroli/ladang/peternakan
/butterbot job herd <2-99>        peternak: batas yang sama untuk semua jenis hewan
/butterbot job herd <domba|sapi|ayam|babi|kambing|kelinci|mooshroom> <2-99>
                                  peternak: batas satu jenis (juga: sheep, cow, chicken, pig, goat, rabbit)
/butterbot job butcher <on|off>   peternak: potong ternak yang melebihi batas
/butterbot job source             kurir: jadikan peti yang kamu lihat peti sumber
/butterbot job source remove <1-4> | clear   kurir: hapus satu / semua peti sumber
/butterbot job smelt <on|off>     kurir: lebur bijih mentah di jalan
/butterbot job ward <nama bot>    pengawal: bot yang dikawal (clear = tidak ada)
/butterbot job pause | resume     jeda / lanjutkan job
```

## Skin

Bot memakai **skin pemain Minecraft biasa**, file PNG yang sama dengan skin untuk dirimu sendiri. Skin bisa diunduh dari situs skin mana pun.

- Tanpa skin kustom, bot memakai salah satu dari **5 skin neko bawaan**: **neko cokelat** (rambut cokelat, seragam hitam), **neko putih**, **neko pink**, **neko pastel** (rambut tosca, baju kuning) dan **neko biru**. Setiap bot baru otomatis mendapat skin yang belum dipakai bot lain milikmu, jadi 5 bot punya 5 skin berbeda. Bot dari versi sebelumnya juga langsung dibagi skinnya.
- Tombol **Bawaan ▶** di tab Skin berganti ke skin bawaan berikutnya (sekaligus melepas skin kustom). Bisa juga lewat `/butterbot skin <1-5>`.
- **Mode neko** (aktif sejak awal): telinga kucing dan ekor yang bergoyang dipasang di atas skin apa pun. Warnanya diambil otomatis dari warna rambut skin. Telinga sesekali berkedut dan ekor bergoyang lebih cepat saat bot berjalan atau bekerja.
- **Suara cewek Jepang** (selama mode neko aktif): bot bicara dengan suara VOICEVOX:小夜/SAYO. Ia menyapa saat dipanggil ("はじめまして、ご主人様！"), menjawab perintah ("了解にゃ！"), melapor saat tugas selesai, mengucap "どうぞ！" saat menyerahkan barang, berteriak saat bertarung dan terluka, merayakan naik level, berlari menyelamatkan barangmu ("ご主人様！今行くにゃ！"), memperingatkan lava, dan sesekali bersenandung saat bekerja. Kalimatnya tidak saling menimpa. Saat mode neko dimatikan, bot kembali memakai suara robot Butter Bot asli.

Cara memasang skin:

1. Buka tab **Skin**, lalu tekan **Buka folder**. Untuk profil SODIUM lokasinya `versions\SODIUM\config\butterbot\skins\`.
2. Salin file PNG ke folder itu, atau **seret file PNG langsung ke jendela panel**.
3. Pilih skin (pratinjau badan muncul di kanan), lalu tekan **Terapkan**. Tombol **Bawaan** mengembalikan skin bawaan.

Ketentuan skin:

- Ukuran 64x64, format lama 64x32, atau HD persegi (128 sampai 1024). Maksimal 2 MB.
- Lengan ramping (model Alex) terdeteksi otomatis. Tambahkan kata `slim` atau `wide` di nama file untuk memaksa salah satunya.
- Setiap bot bisa punya skin berbeda. Skin dikirim ke server sekali dan disimpan di folder dunia (`<world>/butterbot_skins/`). Pemain lain mengunduhnya otomatis.
- Jika skin gagal dimuat, bot memakai skin bawaan.
- Di bawah pratinjau tab Skin tertulis keadaan skin bot: **Skin neko bawaan**, **Skin sudah tampil**, **Memuat skin...** atau **Skin gagal dimuat**.
- File `neko_girl_slim.png` adalah skin yang sama dengan skin bawaan, jadi memasangnya tidak mengubah tampilan bot. Untuk menguji, coba skin lain.

## Program Lua

Program berjalan di sandbox: `os`, `io`, `require`, `luajava`, `debug`, `coroutine` dan akses file dimatikan. Loop yang tidak pernah memanggil fungsi `bot.*` dihentikan otomatis dengan pesan "program is too busy", jadi server tidak membeku. Program yang sedang berjalan mengalahkan semua perilaku lain dan tidak berlanjut otomatis setelah dunia dimuat ulang.

| Fungsi | Keterangan |
|---|---|
| `bot.say(teks)`, `print(...)` | Tampilkan gelembung bicara |
| `bot.pos()` | Posisi bot: `x, y, z` |
| `bot.goTo(x, y, z)` | Berjalan ke titik. Mengembalikan `true` jika sampai |
| `bot.dig(x, y, z)` | Hancurkan blok dalam jangkauan 6 blok |
| `bot.place(x, y, z, "item")` | Taruh blok dari tas |
| `bot.block(x, y, z)` | ID blok di posisi itu, misalnya `"minecraft:stone"` |
| `bot.scan("nama", radius)` | Cari blok terdekat yang namanya mengandung teks itu: `x, y, z` atau `nil` |
| `bot.deliver()` | Antar isi tas ke pemilik |
| `bot.deposit()` | Simpan isi tas di Bot Base |
| `bot.home()` | Berjalan ke Bot Base |
| `bot.count("item")`, `bot.inventoryCount()` | Hitung isi tas |
| `bot.ownerPos()` | Posisi pemilik: `x, y, z` atau `nil` |
| `bot.sleep(detik)` | Jeda |

Contoh:

```lua
for i = 1, 5 do
  local x, y, z = bot.scan("log", 16)
  if not x then break end
  bot.goTo(x, y, z)
  bot.dig(x, y, z)
end
bot.deposit()
bot.say("Kayu sudah di base!")
```

## Perbandingan dengan versi asli

| | Asli (Forge 1.20.1) | Port ini (Fabric 26.3) |
|---|---|---|
| Wujud | Robot mentega (model OBJ) | Orang dengan skin Minecraft biasa, plus telinga & ekor kucing opsional |
| Cara dapat | Craft CPU lalu Butter Bot | `/butterbot summon`, tanpa crafting |
| Baterai | Wajib, bot mati jika habis | Tidak ada |
| Tas | 9 slot, tidak bisa dibuka | 18 sampai 36 slot (naik dengan level), bisa dibuka |
| Bertarung | Tidak | Mode pengawal: melawan monster di dekatmu |
| Bertani & rapikan peti | Tidak | Ada |
| Naik level | Tidak | Level 1 sampai 30, pohon skill, mastery pekerjaan, kemampuan khusus |
| Lava | Berhenti | Ditutup dengan cobblestone, bot kebal api |
| Tujuan antar barang | Pemilik saja | Pemilik atau storage Bot Base (27 slot, bisa dipakai hopper) |
| Membuka panel | Hanya di Bot Base | Di Bot Base, klik kanan bot, atau `/butterbot menu` |
| Animasi kerja | Tidak ada | Memegang alat yang sesuai dan mengayunkan tangan |
| Sumber tambang | 9 | 11 (tambah batu dan pasir), cabang terowongan otomatis |
| Crafting | Peti dalam 100 blok | Peti, tong dan storage base dalam 64 blok |
| Lua | Tanpa batas CPU | Batas instruksi, sandbox lebih ketat, fungsi tambahan |
| Skin kustom | Tidak ada | Ada, tersinkron ke semua pemain |
| Bahasa | Inggris | Inggris dan Indonesia |
| Perintah chat | Butuh OP atau kreatif | Bisa dipakai semua pemain untuk bot miliknya |

## Membangun dari kode sumber

Butuh JDK 25. Runtime Java bawaan TLauncher (`java-runtime-epsilon`) sudah cukup.

```bash
./gradlew assemble             # hanya membuat jar di build/libs/ (ringan, tanpa tes)
./gradlew test                 # unit test (sandbox Lua, validasi skin)
./gradlew runGametest          # GameTest server (menambang, crafting, terowongan, Lua, base)
./gradlew runClientGametest    # GameTest client + screenshot di run/clientGameTest/
./gradlew runClient            # jalankan Minecraft dengan mod ini
```

`./gradlew build` juga menjalankan semua GameTest (membuka server Minecraft), jadi cukup berat untuk laptop. Kalau hanya butuh jar, pakai `assemble`.

## Lisensi dan kredit

- Mod asli, model Bot Base, tekstur dan suara: **creatoreskim** ([GitHub](https://github.com/Eskimek/Rick-And-Morty-butter-bot-mod), [Modrinth](https://modrinth.com/mod/butter-bot-ai-rick-and-morty)). Halaman Modrinth menyebut lisensi CC-BY-4.0, tetapi file `LICENSE` di repo GitHub-nya berbunyi *All Rights Reserved*.
- Skin bawaan bot, semuanya dari mc-skins.net: "Neko Girl Uniform" karya **brandongrahn** ([link](https://mc-skins.net/skin/neko-girl-uniform-045c7e)), "White Cat Girl" karya **queenofcraft9** ([link](https://mc-skins.net/skin/white-cat-girl-b7622e)), "Cute Cat Girl" karya **Tasselisk2011** ([link](https://mc-skins.net/skin/cute-cat-girl-317254)), "Pastel Cat Girl" karya **aikoandcho** ([link](https://mc-skins.net/skin/pastel-cat-girl-6896d3)) dan "Blue Cat Girl" karya **SleepyBlanket** ([link](https://mc-skins.net/skin/blue-cat-girl-bcb143)).
- Suara bot neko: **VOICEVOX:小夜/SAYO** (dibuat dengan [VOICEVOX Core](https://github.com/VOICEVOX/voicevox_core); [pedoman pemakaian SAYO](https://316soramegu.wixsite.com/sayo-official/guideline)).
- Port ini dibuat untuk **pemakaian pribadi**. Jangan unggah atau sebarkan ulang tanpa izin penulis asli.
- Mod penggemar tidak resmi. Tidak berafiliasi dengan Adult Swim atau pembuat Rick and Morty.
