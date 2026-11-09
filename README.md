# QR Share

Send files from a Windows laptop to an Android phone **without any network** —
no Wi-Fi, no Bluetooth, no cable, no server. The laptop shows an animated QR
code, the phone films it and rebuilds the file.

## Download

| Device  | File | Link |
| ------- | ---- | ---- |
| Android (10+) | `QRShare.apk` | https://github.com/Aboss3b13/QR-Code-Share/releases/latest/download/QRShare.apk |
| Windows (x64) | `QRShare.exe` | https://github.com/Aboss3b13/QR-Code-Share/releases/latest/download/QRShare.exe |

Open the APK link on your phone, download it and tap it to install. Android will
ask you to allow installing apps from your browser once.

## How to use

1. Run `QRShare.exe` once on the laptop and answer **Yes** to add the
   right-click entry (stored per user in `HKCU`, no admin needed).
   Remove it again with `QRShare.exe --unregister`.
2. Right-click a file or folder → *Show more options* → **Send via QR Share**.
   Folders are zipped automatically.
3. Open **QR Share** on the phone and point the camera at the QR code.
   Missed frames don't matter — just hold steady until it's 100 %.
4. The file lands in `Download/QR Share` on the phone, with its SHA-256 verified.

### Speed

The sender shows a **grid of QR codes** that changes many times per second; the
phone reads all codes in each camera frame with Google ML Kit (on-device,
offline). Pick a preset in the *Speed* box:

| Preset | Codes per frame | Bytes per code | Frames/s | Max. throughput |
| ------ | --------------- | -------------- | -------- | --------------- |
| Reliable | 1 | 600 | 10 | 6 KB/s |
| Fast (default) | 4 (2×2) | 900 | 15 | 54 KB/s |
| Maximum | 6 (3×2) | 1100 | 20 | 132 KB/s |
| Extreme | 9 (3×3) | 1000 | 20 | 180 KB/s (experimental) |

Real speed is lower: it depends on how many codes per second the phone actually
reads (shown live on the phone). For the best results press **F11** for
fullscreen, set the laptop brightness to maximum, and hold the phone so the
screen fills the camera view. If progress stalls, step down one preset.

Physical limit: a phone camera records ~30 frames/s, and screen-to-camera links
top out around 100–200 KB/s even in research systems, so 1 MB/s is not
reachable with a camera alone.

## How it works

* The file is compressed (zlib, only when it helps), wrapped with name, size
  and SHA-256, and split into blocks.
* A **fountain code** turns the blocks into an endless stream of packets: the
  first pass sends every block once, after that it sends random XOR
  combinations. The phone can rebuild the file from *any* set of packets that is
  only slightly larger than the file, in any order, with any frames missed.
* The phone decodes with peeling plus Gaussian elimination over GF(2).

The wire format is documented in [PROTOCOL.md](PROTOCOL.md), so other senders
or receivers (iOS, Linux, web) can be written against it.

## Project layout

| Path | Contents |
| ---- | -------- |
| `android/` | Kotlin receiver app (CameraX + ZXing) |
| `windows/QRShare/` | C# / WinForms sender (ZXing.Net) with Explorer integration |
| `tools/interop-test/` | Runs the Android decoder on the JVM against packets exported by the Windows sender |
| `.github/workflows/build.yml` | Builds the APK and EXE and publishes a GitHub release on every push to `main` |

### Build locally

```bash
cd windows/QRShare && dotnet build -c Release
```

```bash
cd android && gradle assembleRelease   # needs Android SDK + JDK 17
```

Interop test (C# encoder → Kotlin decoder, with simulated frame loss):

```bash
QRShare.exe --export-packets some.pdf packets.bin 3000 800
cd tools/interop-test && gradle run --args="packets.bin some.pdf 0.3"
```

The APK signing key `android/app/qrshare.jks` is committed on purpose so every
CI build installs as an update over the previous one. Don't reuse it for an app
store release.
