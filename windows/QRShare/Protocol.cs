using System.Buffers.Binary;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace QRShare;

/// <summary>QR Share Transfer Protocol v1 — see PROTOCOL.md.</summary>
public static class Protocol
{
    public const int HeaderSize = 18;
    public const byte Version = 1;
    static readonly int[] Degrees = { 1, 2, 3, 4, 8, 16, 32, 64 };
    static readonly int[] Cumulative = { 10, 110, 210, 310, 500, 750, 900, 1000 };

    public static int BlockCount(int payloadLength, int blockSize) => (payloadLength + blockSize - 1) / blockSize;

    public static int[] IndicesForSeed(uint seed, int k)
    {
        if (seed < (uint)k) return new[] { (int)seed };
        var rng = new XorShift(seed);
        uint r = rng.NextMod(1000);
        int d = Degrees[^1];
        for (int i = 0; i < Cumulative.Length; i++)
            if (r < Cumulative[i]) { d = Degrees[i]; break; }
        d = Math.Min(d, k);
        var chosen = new List<int>(d);
        var set = new HashSet<int>();
        while (chosen.Count < d)
        {
            int idx = (int)rng.NextMod((uint)k);
            if (set.Add(idx)) chosen.Add(idx);
        }
        return chosen.ToArray();
    }

    public sealed class XorShift
    {
        uint _state;
        public XorShift(uint seed)
        {
            unchecked { _state = (seed * 0x9E3779B1u) ^ 0x85EBCA6Bu; }
            if (_state == 0) _state = 0x6D2B79F5u;
        }
        public uint Next()
        {
            uint x = _state;
            x ^= x << 13;
            x ^= x >> 17;
            x ^= x << 5;
            _state = x;
            return x;
        }
        public uint NextMod(uint n) => Next() % n;
    }

    /// <summary>Builds the payload: header + (optionally zlib-compressed) file body.</summary>
    public static byte[] BuildPayload(string fileName, byte[] data)
    {
        byte[] hash = SHA256.HashData(data);
        byte[] compressed;
        using (var ms = new MemoryStream())
        {
            using (var z = new ZLibStream(ms, CompressionLevel.SmallestSize, leaveOpen: true))
                z.Write(data);
            compressed = ms.ToArray();
        }
        bool useCompression = compressed.Length < data.Length;
        byte[] body = useCompression ? compressed : data;
        byte[] name = Encoding.UTF8.GetBytes(fileName);
        if (name.Length > 1000) name = name[..1000];

        var payload = new byte[1 + 2 + name.Length + 8 + 32 + body.Length];
        int o = 0;
        payload[o++] = (byte)(useCompression ? 1 : 0);
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(o), (ushort)name.Length); o += 2;
        name.CopyTo(payload, o); o += name.Length;
        BinaryPrimitives.WriteInt64BigEndian(payload.AsSpan(o), data.LongLength); o += 8;
        hash.CopyTo(payload, o); o += 32;
        body.CopyTo(payload, o);
        return payload;
    }
}

/// <summary>Produces the endless stream of packets for one transfer.</summary>
public sealed class FountainEncoder
{
    readonly byte[][] _blocks;
    readonly uint _transferId;
    readonly int _payloadLength;
    long _frame;
    uint _repairSeed;

    public int BlockSize { get; }
    public int BlockCount => _blocks.Length;
    public int PayloadLength => _payloadLength;
    public long FramesSent => _frame;

    public FountainEncoder(byte[] payload, int blockSize, uint transferId)
    {
        BlockSize = blockSize;
        _transferId = transferId;
        _payloadLength = payload.Length;
        int k = Protocol.BlockCount(payload.Length, blockSize);
        _blocks = new byte[k][];
        for (int i = 0; i < k; i++)
        {
            var b = new byte[blockSize];
            int start = i * blockSize;
            Array.Copy(payload, start, b, 0, Math.Min(blockSize, payload.Length - start));
            _blocks[i] = b;
        }
        _repairSeed = (uint)k;
    }

    public byte[] NextPacket()
    {
        int k = _blocks.Length;
        uint seed;
        if (_frame < k) seed = (uint)_frame;
        else
        {
            long m = _frame - k;
            seed = m % 5 == 0 ? (uint)((m / 5) % k) : _repairSeed++;
        }
        _frame++;
        return BuildPacket(seed);
    }

    public byte[] BuildPacket(uint seed)
    {
        var packet = new byte[Protocol.HeaderSize + BlockSize];
        packet[0] = 0x51; packet[1] = 0x53; packet[2] = Protocol.Version; packet[3] = 0;
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(4), _transferId);
        BinaryPrimitives.WriteInt32BigEndian(packet.AsSpan(8), _payloadLength);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(12), (ushort)BlockSize);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(14), seed);
        var data = packet.AsSpan(Protocol.HeaderSize);
        foreach (int idx in Protocol.IndicesForSeed(seed, _blocks.Length))
        {
            var b = _blocks[idx];
            for (int i = 0; i < b.Length; i++) data[i] ^= b[i];
        }
        return packet;
    }
}
