[CmdletBinding()]
param(
    [ValidateSet('Configure','Run','CheckCatalog','SyncCatalog')][string]$Action = 'Run',
    [Alias('Sender')][string]$SenderEmail = '',
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

function Import-CatalogFlowEnvironment {
    # Explicit process credentials take precedence. Do not print or pass the key as a CLI argument.
    if (-not [string]::IsNullOrWhiteSpace($env:CATALOGFLOW_API_KEY)) { return }
    $providerPath = Join-Path $backendRoot '.env.catalogflow.local.xml'
    if (-not (Test-Path -LiteralPath $providerPath)) { return }
    try { $providerConfig = Import-Clixml -LiteralPath $providerPath }
    catch { throw 'Cannot load CatalogFlow configuration. Use the Windows user that configured it.' }
    if ($providerConfig.ApiKey -isnot [Security.SecureString] -or $providerConfig.ApiKey.Length -eq 0) {
        throw 'Invalid local CatalogFlow configuration. Run catalogflow-local.ps1 -Action Configure -Replace.'
    }
    $providerUri = $null
    if (-not [Uri]::TryCreate($providerConfig.BaseUrl,[UriKind]::Absolute,[ref]$providerUri) -or
        $providerUri.Scheme -ne 'https' -or $providerUri.UserInfo -ne '' -or $providerUri.AbsolutePath -ne '/' -or
        $providerUri.Query -ne '' -or $providerUri.Fragment -ne '') { throw 'Invalid CatalogFlow HTTPS origin.' }
    if (-not [string]::IsNullOrWhiteSpace($env:CATALOGFLOW_BASE_URL) -and
        $env:CATALOGFLOW_BASE_URL.TrimEnd('/') -ne $providerConfig.BaseUrl.TrimEnd('/')) {
        throw 'CATALOGFLOW_BASE_URL differs from the saved key origin. Configure both credentials explicitly.'
    }
    try {
        $env:CATALOGFLOW_BASE_URL = $providerConfig.BaseUrl
        $env:CATALOGFLOW_API_KEY = Get-SecretText $providerConfig.ApiKey
    } finally { $providerConfig.ApiKey.Dispose() }
}

if ($Action -in @('CheckCatalog','SyncCatalog')) {
    $providerNames = @('CATALOGFLOW_API_KEY','CATALOGFLOW_BASE_URL','DB_PASSWORD')
    $providerPrevious = @{}
    foreach ($name in $providerNames) { $providerPrevious[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    try {
        Import-CatalogFlowEnvironment
        if ([string]::IsNullOrWhiteSpace($env:CATALOGFLOW_API_KEY)) { throw 'Configure CatalogFlow using catalogflow-local.ps1 -Action Configure first.' }
        if ($Action -eq 'SyncCatalog' -and [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
            $dbSecret = Read-Host 'PostgreSQL password for smart_shopping_app (hidden)' -AsSecureString
            $env:DB_PASSWORD = Get-SecretText $dbSecret
        }
        Push-Location -LiteralPath $backendRoot
        try {
            $catalogArgument = if ($Action -eq 'SyncCatalog') { '--args=--sync-catalog' } else { '--args=--check-catalog' }
            & .\gradlew.bat run $catalogArgument
            if ($LASTEXITCODE -ne 0) { throw 'CatalogFlow Kotlin check failed. See the safe failure code above.' }
        }
        finally { Pop-Location }
    } finally {
        foreach ($name in $providerNames) { [Environment]::SetEnvironmentVariable($name,$providerPrevious[$name],'Process') }
    }
    return
}

if ($Action -eq 'Configure') {
    if (Test-Path -LiteralPath $privatePath) { throw 'Local configuration exists. Keep it to preserve the challenge key; remove it yourself only if intentionally replacing credentials.' }
    if ([string]::IsNullOrWhiteSpace($SenderEmail)) { $SenderEmail = Read-Host 'Gmail sender email' }
    $SenderEmail = $SenderEmail.Trim()
    try { $address = New-Object System.Net.Mail.MailAddress($SenderEmail) }
    catch { throw 'A valid sender email is required.' }
    if ($address.Address -ne $SenderEmail -or $address.Host -notmatch '\.') { throw 'A valid sender email is required.' }
    $password = Read-Host 'Gmail app password (hidden; not your Google account password)' -AsSecureString
    if ($password.Length -eq 0) { throw 'App password is required.' }
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $key = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    [PSCustomObject]@{Sender=$SenderEmail; WebClientId=$WebClientId; Password=$password; ChallengeKey=$key} |
        Export-Clixml -LiteralPath $privatePath -Encoding UTF8
    Write-Output 'Saved local configuration. Secrets use Windows DPAPI for the current user. Do not share this file.'
    return
}
if (-not (Test-Path -LiteralPath $privatePath)) { throw 'Run this script with -Action Configure first.' }
$config = Import-Clixml -LiteralPath $privatePath
$names = @('GOOGLE_WEB_CLIENT_ID','SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD','AUTH_CHALLENGE_KEY','DB_PASSWORD','CATALOGFLOW_API_KEY','CATALOGFLOW_BASE_URL')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    Import-CatalogFlowEnvironment
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
