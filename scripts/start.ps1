param([switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location $taskRoot
if (Test-Path -LiteralPath (Join-Path $taskRoot '.env')) {
    foreach ($taskLine in Get-Content -LiteralPath (Join-Path $taskRoot '.env')) {
        if ($taskLine -match '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}
if ($env:JAVA_HOME) {
    $taskJavaBin = Join-Path $env:JAVA_HOME 'bin'
    if (-not (Test-Path -LiteralPath (Join-Path $taskJavaBin 'java.exe'))) {
        throw 'JAVA_HOME must point to an installed JDK.'
    }
    $env:Path = "$taskJavaBin;$env:Path"
}
$taskFrontendPort = if ($env:FRONTEND_PORT) { [int]$env:FRONTEND_PORT } else { 5173 }
foreach ($taskPort in @(8080,$taskFrontendPort)) {
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $taskPort is in use. Stop the existing server first." }
}
if (-not $SkipInstall) {
    Push-Location (Join-Path $taskRoot 'frontend')
    try { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' } } finally { Pop-Location }
}
if (-not $SkipInstall -or -not (Test-Path (Join-Path $taskRoot 'analysis-runtime/node_modules/@openai/codex'))) {
    Push-Location (Join-Path $taskRoot 'analysis-runtime')
    try { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'Codex analysis runtime installation failed.' } } finally { Pop-Location }
}
Push-Location (Join-Path $taskRoot 'backend')
try { & .\mvnw.cmd -q '-DskipTests' package; if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' } } finally { Pop-Location }
$taskLogDir = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Force -Path $taskLogDir | Out-Null
$taskJava = (Get-Command java.exe).Source
$taskNode = (Get-Command node.exe).Source
$taskBackend = Start-Process -FilePath $taskJava -ArgumentList @('-jar','target/issue-desk-0.1.0.jar') -WorkingDirectory (Join-Path $taskRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogDir 'backend.log') -RedirectStandardError (Join-Path $taskLogDir 'backend-error.log')
$taskFrontend = Start-Process -FilePath $taskNode -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1') -WorkingDirectory (Join-Path $taskRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogDir 'frontend.log') -RedirectStandardError (Join-Path $taskLogDir 'frontend-error.log')
@(@{id=$taskBackend.Id;started=$taskBackend.StartTime.ToUniversalTime().ToString('o')},@{id=$taskFrontend.Id;started=$taskFrontend.StartTime.ToUniversalTime().ToString('o')}) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskLogDir 'processes.json')
$taskReady = $false
for ($taskAttempt=0; $taskAttempt -lt 60; $taskAttempt++) {
    try { $taskHealth = Invoke-RestMethod 'http://127.0.0.1:8080/api/health'; if ($taskHealth.status -eq 'UP') { $taskReady=$true; break } } catch {}
    Start-Sleep -Seconds 1
}
if (-not $taskReady) { throw 'Backend startup failed. See .tools/backend.log and .tools/backend-error.log.' }
Write-Host "Issue Desk is ready: http://127.0.0.1:$taskFrontendPort"
Write-Host 'Stop with: .\scripts\stop.ps1'
