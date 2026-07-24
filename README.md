# OfflineMesh

Bluetooth-LE based mesh chat app — koi internet ya mobile network nahi chahiye.
Do ya zyada Android phone jab Bluetooth range (~10-50m, jagah ke hisaab se) mein
hote hain, wo directly baat kar sakte hain. Agar range mein 3rd phone ho, wo
message ko aage relay kar deta hai — is tarah chain बन ke message door tak
pahunch sakta hai (mesh network).

## Kaise kaam karta hai

1. Har phone BLE se ek custom "service UUID" advertise karta hai + wahi UUID scan
   bhi karta hai — isi se nearby OfflineMesh phones ek dusre ko dhoondte hain.
2. Jab do phone mil jaate hain, ek GATT connection banta hai aur messages
   ek "characteristic" ke through likhe jaate hain.
3. Har message ke saath ek TTL (hop count, default 5) hota hai. Har relay pe
   TTL 1 kam hota hai, aur jab 0 ho jaaye to wo aage nahi badhta.
4. Har message ki unique ID track hoti hai taaki same message dobara na dikhe
   ya loop mein na phase.
5. Message text AES-GCM se encrypt hota hai ek shared "group passphrase" se —
   sab group members ko same passphrase pata hona chahiye (verbally ya QR se
   share karo, network band hone se **pehle**).

## Setup (Android Studio mein)

1. Is poore folder ko Android Studio mein "Open" karo (File > Open > OfflineMesh)
2. Gradle sync hone do (internet chahiye sirf isi step ke liye, dependencies
   download karne ke liye)
3. Real device pe run karo (BLE peripheral mode zyadatar emulators pe kaam
   nahi karta) — kam se kam 2 phone chahiye test karne ke liye
4. Dono/sab phones mein ek hi group passphrase set karo abhi ke liye
   `MainActivity.getGroupPassphrase()` mein hardcoded value change karke
   (isko baad mein ek proper "enter code" screen se replace karna best rahega)

## Zaroori limitations (honestly bata raha hoon)

- **Range**: BLE ka range typically 10-50 meters hai, obstacles se kam ho
  sakta hai. Ye "shehar-wide" communication ke liye nahi hai — group ke logon
  ko physically kaafi paas paas hona padega, ya phir mesh chain banane ke liye
  beech mein enough log honge.
- **Battery**: continuous BLE scan + advertise battery drain karta hai.
- **Android version quirks**: Android 12+ (API 31+) pe naye Bluetooth runtime
  permissions chahiye (already manifest + code mein handle kiya hai), lekin
  different phone manufacturers (Xiaomi, Samsung etc.) apne background
  restrictions laga sakte hain jo BLE background service ko kill kar sakte hain.
- **Crypto**: ye ek shared-passphrase AES-GCM hai, Signal jaisa full end-to-end
  protocol nahi. Reasonably private hai casual eavesdropping se, lekin isko
  "unbreakable" mat samajhna.
- **Scale**: bahut zyada log (jaise 100+ ek jagah) same UUID advertise/scan
  karenge to BLE radio congestion ho sakta hai — chhote groups (protest ke
  ek corner/cluster) ke liye best design kiya hai.
- Ye abhi ek **working prototype/starting point** hai, production-ready polish
  (proper onboarding, group codes via QR, message history persistence agar
  chahiye, UI polish) abhi baaki hai.

## Store-and-forward (sneakernet) — city-to-city ke liye

BLE ka range 10-50m hi hai, isko app se badha nahi sakte (physics ki limit hai,
VPN bhi yahan kaam nahi karega kyunki VPN ko khud ek internet connection
chahiye hota hai). Lekin agar internet/tower bilkul nahi hai, tab bhi ek
tarika hai messages ko **city A se city B tak, delay ke saath**, pahunchana:

- Jab bhi koi phone message bhejta/relay karta hai, wo apne "outbox" mein
  bhi save ho jaata hai — ab ye disk pe persist hota hai (`PersistentStore.kt`),
  app band/restart hone pe bhi nahi udhta.
- Jo bhi phone kisi bhi naye peer se milta hai (chahe wo doosre cluster/city
  ka ho), apna poora outbox usko forward kar deta hai.
- Isliye agar koi banda/bandi City A ke protest cluster se milke, bus/train
  lekar City B ke cluster tak jaaye (jahan uska phone doosre OfflineMesh users
  ke BLE range mein aaye), to City A ke messages automatically City B tak
  "carry" ho jaayenge — bina kisi extra kaam ke, bas dono jagah app khula/on
  hona chahiye Bluetooth ke saath.
- Undelivered messages 7 din tak outbox mein rehte hain, uske baad drop ho
  jaate hain (taaki storage infinite na badhe) — `MeshMessage.MAX_OUTBOX_AGE_MS`
  mein ye value change kar sakte ho.
- Hop-limit (TTL) 5 se badha ke 20 kar diya hai, kyunki ek carrier phone khud
  hi kai "hops" jaisa kaam karta hai jab wo alag-alag clusters se milta hai.
- UI mein ab "Pending delivery: N" dikhega — matlab N messages abhi bhi kisi
  next peer ka wait kar rahe hain.

**Important honesty check:** ye real-time chat nahi banayega — delay minutes
se lekar din tak ho sakta hai, depend karta hai kitni baar/jaldi log dono
clusters ke beech travel karte hain. Agar koi regular route pe travel karta
rahe (jaise daily City A-B bus), to ye kaafi reliable "relay courier" ban
jaata hai.

## Naye features (is round mein add kiye)

**1. QR code se group join karna**
"Show QR" button apna group passphrase ek QR code mein dikhata hai. Doosra
banda "Scan QR" se usko scan kar le, bas — ab dono same group mein hain,
typing ki zaroorat nahi. (Camera permission chahiye scan karne ke liye.)

**2. Wi-Fi Direct (longer range + higher throughput)**
BLE ke saath-saath ab Wi-Fi Direct bhi chalta hai automatically. Iska range
BLE se kaafi zyada hai (~100-200m outdoors vs ~10-50m BLE) aur speed bhi zyada
hai — isi wajah se photos bhejna possible hua hai. Dono transports parallel
chalte hain aur same message-dedup pipeline use karte hain, isliye app khud
decide kar leta hai kaunsa transport available hai.
*Limitation*: Wi-Fi Direct ek time pe ek "group" banata hai (1 owner + uske
clients), BLE jaisa free-form mesh nahi — lekin BLE apna kaam parallel mein
karta rehta hai, to overall coverage dono milke better ho jaata hai.

**3. Photo bhejna**
"Send Photo" button se gallery se photo pick karo. App automatically usko
chhota (max 480px, JPEG quality 50%) kar deta hai taaki mesh pe bhejna
practical rahe. Chhoti images BLE se bhi ja sakti hain, lekin zyadatar photos
Wi-Fi Direct peer milne ka wait karengi (BLE ka per-write limit ~500 bytes hai).

**4. Panic Wipe button**
"Wipe All" button (laal text mein) is phone ka saara mesh data - chat
history, pending outbox, sab kuch - turant delete kar deta hai. Confirmation
dikhata hai pehle. Ye sirf **is phone** ka data clear karta hai, doosre phones
pe unka copy waise hi rehta hai. Emergency mein (jaise phone check hone ka
darr ho) jaldi se sab clear karne ke liye use karo.

## Advanced round 2 — naye features

**1. Device identity + message signing (KeyManager.kt)**
Har phone ab ek apna ECDSA keypair banata hai (Android Keystore mein, private
key kabhi bhi keystore se bahar nahi jaati). Har outgoing message is key se
sign hoti hai. Receiver side pe signature verify hota hai, aur Trust-On-First-
Use (TOFU) tarike se pin kiya jaata hai: jis senderId se jo public key pehli
baar dikhi, wahi "verified" maani jaayegi aage. Agar wahi senderId achanak
kisi doosri key se sign karke aaye, UI mein "⚠ key changed!" dikhega — matlab
ya to unhone app reinstall kiya, ya koi unka naam use karke spoof karne ki
koshish kar raha hai. **Ye PKI nahi hai** — koi central authority verify nahi
karta, bas "pehli baar jo dikha wahi trust karo" wala model hai (SSH host key
jaisa).

**2. Time-windowed key rotation (RatchetManager.kt)**
Encryption key ab har ~6 ghante mein automatically badal jaati hai (group
passphrase se hi derive hoti hai, per-epoch). **Honest baat**: ye Signal jaisi
"forward secrecy" nahi hai — jisko passphrase pata hai, wo koi bhi epoch ki
key phir se nikaal sakta hai. Jo ye deta hai: ek single static key na hoke,
traffic time-buckets mein bant jaata hai, jo casual bulk-decryption thoda
harder banata hai. Real forward secrecy chahiye to naya passphrase generate
karke QR se re-share karna hi honest tarika hai.

**3. Adaptive TTL**
App ab track karta hai ki pichle 15 minute mein kitne alag senders se message
mile (mesh density ka signal). Agar bahut kam log dikhe, TTL badha diya jaata
hai (door tak pahunchne ki koshish). Agar bahut zyada log dikhe (dense
cluster), TTL kam kar diya jaata hai taaki radio congestion na ho.

**4. Message priority ("Urgent")**
Send button ke paas ek "Urgent" checkbox hai. Urgent messages outbox mein
sabse pehle bheje/relay kiye jaate hain jab bhi koi naya peer milta hai —
emergency broadcasts ("meeting point badal gaya") normal chat se pehle
pahunchti hain.

**5. Voice notes**
🎤 button se recording start/stop hoti hai (AAC, low bitrate ~24kbps, taaki
mesh ke liye chhota rahe). Chat mein ek "▶ Play" button ke saath dikhta hai.

**6. Location sharing**
📍 button apni last-known GPS location ek chhoti si "lat,lon" text ke roop
mein bhejta hai (encrypted, baaki messages jaisa hi). Receiver "Open location"
dabakar apne maps app mein khol sakta hai.

**7. Battery-aware BLE scanning**
Jab battery 20% se neeche ho aur phone charge pe na ho, BLE scan/advertise
automatically low-power mode mein chala jaata hai (kam frequent scanning,
kam Tx power). Charging pe ya battery theek ho to wapas full-speed mode.

**8. Smart outbox eviction**
Outbox ab ek size cap (8MB) follow karta hai. Agar bahut saari undelivered
photos/voice notes jama ho jaayein (koi peer na mile lambe time tak), sabse
purane NORMAL-priority messages pehle drop hote hain — URGENT messages aur
naye messages jitna ho sake bachaye jaate hain.

**9. Duress PIN / decoy mode**
Settings (⚙ button) mein ek "App PIN" aur ek alag "Duress PIN" set kar sakte
ho. App PIN set hote hi, app khulte waqt PIN maangega. Normal PIN se real
chat khulti hai. Duress PIN se ek bilkul **khaali decoy chat** khulti hai —
apna alag passphrase, alag storage files, real data ko chhoo tak nahi payi.
Emergency mein (jaise phone check hone ka darr ho), duress PIN dabao — real
data bilkul safe/untouched rehta hai disk pe, bas is session mein dikh nahi
raha. Decoy session ka apna "Wipe All" bhi hai jo sirf decoy data clear karta
hai.

## Offline AI ("Ask AI" button)

Ek chhota on-device language model add kiya hai jisse **bina internet ke**
general sawaal-jawab ho sake — `OfflineAiManager.kt`, Google ke MediaPipe
LLM Inference API (`com.google.mediapipe:tasks-genai`) use karke.

**Honestly samjho ye kya hai aur kya NAHI hai:**
- Ye **internet search nahi hai**. Koi live data, koi fact-check, koi
  real-time news nahi — sirf ek fixed model jo pehle se "baked" hai apne
  training data ke saath. Jo bhi usme nahi hai, wo model ko pata hi nahi.
- Ye **galat jawab confidently de sakta hai** (hallucination) — chhote
  on-device models mein ye normal hai, koi bug nahi hai. Legal rights,
  medical advice, "ye area safe hai kya" jaise safety-critical sawaalon
  ke liye ise sirf ek rough starting point maano, verified answer nahi —
  jahan tak ho sake logon se hi confirm karo.
- Jab bhi koi AI ka jawab mesh mein share karta hai (dono "Ask AI" dialog
  mein "Mesh mein share karo" button se), wo hamesha `[AI · unverified]`
  tag ke saath jaata hai — taaki koi bhi ise human ke message se confuse
  na kare.

**Setup (ek baar internet chahiye, uske baad sab offline):**
1. Kisi trusted Wi-Fi/data connection pe, ek MediaPipe-compatible `.task`
   model file download karo — jaise Hugging Face ke
   `litert-community` ya `google` orgs se **Gemma-3 1B (int4 quantized)**
   — ye sabse chhota/practical option hai, roughly 500MB-1GB ke aas-paas
   aur zyadatar mid-range Android phones (4GB+ RAM) pe chal jaata hai.
   Bade phone/RAM ho to bigger models (Gemma-3n E2B, ~3GB) better quality
   denge lekin zyada RAM/storage maangte hain.
2. Phone pe app kholo → **⚙ Settings** → **"Import AI model file (.task)"**
   → downloaded `.task` file select karo. Ye file app ke private storage
   mein copy ho jaati hai.
3. Ab **🤖 Ask AI** button se offline hi sawaal pooch sakte ho — koi
   internet ya mobile data nahi lagega is step ke baad.
4. Sirf jis phone pe model import hua hai wahi seedha jawab de sakta hai —
   baaki log group mein sirf tab jawab dekh payenge jab koi manually
   "share karo" dabaye.

**Limitations (honest):**
- Model load hone mein aur har jawab generate hone mein kuch second se
  lekar 20-30 second tak lag sakta hai, phone ke hardware pe depend karta
  hai.
- Battery aur RAM dono zyada use hote hain jab model active ho — kam-end
  phones pe dhyaan rakho.
- Model file khud .zip mein **included nahi hai** (bahut badi hoti hai) —
  upar diye steps se khud download karni padegi.

## Protest-safety features (this round)

**1. App disguise (basic)**
App drawer mein naam ab "Notes" dikhta hai (`strings.xml` ke `app_display_label`
se change kar sakte ho), aur icon bhi ek generic notepad-jaisa hai, exact
"OfflineMesh" jaisa nahi. **Honest limitation**: ye sirf launcher/app-drawer
level disguise hai — agar koi Settings > Apps mein jaakar package name
(`com.offline.mesh`) ya permissions dekh le, to pehchana ja sakta hai. Isko
Duress PIN ke saath combine karo for better protection.

**2. Screenshot/screen-recording block**
`FLAG_SECURE` laga diya hai — koi bhi app ke andar screenshot nahi le sakta,
aur recent-apps switcher mein bhi thumbnail khaali dikhega, screen recording
bhi kaam nahi karegi jab tak app foreground mein hai.

**3. 🆘 SOS button**
Ek tap (confirmation ke saath) se poore group ko highest-priority (URGENT)
emergency broadcast bhejta hai, saath mein last-known GPS location agar
available ho. Receiver side pe URGENT message aane par phone vibrate hota hai
(agar Silent mode ON nahi hai).

**4. ✅ "I'm safe" quick check-in**
Bina type kiye, ek tap se group ko batao ki safe ho.

**5. 📖 Know Your Rights (offline, editable)**
Ek local note jo poora offline available rahta hai. **Important**: ye
app khud koi legal advice nahi deta — ek khaali fill-in-the-blank template
diya hai (emergency contacts, "detain hone par kya karna hai" jaisi
categories) jise protest se PEHLE, apne local lawyer/legal aid/human rights
group se verify karke khud bharna hai. Kanoon aur helpline numbers state/
country aur time ke saath badalte hain, isliye app ke andar fixed/hardcoded
legal claims daalna galat/purana info spread kar sakta tha — isliye ye
deliberately ek template hai, tumhara verified info store karne ki jagah.

**6. Mesh health indicator**
Peer count ke neeche ab "Mesh activity (15 min): N device(s) seen" dikhega —
pichle 15 min mein kitne alag devices se message mila, coverage ka rough sense.

**7. Auto-delete chat history (ephemeral messages)**
Settings mein "Auto-delete after N hours" set kar sakte ho — chat history
(sirf displayed messages, undelivered outbox nahi) us se purani ho to
automatically delete ho jaati hai app open hone par. Blank/0 = kabhi nahi
(default).

**8. Quick panic-wipe shortcut**
App khuli ho to Volume-Down button 3 baar jaldi-jaldi (1.5 second ke andar)
dabao — turant panic-wipe confirmation aa jaata hai. **Honest limitation**:
ye sirf app foreground/open hone par kaam karta hai — screen-off/locked state
mein hardware key intercept karne ke liye device-admin/accessibility jaisi
invasive permissions chahiye hoti, jo is app mein jaan-boojh kar nahi li
gayi hain (privacy risk khud hi badha deti hain). Screen locked ho to
on-screen "Wipe All" button hi reliable tareeka hai.

**9. Silent mode**
Settings mein toggle — ON karne par naye URGENT/SOS message aane par bhi
phone vibrate nahi karega. Jab bhi discretion zaroori ho (jaise phone check
hone ka risk), ye on kar do.

## Protest-safety features (round 2 — is update mein)

**10. 📋 Roll call / safety check**
Organizer "Roll Call" button dabata hai → poore group ko ek URGENT "confirm
you're safe" broadcast jaata hai. Har doosra phone jise ye milta hai, ek
"✅ Main safe hoon" prompt dekhta hai — tap karte hi response wapas jaata hai.
Organizer ke phone pe ek live status dialog khulta hai jo "responded" aur
"abhi tak nahi respond kiya" list dikhata hai, real-time update hoti rehti hai
jaise responses aate hain. **Honest limitation**: "known members" list sirf
un devices ki hoti hai jinse tumhara phone is app-session mein pehle se
message exchange kar chuka hai — koi fixed/persisted roster nahi hai, isliye
ye ek best-effort safety signal hai, guaranteed headcount nahi.

**11. 🚔 Detained-person quick-log**
Dedicated "Detained" button — naam/pehchaan aur location (last-known GPS se
auto-fill, editable) bharke ek tap se poore group ko URGENT broadcast bhejta
hai: "X detained — last seen at [location/time]". Isse legal team/family tak,
jo bhi mesh mein hain, sabse tez tarike se pahunchta hai. Ek "Detained Log"
button bhi hai jo is phone ko dikhe sab detained-alerts ko ek alag scrollable
list mein dikhata hai, taaki normal chat scroll mein kho na jaaye.

**12. 🔒 Evidence Vault (encrypted photos/videos, chat se alag)**
Ek poori tarah alag secure gallery (`EvidenceVaultActivity`) — apna khud ka
Access PIN (chat ke App PIN se alag), apna khud ka optional Duress PIN, aur
apna khud ka scoped "Wipe Vault" jo sirf vault clear karta hai, chat ko nahi
chhedta. Camera se photo/video seedha encrypted vault mein capture hota hai
(phone ki normal gallery/MediaStore mein plaintext copy kabhi nahi jaati) —
"Import" option bhi hai already-existing files ke liye, lekin us case mein
original file jahan tha wahin bhi rehta hai. Duress PIN daalne par ek
genuinely khaali "decoy" vault khulta hai jo real evidence ko bilkul touch
nahi karta. **Honest limitation**: encryption PBKDF2-derived hai PIN se, koi
hardware-backed secret nahi — isliye vault ki security seedha tumhare PIN ki
strength pe depend karti hai.

**13. 🩹 Tear gas / injury first-aid quick reference**
Offline emergency first-aid steps — tear gas/pepper-spray decontamination
(eyes/skin flush, kab professional help chahiye) aur common injury basics
(bleeding, sprain, behoshi). Ye general/publicly-known safety information hai,
doctor/emergency services ka substitute NAHI hai — dialog ke top pe ye clearly
likha hai. CPR steps jaan-boojh kar shaamil nahi kiye — bina hands-on training
ke likhe steps follow karna khud risk create kar sakta hai.

**14. Rotating device ID**
Settings mein "Rotate device ID har session mein" checkbox — ON karne par har
app-restart pe ek naya random device ID milta hai (persist nahi hota), fixed
ID ki jagah. Isse cross-protest tracking/correlation thoda harder ho jaata hai
kyunki har session ek "naya device" jaisa dikhta hai. **Trade-off**: isse
key-verification ("✓ verified" badge) aur roll-call ka "known members" list
bhi har session reset ho jaate hain — off by default, aur change apply hone ke
liye app restart chahiye.

**15. 📌 Pinned dispersal point**
"Dispersal Pt" button se ek fixed meeting/dispersal-point message set/re-
broadcast kar sakte ho. Ye normal chat messages ki tarah scroll mein kho nahi
jaata — chat ke bilkul upar ek fixed yellow banner mein hamesha dikhta rehta
hai, jab tak koi clear (✕) ya naya pin bhej ke replace na kare. Group split ho
jaaye to bhi sabko ek hi jagah pe ye pin turant dikhega bina scroll-back kiye.

**16. Version-mismatch warning**
Har message ke saath sender ka app version (`AppVersion.CODE`) jaata hai. Agar
group mein koi purane/naye build pe hai, chat ke upar ek chhota ⚠️ warning
dikhta hai — kitne log purane version pe hain (unhe update karwana hai) aur
kitne naye pe (tumhe update karna hai). Sirf ek info hai, kisi ko block nahi
karta — lekin agar mismatch hai to naye safety features (roll call, detained
alert, pinned dispersal) sabke liye kaam nahi karenge, isliye protest se pehle
sabko same version pe le aana zaroori hai.

## App update kaise banao aur distribute karo (no hosting needed)

Ye ek fully offline peer-to-peer app hai — koi server/backend/hosting nahi
chahiye. GitHub Actions sirf **APK build karne** ke liye use hota hai, wo bhi
optional hai:

- Har push pe (`.github/workflows/build-apk.yml`) → ek debug APK ban ke
  GitHub Actions ke "Artifacts" mein milta hai, testing ke liye.
- Ek version tag push karne pe (`git tag v6 && git push origin v6`) →
  `.github/workflows/release-apk.yml` ek GitHub **Release** bana deta hai jisme
  APK ka direct download link aur ek scannable **QR code** dono hote hain —
  isse sabko bhejo/QR scan karwao. Tag push karne se pehle
  `AppVersion.kt` mein `CODE`/`NAME` bump karna mat bhoolna, taaki upar wala
  version-mismatch warning sahi rahe.
- Koi auto-update system NAHI hai — naya APK har phone pe manually install
  karwana padta hai (Play Store pe jaan-boojh kar nahi hai, ye protest-safety
  tool hai).

## Stable release signing key (zaroori for 1000+ log distribution)

Debug build (`assembleDebug`, `build-apk.yml`) fine hai testing ke liye, lekin
wide distribution ke liye **release build** use karo (`release-apk.yml`, tag
push pe trigger hota hai) — kyunki debug key har CI run pe alag ho sakti hai,
jisse ek naye version ka install PURANE ke upar "update" nahi ho paata
(Android signature-mismatch pe update block kar deta hai — sabko manually
uninstall+reinstall karna padta, chat/vault data samet).

Setup (ek baar):
1. GitHub repo mein jaake **Settings > Secrets and variables > Actions** kholo
2. Ye 4 secrets add karo (values tumhe ek alag secure file mein diye gaye hain,
   isi conversation mein — GitHub pe kabhi mat daalna keystore ko directly,
   sirf Secrets mein):
   - `RELEASE_KEYSTORE_BASE64`
   - `RELEASE_KEYSTORE_PASSWORD`
   - `RELEASE_KEY_ALIAS`
   - `RELEASE_KEY_PASSWORD`
3. Ab jab bhi `git tag vN && git push origin vN` karoge, ek **signed release
   APK** banega jo purane installs ke upar cleanly update hota hai.
4. `.jks` keystore file aur passwords ko kahin bahut safe rakho (password
   manager / encrypted note) — isse **kabhi bhi repo mein commit mat karna**.
   Agar ye kho gaya, next release se sabko phir se manual reinstall karna
   padega.

Certificate fingerprint (SHA-256) publicly share karna theek hai — log isse
apna downloaded APK verify kar sakte hain ki genuine hai:
```
72:3C:E4:41:68:07:EA:C7:94:6B:BF:55:60:6D:CE:67:47:26:0F:9D:7A:4F:41:41:F2:A2:CC:9C:8A:9A:DD:2D
```

## 1000+ logon tak APK pahunchana (distribution at scale)

Is scale pe ek-ek phone pe manually transfer karna practical nahi hai — isliye
**layered/decentralized approach** use karo, mesh app ki hi philosophy jaisa:

1. **Trusted "cluster lead" model** — har 20-50 logon ka ek trusted organizer
   ho jo unhe Nearby Share/Bluetooth se seedha APK de. Isse koi single
   centralized "distribution list" nahi banti jo target ban sake.
2. **App khud propagate ho sakta hai** — ek baar kuch sau phones pe install ho
   jaaye, APK file ko khud Nearby Share se aage bhejte raho ("mujhe mila,
   3 aur logon ko de do") — bilkul mesh chat ki tarah hi organic spread.
3. **Multiple independent channels** — sirf ek jagah (jaise ek hi Telegram
   channel) pe depend mat karo; GitHub Release link, Signal, local meetups —
   sab parallel use karo taaki ek channel band/monitor ho to baaki chalte
   rahein.
4. **Fingerprint verify karwao** — jahan bhi APK link share karo, saath mein
   upar wala SHA-256 fingerprint bhi do, aur logon ko bolo install se pehile
   verify karein (Settings > Apps > OfflineMesh > App info mein signature
   dikhti hai, ya kisi APK-verifier app se) — isse koi tampered/fake APK
   pakad mein aa jayega.
5. **Public link tab hi use karo jab zaroorat pade** — jahan possible ho,
   local/offline transfer ko priority do; public GitHub link ek digital trail
   chhodta hai (kisne visit kiya, kab).

## Deep bug-fix round (connection reliability)

Kaafi gehraai se audit karne ke baad ye real bugs mile aur fix hue:

1. **One-way BLE messaging** — GATT connections mein "client" aur "server" role
   hota hai; pehle sirf client→server direction kaam karti thi. Ab dono taraf
   kaam karta hai (GATT notify/subscribe mechanism add kiya).
2. **BLE writes silently drop hote the** — agar ek saath multiple messages
   bhejte (jaise naya peer connect hote hi purana outbox flush), Android
   beech ke writes chupke se drop kar deta tha. Ab ek serialized queue hai jo
   ek-ek karke confirm hone ke baad hi agla bhejti hai.
3. **Settings dialog scroll nahi hota tha** — ScrollView wrap missing thi.
4. **Image quality bahut kharab thi** — purana BLE-only zamana ka 480px/50%
   quality tha; ab Wi-Fi Direct ke through 1280px/82% quality jaati hai.
5. **Permissions bug (bahut critical)** — agar Camera ya Mic permission deny
   ki (QR scan / voice note ke liye), poora mesh service hi start nahi hota
   tha, chahe Bluetooth/Location sab sahi ho. Ab sirf Bluetooth/Location/WiFi
   permissions hi mesh start karne ke liye zaroori hain — Camera/Mic optional
   hain, unke bina bas wahi specific feature (QR/voice) disable rehta hai.
6. **Wi-Fi Direct discovery ~2 min baad ruk jaati thi** — Android ka apna P2P
   discovery window khatam ho jaata hai aur khud restart nahi hota; ab har
   90 second mein automatically re-trigger hoti hai.
7. **Wi-Fi Direct sirf pehla mila hua peer try karta tha** — agar wo busy/deny
   kare, doosre peers try hi nahi hote the.
8. **Wi-Fi Direct connection drop hone ke baad kabhi recover nahi hoti thi** —
   ek baar group toot jaaye to session ke baaki hisse mein Wi-Fi Direct
   permanently dead rehta tha. Ab automatically reset hoke dobara discovery
   shuru karta hai.
9. **Android 14 crash risk** — do jagah (`WifiDirectManager`, battery
   receiver) mein naya mandatory `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED`
   flag missing tha, jo Android 14 phones pe app ko turant crash kar sakta
   tha jaise hi mesh service start hoti.

## Advanced round 3 — is update mein

**1. Message compression**
Har message ab encrypt hone se PEHLE Deflate-compress hoti hai (`CryptoUtils.kt`).
Text/JSON-jaisa content typically 30-60% chhota ho jaata hai — BLE ke ~500-byte
writes ke liye ye directly matlab rakhta hai (kam packets, kam radio time, kam
battery). **Honest limitation**: photos/voice notes already-compressed formats
(JPEG/AAC) hain, unpe ye zyada fayda nahi karta — real gain text-heavy chat mein
hai. **Zaroori**: ye ek breaking wire-format change hai — jaise pichle rounds
mein bhi hua, poore group ko is version pe saath update karna hoga (version-
mismatch warning already ise flag karega).

**2. Delivery receipts (✓ delivered)**
Jab bhi koi doosra phone tumhara TEXT/IMAGE/AUDIO/LOCATION/DETAINED_ALERT
message decrypt karke dikhata hai, wo automatically ek chhota "DELIVERY_ACK"
wapas bhejta hai. Jab wo tumhare phone tak pahunch jaaye, tumhare bheje message
ke time ke aage ek "✓" dikhega. **Honest limitation**: ye best-effort hai — mesh
flood-based hai, guaranteed return-path nahi. Sirf "kam se kam ek doosre device
tak pahuncha" batata hai, sabtak nahi. Session-only hai (app restart pe checkmark
state reset ho jaata hai, undelivered nahi maano).

**3. Mesh-density congestion throttling**
Adaptive TTL ke saath-saath, ab agar mesh bahut dense lage (15 min mein 8+
alag senders), BLE scan/advertise bhi automatically low-power mode mein chala
jaata hai — sirf battery ki wajah se nahi, radio congestion kam karne ke liye
bhi. Har 5 minute mein re-check hota hai.

**4. Dead-man's switch (opt-in, Settings)**
Settings mein ek checkbox + "N hours" input. ON karne par: agar itne ghante
tak tumne app unlock nahi kiya ya "I'm safe" nahi dabaya, group ko automatically
ek URGENT alert chala jaata hai. Har check-in (unlock/I'm safe) par timer reset
ho jaata hai. **Honest limitation**: ye sirf tab kaam karta hai jab mesh service
chal rahi ho (phone on, Bluetooth on) — phone band/dead ho jaaye to koi alert
nahi ja sakta, ye koi hardware-level SOS nahi hai.

**5. Evidence Vault: encrypted backup export**
Evidence Vault ke "⚙" (Vault Settings) dialog mein ab ek "Export encrypted
backup" button hai — vault ke already-encrypted files ko ek `.zip` mein app ke
apne external-files folder mein daal deta hai
(`Android/data/com.offline.mesh/files/vault_exports`), koi storage permission
nahi chahiye. Files zip ke andar bhi encrypted hi rehti hain (PIN-protected) —
isse USB/SD card se ek doosri jagah backup rakh sakte ho agar phone confiscate
hone ka darr ho.

## Naye features (Mesh Tools screen)

Chat screen mein ab ek **"🧰 Mesh Tools"** button hai jo ek naya screen kholta hai
(`MeshToolsActivity`) jahan ye sab hai:

**1. Mesh intelligence — contact graph**
`ContactGraph.kt` har decrypt hue message se senderId track karta hai (kitni baar
mila, last kab mila) — disk pe persist hota hai. Ye poori mesh ka route table
NAHI hai (koi ek phone ko poori mesh ka shape pata nahi chal sakta, sirf apne
khud ke encounters ka pata hai) — isko sirf do jagah use kiya hai: (a) jab
ek saath kai peers connected hon, unko outbox sync karne ka order decide karne
mein (bahut-frequent + bahut-rare peers ko priority, beech waale kam), aur
(b) Mesh Tools screen pe "kaun kis se mila" list dikhane ke liye.

**2. Compression/payload stats**
`CompressionStats.kt` — sent/received messages ka average on-wire size aur
plaintext-vs-encoded overhead ratio track karta hai, Mesh Tools screen pe
dikhta hai. Honest note: abhi koi real compression (gzip waghera) nahi hai,
isliye "ratio" asal mein AES-GCM+Base64 ka overhead dikhata hai (~1.3-1.4x), na
ki kuch bacha hua space — agar future mein real compression add ho, usi class
mein `recordCompressed()` already plumbed hai.

**3. Offline maps + pinned locations**
`OfflineMapView.kt` ek custom view hai jo pehle se download kiye hue XYZ tiles
(`<app-external-files>/offline_tiles/{z}/{x}/{y}.png`) dikhata hai — koi Google
Maps/Mapbox SDK nahi (unko internet chahiye hota hai, jo is app ke poore point
ke against hai). Tiles na milen to bhi crash nahi hota, ek simple grid dikhta
hai aur pins fir bhi plot hote hain. Tiles cut karne ke liye (ek baar, internet
hone par) koi bhi standard XYZ tile-cutting tool use karo (e.g. QGIS export,
`gdal2tiles.py`, ya `mb-util`) kisi chhote area ke OpenStreetMap data se — agar
OSM data use karo to "© OpenStreetMap contributors" credit rakhna zaroori hai.
Mesh Tools screen se current location pin kar sakte ho (label ke saath) — ye
list disk pe persist hoti hai (`PinnedLocation.kt`).

**4. Group sub-channels**
`MeshMessage` mein ab ek `channel` field hai (default `"general"`, suggested:
`general`/`medic`/`legal`, custom bhi add kar sakte ho). Ye SECURITY boundary
NAHI hai — same passphrase = same encryption key, har channel har device pe
relay hota hai chahe wo dekh raha ho ya nahi, sirf UI mein feed alag dikhta
hai. Purane build wale phones jo channel field nahi jaante, unko sab kuch ek
hi feed mein mix dikhega (graceful degrade).

**5. Anonymous broadcast mode**
Chat compose box mein ek "Anon" checkbox hai — check karne par us message ka
sender naam sabko "Someone in group" dikhta hai (`MeshMessage.anonymous` +
`displayName()`). Honest limitation: ye ek DISPLAY decision hai — senderId
network pe wahi rehta hai (ACK/roll-call jaisi cheezon ke kaam karne ke liye
zaroori), isliye ye traffic-analysis-level anonymity nahi deta, sirf casual
"kisne bheja" ko UI mein hide karta hai.

**6. On-device translation**
`TranslationManager.kt` — agar tumne already Offline AI model set kiya hai
(Settings > Offline AI), wahi model translation ke liye reuse hota hai (ek
alag TFLite translation model download/manage karne ki zaroorat nahi). Quality
utni hi achhi hogi jitna woh small LLM translation mein achha hai — casual
gist ke liye theek hai, legal/medical-grade translation ke liye nahi.

**7. Voice note transcript**
`VoiceTranscriber.kt` — Android ke built-in on-device-preferred speech
recognizer (`RecognizerIntent.EXTRA_PREFER_OFFLINE`) se live voice ko text mein
badalta hai, Mesh Tools screen se test kar sakte ho. Honest limitation: ye
guarantee nahi deta ki recognition sach mein on-device hi hui — depends on
phone ke language pack settings — isliye transcript hamesha "AI transcript,
verify by listening if possible" ke saath dikhta hai.

**8. Mesh topology visualizer**
`MeshTopologyView.kt` — sirf "main abhi/recently kis kis se juda hoon" dikhata
hai (green line = recently active, dashed grey = purana). Poori mesh ka graph
nahi (koi ek phone ko wo pata nahi chal sakta) — debugging/coverage-check ke
liye hai, poore group ka naksha nahi.

**9. Battery-sharing alert**
`BatteryRelayMonitor.kt` — agar tumhare phone se abhi 3+ devices connected hain
AUR battery 15% se neeche hai, ek high-priority notification aata hai ("charge
dhoondo, warna in logon ka connection toot sakta hai"). `BleMeshService`'s
already-existing periodic check loop mein hi ye check chalta hai, koi extra
battery drain nahi.

## Agla step

Bata do agar ye chahiye:
- Message persistence ko Room DB mein migrate karna (abhi flat JSON hai)
- Delivery/read receipts
- Per-message routing table (abhi flood-based relay + global dedup hai)
