using System.Buffers.Binary;
using ZXing;
using ZXing.Common;
using ZXing.Common.ReedSolomon;
using ZXing.QrCode;
using ZXing.QrCode.Internal;

namespace QRShare;

/// <summary>Geometry and settings of one dense-grid frame. See PROTOCOL.md, part B.</summary>
public sealed record GridParams(uint TransferId, int PayloadLength, int Cols, int Rows, int Bpc, int Ec, int CwPerPacket, int Bands, int Ams)
{
    public int DataPerCw => 255 - Ec;
    public int BlockSize => GridFormat.BlockSizeFor(CwPerPacket, Ec);
}

/// <summary>
/// Dense grid mode: the screen is a grid of cells carrying 1 or 2 bits each, framed by
/// 9 small "anchor" QR codes that tell the receiver where the cells are. Data is cut
/// into horizontal bands; each band holds whole fountain packets, Reed-Solomon coded
/// and byte-interleaved, so glare or a rolling-shutter tear only costs single bands.
/// </summary>
public static class GridFormat
{
    public const int AnchorModules = 25;   // QR version 2
    public const int Quiet = 3;
    /// <summary>Anchor footprint in cells; each anchor module is ams x ams cells.</summary>
    public static int Footprint(int ams) => (AnchorModules + 2 * Quiet) * ams;
    public const int HeaderSize = 23;
    public const int CwLen = 255;
    static readonly byte[] LevelOfGray = { 0, 1, 3, 2 }; // 2-bit value -> brightness level (Gray code)

    public static int BlockSizeFor(int cwPerPacket, int ec) => cwPerPacket * (CwLen - ec) - 8;

    public static int MinCols(int ams) => 3 * Footprint(ams) + 8;
    public static int MinRows(int ams) => 3 * Footprint(ams) + 8;

    public static (int x, int y)[] AnchorOrigins(int cols, int rows, int ams)
    {
        int f = Footprint(ams);
        int[] xs = { 0, (cols - f) / 2, cols - f };
        int[] ys = { 0, (rows - f) / 2, rows - f };
        var r = new (int, int)[9];
        for (int yi = 0; yi < 3; yi++)
            for (int xi = 0; xi < 3; xi++) r[yi * 3 + xi] = (xs[xi], ys[yi]);
        return r;
    }

    public static bool[] AnchorMask(int cols, int rows, int ams)
    {
        var mask = new bool[cols * rows];
        int f = Footprint(ams);
        foreach (var (ax, ay) in AnchorOrigins(cols, rows, ams))
            for (int y = ay; y < ay + f; y++)
                for (int x = ax; x < ax + f; x++) mask[y * cols + x] = true;
        return mask;
    }

    /// <summary>Data cells of each band, row-major, anchors skipped.</summary>
    public static int[][] BandCells(int cols, int rows, int bands, bool[] mask)
    {
        var result = new int[bands][];
        for (int b = 0; b < bands; b++)
        {
            int r0 = b * rows / bands, r1 = (b + 1) * rows / bands;
            var list = new List<int>((r1 - r0) * cols);
            for (int y = r0; y < r1; y++)
                for (int x = 0; x < cols; x++)
                    if (!mask[y * cols + x]) list.Add(y * cols + x);
            result[b] = list.ToArray();
        }
        return result;
    }

    public static int PacketsInBand(int cellCount, int bpc, int cwPerPacket) => cellCount * bpc / 8 / CwLen / cwPerPacket;

    /// <summary>Picks the band count that fits the most packets (ties: more, smaller bands).</summary>
    public static int ChooseBands(int cols, int rows, int bpc, int cwPerPacket, int ams)
    {
        var mask = AnchorMask(cols, rows, ams);
        int best = 1, bestPackets = -1;
        for (int b = Math.Max(1, rows / 64); b <= Math.Max(1, rows / 12); b++)
        {
            int total = BandCells(cols, rows, b, mask).Sum(c => PacketsInBand(c.Length, bpc, cwPerPacket));
            if (total >= bestPackets) { best = b; bestPackets = total; }
        }
        return best;
    }

    public static byte[] Header(GridParams p, int anchorIndex)
    {
        var h = new byte[HeaderSize];
        h[0] = 0x51; h[1] = 0x47; h[2] = 1; h[3] = (byte)anchorIndex;
        BinaryPrimitives.WriteUInt32BigEndian(h.AsSpan(4), p.TransferId);
        BinaryPrimitives.WriteInt32BigEndian(h.AsSpan(8), p.PayloadLength);
        BinaryPrimitives.WriteUInt16BigEndian(h.AsSpan(12), (ushort)p.Cols);
        BinaryPrimitives.WriteUInt16BigEndian(h.AsSpan(14), (ushort)p.Rows);
        h[16] = (byte)p.Bpc; h[17] = (byte)p.Ec; h[18] = (byte)p.CwPerPacket; h[19] = (byte)p.Bands; h[20] = (byte)p.Ams;
        return h;
    }

    /// <summary>Fixed whitening stream for a band (balances dark/light cells for the receiver's threshold).</summary>
    public static byte[] Scramble(int band, int length)
    {
        var rng = new Protocol.XorShift(0x51470000u + (uint)band);
        var s = new byte[length];
        uint x = 0;
        for (int i = 0; i < length; i++)
        {
            if ((i & 3) == 0) x = rng.Next();
            s[i] = (byte)(x >> (24 - 8 * (i & 3)));
        }
        return s;
    }

    static readonly uint[] CrcTable = Enumerable.Range(0, 256).Select(n =>
    {
        uint c = (uint)n;
        for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        return c;
    }).ToArray();

    public static uint Crc32(ReadOnlySpan<byte> data)
    {
        uint c = 0xFFFFFFFFu;
        foreach (byte b in data) c = CrcTable[(c ^ b) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFFu;
    }

    /// <summary>Fills one band with packets and returns the brightness level of each of its cells.</summary>
    public static byte[] EncodeBand(GridParams p, int band, int cellCount, Func<(uint seed, byte[] block)> nextPacket)
    {
        int bytes = cellCount * p.Bpc / 8;
        int packets = PacketsInBand(cellCount, p.Bpc, p.CwPerPacket);
        int ncw = packets * p.CwPerPacket;
        var rs = new ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256);
        var stream = new byte[bytes];
        var buf = new int[CwLen];
        for (int pk = 0; pk < packets; pk++)
        {
            var (seed, block) = nextPacket();
            var pd = new byte[p.CwPerPacket * p.DataPerCw];
            BinaryPrimitives.WriteUInt32BigEndian(pd, seed);
            block.CopyTo(pd, 4);
            BinaryPrimitives.WriteUInt32BigEndian(pd.AsSpan(4 + block.Length), Crc32(pd.AsSpan(0, 4 + block.Length)));
            for (int c = 0; c < p.CwPerPacket; c++)
            {
                Array.Clear(buf);
                for (int j = 0; j < p.DataPerCw; j++) buf[j] = pd[c * p.DataPerCw + j];
                rs.encode(buf, p.Ec);
                int k = pk * p.CwPerPacket + c;
                for (int j = 0; j < CwLen; j++) stream[j * ncw + k] = (byte)buf[j];
            }
        }
        var scr = Scramble(band, bytes);
        for (int i = 0; i < bytes; i++) stream[i] ^= scr[i];

        var levels = new byte[cellCount];
        for (int cell = 0; cell < cellCount; cell++)
        {
            int v = 0;
            for (int b = 0; b < p.Bpc; b++)
            {
                int bit = cell * p.Bpc + b;
                int byteIdx = bit >> 3;
                int value = byteIdx < bytes ? (stream[byteIdx] >> (7 - (bit & 7))) & 1 : 0;
                v = (v << 1) | value;
            }
            // 1 bit: 1 = dark. 2 bits: Gray-coded level, 0 = dark ... 3 = light.
            levels[cell] = p.Bpc == 1 ? (byte)(v == 1 ? 0 : 1) : LevelOfGray[v];
        }
        return levels;
    }

    /// <summary>Renders a full frame as cell intensities (0 = black, 255 = white).</summary>
    static (GridParams key, byte[] cells, int[][] bands)? _template;

    public static byte[] RenderFrame(GridParams p, FountainEncoder enc, QRCodeWriter writer)
    {
        // Anchors and band layout only change with the geometry: build them once.
        var t = _template;
        if (t == null || t.Value.key != p)
        {
            var mask0 = AnchorMask(p.Cols, p.Rows, p.Ams);
            t = (p, RenderAnchors(p, writer), BandCells(p.Cols, p.Rows, p.Bands, mask0));
            _template = t;
        }
        var cells = (byte[])t.Value.cells.Clone();
        var bands = t.Value.bands;
        int maxLevel = (1 << p.Bpc) - 1;
        // Packets must be drawn from the encoder in order; encoding the bands can run in parallel.
        var packets = new List<(uint, byte[])>[bands.Length];
        for (int b = 0; b < bands.Length; b++)
        {
            int np = PacketsInBand(bands[b].Length, p.Bpc, p.CwPerPacket);
            packets[b] = new List<(uint, byte[])>(np);
            for (int i = 0; i < np; i++) packets[b].Add(enc.NextRaw());
        }
        Parallel.For(0, bands.Length, b =>
        {
            int next = 0;
            var levels = EncodeBand(p, b, bands[b].Length, () => packets[b][next++]);
            for (int i = 0; i < levels.Length; i++) cells[bands[b][i]] = (byte)(levels[i] * 255 / maxLevel);
        });
        return cells;
    }

    static byte[] RenderAnchors(GridParams p, QRCodeWriter writer)
    {
        var cells = new byte[p.Cols * p.Rows];
        Array.Fill(cells, (byte)255);
        var hints = new Dictionary<EncodeHintType, object>
        {
            [EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M,
            [EncodeHintType.MARGIN] = 0,
            [EncodeHintType.QR_VERSION] = 2,
        };
        var origins = AnchorOrigins(p.Cols, p.Rows, p.Ams);
        int ams = p.Ams, q = Quiet * ams;
        for (int a = 0; a < 9; a++)
        {
            BitMatrix m = writer.encode(Protocol.Base45Encode(Header(p, a)), BarcodeFormat.QR_CODE, 0, 0, hints);
            if (m.Width != AnchorModules) throw new InvalidOperationException($"anchor is {m.Width} modules");
            var (ax, ay) = origins[a];
            for (int y = 0; y < AnchorModules; y++)
                for (int x = 0; x < AnchorModules; x++)
                    if (m[x, y])
                        for (int dy = 0; dy < ams; dy++)
                            for (int dx = 0; dx < ams; dx++)
                                cells[(ay + q + y * ams + dy) * p.Cols + ax + q + x * ams + dx] = 0;
        }
        return cells;
    }
}
