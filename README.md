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

Tips: press **F11** for fullscreen on the laptop, turn screen brightness up, and
raise *Frames/s* or *Bytes/QR* in the toolbar if your phone keeps up (watch the
"QR/s" counter on the phone). Expect roughly 5–15 KB/s, so this is meant for
documents, images and small files rather than videos.

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
