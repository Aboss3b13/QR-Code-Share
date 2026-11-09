# QR Share Transfer Protocol (QSTP) v2

A one-way, offline file-transfer protocol: the sender shows an endless animation
of QR codes, the receiver films it. No network, no back channel. Frames may be
missed, repeated or received out of order — a fountain code lets the receiver
rebuild the file from *any* sufficiently large set of frames.

## 1. Payload

The file is first wrapped into a single byte string, the **payload**
(all integers big-endian):

| Size      | Field                                              |
| --------- | -------------------------------------------------- |
| 1         | compression: `0` = none, `1` = zlib (RFC 1950)     |
| 2         | `n` = length of file name in bytes                 |
| n         | file name, UTF-8                                   |
| 8         | original file size in bytes                        |
| 32        | SHA-256 of the original (uncompressed) file        |
| rest      | file body (zlib-compressed when compression = 1)   |

The sender uses compression only when it makes the payload smaller.

## 2. Blocks

The payload (length `L`) is cut into `K = ceil(L / B)` blocks of `B` bytes
(the block size). The last block is zero-padded to `B` bytes.

## 3. Packets (one packet = one QR code)

Each QR code holds one binary packet, written as **Base45** text (RFC 9285).
Base45 only uses the 45 characters of the QR *alphanumeric* mode, which stores
5.5 bits per character, so a packet takes ~97% of the space it would in byte
mode, and every scanner (ZXing, ML Kit, phone camera apps) returns it as plain
text without charset problems.

The sender may show several QR codes per screen frame (e.g. a 3×2 grid); each
code is an independent packet.

Packet layout before Base45:

| Offset | Size | Field                                   |
| ------ | ---- | --------------------------------------- |
| 0      | 2    | magic `0x51 0x53` ("QS")                |
| 2      | 1    | version = `2`                           |
| 3      | 1    | flags (reserved, `0`)                   |
| 4      | 4    | transfer id (random per transfer)       |
| 8      | 4    | payload length `L`                      |
| 12     | 2    | block size `B`                          |
| 14     | 4    | seed `s` (uint32)                       |
| 18     | B    | data: XOR of the blocks selected by `s` |

## 4. Which blocks a seed selects

* `s < K` — **systematic** packet: exactly block `s`.
* `s >= K` — **repair** packet: a pseudo-random combination of blocks.

The pseudo-random generator is a 32-bit xorshift (all arithmetic mod 2³²):

```
state = (s * 0x9E3779B1) XOR 0x85EBCA6B
if state == 0: state = 0x6D2B79F5
next():  x = state
         x ^= x << 13
         x ^= x >> 17      (logical shift)
         x ^= x << 5
         state = x
         return x
```

Degree `d`: `r = next() mod 1000`, then pick the first row where `r < cumulative`:

| degree | 1  | 2   | 3   | 4   | 8   | 16  | 32  | 64   |
| ------ | -- | --- | --- | --- | --- | --- | --- | ---- |
| cum.   | 10 | 110 | 210 | 310 | 500 | 750 | 900 | 1000 |

The distribution is denser than a classic LT soliton (mean degree ≈ 17.6) because
the receiver finishes with Gaussian elimination, where coverage matters more
than peelability.

`d = min(d, K)`. Then block indices are drawn with `next() mod K`, skipping
indices that were already chosen, until `d` distinct indices are selected.

## 5. Sender schedule

1. Frames `0 … K-1`: systematic packets `s = 0 … K-1`.
2. Afterwards forever: every 5th frame repeats a systematic packet (cycling),
   the other frames are fresh repair packets `s = K, K+1, K+2, …`.

## 6. Receiver

Peeling (belief-propagation) decoder: XOR every already-known block out of a
new packet; a packet with one unknown block left reveals that block, which is
then XORed out of all stored packets, and so on. When peeling stalls but at
least as many stored packets as unknown blocks exist, the remaining system is
solved by Gaussian elimination over GF(2). When all `K` blocks are known
the payload is truncated to `L`, parsed, decompressed and verified against the
SHA-256. Packets with an unknown transfer id start a new transfer.

---

# Part B: Dense grid mode

QR codes waste space on finder patterns, quiet zones and format information.
Grid mode turns the whole screen into a grid of square **cells**, each one bit
(dark = 1, light = 0), and only uses QR codes as small **anchors** that tell the
receiver the geometry and where the grid is in the camera image.

## B1. Layout

* The sender picks a cell size in screen pixels (2 to 6) and fills its window
  with `cols x rows` cells.
* 9 anchors sit in a 3x3 arrangement (corners, edge centres, centre). Each is a
  QR code version 2 (25 modules, EC level M) with a 3-module light quiet zone;
  one module is `ams x ams` cells, so an anchor covers `31*ams` cells square.
  Anchor x positions: `0, (cols-F)/2, cols-F`; y the same with `rows` (F = 31*ams).
* All other cells are **data cells**. The rows are split into `bands`
  horizontal bands (band `b` = rows `b*rows/bands ... (b+1)*rows/bands - 1`);
  the data cells of a band are numbered row by row, left to right.

## B2. Anchor content (23 bytes, Base45 text in the QR code)

| Offset | Size | Field |
| ------ | ---- | ----- |
| 0 | 2 | magic `QG` |
| 2 | 1 | grid format version = 1 |
| 3 | 1 | anchor index 0-8 (row-major in the 3x3 arrangement) |
| 4 | 4 | transfer id |
| 8 | 4 | payload length `L` |
| 12 | 2 | cols |
| 14 | 2 | rows |
| 16 | 1 | bits per cell (1) |
| 17 | 1 | Reed-Solomon parity bytes per codeword `ec` |
| 18 | 1 | codewords per packet `c` |
| 19 | 1 | bands |
| 20 | 1 | anchor module size in cells `ams` |
| 21 | 2 | reserved, 0 |

## B3. Band contents

A band of `n` data cells carries `bytes = n/8` bytes, room for
`P = floor(bytes / 255 / c)` packets.

1. **Packet** (`c * (255-ec)` bytes): seed (uint32) · block data
   (`B = c*(255-ec) - 8` bytes, the fountain block selected by the seed, same
   rules as part A) · CRC-32 (IEEE) of seed + block.
2. The packet is cut into `c` chunks of `255-ec` bytes; each becomes one
   **Reed-Solomon codeword** over GF(256) (QR code field, polynomial 0x11D,
   generator base 0) with `ec` parity bytes.
3. **Interleaving:** the band's `N = P*c` codewords are written as
   `stream[j*N + k] = codeword k, byte j`, so a damaged area spreads over all
   codewords of the band. Unused bytes are 0.
4. **Whitening:** the stream is XORed with the xorshift generator of part A
   seeded with `0x51470000 + band` (4 bytes per `next()`, most significant
   first). This keeps dark and light cells balanced for the receiver's
   adaptive threshold.
5. Bits are placed into the band's data cells, most significant bit first.

Every band is self-contained: a rolling-shutter tear (camera catching two
screen frames) or a reflection only costs the affected bands.

## B4. Receiver

1. Find anchors (ML Kit on a downscaled frame), then decode each anchor with
   ZXing in the full-resolution frame to get its 3 finder centres and its
   alignment centre: up to 36 precise grid-to-camera point pairs.
2. Fit a homography (least squares), then a 3rd-degree polynomial on the
   residuals to absorb lens distortion.
3. Sample every data cell (5 points), compensate blur between neighbouring
   cells, threshold against the local mean (17x17 cells).
4. Per band: whitening off, de-interleave, Reed-Solomon decode, CRC check; good
   packets go into the fountain decoder of part A.
5. Track the anchors between frames so the expensive search only runs again
   when tracking is lost.
