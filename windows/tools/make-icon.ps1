# Renders the LyricsMatch icon (same artwork as the Android adaptive icon) into
#   windows/LyricsMatch.Windows/Assets/LyricsMatch.ico  (16-256 px)
#   docs/icon.png                                         (256 px, for the README)
# Usage: powershell -ExecutionPolicy Bypass -File windows/tools/make-icon.ps1
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')

$amber = [System.Drawing.Color]::FromArgb(255, 0xFF, 0xC8, 0x57)

function New-IconBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)

    # Rounded-square background with a subtle top-to-bottom gradient.
    $pad = [Math]::Max(1, $size * 0.03)
    $rect = New-Object System.Drawing.RectangleF $pad, $pad, ($size - 2 * $pad), ($size - 2 * $pad)
    $r = $rect.Width * 0.22
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($rect.X, $rect.Y, 2 * $r, 2 * $r, 180, 90)
    $path.AddArc($rect.Right - 2 * $r, $rect.Y, 2 * $r, 2 * $r, 270, 90)
    $path.AddArc($rect.Right - 2 * $r, $rect.Bottom - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $path.CloseFigure()
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect,
        ([System.Drawing.Color]::FromArgb(255, 0x26, 0x21, 0x36)),
        ([System.Drawing.Color]::FromArgb(255, 0x0E, 0x0C, 0x14)), 90
    $g.FillPath($bg, $path)

    # Artwork uses the Android 108-unit viewport (art spans x 23..86, y 34..75);
    # map an 84-unit square centred on it onto the tile, leaving comfortable padding.
    $s = $size / 84.0
    $g.TranslateTransform(-12.5 * $s, -12.5 * $s)
    $g.ScaleTransform($s, $s)

    # Mic head (capsule 30..46 x 34..58)
    $mic = New-Object System.Drawing.Drawing2D.GraphicsPath
    $mic.AddArc(30, 34, 16, 16, 180, 180)
    $mic.AddArc(30, 42, 16, 16, 0, 180)
    $mic.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush $amber), $mic)

    $pen = New-Object System.Drawing.Pen $amber, 4
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    $g.DrawArc($pen, 25, 37, 26, 26, 0, 180)   # cradle
    $g.DrawLine($pen, 38, 63, 38, 72)           # stem
    $g.DrawLine($pen, 31, 73, 45, 73)           # base

    function Line($x1, $y, $x2, $a, $color) {
        $p = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb($a, $color)), 6
        $p.StartCap = 'Round'; $p.EndCap = 'Round'
        $g.DrawLine($p, $x1, $y, $x2, $y)
    }
    Line 58 42 83 255 $amber
    Line 58 54 76 204 ([System.Drawing.Color]::White)
    Line 58 66 69 115 ([System.Drawing.Color]::White)

    $g.Dispose()
    return $bmp
}

function Get-PngBytes($bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    return , $ms.ToArray()
}

# ---- .ico (PNG-compressed entries) ----
$sizes = 16, 20, 24, 32, 40, 48, 64, 128, 256
$images = foreach ($sz in $sizes) { , (Get-PngBytes (New-IconBitmap $sz)) }

$icoPath = Join-Path $root 'windows\LyricsMatch.Windows\Assets\LyricsMatch.ico'
New-Item -ItemType Directory -Force (Split-Path $icoPath) | Out-Null
$fs = [System.IO.File]::Create($icoPath)
$w = New-Object System.IO.BinaryWriter $fs
$w.Write([UInt16]0); $w.Write([UInt16]1); $w.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $sz = $sizes[$i]; $len = $images[$i].Length
    $dim = if ($sz -ge 256) { 0 } else { $sz }
    $w.Write([byte]$dim); $w.Write([byte]$dim); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([UInt16]1); $w.Write([UInt16]32); $w.Write([UInt32]$len); $w.Write([UInt32]$offset)
    $offset += $len
}
foreach ($img in $images) { $w.Write($img) }
$w.Dispose()

# ---- README icon ----
$docs = Join-Path $root 'docs'
New-Item -ItemType Directory -Force $docs | Out-Null
(New-IconBitmap 256).Save((Join-Path $docs 'icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)

"Wrote $icoPath and docs\icon.png"
