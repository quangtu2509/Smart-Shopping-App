[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $temporaryRoot ('smart-shopping-catalog-tests-' + [guid]::NewGuid().ToString('N'))
$fixtureScript = Join-Path $fixtureRoot 'backend with spaces\scripts\catalogflow-local.ps1'
$fixtureBackend = Split-Path -Parent (Split-Path -Parent $fixtureScript)
$privatePath = Join-Path $fixtureBackend '.env.catalogflow.local.xml'
$reportPath = Join-Path $fixtureBackend 'build\provider\catalogflow-probe.json'
New-Item -ItemType Directory -Path (Split-Path -Parent $fixtureScript) -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $backendRoot 'scripts\catalogflow-local.ps1') -Destination $fixtureScript
$global:CatalogFixtureState = @{}
$script:passed = 0
$global:CatalogFixtureState.key = 'fixture-provider-key-never-print'
$global:CatalogFixtureState.nextKey = $global:CatalogFixtureState.key
$global:CatalogFixtureState.calls = 0
$global:CatalogFixtureState.httpStatus = 0
$global:CatalogFixtureState.content = '{"items":[{"id":"product-private-value","name":"private-product-name","price":"123456.78","images":["https://images.example.test/secret.jpg"],"nested":{"stale":false},"api_key":"do-not-save-this"}],"total":3,"page":1,"limit":1}'
$validContent = $global:CatalogFixtureState.content

function Assert-True([bool]$Condition, [string]$Label) { if (-not $Condition) { throw $Label } }
function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    Assert-True $AsSecureString.IsPresent 'Key input must be hidden'
    return ConvertTo-SecureString $global:CatalogFixtureState.nextKey -AsPlainText -Force
}
function Invoke-WebRequest {
    param([string]$Uri, [string]$Method, $Headers, [switch]$UseBasicParsing,
        [int]$TimeoutSec, [int]$MaximumRedirection, [string]$ErrorAction)
    $global:CatalogFixtureState.calls++
    Assert-True ($Uri -eq 'https://crawl-data-rosy.vercel.app/api/v1/live-products?page=1&limit=1') 'Probe must request exactly one live product'
    Assert-True ($Method -eq 'Get' -and $UseBasicParsing -and $TimeoutSec -eq 20 -and $MaximumRedirection -eq 0) 'Unsafe request policy'
    Assert-True ($Headers.Authorization -eq ('Bearer ' + $global:CatalogFixtureState.nextKey)) 'Bearer must be sent in the header'
    if ($global:CatalogFixtureState.httpStatus -ne 0) {
        $exception = New-Object Exception ('Simulated unsafe provider text: ' + $global:CatalogFixtureState.key)
        if ($global:CatalogFixtureState.httpStatus -gt 0) {
            $exception | Add-Member -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{StatusCode=$global:CatalogFixtureState.httpStatus})
        }
        throw $exception
    }
    return [pscustomobject]@{StatusCode=200;Content=$global:CatalogFixtureState.content}
}
function Expect-Failure([scriptblock]$Action, [string]$Expected) {
    $message = ''
    try { & $Action | Out-Null } catch { $message = $_.Exception.Message }
    Assert-True ($message -like ('*' + $Expected + '*')) ('Expected safe error: ' + $Expected + '; actual: ' + $message.Replace($global:CatalogFixtureState.key, '[redacted]'))
    Assert-True (-not $message.Contains($global:CatalogFixtureState.key)) 'Secret leaked into error'
    $script:passed++
}
function Assert-PrivateKey([string]$Expected) {
    $stored = Import-Clixml -LiteralPath $privatePath
    Assert-True ($stored.ApiKey -is [Security.SecureString]) 'Key must deserialize as SecureString'
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($stored.ApiKey)
    try { Assert-True ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) -eq $Expected) 'DPAPI round trip failed' }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $stored.ApiKey.Dispose() }
    $xml = Get-Content -LiteralPath $privatePath -Raw
    Assert-True (-not $xml.Contains($global:CatalogFixtureState.key) -and -not $xml.Contains($Expected)) 'Plaintext key persisted'
}
try {
    Expect-Failure { & $fixtureScript -Action Probe } 'Configure first'
    foreach ($url in @('http://example.test','https://user:secret@example.test','https://example.test/path','https://example.test/?key=secret','https://example.test/#fragment')) {
        Expect-Failure { & $fixtureScript -Action Configure -BaseUrl $url } 'HTTPS origin'
    }
    Assert-True ($global:CatalogFixtureState.calls -eq 0) 'Invalid configuration must fail before network access'
    $global:CatalogFixtureState.nextKey = 'invalid key with spaces'
    Expect-Failure { & $fixtureScript -Action Configure } 'whitespace'
    $global:CatalogFixtureState.nextKey = $global:CatalogFixtureState.key

    $global:CatalogFixtureState.httpStatus = 401
    Expect-Failure { & $fixtureScript -Action Configure } 'HTTP 401'
    Assert-True (-not (Test-Path -LiteralPath $privatePath)) 'Rejected key must not be persisted'
    $global:CatalogFixtureState.httpStatus = 0
    $output = (& $fixtureScript -Action Configure) -join "`n"
    Assert-True ($output.Contains('HTTP 200') -and -not $output.Contains($global:CatalogFixtureState.key)) 'Successful output is missing or unsafe'
    Assert-PrivateKey $global:CatalogFixtureState.key
    $reportText = Get-Content -LiteralPath $reportPath -Raw
    Assert-True ($reportText -notmatch 'private-product-name|product-private-value|123456|secret.jpg|do-not-save-this|api_key') 'Report must exclude product values and credential fields'
    $report = $reportText | ConvertFrom-Json
    Assert-True ($report.total -eq 3 -and $report.sample_count -eq 1 -and $report.item_shape.price -eq 'string' -and $report.item_shape.nested.stale -eq 'boolean') 'Field/type report is incorrect'
    $script:passed++

    $before = $global:CatalogFixtureState.calls
    Expect-Failure { & $fixtureScript -Action Configure } 'configuration exists'
    Assert-True ($global:CatalogFixtureState.calls -eq $before) 'Existing key must not trigger network or overwrite'
    & $fixtureScript -Action Probe | Out-Null
    Assert-PrivateKey $global:CatalogFixtureState.key
    $script:passed++

    foreach ($status in @(401,403,429,302,500,-1)) {
        $global:CatalogFixtureState.httpStatus = $status
        $expected = 'HTTP ' + [Math]::Max($status,0)
        Expect-Failure { & $fixtureScript -Action Configure -Replace } $expected
        Assert-PrivateKey $global:CatalogFixtureState.key
    }
    $global:CatalogFixtureState.httpStatus = 0
    foreach ($invalid in @('<html>bad</html>', '{"items":{},"total":1,"page":1,"limit":1}',
        '{"items":[],"total":-1,"page":1,"limit":1}', '{"items":[],"total":0,"page":2,"limit":1}',
        '{"items":["not-an-object"],"total":1,"page":1,"limit":1}')) {
        $global:CatalogFixtureState.content = $invalid
        Expect-Failure { & $fixtureScript -Action Configure -Replace } 'CatalogFlow'
        Assert-PrivateKey $global:CatalogFixtureState.key
    }
    $global:CatalogFixtureState.content = 'x' * 2097153
    Expect-Failure { & $fixtureScript -Action Probe } 'size limit'

    $global:CatalogFixtureState.content = '{"items":[],"total":0,"page":1,"limit":1}'
    $output = (& $fixtureScript -Action Probe) -join "`n"
    Assert-True ($output.Contains('0 approved products') -and $output.Contains('Approve at least one')) 'Empty catalog must give actionable output'
    $script:passed++
    Expect-Failure { & $fixtureScript -Action Probe -Replace } 'only to Configure'
    Expect-Failure { & $fixtureScript -Action Probe -BaseUrl 'https://example.test' } 'only to Configure'

    $global:CatalogFixtureState.content = $validContent
    $global:CatalogFixtureState.nextKey = 'fixture-replacement-provider-key'
    & $fixtureScript -Action Configure -Replace | Out-Null
    Assert-PrivateKey $global:CatalogFixtureState.nextKey
    $script:passed++
    Write-Output ('Passed {0} CatalogFlow configuration checks. Fake HTTP responses and fixture keys only; Windows DPAPI round trip verified.' -f $script:passed)
} finally {
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $prefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^smart-shopping-catalog-tests-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
