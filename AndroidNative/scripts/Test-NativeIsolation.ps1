param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$CacheRoot
)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskCache = [IO.Path]::GetFullPath($CacheRoot)
if ([IO.Path]::GetPathRoot($taskCache) -eq 'C:\') { throw 'Use a non-C drive for native build tests.' }
$taskClasses = Join-Path $taskCache 'classes'
$taskTemp = Join-Path $taskCache 'temp'
New-Item -ItemType Directory -Path $taskClasses, $taskTemp -Force | Out-Null
# Chromium/Robolectric tests at tests/ have their own Android build targets.
$taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'java'), (Join-Path $taskRoot 'tests\com') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') '-J-Dfile.encoding=UTF-8' "-J-Djava.io.tmpdir=$taskTemp" --release 11 -encoding UTF-8 -d $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Native environment core compilation failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentLeaseStoreTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment isolation tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentCatalogTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment catalog tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentRecycleTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment recycle tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentOrganizationTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment organization tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentTransferTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native configuration transfer tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentTransferControllerTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native configuration transfer lifecycle tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.FingerprintSettingsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native fingerprint settings tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.UserAgentAnimationSettingsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native UA-CH and RAF persistence tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentShutdownCoordinatorTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment shutdown tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentSaveBarrierTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native window save barrier tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentLegacySaveBarrierTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native legacy store save barrier tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentNetworkSettingsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment network settings tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.ProxyCredentialsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native proxy credential tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.Socks5AuthenticationTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native SOCKS5 authentication wire and input tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.TotpTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native TOTP tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.AccountCredentialsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native encrypted account tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.CookieTransferStreamsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Cookie transfer stream tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentRegionSettingsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Region settings tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentGeolocationSettingsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment geolocation settings tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.CanvasSettingsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment canvas settings tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.DoNotTrackSettingsTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native environment Do Not Track settings tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentAndroidIdentityTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native Android identity settings tests failed.' }
& (Join-Path $JavaHome 'bin\java.exe') "-Djava.io.tmpdir=$taskTemp" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $taskClasses com.landu.browser.nativecore.EnvironmentGpuMetadataTest $taskCache
if ($LASTEXITCODE -ne 0) { throw 'Native GPU metadata settings tests failed.' }
