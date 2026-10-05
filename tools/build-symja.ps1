<#
.SYNOPSIS
    构建本项目使用的 Symja 内核 jar。

.DESCRIPTION
    参考实现用的 Symja 没有发布到 Maven Central，所以需要从上游 tag 自行构建。
    为什么要费这个劲：新版 Symja 的 TeX 排版器输出不同（`\ln{x}` 变成 `\log (x)`、
    乘法间距不同、加法项序不同），差分测试的一致率会掉一大截。

    脚本做三件事：
      1. 取上游 tag `version_2016-04-15` 的源码
      2. 打上 tools/symja-patches 里的补丁（TeX 排版上的三处行为差异）
      3. 编译并打包成 engine/libs/symja-2016-04-15.jar

    产物已随仓库提供，正常运行不需要跑这个脚本；只有要改动内核时才需要。

.EXAMPLE
    pwsh tools/build-symja.ps1 -WorkDir work/symja
#>
param(
    [string]$WorkDir = "build/symja",
    [string]$Tag = "version_2016-04-15"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$work = Join-Path $repo $WorkDir
$src = $work
$classes = Join-Path $work "classes"
$jarOut = Join-Path $repo "engine/libs/symja-2016-04-15.jar"

if (-not (Test-Path $src)) {
    Write-Host "拉取 Symja $Tag ..."
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    git clone --depth 1 --branch $Tag --single-branch `
        https://github.com/axkr/symja_android_library.git $src
}

$root = Join-Path $src "symja_android_library"

Write-Host "应用补丁 ..."
# 注意：补丁文件要和源码树挂到同一个 package 根下，
# 也就是 matheclipse-core/src/main/java，不能挂到 $root（那是仓库根，不参与编译）。
Copy-Item (Join-Path $repo "tools/symja-patches/*") `
    (Join-Path $root "matheclipse-core/src/main/java") -Recurse -Force

# ============================================================
# 行为补丁
# ============================================================
# 参考实现里的 Symja 并不是原版：它是一个被大改过的分支——多出
# core/computeprocess、core/eval/util/segmentfunction 等整包，同时砍掉了
# DSolve、Matcher、LaplaceTransform 等。那份分叉源码没有公开，所以这里不走
# 「对齐源码」的路线，只按插桩探针跑出来的差分结果，在上游 2016-04-15 上打
# 最小行为补丁。每处补丁的锚点都要求唯一命中，改不动就直接报错。

function Edit-SourceOnce {
    param([string]$Text, [string]$From, [string]$To, [string]$What)
    # 先看结果在不在：追加式补丁（To 里含 From）如果直接再插一次就会重复定义，
    # 所以命中结果就一律当「已经打过」处理。
    if ($Text.IndexOf($To) -ge 0) { return $Text }
    $idx = $Text.IndexOf($From)
    if ($idx -lt 0) {
        throw "补丁锚点未找到（$What）"
    }
    if ($Text.IndexOf($From, $idx + 1) -ge 0) { throw "补丁锚点不唯一（$What）" }
    return $Text.Substring(0, $idx) + $To + $Text.Substring($idx + $From.Length)
}

# 上游各文件的换行符不统一（TeXFormFactory 是 CRLF，EvalEngine 也是 CRLF，
# 但被编辑器改过的行会变成 LF）。统一按 LF 处理，锚点只用 `n，省得反复踩坑。
function Normalize-Lf {
    param([string]$Text)
    return $Text.Replace("`r`n", "`n")
}

function Edit-SourceAll {
    param([string]$Text, [string]$From, [string]$To, [int]$Expected, [string]$What)
    $count = ([regex]::Matches($Text, [regex]::Escape($From))).Count
    if ($count -eq 0 -and $Text.IndexOf($To) -ge 0) { return $Text }
    if ($count -ne $Expected) { throw "补丁锚点数量不对（$What）：期望 $Expected 实际 $count" }
    return $Text.Replace($From, $To)
}

# ---- 补丁 1：TeXFormFactory（排版）----
# 1a. Log 不进 operTab，改由 reflection 包里的 Log 转换器渲染成 \ln{x} / \log_{b}{x}
# 1b. 补上基准里多出来的 Sec / Csc 两个三角函数
# 1c. 补上自然常数 e 的 TeX 名
# 1d. 通用函数调用用 \left( \right) 包起来，而不是裸括号
$texFactory = Join-Path $root "matheclipse-core/src/main/java/org/matheclipse/core/form/tex/TeXFormFactory.java"
$texText = Normalize-Lf ([System.IO.File]::ReadAllText($texFactory))

$anchorLog = "`t`toperTab.put(""Log"", new TeXFunction(this, ""log""));`n"
$replLog = "`t`toperTab.put(""Sec"", new TeXFunction(this, ""sec""));`n" +
           "`t`toperTab.put(""Csc"", new TeXFunction(this, ""csc""));`n"
$texText = Edit-SourceOnce $texText $anchorLog $replLog "TeXFormFactory/Log"

$anchorE = "`t`tCONSTANT_EXPRS.put(F.Khinchin, ""K"");`n"
$replE = $anchorE + "`t`tCONSTANT_EXPRS.put(F.E, ""e"");`n"
$texText = Edit-SourceOnce $texText $anchorE $replE "TeXFormFactory/E"

$texText = Edit-SourceAll $texText 'buf.append("(");' 'buf.append("\\left(");' 2 "TeXFormFactory/leftParen"
$texText = Edit-SourceAll $texText 'buf.append(")");' 'buf.append("\\right)");' 2 "TeXFormFactory/rightParen"

[System.IO.File]::WriteAllText($texFactory, $texText)

# ---- 补丁 2：EvalAttributes 增加一个「降幂」排序重载 ----
$evalAttributes = Join-Path $root "matheclipse-core/src/main/java/org/matheclipse/core/eval/EvalAttributes.java"
$attrText = Normalize-Lf ([System.IO.File]::ReadAllText($evalAttributes))
$anchorSort = "`tpublic final static void sort(final IAST ast, Comparator<IExpr> comparator) {`n" +
              "`t`tast.args().sort(comparator);`n" +
              "`t}`n"
$addedSort = $anchorSort +
    "`n" +
    "`t/**`n" +
    "`t * 参考实现的行为：Plus 的结果按降幂重排。`n" +
    "`t *`n" +
    "`t * 这里不能直接用 ast.args().sort(...)：那条路会走到 Range.sort 的内部数组，`n" +
    "`t * 小 AST 的后备数组带空洞，会抛 NPE。只拷贝参数区间再写回就没这个问题。`n" +
    "`t */`n" +
    "`tpublic final static void sort(final IAST ast, final boolean desc) {`n" +
    "`t`tfinal int astSize = ast.size();`n" +
    "`t`tif (astSize <= 2) {`n" +
    "`t`t`treturn;`n" +
    "`t`t}`n" +
    "`t`tfinal IExpr[] args = new IExpr[astSize - 1];`n" +
    "`t`tfor (int i = 1; i < astSize; i++) {`n" +
    "`t`t`targs[i - 1] = ast.get(i);`n" +
    "`t`t}`n" +
    "`t`tif (desc) {`n" +
    "`t`t`tjava.util.Arrays.sort(args, Comparators.ExprReverseComparator.CONS);`n" +
    "`t`t} else {`n" +
    "`t`t`tjava.util.Arrays.sort(args, Comparators.ExprComparator.CONS);`n" +
    "`t`t}`n" +
    "`t`tfor (int i = 1; i < astSize; i++) {`n" +
    "`t`t`tast.set(i, args[i - 1]);`n" +
    "`t`t}`n" +
    "`t}`n"
$attrText = Edit-SourceOnce $attrText $anchorSort $addedSort "EvalAttributes/descSort"
[System.IO.File]::WriteAllText($evalAttributes, $attrText)

# ---- 补丁 3：EvalEngine 每次顶层求值后递归把 Plus 按降幂重排 ----
$evalEngine = Join-Path $root "matheclipse-core/src/main/java/org/matheclipse/core/eval/EvalEngine.java"
$engineText = Normalize-Lf ([System.IO.File]::ReadAllText($evalEngine))
$anchorEval = "`tpublic final IExpr evalWithoutNumericReset(final IExpr expr) {`n" +
              "`t`tIExpr temp = evalLoop(expr);`n" +
              "`t`treturn temp.isPresent() ? temp : expr;`n" +
              "`t}`n"
$addedEval = "`tpublic final IExpr evalWithoutNumericReset(final IExpr expr) {`n" +
             "`t`tIExpr temp = evalLoop(expr);`n" +
             "`t`tsortResult(temp);`n" +
             "`t`treturn temp.isPresent() ? temp : expr;`n" +
             "`t}`n" +
             "`n" +
             "`t/**`n" +
             "`t * 参考实现的行为：递归把结果里的每个 Plus 按降幂重排。`n" +
             "`t */`n" +
             "`tprivate void sortResult(final IExpr result) {`n" +
             "`t`tif (result != null && result.isAST()) {`n" +
             "`t`t`tfinal IAST resultTemp = (IAST) result;`n" +
             "`t`t`tfinal int size = resultTemp.size();`n" +
             "`t`t`tfor (int i = 1; i < size; i++) {`n" +
             "`t`t`t`tsortResult(resultTemp.get(i));`n" +
             "`t`t`t}`n" +
             "`t`t`tif (resultTemp.isPlus()) {`n" +
             "`t`t`t`tEvalAttributes.sort(resultTemp, true);`n" +
             "`t`t`t}`n" +
             "`t`t}`n" +
             "`t}`n"
$engineText = Edit-SourceOnce $engineText $anchorEval $addedEval "EvalEngine/sortResult"
[System.IO.File]::WriteAllText($evalEngine, $engineText)

# 补丁 4（TeXFunction 用花括号）走 tools/symja-patches 的文件覆盖，见上面 Copy-Item。

# ---- 补丁 5：F 增加 SolveInEquality 符号 ----
# 参考实现的分支多了 core/computeprocess 一整包（解方程过程），那份代码里
# ComputeProcessFactory / SolveInequalityComputeProcess 会引用 F.SolveInEquality。
# 我们只搬它的方程过程，不等式的过程暂不走这条链，但符号得先有，否则编译不过。
$fSource = Join-Path $root "matheclipse-core/src/main/java/org/matheclipse/core/expression/F.java"
$fText = Normalize-Lf ([System.IO.File]::ReadAllText($fSource))
$anchorSolve = "`tpublic final static ISymbol Solve = initFinalSymbol(Config.PARSER_USE_LOWERCASE_SYMBOLS ? ""solve"" : ""Solve"");`n"
$replSolve = $anchorSolve +
    "`tpublic final static ISymbol SolveInEquality = initFinalSymbol(`n" +
    "`t`t`tConfig.PARSER_USE_LOWERCASE_SYMBOLS ? ""solveinequality"" : ""SolveInEquality"");`n"
$fText = Edit-SourceOnce $fText $anchorSolve $replSolve "F/SolveInEquality"
[System.IO.File]::WriteAllText($fSource, $fText)

# ---- 补丁 6：解方程过程的 trace 发点 + 记账本 ----
# Solve / Roots / QuarticSolver 里按点插入 `getStepListener().add(...)`，
# 让 evalTrace 能把「度数、因式分解、求根公式、配方、高斯消元」这些中间结果
# 记进 trace 列表；TraceStack 收到发点时顺手写进 StepJournal，
# engine 模块的 SolveSteps 再把它整理成结果页的解题步骤。
python (Join-Path $PSScriptRoot "patch-symja-trace.py") $root
if ($LASTEXITCODE -ne 0) { throw "patch-symja-trace.py 失败" }

# ---- 补丁 7：Limit 补一条 Sec 特判（重要极限算错的根因）----
# 用户报「lim x→0 x/sin x 算成 0」——原版算得对，我们算错。根因：洛必达之后
# 1/cos(x) 会被 Symja 求值成 Sec(x)，而 2016 上游的 evalLimit 只认
# Sin/Cos/Plus/Times/Power，Sec 落到最后返回 NIL，外层再退回 mapLimit，
# 得到 `Limit(x)·Limit(csc x)`＝0·∞ 被简化成 0（错的）。
# 参考实现的分支里多一条 Sec 分支：把极限推进去（Sec(lim x)=Sec(0)=1）。
$limitSource = Join-Path $root "matheclipse-core/src/main/java/org/matheclipse/core/reflection/system/Limit.java"
$limitText = Normalize-Lf ([System.IO.File]::ReadAllText($limitSource))
$anchorSec = "`t`t`t} else if (arg1.isPower()) {`n" +
             "`t`t`t`treturn powerLimit(arg1, data);`n" +
             "`t`t`t}`n"
$replSec = "`t`t`t} else if (arg1.isPower()) {`n" +
           "`t`t`t`treturn powerLimit(arg1, data);`n" +
           "`t`t`t} else if (arg1.head().equals(F.Sec) && arg1.size() == 2`n" +
           "`t`t`t`t`t&& !F.evalQuiet(F.Cos(data.getRule().arg2())).equals(F.C0)) {`n" +
           "`t`t`t`t// 参考实现的分支：1/cos(x) 经洛必达会变成 Sec(x)，`n" +
           "`t`t`t`t// 这里把极限推进去（cos(lim) 非 0 时 Sec 连续）。`n" +
           "`t`t`t`treturn F.unaryAST1(arg1.head(), F.Limit(arg1.arg1(), data.getRule()));`n" +
           "`t`t`t}`n"
$limitText = Edit-SourceOnce $limitText $anchorSec $replSec "Limit/Sec"
[System.IO.File]::WriteAllText($limitSource, $limitText)

Write-Host "编译 ..."
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$dirs = @(
    "matheclipse-core/src/main/java",
    "matheclipse-parser/src/main/java",
    "jas/src/main/java",
    "commons-math/src/main/java",
    "guava/src/main/java",
    "apfloat/src"
)
$files = foreach ($d in $dirs) {
    Get-ChildItem (Join-Path $root $d) -Recurse -Filter *.java | ForEach-Object FullName
}
$argfile = Join-Path $work "sources.txt"
$files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } |
    Set-Content $argfile -Encoding ASCII

$lib = Join-Path $root "lib"
$cp = @(
    (Join-Path $lib "commons-io-1.3.1.jar"),
    (Join-Path $lib "commons-logging-1.1.1.jar"),
    (Join-Path $lib "log4j-1.2.11.jar"),
    (Join-Path $lib "jsr305.jar")
) -join ";"

javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp $cp -d $classes "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "收集资源 ..."
Copy-Item (Join-Path $root "apfloat/src/apfloat.properties") $classes -Force
Copy-Item (Join-Path $root "jas/src/main/java/log4j.properties") $classes -Force
Copy-Item (Join-Path $root "matheclipse-core/src/main/java/System.mep") $classes -Force
$services = Join-Path $classes "META-INF/services"
New-Item -ItemType Directory -Force -Path $services | Out-Null
Copy-Item (Join-Path $root "matheclipse-core/src/main/java/META-INF/services/javax.script.ScriptEngineFactory") `
    $services -Force -ErrorAction SilentlyContinue

Write-Host "打包 ..."
New-Item -ItemType Directory -Force -Path (Split-Path $jarOut) | Out-Null
Push-Location $classes
try {
    jar --create --file $jarOut --no-compress .
} finally {
    Pop-Location
}
Write-Host "完成：$jarOut"
