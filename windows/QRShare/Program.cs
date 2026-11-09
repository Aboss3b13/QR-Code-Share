using System.IO.Compression;
using Microsoft.Win32;

namespace QRShare;

static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        // Headless helper used by the interop test: QRShare.exe --export-packets <file> <out> <count> <blockSize>
        if (args.Length == 5 && args[0] == "--export-packets")
            return ExportPackets(args[1], args[2], int.Parse(args[3]), int.Parse(args[4]));
        // QRShare.exe --bench <presetIndex>  (render speed of the sender, prints ms per frame)
        if (args.Length == 2 && args[0] == "--bench") return Bench(MainForm.Presets[int.Parse(args[1])]);
        // QRShare.exe --export-frames <file> <outDir> <frames> <presetIndex>  (renders 1920x1080 PNGs)
        if (args.Length == 5 && args[0] == "--export-frames")
            return ExportFrames(args[1], args[2], int.Parse(args[3]), MainForm.Presets[int.Parse(args[4])]);

        ApplicationConfiguration.Initialize();

        if (args.Length >= 1 && args[0] == "--register") { ContextMenu.Register(); MessageBox.Show("Context menu entry added.", "QR Share"); return 0; }
        if (args.Length >= 1 && args[0] == "--unregister") { ContextMenu.Unregister(); MessageBox.Show("Context menu entry removed.", "QR Share"); return 0; }

        string? path = args.Length >= 1 ? args[0] : null;
        if (path == null)
        {
            using var dlg = new OpenFileDialog { Title = "Choose a file to send via QR" };
            if (dlg.ShowDialog() != DialogResult.OK) return 0;
            path = dlg.FileName;
        }

        if (!ContextMenu.IsRegistered())
        {
            // Offer the Explorer integration once on first start.
            var answer = MessageBox.Show(
                "Add \"Send via QR Share\" to the Explorer right-click menu?\n(On Windows 11 it appears under \"Show more options\".)",
                "QR Share", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (answer == DialogResult.Yes) ContextMenu.Register();
            else ContextMenu.MarkDeclined();
        }

        try
        {
            var (name, data) = LoadInput(path);
            Application.Run(new MainForm(name, data));
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "QR Share", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        return 0;
    }

    /// <summary>Reads a file, or zips a folder.</summary>
    static (string name, byte[] data) LoadInput(string path)
    {
        if (Directory.Exists(path))
        {
            using var ms = new MemoryStream();
            using (var zip = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
            {
                foreach (var file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
                    zip.CreateEntryFromFile(file, Path.GetRelativePath(path, file).Replace('\\', '/'), CompressionLevel.SmallestSize);
            }
            return (Path.GetFileName(Path.TrimEndingDirectorySeparator(path)) + ".zip", ms.ToArray());
        }
        return (Path.GetFileName(path), File.ReadAllBytes(path));
    }

    static int ExportPackets(string file, string outPath, int count, int blockSize)
    {
        var payload = Protocol.BuildPayload(Path.GetFileName(file), File.ReadAllBytes(file));
        var enc = new FountainEncoder(payload, blockSize, (uint)Random.Shared.Next());
        using var w = new StreamWriter(outPath);
        for (int i = 0; i < count; i++) w.WriteLine(Protocol.Base45Encode(enc.NextPacket()));
        Console.WriteLine($"payload={payload.Length} blocks={enc.BlockCount}");
        return 0;
    }

    static int Bench(SpeedPreset p)
    {
        var payload = new byte[2_000_000]; Random.Shared.NextBytes(payload);
        var enc = new FountainEncoder(payload, p.BlockSize, 1);
        var writer = new ZXing.QrCode.QRCodeWriter();
        var sw = System.Diagnostics.Stopwatch.StartNew();
        int n = 40;
        for (int f = 0; f < n; f++)
        {
            var gp = MainForm.GridGeometry(p, enc, 1920, 1080)!;
            using var bmp = MainForm.ComposeGrid(GridFormat.RenderFrame(gp, enc, writer), gp.Cols, gp.Rows, p.CellPx, 1920, 1080);
        }
        File.WriteAllText("bench.txt", $"{p.Name}: {sw.Elapsed.TotalMilliseconds / n:0.0} ms/frame");
        return 0;
    }

    static int ExportFrames(string file, string outDir, int frames, SpeedPreset p)
    {
        var payload = Protocol.BuildPayload(Path.GetFileName(file), File.ReadAllBytes(file));
        var enc = new FountainEncoder(payload, p.BlockSize, (uint)Random.Shared.Next());
        var writer = new ZXing.QrCode.QRCodeWriter();
        var hints = new Dictionary<ZXing.EncodeHintType, object>
        {
            [ZXing.EncodeHintType.ERROR_CORRECTION] = ZXing.QrCode.Internal.ErrorCorrectionLevel.L,
            [ZXing.EncodeHintType.MARGIN] = 4,
        };
        Directory.CreateDirectory(outDir);
        for (int f = 0; f < frames; f++)
        {
            System.Drawing.Bitmap bmp;
            if (p.IsGrid)
            {
                var gp = MainForm.GridGeometry(p, enc, 1920, 1080)!;
                bmp = MainForm.ComposeGrid(GridFormat.RenderFrame(gp, enc, writer), gp.Cols, gp.Rows, p.CellPx, 1920, 1080);
                if (f == 0) Console.WriteLine($"blocks={enc.BlockCount} grid={gp.Cols}x{gp.Rows} bands={gp.Bands} blockSize={gp.BlockSize}");
            }
            else
            {
                var ms = new ZXing.Common.BitMatrix[p.Cols * p.Rows];
                for (int i = 0; i < ms.Length; i++)
                    ms[i] = writer.encode(Protocol.Base45Encode(enc.NextPacket()), ZXing.BarcodeFormat.QR_CODE, 0, 0, hints);
                bmp = MainForm.Compose(ms, p.Cols, p.Rows, 1920, 1080);
                if (f == 0) Console.WriteLine($"blocks={enc.BlockCount} modules={ms[0].Width - 8}");
            }
            using (bmp) bmp.Save(Path.Combine(outDir, $"frame{f:D4}.png"), System.Drawing.Imaging.ImageFormat.Png);
        }
        return 0;
    }
}

static class ContextMenu
{
    const string FileKey = @"Software\Classes\*\shell\QRShare";
    const string DirKey = @"Software\Classes\Directory\shell\QRShare";
    const string SettingsKey = @"Software\QRShare";

    public static bool IsRegistered()
    {
        using var k = Registry.CurrentUser.OpenSubKey(FileKey);
        using var s = Registry.CurrentUser.OpenSubKey(SettingsKey);
        return k != null || (s?.GetValue("Declined") as int? ?? 0) == 1;
    }

    public static void MarkDeclined()
    {
        using var s = Registry.CurrentUser.CreateSubKey(SettingsKey);
        s.SetValue("Declined", 1, RegistryValueKind.DWord);
    }

    public static void Register()
    {
        string exe = Environment.ProcessPath ?? Application.ExecutablePath;
        foreach (var key in new[] { FileKey, DirKey })
        {
            using var k = Registry.CurrentUser.CreateSubKey(key);
            k.SetValue("", "Send via QR Share");
            k.SetValue("Icon", $"\"{exe}\",0");
            using var cmd = k.CreateSubKey("command");
            cmd.SetValue("", $"\"{exe}\" \"%1\"");
        }
    }

    public static void Unregister()
    {
        Registry.CurrentUser.DeleteSubKeyTree(FileKey, false);
        Registry.CurrentUser.DeleteSubKeyTree(DirKey, false);
        MarkDeclined();
    }
}
