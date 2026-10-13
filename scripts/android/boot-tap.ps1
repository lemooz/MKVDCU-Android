# Boots the game by tapping JOGAR reliably (finds the button via UI dump).
param([switch]$NoTap, [Nullable[bool]]$ScissorArea = $null, [Nullable[bool]]$SpirvOptimize = $null)
$ErrorActionPreference = 'Continue'

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$root = "C:\Users\pedro\OneDrive\Documentos\mkvdcu-android"

& $adb shell am force-stop org.mkvdcu.android 2>&1 | Out-Null
$launchArgs = @('shell', 'am', 'start', '-n', 'org.mkvdcu.android/.MainActivity')
if ($null -ne $ScissorArea) { $launchArgs += @('--ez', 'benchmark_scissor_area', $ScissorArea.ToString().ToLowerInvariant()) }
if ($null -ne $SpirvOptimize) { $launchArgs += @('--ez', 'benchmark_spirv_optimize', $SpirvOptimize.ToString().ToLowerInvariant()) }
& $adb @launchArgs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Launcher failed' }
Start-Sleep -Seconds 4
function Read-LauncherUi {
    $remote = '/data/local/tmp/mkvdcu-launch-ui.xml'
    & $adb shell rm -f $remote 2>&1 | Out-Null
    & $adb shell uiautomator dump $remote 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'UI dump failed' }
    $xml = (& $adb shell cat $remote) -join "`n"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($xml)) { throw 'UI dump missing or empty' }
    return $xml
}
$x = Read-LauncherUi
$m = [regex]::Match($x, 'text="JOGAR"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if (-not $m.Success) {
    & $adb shell input swipe 800 500 800 150 2>&1 | Out-Null
    Start-Sleep -Seconds 1
    $x = Read-LauncherUi
    $m = [regex]::Match($x, 'text="JOGAR"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
}
if ($m.Success) {
    $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $cy = [int]( ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    Write-Output "JOGAR at $cx, $cy"
    if (-not $NoTap) {
        & $adb shell input tap $cx $cy 2>&1 | Out-Null
        Write-Output "tapped"
    }
} else {
    throw 'JOGAR not found in fresh launcher UI'
}
