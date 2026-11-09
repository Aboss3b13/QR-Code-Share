using System.Diagnostics;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using ZXing;
using ZXing.Common;
using ZXing.QrCode;
using ZXing.QrCode.Internal;

namespace QRShare;

public sealed record SpeedPreset(string Name, int Cols, int Rows, int BlockSize, int Fps)
{
    public override string ToString() => Name;
}

public sealed class MainForm : Form
{
    internal static readonly SpeedPreset[] Presets =
    {
        new("Reliable — 1 code", 1, 1, 600, 10),
        new("Fast — 4 codes", 2, 2, 900, 15),
        new("Maximum — 6 codes", 3, 2, 1100, 20),
        new("Extreme — 9 codes", 3, 3, 1000, 20),
    };

    readonly byte[] _payload;
    readonly long _originalSize;
    volatile FountainEncoder _encoder;

    readonly QrView _view = new() { Dock = DockStyle.Fill };
    readonly ComboBox _preset = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 170 };
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

        _preset.SelectedIndex = 1;
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
        _cols.Value = p.Cols; _rows.Value = p.Rows; _fps.Value = p.Fps;
        _block.Value = p.BlockSize; // fires SettingsChanged(true) if it differs
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
            var matrices = new BitMatrix[cols * rows];
            for (int i = 0; i < matrices.Length; i++)
                matrices[i] = writer.encode(Protocol.Base45Encode(enc.NextPacket()), BarcodeFormat.QR_CODE, 0, 0, hints);
            var bmp = Compose(matrices, cols, rows, w, h);
            _qrModules = matrices[0].Width - 8;

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

    void UpdateStatus()
    {
        var enc = _encoder;
        int perFrame = _cfgCols * _cfgRows;
        double nominal = perFrame * enc.BlockSize * (double)_cfgFps;
        double firstPass = enc.BlockCount / (double)(perFrame * _cfgFps);
        _status.Text = $"{FormatSize(_originalSize)} → {enc.BlockCount} blocks  ·  {perFrame} codes × {enc.BlockSize} B × {_cfgFps} fps = {FormatSize((long)nominal)}/s max  ·  " +
                       $"QR {_qrModules}×{_qrModules}  ·  best case ≈ {firstPass:0}s  ·  frame {_framesShown}  ·  Space = pause, F11 = fullscreen";
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
