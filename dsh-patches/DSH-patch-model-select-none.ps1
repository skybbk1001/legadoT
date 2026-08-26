# DSH 本地补丁：把「获取可用模型」候选默认从“全选”改为“全不选”
# 幂等：已改过则跳过。用法：
#   pwsh -File DSH-patch-model-select-none.ps1
#   pwsh -File DSH-patch-model-select-none.ps1 -Path "C:\...\client.js"

param([string]$Path)

$ErrorActionPreference = 'Stop'

$old = 'setPicked(new Set(found.filter((model) => !known.has(model.id)).map((model) => model.id)));'
$new = 'setPicked(new Set());'

function Find-Target {
    if ($Path) { return $Path }
    $root = Join-Path $env:LOCALAPPDATA 'npm-cache\_npx'
    if (-not (Test-Path $root)) { return $null }
    $hits = Get-ChildItem -Path $root -Recurse -Filter client.js -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like '*@deepseek-ai\dsh-client-ui-settings-models\lib\client.js' }
    if ($hits) { return $hits[0].FullName }
    return $null
}

$target = Find-Target
if (-not $target) {
    Write-Host '未找到 dsh-client-ui-settings-models/lib/client.js，请用 -Path 指定。'
    exit 1
}
Write-Host "目标: $target"

$text = [System.IO.File]::ReadAllText($target)

if ($text.Contains($new) -and -not $text.Contains($old)) {
    Write-Host '已应用过，跳过。'
    exit 0
}
if (-not $text.Contains($old)) {
    Write-Host '未找到目标代码行（版本可能不同或已改过），未做改动。'
    exit 2
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$updated = $text.Replace($old, $new)
[System.IO.File]::WriteAllText($target, $updated, $utf8NoBom)
Write-Host '已应用：候选模型默认改为全不选。'
