<#
.SYNOPSIS
    构建本项目使用的 MathQuill（公式编辑器）。

.DESCRIPTION
    为什么不用 npm 上的包：`mathquill@0.10.1` 在 npm 上的 repository 字段指向
    `wensheng/umeditor-mathquill`，是第三方重新发布的，血统不干净。
    所以这里直接从官方仓库 tag `v0.10.1` 的源码构建：

      1. 取 codeload 上的官方源码包（记录 sha256）
      2. npm install 装构建依赖（less / uglify-js / pjs）
      3. 按官方 Makefile 的顺序拼出 build/mathquill.js（含非 ASCII 转义）
      4. uglify + lessc 产出 mathquill.min.js / mathquill.css
      5. 打上字体补丁（数学字体换掉上游的 Symbola，见 NOTICE.md），
         再连同编辑器要的字体一起放进 app/src/main/assets/matheditor/

    许可证是 MPL-2.0（不是 MIT），和本项目的 GPL-3.0 兼容，
    但发布时必须保留 MPL 声明与对应源码的获取方式，见 README。

    产物已随仓库提供，正常运行不需要跑这个脚本；只有要升级 MathQuill 时才需要。

.EXAMPLE
    pwsh tools/build-mathquill.ps1 -WorkDir work/mathquill
#>
param(
    [string]$WorkDir = "build/mathquill",
    [string]$Tag = "v0.10.1"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$work = Join-Path $repo $WorkDir
$src = Join-Path $work "mathquill-$($Tag.TrimStart('v'))"
$assets = Join-Path $repo "app/src/main/assets/matheditor"

if (-not (Test-Path $src)) {
    Write-Host "拉取 MathQuill $Tag 源码 ..."
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $tarball = Join-Path $work "mathquill-$Tag.tar.gz"
    if (-not (Test-Path $tarball)) {
        $url = "https://codeload.github.com/mathquill/mathquill/tar.gz/refs/tags/$Tag"
        Invoke-WebRequest -Uri $url -OutFile $tarball -TimeoutSec 300
    }
    $hash = (Get-FileHash $tarball -Algorithm SHA256).Hash
    Write-Host "  源码包 sha256 = $hash"
    tar -xzf $tarball -C $work
}

Push-Location $src
try {
    if (-not (Test-Path "node_modules/.bin/lessc.cmd") -and
        -not (Test-Path "node_modules/.bin/lessc")) {
        Write-Host "安装构建依赖 ..."
        # pjs 的 postinstall 要调用 make，而我们只需要它的 src/p.js，所以跳过脚本。
        # less 必须锁 2.x：MathQuill 0.10.1 的 .less 用的是 2016 年的语法，
        # Less 3 之后会直接报 "Cannot read properties of null (reading 'eval')"。
        npm install --ignore-scripts --no-audit --no-fund `
            --registry=https://registry.npmmirror.com `
            --no-save less@2.7.3 uglify-js@2.8.29 | Out-Null
    }

    New-Item -ItemType Directory -Force -Path "build" | Out-Null

    Write-Host "拼接源码 ..."
    node (Join-Path $repo "tools/mathquill-concat.js") $src "build/mathquill.js" `
        (Join-Path $repo "tools/mathquill-patches/expose-internals.js")

    Write-Host "压缩 JS ..."
    # 走 Node 脚本而不是 PowerShell 管道：管道会把非 ASCII 写成 '?'，
    # LatexCmds['÷'] 会变成 LatexCmds['?']，详见 tools/mathquill-minify.js 的注释。
    node (Join-Path $repo "tools/mathquill-minify.js") "build/mathquill.js" `
        "build/mathquill.min.js" (Join-Path $src "node_modules/uglify-js")

    Write-Host "编译样式 ..."
    $lessc = Join-Path $src "node_modules/.bin/lessc.cmd"
    if (-not (Test-Path $lessc)) { $lessc = Join-Path $src "node_modules/.bin/lessc" }
    & $lessc "src/css/main.less" | Out-File -Encoding ascii "build/mathquill.css"

    Write-Host "换数学字体 ..."
    node (Join-Path $repo "tools/mathquill-css-patch.js") "build/mathquill.css"
}
finally {
    Pop-Location
}

Write-Host "复制到 assets ..."
$target = Join-Path $assets "mathquill"
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item "$src/build/mathquill.min.js" $target -Force
Copy-Item "$src/build/mathquill.css" $target -Force
# Copy-Item 在同名目录已存在时会把源目录塞进去变成 font/font，先删干净
$fontTarget = Join-Path $target "font"
if (Test-Path $fontTarget) { Remove-Item $fontTarget -Recurse -Force }
# 上游 src/font 里是 Symbola，许可不允许再分发，所以不复制（见 NOTICE.md）；
# 编辑器要的字体（TeX Gyre Termes 从 CTAN、STIX Two Math 从 Google Fonts）脚本里取。
Write-Host "补齐公式编辑器字体 ..."
& (Join-Path $repo "tools/fetch-editor-fonts.ps1")

Get-ChildItem $target -Recurse -File |
    ForEach-Object { "  {0}  {1}" -f $_.FullName.Substring($target.Length + 1), $_.Length }
