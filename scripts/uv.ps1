param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$UvArgs
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolRoot = Join-Path $repo '.local-tools'
$uv = Join-Path $toolRoot 'uv/uv.exe'
if (-not (Test-Path -LiteralPath $uv)) {
    throw "uv is not installed at $uv. Install it with UV_UNMANAGED_INSTALL set to that directory."
}

$env:UV_CACHE_DIR = Join-Path $toolRoot 'uv-cache'
$env:UV_PYTHON_INSTALL_DIR = Join-Path $toolRoot 'uv-python'
& $uv @UvArgs
exit $LASTEXITCODE
