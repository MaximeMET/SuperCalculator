# 生成并签名"规则包更新"文件。
#
# 用法（在仓库根目录）：
#     pwsh tools/pack-rules.ps1 -Version 2
#
# 做三件事：
#   1. 把 engine/src/main/resources/rules/ 下的四张表合成 updates/rules-v<N>.json；
#   2. 用私有子模块里的私钥签名（ECDSA P-256 / SHA-256，DER），写 updates/rules-v<N>.json.sig；
#   3. 顺手把 updates/manifest.json 的 rules 段指到新版本（文件不存在就建一个只有 rules 段的）。
#
# 私钥在 work/keys/rules-signing-private.pem（私有子模块，绝不能进公开仓库）。
# 发布后 App 端：设置 → 检查更新 → 下载这两个文件 → 验签 → 安装。

param(
    [Parameter(Mandatory = $true)][int]$Version,
    [string]$KeyPath = ""
)

$ErrorActionPreference = "Stop"

$repo = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $KeyPath) {
    $KeyPath = Join-Path $repo "work\keys\rules-signing-private.pem"
}
if (-not (Test-Path $KeyPath)) {
    throw "找不到签名私钥：$KeyPath（在私有子模块 work/keys/ 里）"
}

$rulesDir = Join-Path $repo "engine\src\main\resources\rules"
function Read-Section([string]$name) {
    $path = Join-Path $rulesDir $name
    $doc = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    # needsParser>1 的规则依赖新版解析（常数校验、负数绑定、positive 档），
    # 老版本 App 读到会忽略这些新语义、可能标错公式——发布包里先不放，随新 App 内置分发。
    $rules = @($doc.rules)
    $skipped = @($rules | Where-Object { $_.PSObject.Properties.Name -contains "needsParser" -and $_.needsParser -gt 1 })
    if ($skipped.Count -gt 0) {
        Write-Host "  $name：跳过 $($skipped.Count) 条需要新版解析的规则（$($skipped.id -join ', ')）"
    }
    return @($rules | Where-Object { -not ($_.PSObject.Properties.Name -contains "needsParser" -and $_.needsParser -gt 1) })
}

$pack = [ordered]@{
    version     = $Version
    generated   = (Get-Date -Format "yyyy-MM-dd")
    comment     = "由 tools/pack-rules.ps1 从 engine/src/main/resources/rules/ 生成；验签通过才会被 App 安装。"
    integrals   = Read-Section "integrals.json"
    derivatives = Read-Section "derivatives.json"
    equivalents = Read-Section "equivalents.json"
    polynomials = Read-Section "polynomials.json"
}
$json = $pack | ConvertTo-Json -Depth 20
# Windows 上 ConvertTo-Json 输出 CRLF，进 git 会被规范成 LF——签名的对象是"发布出去的
# 那个字节串"，所以这里先统一成 LF 再签，配合 .gitattributes 的 `updates/*.json eol=lf`，
# 本地文件和 CDN 上的字节完全一致（踩过的坑：签了 CRLF、CDN 发 LF，App 一律验签失败）。
$json = $json -replace "`r`n", "`n"

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$updatesDir = Join-Path $repo "updates"
New-Item -ItemType Directory -Force $updatesDir | Out-Null

$packName = "rules-v$Version.json"
$packPath = Join-Path $updatesDir $packName
$bytes = $utf8NoBom.GetBytes($json)
[System.IO.File]::WriteAllBytes($packPath, $bytes)

# 签名：注意签名对象就是上面写盘的字节；.NET 默认输出 r||s，必须显式要 DER，
# 不然 Android/JCA 的 SHA256withECDSA 会验不过。
$keyPem = [System.IO.File]::ReadAllText($KeyPath)
$ecdsa = [System.Security.Cryptography.ECDsa]::Create()
$ecdsa.ImportFromPem($keyPem)
$signature = $ecdsa.SignData(
    $bytes,
    [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence
)
$sigPath = "$packPath.sig"
[System.IO.File]::WriteAllText($sigPath, [Convert]::ToBase64String($signature))

# 自检：用引擎里写死的公钥再验一遍，防止"签名的私钥"和"App 内置的公钥"不是一对。
$rulePacksKt = Join-Path $repo "engine\src\main\kotlin\io\github\maximemet\supercalc\engine\RulePacks.kt"
if (Test-Path $rulePacksKt) {
    $kt = [System.IO.File]::ReadAllText($rulePacksKt)
    $match = [regex]::Match(
        $kt,
        'PUBLIC_KEY_B64\s*=\s*((?:"[^"]*"\s*\+?\s*)+)',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )
    if ($match.Success) {
        $b64 = ($match.Groups[1].Value -split '"' | Where-Object { $_ -match '^[A-Za-z0-9+/=]+$' }) -join ''
        $publicKey = [System.Security.Cryptography.ECDsa]::Create()
        $read = 0
        $publicKey.ImportSubjectPublicKeyInfo([Convert]::FromBase64String($b64), [ref]$read)
        # 验的是"磁盘上那份文件"，不是内存里的字节——发布出去的就是它
        $ok = $publicKey.VerifyData(
            [System.IO.File]::ReadAllBytes($packPath),
            $signature,
            [System.Security.Cryptography.HashAlgorithmName]::SHA256,
            [System.Security.Cryptography.DSASignatureFormat]::Rfc3279DerSequence
        )
        if (-not $ok) {
            throw "自检失败：引擎内置公钥验不过这次的签名（私钥和公钥不是一对？）"
        }
        Write-Host "自检通过：引擎内置公钥能验这次的签名"
    }
}

# 更新 manifest 的 rules 段（app 段是发布时人工维护的，不动）
$manifestPath = Join-Path $updatesDir "manifest.json"
$baseUrl = "https://cdn.jsdelivr.net/gh/MaximeMET/SuperCalculator@main/updates/$packName"
$rulesSection = [ordered]@{
    version = $Version
    json    = $baseUrl
    sig     = "$baseUrl.sig"
}
if (Test-Path $manifestPath) {
    $manifest = [System.IO.File]::ReadAllText($manifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $manifest.rules = $rulesSection
} else {
    $manifest = [ordered]@{
        app   = $null
        rules = $rulesSection
    }
    Write-Host "manifest.json 不存在，先写了一个只有 rules 段的，app 段发布时补。"
}
$manifestJson = ($manifest | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
[System.IO.File]::WriteAllBytes($manifestPath, $utf8NoBom.GetBytes($manifestJson))

Write-Host "已生成 $packPath（$($bytes.Length) 字节）"
Write-Host "已生成 $sigPath"
Write-Host "manifest 的 rules 段已指到 v$Version"
