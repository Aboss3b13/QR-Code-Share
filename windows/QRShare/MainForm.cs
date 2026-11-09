using System.Drawing.Imaging;
using System.Text;
using ZXing;
using ZXing.Common;
using ZXing.QrCode;
using ZXing.QrCode.Internal;

namespace QRShare;

public sealed class MainForm : Form
{
    readonly string _fileName;
    readonly byte[] _payload;
    readonly long _originalSize;
    FountainEncoder _encoder;

    readonly QrView _view = new() { Dock = DockStyle.Fill };
    readonly System.Windows.Forms.Timer _timer = new();
    readonly NumericUpDown _fps = new() { Minimum = 1, Maximum = 30, Value = 12, Width = 55 };
    readonly NumericUpDown _block = new() { Minimum = 100, Maximum = 2000, Increment = 100, Value = 800, Width = 70 };
    readonly Button _pause = new() { Text = "Pause", AutoSize = true };
    readonly Button _full = new() { Text = "Fullscreen (F11)", AutoSize = true };
    readonly Label _status = new() { Dock = DockStyle.Bottom, Height = 28, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(8, 0, 0, 0) };
    readonly FlowLayoutPanel _toolbar = new() { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(6), WrapContents = true };
    readonly QRCodeWriter _writer = new();
    readonly Dictionary<EncodeHintType, object> _hints = new()
    {
        [EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L,
        [EncodeHintType.MARGIN] = 4,
    };
    readonly System.Diagnostics.Stopwatch _clock = System.Diagnostics.Stopwatch.StartNew();

    public MainForm(string fileName, byte[] data)
    {
        _fileName = fileName;
        _originalSize = data.LongLength;
        _payload = Protocol.BuildPayload(fileName, data);
        _encoder = NewEncoder();

        Text = $"QR Share — {fileName}";
        Width = 900; Height = 900;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.White;
        KeyPreview = true;
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }

        _toolbar.Controls.Add(new Label { Text = fileName, AutoSize = true, Font = new Font(Font, FontStyle.Bold), Margin = new Padding(3, 8, 12, 3) });
        _toolbar.Controls.Add(new Label { Text = "Frames/s", AutoSize = true, Margin = new Padding(3, 8, 3, 3) });
        _toolbar.Controls.Add(_fps);
        _toolbar.Controls.Add(new Label { Text = "Bytes/QR", AutoSize = true, Margin = new Padding(12, 8, 3, 3) });
        _toolbar.Controls.Add(_block);
        _toolbar.Controls.Add(_pause);
        _toolbar.Controls.Add(_full);

        Controls.Add(_view);
        Controls.Add(_toolbar);
        Controls.Add(_status);

        _fps.ValueChanged += (_, _) => _timer.Interval = 1000 / (int)_fps.Value;
        _block.ValueChanged += (_, _) => { _encoder = NewEncoder(); _clock.Restart(); };
        _pause.Click += (_, _) => TogglePause();
        _full.Click += (_, _) => ToggleFullscreen();
        KeyDown += OnKey;

        _timer.Interval = 1000 / (int)_fps.Value;
        _timer.Tick += (_, _) => ShowNextFrame();
        _timer.Start();
        ShowNextFrame();
    }

    FountainEncoder NewEncoder() => new(_payload, (int)_block.Value, (uint)Random.Shared.NextInt64(1, uint.MaxValue));

    void OnKey(object? sender, KeyEventArgs e)
    {
        if (e.KeyCode == Keys.F11) ToggleFullscreen();
        else if (e.KeyCode == Keys.Escape && FormBorderStyle == FormBorderStyle.None) ToggleFullscreen();
        else if (e.KeyCode == Keys.Space) TogglePause();
        else return;
        e.Handled = true;
    }

    void TogglePause()
    {
        _timer.Enabled = !_timer.Enabled;
        _pause.Text = _timer.Enabled ? "Pause" : "Resume";
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
        }
        else
        {
            FormBorderStyle = FormBorderStyle.Sizable;
            WindowState = _prevState;
            _toolbar.Visible = true;
        }
    }

    void ShowNextFrame()
    {
        byte[] packet = _encoder.NextPacket();
        // Latin-1 maps every byte 1:1 to a char, so ZXing emits a pure QR byte-mode segment.
        string contents = Encoding.Latin1.GetString(packet);
        BitMatrix m = _writer.encode(contents, BarcodeFormat.QR_CODE, 0, 0, _hints);
        _view.SetMatrix(m);

        int k = _encoder.BlockCount;
        double secs = Math.Max(_clock.Elapsed.TotalSeconds, 0.001);
        double minSeconds = k / (double)_fps.Value;
        _status.Text = $"{FormatSize(_originalSize)} → {k} blocks of {_encoder.BlockSize} B  ·  frame {_encoder.FramesSent}  ·  " +
                       $"QR {m.Width - 8}×{m.Width - 8} modules  ·  first full pass ≈ {minSeconds:0}s  ·  running {secs:0}s  ·  Space = pause, F11 = fullscreen";
    }

    static string FormatSize(long b) => b switch
    {
        < 1024 => $"{b} B",
        < 1024 * 1024 => $"{b / 1024.0:0.0} KB",
        _ => $"{b / 1024.0 / 1024.0:0.00} MB",
    };

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        _timer.Stop();
        base.OnFormClosed(e);
    }
}

/// <summary>Draws a QR BitMatrix as crisp, integer-scaled square pixels.</summary>
sealed class QrView : Control
{
    Bitmap? _bmp;

    public QrView()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        BackColor = Color.White;
    }

    public unsafe void SetMatrix(BitMatrix m)
    {
        if (_bmp == null || _bmp.Width != m.Width || _bmp.Height != m.Height)
        {
            _bmp?.Dispose();
            _bmp = new Bitmap(m.Width, m.Height, PixelFormat.Format32bppRgb);
        }
        var data = _bmp.LockBits(new Rectangle(0, 0, m.Width, m.Height), ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
        try
        {
            for (int y = 0; y < m.Height; y++)
            {
                uint* row = (uint*)((byte*)data.Scan0 + y * data.Stride);
                for (int x = 0; x < m.Width; x++) row[x] = m[x, y] ? 0xFF000000u : 0xFFFFFFFFu;
            }
        }
        finally { _bmp.UnlockBits(data); }
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Color.White);
        if (_bmp == null) return;
        int scale = Math.Max(1, Math.Min(ClientSize.Width, ClientSize.Height) / _bmp.Width);
        int size = _bmp.Width * scale;
        int x = (ClientSize.Width - size) / 2, y = (ClientSize.Height - size) / 2;
        e.Graphics.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.NearestNeighbor;
        e.Graphics.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.Half;
        e.Graphics.DrawImage(_bmp, new Rectangle(x, y, size, size));
    }
}
