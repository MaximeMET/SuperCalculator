# 发布之后（git push 完成）把 jsDelivr 的边缘缓存清掉。
#
# 背景：jsDelivr 对分支引用（@main）的文件按 `s-maxage=43200` 缓存 12 小时，
# 不主动 purge 的话，刚推上去的新清单/新规则包最多半天后才对用户可见
# （App 端的 useCaches=false 只绕过本地缓存，绕不过 CDN 边缘）。
#
# 用法：pwsh tools/purge-cdn.ps1 -Version 2
#   清 manifest.json + rules-v2.json + rules-v2.json.sig 三个地址。

param([Parameter(Mandatory = $true)][int]$Version)

$ErrorActionPreference = "Stop"

$paths = @(
    "updates/manifest.json",
    "updates/rules-v$Version.json",
    "updates/rules-v$Version.json.sig"
)

foreach ($path in $paths) {
    $url = "https://purge.jsdelivr.net/gh/MaximeMET/SuperCalculator@main/$path"
    try {
        $response = Invoke-RestMethod -Uri $url -TimeoutSec 30
        $status = $response.status
        Write-Host "已清缓存（$status）：$path"
    } catch {
        Write-Host "清除失败：$path —— $($_.Exception.Message)"
    }
}
