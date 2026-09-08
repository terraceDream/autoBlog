$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskState = Join-Path $taskRoot '.tools/processes.json'
if (-not (Test-Path -LiteralPath $taskState)) { Write-Host 'No recorded servers.'; exit }
foreach ($taskSaved in (Get-Content -LiteralPath $taskState -Raw | ConvertFrom-Json)) {
    $taskProcess = Get-Process -Id $taskSaved.id -ErrorAction SilentlyContinue
    if ($taskProcess -and $taskProcess.StartTime.ToUniversalTime().Ticks -eq ([datetime]$taskSaved.started).ToUniversalTime().Ticks) { Stop-Process -Id $taskProcess.Id }
}
Remove-Item -LiteralPath $taskState
Write-Host 'Issue Desk servers stopped.'
