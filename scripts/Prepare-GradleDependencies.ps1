<#
Downloads this project's Gradle distribution and dependencies from PowerShell,
then builds the debug APK to populate the normal Gradle cache.

Run from any directory:
  powershell -ExecutionPolicy Bypass -File .\scripts\Prepare-GradleDependencies.ps1

Optional:
  -ProjectDirectory C:\path\to\AndroidPoseRecorder
  -GradleUserHome C:\Users\you\.gradle
#>
[CmdletBinding()]
param(
    [string]$ProjectDirectory,
    [string]$GradleUserHome = (Join-Path $env:USERPROFILE '.gradle')
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectDirectory)) {
    $ScriptPath = $MyInvocation.MyCommand.Path
    if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
        throw 'Could not determine script path. Pass -ProjectDirectory explicitly.'
    }
    $ProjectDirectory = Split-Path -Parent (Split-Path -Parent $ScriptPath)
}
$ProjectDirectory = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$GradleUserHome = [IO.Path]::GetFullPath($GradleUserHome)
$WrapperProperties = Join-Path $ProjectDirectory 'gradle\wrapper\gradle-wrapper.properties'
if (-not (Test-Path -LiteralPath $WrapperProperties)) {
    throw "Gradle wrapper properties not found: $WrapperProperties"
}

# Keep downloads under a task-specific directory rather than changing machine settings.
$DownloadRoot = Join-Path $ProjectDirectory '.gradle-manual-downloads'
New-Item -ItemType Directory -Force -Path $DownloadRoot | Out-Null
$env:GRADLE_USER_HOME = $GradleUserHome

# Use a JDK supported by current Android Gradle Plugin versions.
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $JdkCandidates = @(
        'C:\Program Files\Java\jdk-21',
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Eclipse Adoptium\jdk-17'
    )
    $Jdk = $JdkCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
    if (-not $Jdk) { throw 'JDK 17 or newer was not found. Install a JDK, then set JAVA_HOME and rerun.' }
    $env:JAVA_HOME = $Jdk
}

$GradleVersion = ([regex]::Match((Get-Content -Raw -LiteralPath $WrapperProperties), 'gradle-(\d+\.\d+(?:\.\d+)?)-bin\.zip')).Groups[1].Value
if (-not $GradleVersion) { throw 'Could not read Gradle distribution version from wrapper properties.' }

# The wrapper downloads Gradle into GRADLE_USER_HOME and resolves all project
# plugins and Maven dependencies while executing assembleDebug.
$Wrapper = Join-Path $ProjectDirectory 'gradlew.bat'
if (-not (Test-Path -LiteralPath $Wrapper)) { throw "Gradle wrapper not found: $Wrapper" }
Push-Location $ProjectDirectory
try {
    Write-Host "Project: $ProjectDirectory"
    Write-Host "Gradle cache: $GradleUserHome"
    Write-Host "JDK: $env:JAVA_HOME"
    Write-Host "Preparing Gradle $GradleVersion and project dependencies..."
    & $Wrapper assembleDebug --refresh-dependencies
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE. See the error above." }
} finally {
    Pop-Location
}

$Apk = Join-Path $ProjectDirectory 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $Apk)) { throw "Build reported success but APK was not found: $Apk" }
Write-Host ''
Write-Host 'Dependency preparation and debug build completed.' -ForegroundColor Green
Write-Host "APK: $Apk"
Write-Host "Gradle cache: $GradleUserHome"
