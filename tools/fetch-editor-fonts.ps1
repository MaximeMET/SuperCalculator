<#
.SYNOPSIS
    取公式编辑器要用的两套字体。

.DESCRIPTION
    编辑器用两套字体，都从上游发布取，逐个核对 SHA-256：

      1. TeX Gyre Termes（4 个 otf）—— fonts.css 把 `Times New Roman` 指向它。
         没有它公式的字宽和行高都会变（详见 README 的 M2 一节）。
         GUST Font License（LPPL 家族），允许原样再分发，全文见
         licenses/GUST-Font-License.txt。

      2. STIX Two Math（1 个 ttf）—— 数学字体，排在字体栈最前面。
         上游原本用的是 Symbola，但它的许可不允许再分发（见 NOTICE.md），
         M6 换成了 STIX Two Math：科技出版领域的标准数学字体，SIL OFL-1.1，
         随软件分发没有障碍，覆盖率和 Symbola 基本持平（245 个字形里 243 个）。
         这份 ttf 是 Google Fonts 镜像的 OFL 发布版，和 stixfonts 上游同源。

    五个文件逐个核对 SHA-256；对不上就直接报错，不会悄悄换成一个别的文件。

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
    "STIXTwoMath-Regular.ttf"      = "562551B15B836E6E01D1B7350909BAF3C8C8D83260C1190FBF4544333E6936DE"
}

# STIX Two Math 不在 CTAN，走 Google Fonts 的官方镜像
$stixUrl = "https://raw.githubusercontent.com/google/fonts/main/ofl/stixtwomath/STIXTwoMath-Regular.ttf"

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

    $urls = if ($name -eq "STIXTwoMath-Regular.ttf") { @($stixUrl) } else { @("$Mirror/$name") }
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
  TeX Gyre Termes 2.004:  https://mirrors.ctan.org/fonts/tex-gyre/opentype/
  STIX Two Math 2.12:     https://github.com/google/fonts/tree/main/ofl/stixtwomath
                          （或上游 https://github.com/stipub/stixfonts）
  期望 SHA-256: $want
"@
    }
}

$stale = Get-ChildItem $fontDir -File -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -like "Symbola*" -or
        $_.Name -like "DejaVuMath*" -or
        $_.Name -like "texgyretermes-math*"
    }
if ($stale) {
    Write-Warning "字体目录里还有用不上的旧字体（$($stale.Name -join ', ')）：Symbola 不允许再分发，DejaVu Math / Termes Math 已经被 STIX Two Math 取代，请从仓库里删掉。"
}

Write-Host "编辑器字体就位：TeX Gyre Termes 2.004 + STIX Two Math 2.12（SHA-256 全部匹配）"
