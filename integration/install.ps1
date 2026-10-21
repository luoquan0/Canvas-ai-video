param(
    [Parameter(Mandatory = $true)]
    [string]$TomcatPath
)

$ErrorActionPreference = 'Stop'
$tomcat = (Resolve-Path -LiteralPath $TomcatPath -ErrorAction Stop).ProviderPath
$webapps = Join-Path $tomcat 'webapps'
$root = Join-Path $webapps 'ROOT'
$assets = Join-Path $root 'assets'
$index = Join-Path $root 'index.html'
$source = Join-Path $PSScriptRoot 'runninghub-settings.js'
$war = Join-Path $PSScriptRoot 'runninghub.war'
$installedJs = Join-Path $assets 'runninghub-settings.js'
$installedWar = Join-Path $webapps 'runninghub.war'
$backup = Join-Path $root 'index.html.before-runninghub.bak'
$marker = '<script defer src="/assets/runninghub-settings.js"></script>'

# ASCII-only source text: compatible with Windows PowerShell 5.1 ANSI file decoding.
if (-not (Test-Path -LiteralPath $index -PathType Leaf)) { throw "Tomcat ROOT index.html not found: $index" }
if (-not (Test-Path -LiteralPath $assets -PathType Container)) { throw "Tomcat ROOT assets directory not found: $assets" }
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Installer asset missing: $source" }
if (-not (Test-Path -LiteralPath $war -PathType Leaf)) { throw "Installer WAR missing: $war" }

$original = [IO.File]::ReadAllText($index, [Text.Encoding]::UTF8)
$needsInjection = -not $original.Contains($marker)
if ($needsInjection -and -not $original.Contains('</head>')) { throw 'Unexpected index.html: missing closing head tag' }

# Preserve the upstream index only once. Subsequent runs update files without duplicating the tag.
if ($needsInjection -and -not (Test-Path -LiteralPath $backup)) {
    [IO.File]::Copy($index, $backup)
}

# Install independent application and UI asset before enabling the script in index.html.
[IO.File]::Copy($war, $installedWar, $true)
[IO.File]::Copy($source, $installedJs, $true)
if ($needsInjection) {
    $replacement = '    ' + $marker + [Environment]::NewLine + '  </head>'
    $updated = $original.Replace('</head>', $replacement)
    [IO.File]::WriteAllText($index, $updated, (New-Object System.Text.UTF8Encoding($false)))
}

Write-Host ''
Write-Host 'Canvas RunningHub extension installed.' -ForegroundColor Green
Write-Host 'Restart the original Tomcat, then open: http://localhost:18080/'
Write-Host 'Go to Settings > Video Generation API > RunningHub Cloud Workflow.'
Write-Host "Original index backup: $backup"