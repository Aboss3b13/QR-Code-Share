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

Pick a preset in the *Speed* box of the sender. The phone detects the mode by
itself.

**Grid mode** (fastest): the whole screen becomes a grid of tiny black/white
cells, with 9 small QR codes as anchors. The phone films it at up to 4K.

| Preset | Cell size | Data per frame (1080p screen) | Max. throughput | Phone camera |
| ------ | --------- | ----------------------------- | --------------- | ------------ |
| Grid · Safe | 6 px | ~4.5 KB | ~45 KB/s | 1080p is enough |
| Grid · Fast (default) | 4 px | ~12 KB | ~150 KB/s | 1080p is enough |
| Grid · Turbo | 3 px | ~20 KB | ~240 KB/s | needs 4K |
| Grid · Extreme | 2 px | ~42 KB | ~500 KB/s | needs 4K |

**QR mode** (most compatible): a grid of 1-6 normal QR codes, ~6-130 KB/s.

Real speed depends on your phone: watch the live KB/s on the phone. Tips: press
**F11** for fullscreen, laptop brightness to maximum, hold the phone steady so
the screen fills the camera view (all 9 anchors visible), avoid reflections. If
nothing happens, step down one preset or switch to a QR preset.

Measured in a camera simulation (perspective, lens distortion, blur, noise,
rolling-shutter tears on every 2nd frame): Grid · Extreme rebuilt a 400 KB file
from 16 simulated 4K camera frames. Not yet measured on a real phone.

### If it's slow: read the phone's diagnostics

In grid mode the phone shows a live line such as
`Grid · 3840×2160 · 9/9 anchors · bands ok 92% · phone decodes 11.5 frames/s (70 ms) · white 205 · black 48 · clipped 0% · EV -1.0`.

| What you see | What it means | What to do |
| ------------ | ------------- | ---------- |
| fewer than 9/9 anchors | part of the grid is outside the view | move back / re-aim until all 9 small squares are visible |
| clipped above 2% | screen is over-exposed, white bleeds into black | the app lowers exposure automatically; also lower laptop brightness a bit if it stays high |
| bands ok below ~50% | cells are too small/blurry for this camera | move closer, hold steadier (rest your elbows), or pick a bigger-cell preset |
| bands ok high, speed still low | phone decodes fewer frames/s than the laptop shows | set laptop *Frames/s* to about the phone's "decodes … frames/s" |
| resolution 1920×1080 | the phone does not give apps 4K frames | Turbo/Extreme won't work; use Grid · Fast |

The laptop status bar shows how many frames/s it really displays.

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
| `tools/interop-test/` | Runs the Android decoder on the JVM against frames exported by the Windows sender, including a camera simulation (`CameraSim.kt`) |
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
