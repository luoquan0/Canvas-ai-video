param(
    [Parameter(Mandatory = $true)]
    [string]$TomcatPath
)
$ErrorActionPreference = 'Stop'
$tomcat = (Resolve-Path -LiteralPath $TomcatPath).Path
$root = Join-Path $tomcat 'webapps\ROOT'
$assets = Join-Path $root 'assets'
$index = Join-Path $root 'index.html'
$source = Join-Path $PSScriptRoot 'runninghub-settings.js'
$war = Join-Path $PSScriptRoot 'runninghub.war'
if (-not (Test-Path -LiteralPath $index)) { throw "未找到原版前端: $index" }
if (-not (Test-Path -LiteralPath $source)) { throw "缺少 RunningHub 集成脚本" }
if (-not (Test-Path -LiteralPath $war)) { throw "缺少 runninghub.war。请下载整合安装包，勿只下载源码。" }
$original = [IO.File]::ReadAllText($index, [Text.Encoding]::UTF8)
$marker = '<script defer src="/assets/runninghub-settings.js"></script>'
if (-not $original.Contains($marker)) {
    $backup = Join-Path $root 'index.html.before-runninghub.bak'
    if (-not (Test-Path -LiteralPath $backup)) {
        [IO.File]::Copy($index, $backup)
    }
    if (-not $original.Contains('</head>')) { throw 'index.html 缺少 </head>，已停止安装' }
    $updated = $original.Replace('</head>', '    ' + $marker + [Environment]::NewLine + '  </head>')
    [IO.File]::WriteAllText($index, $updated, [Text.UTF8Encoding]::new($false))
}
[IO.File]::Copy($source, (Join-Path $assets 'runninghub-settings.js'), $true)
[IO.File]::Copy($war, (Join-Path $tomcat 'webapps\runninghub.war'), $true)
Write-Host ''
Write-Host 'RunningHub 扩展已安装。' -ForegroundColor Green
Write-Host '请先重启 Tomcat，然后在浏览器打开系统设置 -> 视频生成接口 -> RunningHub 云端工作流。'
Write-Host '原版视频服务没有被替换，恢复可用 webapps\ROOT\index.html.before-runninghub.bak。'
