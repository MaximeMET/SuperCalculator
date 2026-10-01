<#
.SYNOPSIS
    取公式编辑器要用的两套字体。

.DESCRIPTION
    编辑器用两套字体，都从上游发布取，逐个核对 SHA-256：

      1. TeX Gyre Termes（4 个 otf）—— fonts.css 把 `Times New Roman` 指向它。
         没有它公式的字宽和行高都会变（详见 README 的 M2 一节）。
         GUST Font License（LPPL 家族），允许原样再分发，全文见
         licenses/GUST-Font-License.txt。

      2. DejaVu Math TeX Gyre（1 个 ttf）—— 数学字体，排在字体栈最前面。
         上游原本用的是 Symbola，但它的许可不允许再分发（见 NOTICE.md），
         M6 换成了 DejaVu Math TeX Gyre：DejaVu/Bitstream 许可允许再分发，
         全文见 licenses/DejaVu-Fonts-License.txt。

    注意：DejaVu 那条下载地址是在没有网络的沙箱里写的，还没实际跑过；
    真正下载时以 SHA-256 为准，对不上就直接报错，不会悄悄换成一个别的文件。

.EXAMPLE
    pwsh tools/fetch-editor-fonts.ps1
#>
param(
    [string]$Mirror = "https://mirrors.ctan.org/fonts/tex-gyre/opentype"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$fontDir = Join-Path $repo "app/src/main/assets/matheditor/mathquill/font"

# 上游 2.004 版的 SHA-256（2026-10 从 CTAN 镜像实测）
$expected = [ordered]@{
    "texgyretermes-regular.otf"    = "CC3FE7C707B81428D23D54DF3EADD9228A2BF6A4D43125D94DF56F5F63134659"
    "texgyretermes-bold.otf"       = "2FB3E952065FA153C7E4E64E04B98B9D79225739B6025AA3F0F0782D299FF61E"
    "texgyretermes-italic.otf"     = "6DD103A1672E50568CD2F8A706CCD48443D44D7D073A59D2286F4E6F746575D6"
    "texgyretermes-bolditalic.otf" = "1BF6AF99CB0E26C12951317032D79B96AE009551E59CCF02A5B24F325ECFEC87"
    "DejaVuMathTeXGyre.ttf"        = "40DA67C0B6B03076504FBDA4BF3E7B4F20B35999C98063019A3392FE9B1294FE"
}

# DejaVu Math TeX Gyre 2.37 的候选下载地址（依次尝试，哪个对得上哈希用哪个）
$dejavuUrls = @(
    "https://mirrors.ctan.org/fonts/dejavu/DejaVuMathTeXGyre.ttf"
    "https://www.tug.org/svn/texlive/trunk/Master/texmf-dist/fonts/truetype/public/dejavu/DejaVuMathTeXGyre.ttf"
)

New-Item -ItemType Directory -Force -Path $fontDir | Out-Null

foreach ($name in $expected.Keys) {
    $out = Join-Path $fontDir $name
    $want = $expected[$name]

    if (Test-Path $out) {
        $have = (Get-FileHash $out -Algorithm SHA256).Hash
        if ($have -eq $want) {
            Write-Host "  已就位 $name"
            continue
        }
    }

    $urls = if ($name -eq "DejaVuMathTeXGyre.ttf") { $dejavuUrls } else { @("$Mirror/$name") }
    $ok = $false
    foreach ($url in $urls) {
        Write-Host "  下载 $name ...`n    $url"
        try {
            Invoke-WebRequest -Uri $url -OutFile $out -TimeoutSec 300
        } catch {
            Write-Host "    失败：$($_.Exception.Message)"
            continue
        }
        $have = (Get-FileHash $out -Algorithm SHA256).Hash
        if ($have -eq $want) { $ok = $true; break }
        Write-Host "    SHA-256 对不上：期望 $want，实际 $have"
        Remove-Item $out -Force
    }
    if (-not $ok) {
        throw @"
取不到 $name。请手动下载后放到 $fontDir，然后重跑本脚本。
  TeX Gyre Termes 2.004: https://mirrors.ctan.org/fonts/tex-gyre/opentype/
  DejaVu Math TeX Gyre 2.37: https://www.gust.org.pl/projects/e-foundry/tex-gyre/ 或 CTAN fonts/dejavu
  期望 SHA-256: $want
"@
    }
}

$stale = Get-ChildItem $fontDir -Filter "Symbola*" -File -ErrorAction SilentlyContinue
if ($stale) {
    Write-Warning "字体目录里还有 Symbola（$($stale.Count) 个文件）。它的许可不允许再分发，请从仓库里删掉。"
}

Write-Host "编辑器字体就位：TeX Gyre Termes 2.004 + DejaVu Math TeX Gyre 2.37（SHA-256 全部匹配）"
