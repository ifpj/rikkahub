param(
    [ValidateSet('Release', 'Debug')]
    [string]$Variant = 'Release',
    [switch]$SkipWebInstall
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolRoot = Join-Path $repo '.local-tools'
$nodeRoot = Join-Path $toolRoot 'node22'
$sdkRoot = Join-Path $repo '.android-sdk'
$javaRoot = Join-Path $toolRoot 'jdk21'

if (-not (Test-Path (Join-Path $repo 'material3/material-color-utilities/kotlin/dynamiccolor/DynamicScheme.kt'))) {
    $gitCommand = Get-Command git -ErrorAction Stop
    $gitRoot = Split-Path (Split-Path $gitCommand.Source -Parent) -Parent
    $env:PATH = "$(Join-Path $gitRoot 'usr/bin');$(Join-Path $gitRoot 'mingw64/bin');$env:PATH"
    & git -C $repo submodule update --init --recursive
    if ($LASTEXITCODE -ne 0) { throw "Git submodule update failed: $LASTEXITCODE" }
}

foreach ($required in @(
    (Join-Path $javaRoot 'bin/java.exe'),
    (Join-Path $nodeRoot 'node.exe'),
    (Join-Path $sdkRoot 'platforms/android-37.2/android.jar'),
    (Join-Path $sdkRoot 'build-tools/36.0.0/aapt2.exe'),
    (Join-Path $sdkRoot 'cmake/3.22.1/bin/cmake.exe'),
    (Join-Path $sdkRoot 'ndk/28.2.13676358/source.properties')
)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing local build dependency: $required"
    }
}

$env:JAVA_HOME = $javaRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user-home'
$env:GRADLE_USER_HOME = Join-Path $repo '.gradle-home'
$env:COREPACK_HOME = Join-Path $toolRoot 'corepack-cache'
$env:NPM_CONFIG_CACHE = Join-Path $toolRoot 'npm-cache'
$env:TEMP = Join-Path $toolRoot 'tmp'
$env:TMP = $env:TEMP
$env:PATH = "$nodeRoot;$(Join-Path $sdkRoot 'platform-tools');$env:PATH"

foreach ($directory in @(
    $env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME, $env:COREPACK_HOME,
    $env:NPM_CONFIG_CACHE, $env:TEMP, (Join-Path $repo '.pnpm-store')
)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

if (-not $SkipWebInstall) {
    Push-Location (Join-Path $repo 'web-ui')
    try {
        & (Join-Path $nodeRoot 'corepack.cmd') pnpm@11.19.0 install --frozen-lockfile `
            --store-dir (Join-Path $repo '.pnpm-store')
        if ($LASTEXITCODE -ne 0) { throw "pnpm install failed: $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

$gradleArgs = @("assemble$Variant")
if ($Variant -eq 'Release') {
    $propertiesPath = Join-Path $repo 'local.properties'
    $signingProperties = @{}
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        throw 'Missing local.properties with release signing configuration.'
    }
    Get-Content -LiteralPath $propertiesPath | ForEach-Object {
        if ($_ -match '^\s*(storeFile|storePassword|keyAlias|keyPassword)=(.*)$') {
            $signingProperties[$Matches[1]] = $Matches[2]
        }
    }
    if (@('storeFile', 'storePassword', 'keyAlias', 'keyPassword') |
        Where-Object { -not $signingProperties.ContainsKey($_) }) {
        throw 'Incomplete release signing configuration in local.properties.'
    }
    $keystorePath = Join-Path (Join-Path $repo 'app') $signingProperties.storeFile
    if (-not (Test-Path -LiteralPath $keystorePath)) {
        throw "Missing release keystore: $keystorePath"
    }
}

Push-Location $repo
try {
    & (Join-Path $repo 'gradlew.bat') @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
