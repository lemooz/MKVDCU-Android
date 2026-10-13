param([switch]$SkipNativeBuild, [ValidateRange(1,8)][int]$Jobs = 2,
    [ValidateSet('Release','Diagnostic','Both')][string]$Variant = 'Both')
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$hostRoot = Join-Path $workspace 'targets/mkvsdcu/private/rexglue-host'
$gameBuild = Join-Path $hostRoot 'out/android-arm64'
$sdkOutput = Join-Path $workspace 'references/rexglue-sdk/out/android-arm64'
$androidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$cmake = Join-Path $androidSdk 'cmake/3.31.6/bin/cmake.exe'
$llvm = Join-Path $androidSdk 'ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin'
$staged = Join-Path $workspace 'android/app/src/main/jniLibs/arm64-v8a'
$cache = Get-Content -LiteralPath (Join-Path $gameBuild 'CMakeCache.txt')
$homeLine = $cache | Where-Object { $_.StartsWith('CMAKE_HOME_DIRECTORY:INTERNAL=') }
$configuredHome = $homeLine.Substring('CMAKE_HOME_DIRECTORY:INTERNAL='.Length)
if ([IO.Path]::GetFullPath($configuredHome) -ne [IO.Path]::GetFullPath($hostRoot)) {
    throw "Wrong CMake source tree: $configuredHome"
}
if (-not $SkipNativeBuild) {
    & $cmake --build $gameBuild --target mkvdcu rexruntime rexgpu-xenos --parallel $Jobs
    if ($LASTEXITCODE -ne 0) { throw 'Real native build failed' }
}
$libraries = @{
    'libmkvdcu.so' = Join-Path $gameBuild 'libmkvdcu.so'
    'librexruntime.so' = Join-Path $sdkOutput 'librexruntime.so'
    'librexgpu-xenos.so' = Join-Path $sdkOutput 'librexgpu-xenos.so'
}
foreach ($hook in @('main_hook','hook_impl','file_redirect_hook','gsl_alloc_hook','panvk_loader_hook','panvk_mapper_hook')) {
    $name = "lib$hook.so"
    $candidates = @(Get-ChildItem -LiteralPath $gameBuild -Filter $name -File -Recurse)
    $direct = Join-Path $sdkOutput $name
    if (Test-Path -LiteralPath $direct) { $libraries[$name] = $direct }
    elseif ($candidates.Count -eq 1) { $libraries[$name] = $candidates[0].FullName }
    else { throw "Driver hook output missing or ambiguous: $name" }
}
$symbols = 'C:/Temp/mkvdcu-start-symbols'
$licenses = Join-Path $workspace 'android/app/src/main/assets/licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
$loaderSource = Join-Path $gameBuild '_deps/adrenotools_source-src'
Copy-Item -LiteralPath (Join-Path $loaderSource 'LICENSE') -Destination (Join-Path $licenses 'libadrenotools.txt') -Force
Copy-Item -LiteralPath (Join-Path $loaderSource 'lib/linkernsbypass/LICENSE') -Destination (Join-Path $licenses 'liblinkernsbypass.txt') -Force
Copy-Item -LiteralPath (Join-Path $workspace 'MKVDCU-Recomp/references/rexglue-sdk/thirdparty/spirv-tools/LICENSE') -Destination (Join-Path $licenses 'SPIRV-Tools.txt') -Force
New-Item -ItemType Directory -Force -Path $symbols,$staged | Out-Null
foreach ($name in $libraries.Keys) {
    $source = $libraries[$name]
    Copy-Item -LiteralPath $source -Destination (Join-Path $symbols $name) -Force
    $dest = Join-Path $staged $name
    Copy-Item -LiteralPath $source -Destination $dest -Force
    & (Join-Path $llvm 'llvm-strip.exe') --strip-unneeded $dest
    if ($LASTEXITCODE -ne 0) { throw "Strip failed: $name" }
}
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
Push-Location (Join-Path $workspace 'android')
try {
    [string[]]$tasks = if ($Variant -eq 'Both') { @(':app:assembleRelease', ':app:assembleDiagnostic') }
        else { @(':app:assemble' + $Variant) }
    & ./gradlew.bat @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Gradle failed' }
} finally { Pop-Location }
$apkRoot = if ($env:MKVDCU_ANDROID_BUILD_DIR) { $env:MKVDCU_ANDROID_BUILD_DIR } else { Join-Path $workspace 'android/app/build' }
foreach ($type in @('release','diagnostic')) {
    if ($Variant -ne 'Both' -and $Variant.ToLowerInvariant() -ne $type) { continue }
    $apk = Join-Path $apkRoot "outputs/apk/$type/app-$type.apk"
    & python (Join-Path $PSScriptRoot 'verify-real-apk.py') $workspace $apk "C:/Temp/mkvdcu-$type-apk-manifest.json"
    if ($LASTEXITCODE -ne 0) { throw 'APK provenance failed' }
    Write-Output "APK: $apk"
}
