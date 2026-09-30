<#
.SYNOPSIS
    取公式编辑器要用的 TeX Gyre Termes 字体。

.DESCRIPTION
    编辑器页面里 `tex-font.css` 把 `Times New Roman` 指向 TeX Gyre Termes，
    没有这几个文件公式的字宽和行高都会变（详见 README 的 M2 一节）。

    字体本身是 GUST 按 GUST Font License（LPPL 家族）发布的，可以随本项目分发；
    这里从 CTAN 官方镜像取 2.004 版，并对每个文件校验 SHA-256——
    也就是说这几个文件的内容可以溯源到上游发布，不是从任何 App 包里抠的。

    许可证全文见 licenses/GUST-Font-License.txt。

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
}

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

    Write-Host "  下载 $name ..."
    Invoke-WebRequest -Uri "$Mirror/$name" -OutFile $out -TimeoutSec 300
    $have = (Get-FileHash $out -Algorithm SHA256).Hash
    if ($have -ne $want) {
        Remove-Item $out -Force
        throw "$name 的 SHA-256 对不上：期望 $want，实际 $have"
    }
}

Write-Host "TeX Gyre Termes 2.004 就位（4 个文件，SHA-256 全部匹配 CTAN 发布）"
