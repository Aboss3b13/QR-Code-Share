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
        using var fs = File.Create(outPath);
        using var w = new BinaryWriter(fs);
        for (int i = 0; i < count; i++)
        {
            var p = enc.NextPacket();
            w.Write(p.Length);
            w.Write(p);
        }
        Console.WriteLine($"payload={payload.Length} blocks={enc.BlockCount}");
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
