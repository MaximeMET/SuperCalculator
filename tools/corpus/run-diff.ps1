# 一键跑差分：把公开的题目清单喂给引擎，再和私有基准逐条对比。
#
# 用法（在仓库根目录）：
#     pwsh tools/corpus/run-diff.ps1
#
# 没初始化私有子模块时也能跑：引擎那一步照常，最后对比会提示缺少基准数据。
$ErrorActionPreference = "Stop"

$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$inFile = Join-Path $PSScriptRoot "in.txt"
$dest = Join-Path $repo "engine\build\corpus\in.txt"

New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
Copy-Item $inFile $dest -Force
Write-Host "语料已拷贝：$dest"

Push-Location $repo
try {
    & .\gradlew.bat :engine:test --tests "*CorpusRunnerTest*" --rerun-tasks --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "引擎批跑失败（exit $LASTEXITCODE）"
    }
    python tools/corpus/compare.py
} finally {
    Pop-Location
}
