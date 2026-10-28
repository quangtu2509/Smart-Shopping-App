[CmdletBinding()]
param(
    [string]$Serial='emulator-5554',
    [int]$TimeoutSeconds=180
)
$ErrorActionPreference='Stop'
$frontendRoot=Split-Path -Parent $PSScriptRoot
$outputRoot=Join-Path $frontendRoot 'docs\catalog\evidence\pixel-9a'
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if(-not (Test-Path -LiteralPath $adb)) {throw 'ADB not found in the local Android SDK.'}
if($TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 600) {throw 'Timeout must be 1 to 600 seconds.'}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$remote='/sdcard/Android/data/com.toked.smartshopping/files/catalog01-evidence'
$watch=[Diagnostics.Stopwatch]::StartNew()
$last=''
while($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    try {$request=(& $adb -s $Serial shell cat "$remote/capture-request.txt" 2>$null) -join ''}
    catch {$request=''} # A missing marker is normal before/between tests (Windows PowerShell native stderr is terminating).
    if($LASTEXITCODE -eq 0 -and $request -match '^(catalog-[a-z0-9-]{1,80})\|([a-f0-9]{32})$' -and $request -ne $last) {
        $name=$Matches[1];$token=$Matches[2]
        & $adb -s $Serial shell screencap -p /sdcard/catalog-fixture-capture.png
        if($LASTEXITCODE -ne 0) {throw 'Native screenshot failed.'}
        $target=Join-Path $outputRoot ($name+'.png')
        & $adb -s $Serial pull /sdcard/catalog-fixture-capture.png $target | Out-Null
        if($LASTEXITCODE -ne 0) {throw 'Screenshot copy failed.'}
        $bytes=[IO.File]::ReadAllBytes($target)
        if($bytes.Length -lt 100 -or [BitConverter]::ToString($bytes[0..7]) -ne '89-50-4E-47-0D-0A-1A-0A') {throw 'Invalid screenshot PNG.'}
        & $adb -s $Serial shell "echo $token > $remote/capture-ack-$token.txt"
        if($LASTEXITCODE -ne 0) {throw 'Screenshot acknowledgement failed.'}
        $last=$request
        Write-Output ('Saved '+$name+'.png')
    }
    Start-Sleep -Milliseconds 200
}
Write-Output 'Catalog evidence capture window ended.'
