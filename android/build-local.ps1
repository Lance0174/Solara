param(
    [string]$ToolchainRoot = 'E:\GitHub\Alle-android-toolchain-20260822'
)

$ErrorActionPreference = 'Stop'
$solaraJdk = Join-Path $ToolchainRoot 'jdk\jdk-17.0.20+8'
$solaraSdk = Join-Path $ToolchainRoot 'sdk'
$solaraGradle = Join-Path $ToolchainRoot 'gradle\gradle-8.9\bin\gradle.bat'
foreach ($solaraPath in @($solaraJdk, $solaraSdk, $solaraGradle)) {
    if (-not (Test-Path -LiteralPath $solaraPath)) { throw "工具链路径不存在：$solaraPath" }
}

$solaraEnvironment = @{}
foreach ($solaraName in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME')) {
    $solaraEnvironment[$solaraName] = [Environment]::GetEnvironmentVariable($solaraName, 'Process')
}
try {
    $env:JAVA_HOME = $solaraJdk
    $env:ANDROID_HOME = $solaraSdk
    $env:ANDROID_SDK_ROOT = $solaraSdk
    $env:GRADLE_USER_HOME = Join-Path $ToolchainRoot 'gradle-home'
    & $solaraGradle -p $PSScriptRoot :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android 构建失败，Gradle 退出码：$LASTEXITCODE" }
    Write-Output (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')
} finally {
    foreach ($solaraName in $solaraEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($solaraName, $solaraEnvironment[$solaraName], 'Process')
    }
}
