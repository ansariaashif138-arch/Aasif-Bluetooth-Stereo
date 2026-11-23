# Aasif Bluetooth Stereo

Phone ki audio, TV ke speaker par — saare Android TV ke liye.

Do app hain:
- **TV app** (`:app`, `com.aasif.btstereo.tv`) — Android TV par install karo
- **Phone companion** (`:phone`, `com.aasif.btstereo.phone`) — Android 10+ phone par, sirf WiFi mode ke liye chahiye

## Mode 1 — Bluetooth (TV Bluetooth Stereo)

Jo feature OnePlus TV me "TV Bluetooth Stereo" naam se chhupa hai, wahi A2DP Sink hai:
phone audio source banta hai, TV Bluetooth speaker ban jata hai.

TV app:
1. TV ko 5 minute ke liye discoverable karta hai
2. System ki Bluetooth Audio / TV Bluetooth Stereo activity dhundh ke kholta hai
3. A2DP Sink (profile 11) support detect karke screen par saaf dikhata hai
4. Paired devices list + connect hone par status, aur AVRCP media keys (Play/Pause/Next/Prev)

Phone par kuch install nahi karna — sirf Bluetooth settings se TV pair karo.

**Pakki limit (jhooth nahi bolenge):** A2DP Sink ek *system profile* hai.
Ek normal APK Bluetooth stack me naya sink profile "install" nahi kar sakti —
ye TV ke firmware me manufacturer ne enable kiya hota hai ya nahi hota.
Jis TV me ye band hai, waha Bluetooth mode nahi chalega, chahe koi bhi app
install kar lo (system-signed / root wale TV isse bahar hain). Isi liye Mode 2 hai.

## Mode 2 — WiFi Audio (har Android TV par feature yahi se aata hai)

Jis TV me Bluetooth Sink nahi hai, waha ye app khud receiver ban jata hai —
isi se "feature nahi tha, app lagate hi aa gaya" wala kaam har TV par hota hai:

- TV app: "WiFi Mode ON" → TCP server (port 24880) + NSD advertisement `_aasifaudio._tcp.`
- Phone app: TV ko WiFi par auto-dhundhta hai, Android ke official
  AudioPlaybackCapture (MediaProjection permission) se phone ki playback
  audio capture karke PCM stream TV ko bhejta hai, TV use AudioTrack se bajata hai

Protocol: header `AAS1` + sampleRate (int BE) + channels (int BE), phir raw PCM 16-bit LE.

Limit: jo phone app apni audio par capture-block / DRM lagati hai, uski audio
Android khud capture nahi karne deta. Zyadatar music/video apps allow karti hain.

## Build

GitHub Actions (`.github/workflows/build.yml`) push par dono debug APK artifacts banata hai:
`Aasif-Bluetooth-Stereo-TV-debug-apk` aur `Aasif-Bluetooth-Stereo-Phone-debug-apk`.

Local: `gradle :app:assembleDebug :phone:assembleDebug` (JDK 17, Gradle 8.9)

## Test checklist (asli hardware par)

- [ ] OnePlus TV: Bluetooth mode — discoverable → phone pair → YouTube/Spotify audio TV par
- [ ] Dusri Android TV (jahan sink nahi): WiFi mode — TV ON, phone se search, connect, audio TV par
- [ ] Play/Pause/Next TV remote se Bluetooth phone par asar karta hai ya nahi (firmware par depend)

Powered by Aasif
