param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Drawable,

    [string]$OutDir = "build/icon-preview",
    [string]$Tint = "#D7EFE5",
    [string]$Background = "#005643",
    [int]$Size = 256
)

$ErrorActionPreference = "Stop"

$magick = Get-Command magick -ErrorAction SilentlyContinue
if (-not $magick) {
    throw "ImageMagick 'magick' is required for vector preview."
}

$androidNs = "http://schemas.android.com/apk/res/android"
$doc = [xml](Get-Content -LiteralPath $Drawable -Raw)
$vector = $doc.vector
$viewportWidth = [double]$vector.GetAttribute("viewportWidth", $androidNs)
$viewportHeight = [double]$vector.GetAttribute("viewportHeight", $androidNs)
if ($viewportWidth -le 0 -or $viewportHeight -le 0) {
    throw "Invalid vector viewport in $Drawable"
}

New-Item -ItemType Directory -Force $OutDir | Out-Null
$baseName = [IO.Path]::GetFileNameWithoutExtension($Drawable)
$svgPath = Join-Path $OutDir "$baseName.preview.svg"
$pngPath = Join-Path $OutDir "$baseName.preview.png"

function Convert-Color([string]$color, [string]$fallback) {
    if ([string]::IsNullOrWhiteSpace($color)) { return $fallback }
    switch ($color) {
        "@android:color/black" { return $Tint }
        "@android:color/white" { return "#FFFFFF" }
        "@android:color/transparent" { return "none" }
        default { return $color }
    }
}

$scale = $Size / [Math]::Max($viewportWidth, $viewportHeight)
$offsetX = ($Size - $viewportWidth * $scale) / 2
$offsetY = ($Size - $viewportHeight * $scale) / 2

$elements = foreach ($path in $vector.path) {
    $pathData = $path.GetAttribute("pathData", $androidNs)
    if ([string]::IsNullOrWhiteSpace($pathData)) { continue }

    $fill = Convert-Color $path.GetAttribute("fillColor", $androidNs) "none"
    $stroke = Convert-Color $path.GetAttribute("strokeColor", $androidNs) "none"
    $strokeWidth = $path.GetAttribute("strokeWidth", $androidNs)
    $strokeLineCap = $path.GetAttribute("strokeLineCap", $androidNs)
    $strokeLineJoin = $path.GetAttribute("strokeLineJoin", $androidNs)

    $attrs = @(
        "d=`"$pathData`"",
        "fill=`"$fill`"",
        "stroke=`"$stroke`""
    )
    if ($strokeWidth) { $attrs += "stroke-width=`"$strokeWidth`"" }
    if ($strokeLineCap) { $attrs += "stroke-linecap=`"$strokeLineCap`"" }
    if ($strokeLineJoin) { $attrs += "stroke-linejoin=`"$strokeLineJoin`"" }
    "    <path $($attrs -join " ") />"
}

$svg = @"
<svg xmlns="http://www.w3.org/2000/svg" width="$Size" height="$Size" viewBox="0 0 $Size $Size">
  <rect width="$Size" height="$Size" rx="32" fill="$Background" />
  <g transform="translate($offsetX $offsetY) scale($scale)">
$($elements -join "`n")
  </g>
</svg>
"@

Set-Content -LiteralPath $svgPath -Value $svg -Encoding utf8
& $magick.Source $svgPath $pngPath
Write-Output (Resolve-Path $pngPath).Path
