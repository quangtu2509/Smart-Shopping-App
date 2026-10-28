[CmdletBinding()]
param(
    [ValidateSet('Configure', 'Probe')][string]$Action = 'Probe',
    [string]$BaseUrl = 'https://crawl-data-rosy.vercel.app',
    [switch]$Replace
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$privatePath = Join-Path $backendRoot '.env.catalogflow.local.xml'
$reportPath = Join-Path $backendRoot 'build\provider\catalogflow-probe.json'

function Get-ValidatedOrigin([string]$Value) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        $uri.AbsolutePath -ne '/' -or $uri.Query -ne '' -or $uri.Fragment -ne '') {
        throw 'BaseUrl must be an HTTPS origin without credentials, path, query or fragment.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

function Get-SecretText([Security.SecureString]$Secret) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

# Keep field names/types only. Product values, response bodies and headers are never saved.
function Get-Shape($Value, [int]$Depth = 0) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [string]) { return 'string' }
    if ($Value -is [bool]) { return 'boolean' }
    if ($Value -is [ValueType]) { return 'number' }
    if ($Depth -ge 4) { return 'nested' }
    if ($Value -is [array]) {
        if ($Value.Count -eq 0) { return 'array(empty)' }
        return [ordered]@{ array_item = (Get-Shape $Value[0] ($Depth + 1)) }
    }
    $shape = [ordered]@{}
    foreach ($property in @($Value.PSObject.Properties | Select-Object -First 100)) {
        if ($property.Name -match '^[a-zA-Z_][a-zA-Z0-9_]{0,79}$' -and
            $property.Name -notmatch '(?i)secret|password|token|authorization|cookie|api_?key') {
            $shape[$property.Name] = Get-Shape $property.Value ($Depth + 1)
        }
    }
    return $shape
}

if ($Action -eq 'Configure') {
    $origin = Get-ValidatedOrigin $BaseUrl
    if ((Test-Path -LiteralPath $privatePath) -and -not $Replace) {
        throw 'CatalogFlow configuration exists. Use -Action Probe, or -Action Configure -Replace after rotating your key.'
    }
    $secret = Read-Host 'CatalogFlow API key (hidden; paste here, never in chat)' -AsSecureString
} else {
    if ($Replace -or $PSBoundParameters.ContainsKey('BaseUrl')) {
        throw 'BaseUrl and Replace apply only to Configure.'
    }
    if (-not (Test-Path -LiteralPath $privatePath)) { throw 'Run this script with -Action Configure first.' }
    try { $config = Import-Clixml -LiteralPath $privatePath }
    catch { throw 'Cannot read CatalogFlow configuration. Use the Windows account that configured it.' }
    $origin = Get-ValidatedOrigin $config.BaseUrl
    $secret = $config.ApiKey
}
if ($secret -isnot [Security.SecureString] -or $secret.Length -eq 0) { throw 'An API key is required.' }

$plain = $null
$headers = @{}
try {
    $plain = Get-SecretText $secret
    if ($plain -match '\s' -or $plain -match '[\x00-\x1f\x7f]') { throw 'API key must not contain whitespace or control characters.' }
    $headers = @{ Authorization = ('Bearer ' + $plain); Accept = 'application/json' }
    try {
        $response = Invoke-WebRequest -Uri ($origin + '/api/v1/live-products?page=1&limit=1') `
            -Method Get -Headers $headers -UseBasicParsing -TimeoutSec 20 -MaximumRedirection 0 -ErrorAction Stop
    } catch {
        $status = 0
        if ($null -ne $_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        switch ($status) {
            401 { throw 'CatalogFlow HTTP 401: API key rejected. Check the key or configure again after rotating it.' }
            403 { throw 'CatalogFlow HTTP 403: account/key does not have access.' }
            429 { throw 'CatalogFlow HTTP 429: rate limit reached. Wait before probing again.' }
            default { throw ('CatalogFlow request failed (HTTP {0}; 0 means network/TLS/timeout). Redirects are disabled. No configuration was changed.' -f $status) }
        }
    }
    if ([int]$response.StatusCode -ne 200) { throw 'CatalogFlow did not return HTTP 200. No configuration was changed.' }
    if ($response.Content.Length -gt 2097152) { throw 'CatalogFlow response exceeds the probe size limit.' }
    try { $page = $response.Content | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'CatalogFlow returned invalid JSON. No configuration was changed.' }
    if ($page.items -isnot [array] -or $page.items.Count -gt 1 -or
        ($page.total -isnot [int] -and $page.total -isnot [long]) -or $page.total -lt $page.items.Count -or
        $page.page -ne 1 -or $page.limit -ne 1) {
        throw 'CatalogFlow response does not match items/total/page/limit for the requested page.'
    }
    if ($page.items.Count -gt 0 -and $page.items[0] -isnot [PSCustomObject]) { throw 'CatalogFlow item must be a JSON object.' }
    if ($Action -eq 'Configure') {
        # Windows PowerShell encrypts SecureString with DPAPI for this user on this machine.
        [PSCustomObject]@{ BaseUrl = $origin; ApiKey = $secret } |
            Export-Clixml -LiteralPath $privatePath -Encoding UTF8
        Write-Output 'Saved CatalogFlow key encrypted for the current Windows user. Existing auth configuration was not changed.'
    }
    $shape = $null
    if ($page.items.Count -gt 0) { $shape = Get-Shape $page.items[0] }
    $report = [ordered]@{
        checked_at = [DateTime]::UtcNow.ToString('o')
        origin = $origin
        status = 200
        total = $page.total
        sample_count = $page.items.Count
        item_shape = $shape
        scope = 'Connection and field types only; product/price semantics are not verified.'
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $reportPath) -Force | Out-Null
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Output ('CatalogFlow connected: HTTP 200; {0} approved products; {1} sampled.' -f $page.total, $page.items.Count)
    if ($page.items.Count -eq 0) { Write-Output 'Approve at least one product on CatalogFlow, then run -Action Probe again.' }
    Write-Output 'Saved field-name/type report: build\provider\catalogflow-probe.json. No API key or product values in this report.'
    Write-Output 'This checks the provider only. Android catalog integration is a separate implementation step.'
} finally {
    $headers.Clear()
    $plain = $null
    if ($secret -is [Security.SecureString]) { $secret.Dispose() }
}
