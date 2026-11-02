param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$CacheRoot,
    [string]$GradleExecutable,
    [string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug', 'assembleDebugAndroidTest')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $CacheRoot) { $CacheRoot = Join-Path $projectRoot '.local\cache' }
$CacheRoot = [IO.Path]::GetFullPath($CacheRoot)
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Specify -JavaHome with a JDK 17 or 21 installation.'
}
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-35\android.jar'))) {
    throw 'Specify -AndroidSdk with Android platform 35 and Build Tools 35.0.0 installed.'
}
$aliases = [Collections.Generic.List[string]]::new()
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME', 'ANDROID_USER_HOME', 'TEMP', 'TMP', 'TMPDIR', 'JAVA_TOOL_OPTIONS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Use-AsciiDirectory([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute -notmatch '[^\x00-\x7F]') { return $absolute }
    foreach ($letter in @('Z', 'Y', 'X', 'W', 'V', 'U', 'T', 'S', 'R')) {
        $drive = "${letter}:"
        if ([Environment]::GetLogicalDrives() -contains "$drive\") { continue }
        & subst.exe $drive $absolute
        if ($LASTEXITCODE -ne 0) { throw "Cannot map $absolute to an unused drive." }
        $aliases.Add($drive)
        Write-Host "Temporary build path: $drive -> $absolute"
        return "$drive\"
    }
    throw 'No unused drive letter is available for non-ASCII build paths.'
}

try {
    New-Item -ItemType Directory -Path $CacheRoot -Force | Out-Null
    $projectRoot = Use-AsciiDirectory $projectRoot
    $CacheRoot = Use-AsciiDirectory $CacheRoot
    $AndroidSdk = Use-AsciiDirectory $AndroidSdk
    $JavaHome = Use-AsciiDirectory $JavaHome
    if (-not $GradleExecutable) { $GradleExecutable = Join-Path $projectRoot 'gradlew.bat' }
    if (-not (Test-Path -LiteralPath $GradleExecutable)) { throw 'Gradle wrapper is missing. Specify -GradleExecutable with Gradle 8.11.1.' }
    $gradleDirectory = Split-Path -Parent $GradleExecutable
    if ((Split-Path -Leaf $gradleDirectory) -eq 'bin') {
        $gradleRoot = Use-AsciiDirectory (Split-Path -Parent $gradleDirectory)
        $GradleExecutable = Join-Path $gradleRoot ('bin\' + (Split-Path -Leaf $GradleExecutable))
    } else {
        $GradleExecutable = Join-Path (Use-AsciiDirectory $gradleDirectory) (Split-Path -Leaf $GradleExecutable)
    }
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:GRADLE_USER_HOME = Join-Path $CacheRoot 'gradle'
    $env:ANDROID_USER_HOME = Join-Path $CacheRoot 'android-user'
    $env:TEMP = Join-Path $CacheRoot 'temp'
    $env:TMP = $env:TEMP
    $env:TMPDIR = $env:TEMP
    $javaUserHome = Join-Path $CacheRoot 'java-user'
    foreach ($directory in @($env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, $env:TEMP, $javaUserHome)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $env:JAVA_TOOL_OPTIONS = "`"-Duser.home=$javaUserHome`" `"-Djava.io.tmpdir=$env:TEMP`" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
    $keyDirectory = Join-Path $projectRoot '.local\keys'
    New-Item -ItemType Directory -Path $keyDirectory -Force | Out-Null
    $debugKey = Join-Path $keyDirectory 'debug.keystore'
    if (-not (Test-Path -LiteralPath $debugKey)) {
        & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $debugKey -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Landu Local Debug' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Failed to generate local debug key.' }
    }
    & $GradleExecutable -p $projectRoot --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    foreach ($drive in $aliases) { & subst.exe $drive /D }
}
