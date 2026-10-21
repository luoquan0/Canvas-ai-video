param([Parameter(Mandatory = $true)][string]$PackagePath)
$ErrorActionPreference = 'Stop'
$package = (Resolve-Path -LiteralPath $PackagePath).ProviderPath
$installer = Join-Path $package 'install.ps1'
$js = Join-Path $package 'runninghub-settings.js'
$war = Join-Path $package 'runninghub.war'
if (-not (Test-Path -LiteralPath $installer)) { throw 'install.ps1 missing' }
if (-not (Test-Path -LiteralPath $js)) { throw 'JS asset missing' }
if (-not (Test-Path -LiteralPath $war)) { throw 'WAR missing' }
$script = [IO.File]::ReadAllText($installer, [Text.Encoding]::ASCII)
if ($script -match '[^\x00-\x7F]') { throw 'PowerShell 5.1 installer must be ASCII-only' }

$fixture = Join-Path $env:TEMP ('Canvas RunningHub Test ' + [guid]::NewGuid().ToString('N'))
$tomcat = Join-Path $fixture 'apache-tomcat-10.1.59'
$root = Join-Path $tomcat 'webapps\ROOT'
$assets = Join-Path $root 'assets'
$index = Join-Path $root 'index.html'
$marker = '<script defer src="/assets/runninghub-settings.js"></script>'
$seed = "<!doctype html>`n<html><head><meta charset=`"utf-8`"></head><body>upstream</body></html>"
try {
    New-Item -ItemType Directory -Path $assets -Force | Out-Null
    [IO.File]::WriteAllText($index, $seed, (New-Object System.Text.UTF8Encoding($false)))
    # Execute with Windows PowerShell 5.1 syntax/behavior, not pwsh.
    & $installer -TomcatPath $tomcat
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'First run failed' }
    $first = [IO.File]::ReadAllText($index, [Text.Encoding]::UTF8)
    if (($first.Split([string[]]@($marker), [StringSplitOptions]::None).Length - 1) -ne 1) { throw 'Script tag missing or duplicated after first installation' }
    $backup = Join-Path $root 'index.html.before-runninghub.bak'
    if (-not (Test-Path -LiteralPath $backup)) { throw 'Backup not created' }
    if ([IO.File]::ReadAllText($backup, [Text.Encoding]::UTF8) -ne $seed) { throw 'Backup content changed' }
    if ((Get-FileHash -LiteralPath (Join-Path $assets 'runninghub-settings.js') -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $js -Algorithm SHA256).Hash) { throw 'JS file hash mismatch' }
    if ((Get-FileHash -LiteralPath (Join-Path $tomcat 'webapps\runninghub.war') -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $war -Algorithm SHA256).Hash) { throw 'WAR hash mismatch' }
    & $installer -TomcatPath $tomcat
    $second = [IO.File]::ReadAllText($index, [Text.Encoding]::UTF8)
    if ($second -ne $first) { throw 'Installer is not idempotent' }
    if ([IO.File]::ReadAllText($backup, [Text.Encoding]::UTF8) -ne $seed) { throw 'Second run overwrote original backup' }
    Write-Host 'PASS: Windows PowerShell installer smoke test and repeat installation.' -ForegroundColor Green
} finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}