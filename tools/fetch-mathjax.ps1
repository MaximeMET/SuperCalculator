# Fetch the offline MathJax runtime used by the result page.
#
# The result page renders TeX with MathJax v3 (SVG output). Only a subset of
# the official npm package is needed, but the bundle loads the font data and
# a few optional components as separate files, so those have to sit next to
# it under app/src/main/assets/mathjax/.
#
# Usage: pwsh tools/fetch-mathjax.ps1

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$version = '3.2.2'
$base = "https://cdn.jsdelivr.net/npm/mathjax@$version/es5"
$dest = Join-Path (Split-Path $PSScriptRoot -Parent) 'app\src\main\assets\mathjax'

$files = @(
    'tex-svg.js',
    'output/svg/fonts/tex.js',
    'input/tex/extensions/ams.js',
    'input/tex/extensions/newcommand.js',
    'input/tex/extensions/noundefined.js',
    'input/tex/extensions/require.js',
    'input/tex/extensions/autoload.js',
    'input/tex/extensions/configmacros.js',
    'ui/menu.js',
    'a11y/assistive-mml.js'
)

foreach ($file in $files) {
    $out = Join-Path $dest ($file -replace '/', '\')
    $dir = Split-Path $out -Parent
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Invoke-WebRequest -Uri "$base/$file" -OutFile $out
    Write-Host "fetched $file"
}

Write-Host "MathJax $version runtime is in $dest"
