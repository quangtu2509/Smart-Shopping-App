[CmdletBinding()]
param(
    [ValidateSet('Configure','Run')][string]$Action = 'Run',
    [string]$Sender = 'nguyeenxtask@gmail.com',
    [string]$WebClientId = '803153983048-beki7emtkn0ph45sjm5bs4emud5mkn1o.apps.googleusercontent.com'
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$privatePath = Join-Path $backendRoot '.env.local.xml'

function Get-SecretText([Security.SecureString]$Secret) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

if ($Action -eq 'Configure') {
    if (Test-Path -LiteralPath $privatePath) { throw 'Local configuration exists. Keep it to preserve the challenge key; remove it yourself only if intentionally replacing credentials.' }
    $password = Read-Host 'Gmail app password (hidden; not your Google account password)' -AsSecureString
    if ($password.Length -eq 0) { throw 'App password is required.' }
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $key = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    [PSCustomObject]@{Sender=$Sender; WebClientId=$WebClientId; Password=$password; ChallengeKey=$key} |
        Export-Clixml -LiteralPath $privatePath -Encoding UTF8
    Write-Output 'Saved local configuration. Secrets use Windows DPAPI for the current user. Do not share this file.'
    return
}
if (-not (Test-Path -LiteralPath $privatePath)) { throw 'Run this script with -Action Configure first.' }
$config = Import-Clixml -LiteralPath $privatePath
$names = @('GOOGLE_WEB_CLIENT_ID','SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD','AUTH_CHALLENGE_KEY','DB_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:GOOGLE_WEB_CLIENT_ID = $config.WebClientId
    $env:SMTP_HOST = 'smtp.gmail.com'
    $env:SMTP_PORT = '587'
    $env:SMTP_USERNAME = $config.Sender
    $env:SMTP_PASSWORD = (Get-SecretText $config.Password) -replace ' ',''
    $env:AUTH_CHALLENGE_KEY = Get-SecretText $config.ChallengeKey
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
        $dbSecret = Read-Host 'PostgreSQL password for smart_shopping_app (hidden)' -AsSecureString
        $env:DB_PASSWORD = Get-SecretText $dbSecret
    }
    Push-Location -LiteralPath $backendRoot
    try { & .\gradlew.bat run; if ($LASTEXITCODE -ne 0) { throw 'Backend stopped with an error.' } }
    finally { Pop-Location }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
