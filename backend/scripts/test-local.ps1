[CmdletBinding()]
param([ValidateSet('Start','Stop')][string]$Action = 'Start')
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$pgBin = 'C:\Program Files\PostgreSQL\18\bin'
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$cluster = Join-Path $temporaryRoot ('smart-shopping-auth00-' + [guid]::NewGuid().ToString('N'))
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $backendRoot)
$stateDirectory = Join-Path $workspaceRoot '.agent-output'
$statePath = Join-Path $stateDirectory 'auth00-postgres.json'
function Invoke-PgTool {
    param([string]$Name,[string[]]$Arguments)
    New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
    $stdout = Join-Path $stateDirectory ($Name + '.stdout.txt')
    $stderr = Join-Path $stateDirectory ($Name + '.stderr.txt')
    $process = Start-Process -FilePath (Join-Path $pgBin $Name) -ArgumentList $Arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $nativeHandle = $process.Handle
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw "$Name timed out." }
    if ($process.ExitCode -ne 0) { throw "$Name failed. Inspect local tool output; check PostgreSQL 18 and port 55432." }
}
if ($Action -eq 'Start') {
    if (Test-Path -LiteralPath $statePath) { throw 'A test cluster is recorded. Stop it first.' }
    New-Item -ItemType Directory -Path $cluster -Force | Out-Null
    Invoke-PgTool 'initdb.exe' @('-D',$cluster,'-U','postgres','--auth=trust','--encoding=UTF8','--locale=C')
    New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
    @{cluster=$cluster;port=55432} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    Invoke-PgTool 'pg_ctl.exe' @('-D',$cluster,'-l',(Join-Path $cluster 'server.log'),'-o','"-p 55432 -h 127.0.0.1"','-w','start')
    Invoke-PgTool 'psql.exe' @('-h','127.0.0.1','-p','55432','-U','postgres','-d','postgres','-w','-v','ON_ERROR_STOP=1','-c','"CREATE ROLE auth00_test LOGIN;"')
    Invoke-PgTool 'psql.exe' @('-h','127.0.0.1','-p','55432','-U','postgres','-d','postgres','-w','-v','ON_ERROR_STOP=1','-c','"CREATE DATABASE auth00_test OWNER auth00_test;"')
    Write-Output 'Isolated PostgreSQL ready at 127.0.0.1:55432/auth00_test. Run backend tests, then Stop.'
} else {
    if (-not (Test-Path -LiteralPath $statePath)) { throw 'No test cluster recorded.' }
    $state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    $cluster = $state.cluster
    $resolvedCluster = [System.IO.Path]::GetFullPath($cluster)
    if ($resolvedCluster.StartsWith($temporaryRoot,[StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedCluster -Leaf) -match '^smart-shopping-auth00-[a-f0-9]{32}$' -and $state.port -eq 55432) {
        if (Test-Path -LiteralPath (Join-Path $cluster 'postmaster.pid')) { Invoke-PgTool 'pg_ctl.exe' @('-D',$cluster,'-w','stop','-m','fast') }
        if (Test-Path -LiteralPath $resolvedCluster) { Remove-Item -LiteralPath $resolvedCluster -Recurse -Force }
        Remove-Item -LiteralPath $statePath
        Write-Output 'Stopped and removed only the recorded PostgreSQL test cluster.'
    } else { throw 'Refusing cleanup outside the generated test directory.' }
}
