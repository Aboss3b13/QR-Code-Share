using System.Diagnostics;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using ZXing;
using ZXing.Common;
using ZXing.QrCode;
using ZXing.QrCode.Internal;

namespace QRShare;

/// <summary>A sender configuration. CellPx &gt; 0 selects the dense grid mode, otherwise QR codes.</summary>
public sealed record SpeedPreset(string Name, int Cols, int Rows, int BlockSize, int Fps, int CellPx = 0, int Bpc = 1, int Ec = 32, int Ams = 1)
{
    public const int GridCwPerPacket = 2;
    public bool IsGrid => CellPx > 0;
    public static SpeedPreset Grid(string name, int cellPx, int ams, int ec, int fps) =>
        new(name, 0, 0, GridFormat.BlockSizeFor(GridCwPerPacket, ec), fps, cellPx, 1, ec, ams);
    public override string ToString() => Name;
}

public sealed class MainForm : Form
{
    internal static readonly SpeedPreset[] Presets =
    {
        new("QR · Reliable (1 code)", 1, 1, 600, 10),
        new("QR · Fast (4 codes)", 2, 2, 900, 15),
        new("QR · Maximum (6 codes)", 3, 2, 1100, 20),
        SpeedPreset.Grid("Grid · Safe (6 px cells)", 6, 1, 48, 10),
        SpeedPreset.Grid("Grid · Fast (4 px cells)", 4, 1, 32, 12),
        SpeedPreset.Grid("Grid · Turbo (3 px cells, 4K camera)", 3, 2, 32, 12),
        SpeedPreset.Grid("Grid · Extreme (2 px cells, 4K camera)", 2, 3, 32, 12),
    };
    internal const int DefaultPreset = 4;

    readonly byte[] _payload;
    readonly long _originalSize;
    volatile FountainEncoder _encoder;

    readonly QrView _view = new() { Dock = DockStyle.Fill };
    readonly ComboBox _preset = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 250 };
    readonly NumericUpDown _cols = new() { Minimum = 1, Maximum = 6, Value = 2, Width = 40 };
    readonly NumericUpDown _rows = new() { Minimum = 1, Maximum = 4, Value = 2, Width = 40 };
    readonly NumericUpDown _fps = new() { Minimum = 1, Maximum = 30, Value = 15, Width = 50 };
    readonly NumericUpDown _block = new() { Minimum = 100, Maximum = 2800, Increment = 100, Value = 900, Width = 65 };
    readonly Button _pause = new() { Text = "Pause", AutoSize = true };
    readonly Button _full = new() { Text = "Fullscreen (F11)", AutoSize = true };
    readonly Label _status = new() { Dock = DockStyle.Bottom, Height = 28, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(8, 0, 0, 0) };
    readonly FlowLayoutPanel _toolbar = new() { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(6), WrapContents = true };

    // Snapshot of the settings, read by the render thread.
    volatile int _cfgCols = 2, _cfgRows = 2, _cfgFps = 15;
    volatile SpeedPreset _cfgPreset = Presets[DefaultPreset];
    volatile int _frameBytes;
    volatile bool _paused, _running = true;
    volatile int _viewW, _viewH;
    long _framesShown;
    int _qrModules;
    readonly Stopwatch _clock = Stopwatch.StartNew();
    readonly Thread _renderThread;

    [DllImport("winmm.dll")] static extern uint timeBeginPeriod(uint ms);
    [DllImport("winmm.dll")] static extern uint timeEndPeriod(uint ms);

    public MainForm(string fileName, byte[] data)
    {
        _originalSize = data.LongLength;
        _payload = Protocol.BuildPayload(fileName, data);
        _encoder = NewEncoder();

        Text = $"QR Share — {fileName}";
        Width = 1280; Height = 800;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.White;
        KeyPreview = true;
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }

        _preset.Items.AddRange(Presets);
        AddLabel(fileName, bold: true);
        AddLabel("Speed"); _toolbar.Controls.Add(_preset);
        AddLabel("Grid"); _toolbar.Controls.Add(_cols); AddLabel("×", 0); _toolbar.Controls.Add(_rows);
        AddLabel("Frames/s"); _toolbar.Controls.Add(_fps);
        AddLabel("Bytes/code"); _toolbar.Controls.Add(_block);
        _toolbar.Controls.Add(_pause);
        _toolbar.Controls.Add(_full);

        Controls.Add(_view);
        Controls.Add(_toolbar);
        Controls.Add(_status);

        _preset.SelectedIndexChanged += (_, _) => ApplyPreset((SpeedPreset)_preset.SelectedItem!);
        _cols.ValueChanged += (_, _) => SettingsChanged(false);
        _rows.ValueChanged += (_, _) => SettingsChanged(false);
        _fps.ValueChanged += (_, _) => SettingsChanged(false);
        _block.ValueChanged += (_, _) => SettingsChanged(true);
        _pause.Click += (_, _) => TogglePause();
        _full.Click += (_, _) => ToggleFullscreen();
        _view.Resize += (_, _) => { _viewW = _view.ClientSize.Width; _viewH = _view.ClientSize.Height; };
        KeyDown += OnKey;

        _preset.SelectedIndex = DefaultPreset;
        _viewW = _view.ClientSize.Width; _viewH = _view.ClientSize.Height;

        var ui = new System.Windows.Forms.Timer { Interval = 250 };
        ui.Tick += (_, _) => UpdateStatus();
        ui.Start();

        timeBeginPeriod(1);
        _renderThread = new Thread(RenderLoop) { IsBackground = true, Name = "QR render", Priority = ThreadPriority.AboveNormal };
        _renderThread.Start();
    }

    void AddLabel(string text, int leftMargin = 10, bool bold = false) =>
        _toolbar.Controls.Add(new Label
        {
            Text = text, AutoSize = true, Margin = new Padding(leftMargin, 8, 3, 3),
            Font = bold ? new Font(Font, FontStyle.Bold) : Font,
        });

    void ApplyPreset(SpeedPreset p)
    {
        _cfgPreset = p;
        _cols.Enabled = _rows.Enabled = _block.Enabled = !p.IsGrid;
        if (!p.IsGrid) { _cols.Value = p.Cols; _rows.Value = p.Rows; }
        _fps.Value = p.Fps;
        bool changed = _block.Value != p.BlockSize;
        _block.Value = p.BlockSize; // fires SettingsChanged(true) if it differs
        if (!changed) { _encoder = NewEncoder(); _clock.Restart(); }
        SettingsChanged(false);
    }

    void SettingsChanged(bool newEncoder)
    {
        _cfgCols = (int)_cols.Value; _cfgRows = (int)_rows.Value; _cfgFps = (int)_fps.Value;
        if (newEncoder) { _encoder = NewEncoder(); _clock.Restart(); }
    }

    FountainEncoder NewEncoder() => new(_payload, (int)_block.Value, (uint)Random.Shared.NextInt64(1, uint.MaxValue));

    void OnKey(object? sender, KeyEventArgs e)
    {
        if (e.KeyCode == Keys.F11) ToggleFullscreen();
        else if (e.KeyCode == Keys.Escape && FormBorderStyle == FormBorderStyle.None) ToggleFullscreen();
        else if (e.KeyCode == Keys.Space) TogglePause();
        else return;
        e.Handled = true;
        e.SuppressKeyPress = true;
    }

    void TogglePause()
    {
        _paused = !_paused;
        _pause.Text = _paused ? "Resume" : "Pause";
    }

    FormWindowState _prevState;
    void ToggleFullscreen()
    {
        if (FormBorderStyle != FormBorderStyle.None)
        {
            _prevState = WindowState;
            FormBorderStyle = FormBorderStyle.None;
            WindowState = FormWindowState.Normal;
            WindowState = FormWindowState.Maximized;
            _toolbar.Visible = false;
            _status.Visible = false;
        }
        else
        {
            FormBorderStyle = FormBorderStyle.Sizable;
            WindowState = _prevState;
            _toolbar.Visible = true;
            _status.Visible = true;
        }
    }

    /// <summary>Renders frames ahead of time on a background thread and presents them on a steady clock.</summary>
    void RenderLoop()
    {
        var writer = new QRCodeWriter();
        var hints = new Dictionary<EncodeHintType, object>
        {
            [EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L,
            [EncodeHintType.MARGIN] = 4,
        };
        var timer = Stopwatch.StartNew();
        double next = 0;
        while (_running)
        {
            if (_paused || _viewW < 50 || _viewH < 50) { Thread.Sleep(30); next = timer.Elapsed.TotalMilliseconds; continue; }

            int cols = _cfgCols, rows = _cfgRows, w = _viewW, h = _viewH;
            var enc = _encoder;
            var preset = _cfgPreset;
            Bitmap bmp;
            if (preset.IsGrid && enc.BlockSize == preset.BlockSize)
            {
                var p = GridGeometry(preset, enc, w, h);
                if (p == null) { Thread.Sleep(50); continue; }
                bmp = ComposeGrid(GridFormat.RenderFrame(p, enc, writer), p.Cols, p.Rows, preset.CellPx, w, h);
                _frameBytes = GridFormat.BandCells(p.Cols, p.Rows, p.Bands, GridFormat.AnchorMask(p.Cols, p.Rows, p.Ams))
                    .Sum(c => GridFormat.PacketsInBand(c.Length, p.Bpc, p.CwPerPacket)) * p.BlockSize;
                _qrModules = 0;
            }
            else
            {
                var matrices = new BitMatrix[cols * rows];
                for (int i = 0; i < matrices.Length; i++)
                    matrices[i] = writer.encode(Protocol.Base45Encode(enc.NextPacket()), BarcodeFormat.QR_CODE, 0, 0, hints);
                bmp = Compose(matrices, cols, rows, w, h);
                _qrModules = matrices[0].Width - 8;
                _frameBytes = matrices.Length * enc.BlockSize;
            }

            double interval = 1000.0 / Math.Max(1, _cfgFps);
            double now = timer.Elapsed.TotalMilliseconds;
            if (next < now - interval) next = now; // fell behind: resync instead of bursting
            double wait = next - now;
            if (wait > 2) Thread.Sleep((int)(wait - 1.5));
            while (timer.Elapsed.TotalMilliseconds < next) Thread.SpinWait(50);
            next += interval;

            try { _view.Invoke(() => { _view.Present(bmp); _framesShown++; }); }
            catch (ObjectDisposedException) { return; }
            catch (InvalidOperationException) { return; }
        }
    }

    /// <summary>Grid geometry for a screen area, or null if the area is too small.</summary>
    internal static GridParams? GridGeometry(SpeedPreset preset, FountainEncoder enc, int w, int h)
    {
        int cols = w / preset.CellPx, rows = h / preset.CellPx;
        if (cols < GridFormat.MinCols(preset.Ams) || rows < GridFormat.MinRows(preset.Ams)) return null;
        int bands = GridFormat.ChooseBands(cols, rows, preset.Bpc, SpeedPreset.GridCwPerPacket, preset.Ams);
        return new GridParams(enc.TransferId, enc.PayloadLength, cols, rows, preset.Bpc, preset.Ec, SpeedPreset.GridCwPerPacket, bands, preset.Ams);
    }

    /// <summary>Draws grid cells (intensity per cell) as cellPx-sized squares, centred.</summary>
    internal static unsafe Bitmap ComposeGrid(byte[] cells, int cols, int rows, int cellPx, int w, int h)
    {
        var bmp = new Bitmap(w, h, PixelFormat.Format32bppRgb);
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
        try
        {
            byte* basePtr = (byte*)data.Scan0;
            for (int y = 0; y < h; y++) new Span<uint>(basePtr + y * data.Stride, w).Fill(0xFFFFFFFFu);
            int ox = (w - cols * cellPx) / 2, oy = (h - rows * cellPx) / 2;
            nint scan0 = data.Scan0; int stride = data.Stride;
            Parallel.For(0, rows, r =>
            {
                byte* bp = (byte*)scan0;
                for (int dy = 0; dy < cellPx; dy++)
                {
                    uint* row = (uint*)(bp + (oy + r * cellPx + dy) * stride) + ox;
                    for (int c = 0; c < cols; c++)
                    {
                        uint v = cells[r * cols + c];
                        uint px = 0xFF000000u | (v << 16) | (v << 8) | v;
                        for (int dx = 0; dx < cellPx; dx++) row[c * cellPx + dx] = px;
                    }
                }
            });
        }
        finally { bmp.UnlockBits(data); }
        return bmp;
    }

    /// <summary>Lays the QR codes out in a grid, each drawn with whole-pixel modules.</summary>
    internal static unsafe Bitmap Compose(BitMatrix[] matrices, int cols, int rows, int w, int h)
    {
        var bmp = new Bitmap(w, h, PixelFormat.Format32bppRgb);
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
        try
        {
            byte* basePtr = (byte*)data.Scan0;
            for (int y = 0; y < h; y++) new Span<uint>(basePtr + y * data.Stride, w).Fill(0xFFFFFFFFu);

            int n = matrices[0].Width;
            int cellW = w / cols, cellH = h / rows;
            int scale = Math.Max(1, Math.Min(cellW, cellH) / n);
            int size = n * scale;
            for (int c = 0; c < matrices.Length; c++)
            {
                var m = matrices[c];
                int ox = (c % cols) * cellW + (cellW - size) / 2;
                int oy = (c / cols) * cellH + (cellH - size) / 2;
                for (int my = 0; my < n; my++)
                {
                    for (int mx = 0; mx < n; mx++)
                    {
                        if (!m[mx, my]) continue;
                        for (int dy = 0; dy < scale; dy++)
                        {
                            int py = oy + my * scale + dy;
                            if ((uint)py >= (uint)h) continue;
                            uint* row = (uint*)(basePtr + py * data.Stride);
                            int px = ox + mx * scale;
                            int len = Math.Min(scale, w - px);
                            if (px >= 0 && len > 0) new Span<uint>(row + px, len).Fill(0xFF000000u);
                        }
                    }
                }
            }
        }
        finally { bmp.UnlockBits(data); }
        return bmp;
    }

    long _lastFrames; double _lastStatusSec, _achievedFps;
    void UpdateStatus()
    {
        double nowSec = _clock.Elapsed.TotalSeconds + 0; // clock restarts on encoder change; guard below
        double dt = nowSec - _lastStatusSec;
        if (dt >= 1 || dt < 0)
        {
            if (dt > 0) _achievedFps = (_framesShown - _lastFrames) / dt;
            _lastFrames = _framesShown; _lastStatusSec = nowSec;
        }
        var enc = _encoder;
        double nominal = _frameBytes * (double)_cfgFps;
        double firstPass = nominal > 0 ? enc.BlockCount * (double)enc.BlockSize / nominal : 0;
        string what = _cfgPreset.IsGrid ? $"grid {_cfgPreset.CellPx}px cells" : $"{_cfgCols * _cfgRows} × QR {_qrModules}×{_qrModules}";
        _status.Text = $"{FormatSize(_originalSize)} → {enc.BlockCount} blocks  ·  {what}  ·  {FormatSize(_frameBytes)}/frame × {_cfgFps} fps = {FormatSize((long)nominal)}/s max  ·  " +
                       $"best case ≈ {firstPass:0}s  ·  showing {_achievedFps:0.0} frames/s  ·  Space = pause, F11 = fullscreen";
    }

    static string FormatSize(long b) => b switch
    {
        < 1024 => $"{b} B",
        < 1024 * 1024 => $"{b / 1024.0:0.0} KB",
        _ => $"{b / 1024.0 / 1024.0:0.00} MB",
    };

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _running = false;
        timeEndPeriod(1);
        base.OnFormClosing(e);
    }
}

/// <summary>Shows pre-rendered frames 1:1, no scaling.</summary>
sealed class QrView : Control
{
    Bitmap? _bmp;

    public QrView()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.Opaque, true);
        BackColor = Color.White;
    }

    public void Present(Bitmap bmp)
    {
        var old = _bmp;
        _bmp = bmp;
        Invalidate();
        Update();
        old?.Dispose();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        if (_bmp == null || _bmp.Width != ClientSize.Width || _bmp.Height != ClientSize.Height) e.Graphics.Clear(Color.White);
        if (_bmp != null) e.Graphics.DrawImageUnscaled(_bmp, 0, 0);
    }
}
