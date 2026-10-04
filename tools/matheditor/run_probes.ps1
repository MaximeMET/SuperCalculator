<#
.SYNOPSIS
    编辑器命令层回归：把我们的 editor.js 跑一遍命令表，和原版实测基准逐条比。

.DESCRIPTION
    默认还会拿原版 mathquill.min.js 再跑一遍（需要它放在 -OrigMqDir 里，
    正常仓库里没有这个文件，只有开发机上从原版 APK 抠出来的那一份）。

    比对三样东西：latex()、渲染出来的 HTML（去掉 reflow 产生的内联样式）、
    symja()（引擎输入）。全绿才算「和原版一致」。

.EXAMPLE
    pwsh tools/matheditor/run_probes.ps1 -SkipOrig
#>
param(
    [string]$Work = "build/matheditor-probe",
    [string]$OrigMqDir = "build/matheditor-probe/orig-mq",
    [int]$Port = 8777,
    [switch]$SkipOrig
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $repo

$tools = Join-Path $repo "tools/matheditor"
$work = Join-Path $repo $Work
New-Item -ItemType Directory -Force -Path $work | Out-Null

$editorHtml = Join-Path $repo "app/src/main/assets/matheditor/editor.html"
$assets = Join-Path $repo "app/src/main/assets/matheditor"

# 静态服务器：根目录是 assets/matheditor，第二根目录放探针页（不污染 APK assets）
$listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    Write-Host "启动静态服务器 :$Port ..."
    $serve = Join-Path $tools "serve.js"
    Start-Process -WindowStyle Hidden -FilePath "node" `
        -ArgumentList @($serve, $assets, $Port, $work)
    Start-Sleep -Seconds 2
}

# 本机只有 Edge；--browser 是 open 子命令的参数，不能放到全局位置
$cli = @("--yes", "--package", "@playwright/cli", "playwright-cli", "-s=matheditor")
$dump = "() => JSON.stringify({keys: window.__results, seqs: window.__seqs})"

if (-not $SkipOrig) {
    $origMq = Join-Path $repo "$OrigMqDir/mathquill.min.js"
    if (-not (Test-Path $origMq)) {
        throw "找不到原版 mathquill.min.js：$origMq（只想测自己就加 -SkipOrig）"
    }
    python (Join-Path $tools "bare_probe.py") "orig-mq/mathquill.min.js" `
        (Join-Path $work "keys-orig.html") | Out-Null
    npx @cli open "http://127.0.0.1:$Port/keys-orig.html" --browser msedge | Out-Null
    npx @cli eval $dump --raw | Out-File -Encoding utf8 (Join-Path $work "keys-orig.json")
}

python (Join-Path $tools "editor_probe.py") $editorHtml (Join-Path $work "keys-ours.html") | Out-Null
npx @cli open "http://127.0.0.1:$Port/keys-ours.html" --browser msedge | Out-Null
npx @cli eval $dump --raw | Out-File -Encoding utf8 (Join-Path $work "keys-ours.json")

$golden = Join-Path $tools "golden-keys-orig.json"
$report = Join-Path $work "report.txt"
python (Join-Path $tools "diff_keys.py") $golden (Join-Path $work "keys-ours.json") $report
Write-Host "报告：$report"

# 三角函数自动补度数：走的是 writeCommand 那条路（原版 filterCommand 的移植），
# 裸 MathQuill 里没有这段逻辑，所以只和自己比。期望值见 degree_cases.py。
$degreeHtml = Join-Path $work "degree-ours.html"
$degreeJson = Join-Path $work "degree-ours.json"
python (Join-Path $tools "degree_probe.py") $editorHtml $degreeHtml | Out-Null
npx @cli open "http://127.0.0.1:$Port/degree-ours.html" --browser msedge | Out-Null
npx @cli eval "() => JSON.stringify(window.__seqs)" --raw | Out-File -Encoding utf8 $degreeJson
python (Join-Path $tools "check_degree.py") $degreeJson
