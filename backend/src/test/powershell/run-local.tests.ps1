[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixtureRoot = Join-Path $temporaryRoot ('smart-shopping-smtp-tests-' + [guid]::NewGuid().ToString('N'))
$script:fixtureScript = Join-Path $fixtureRoot 'backend with spaces\scripts\run-local.ps1'
New-Item -ItemType Directory -Path (Split-Path -Parent $script:fixtureScript) -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $backendRoot 'scripts\run-local.ps1') -Destination $script:fixtureScript
$script:stubPath = Join-Path (Split-Path -Parent (Split-Path -Parent $script:fixtureScript)) 'gradlew.bat'
$script:passed = 0
$script:names = @('GOOGLE_WEB_CLIENT_ID','SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD','AUTH_CHALLENGE_KEY','DB_PASSWORD','CATALOGFLOW_API_KEY','CATALOGFLOW_BASE_URL')
function Assert-True([bool]$Condition, [string]$Label) { if (-not $Condition) { throw $Label } }
function Reset-Fixture {
    $global:SmtpFixtureState = @{}
    $global:SmtpFixtureState.exists = $false; $global:SmtpFixtureState.providerExists = $false; $global:SmtpFixtureState.imported = @(); $global:SmtpFixtureState.exported = $null; $global:SmtpFixtureState.prompts = @(); $global:SmtpFixtureState.senderAnswer = 'sender@example.test'
    $global:SmtpFixtureState.legacyConfig = [pscustomobject]@{Sender='sender@example.test';WebClientId='fixture-client';
        Password=(ConvertTo-SecureString 'fixture-app-secret' -AsPlainText -Force);
        ChallengeKey=(ConvertTo-SecureString ([Convert]::ToBase64String((New-Object byte[] 32))) -AsPlainText -Force)}
    foreach ($name in $script:names) { [Environment]::SetEnvironmentVariable($name,('before-' + $name),'Process') }
}
function Test-Path {
    param([string]$LiteralPath)
    if ($LiteralPath.EndsWith('.env.catalogflow.local.xml')) { return $global:SmtpFixtureState.providerExists }
    return $global:SmtpFixtureState.exists
}
function Read-Host {
    param([string]$Prompt,[switch]$AsSecureString)
    $global:SmtpFixtureState.prompts += $Prompt
    if ($AsSecureString) { return ConvertTo-SecureString 'fixture-app-secret' -AsPlainText -Force }
    return $global:SmtpFixtureState.senderAnswer
}
function Export-Clixml {
    param([Parameter(ValueFromPipeline)]$InputObject,[string]$LiteralPath,[string]$Encoding)
    process { $global:SmtpFixtureState.exported = $InputObject }
}
function Import-Clixml {
    param([string]$LiteralPath)
    $global:SmtpFixtureState.imported += $LiteralPath
    if ($LiteralPath.EndsWith('.env.catalogflow.local.xml')) {
        return [pscustomobject]@{BaseUrl='https://crawl-data-rosy.vercel.app';ApiKey=(ConvertTo-SecureString 'fixture-catalog-key' -AsPlainText -Force)}
    }
    return $global:SmtpFixtureState.legacyConfig
}
function Expect-Failure([scriptblock]$Action,[string]$Message) {
    $caught = $false
    try { & $Action | Out-Null } catch { $caught = $_.Exception.Message -like ('*' + $Message + '*') }
    Assert-True $caught 'Expected validation failure did not occur'
}
function Assert-EnvironmentRestored {
    foreach ($name in $script:names) { Assert-True ([Environment]::GetEnvironmentVariable($name,'Process') -eq ('before-' + $name)) 'Environment was not restored' }
}
try {
    Reset-Fixture
    & $script:fixtureScript -Action Configure | Out-Null
    Assert-True ($global:SmtpFixtureState.prompts[0] -eq 'Gmail sender email') 'Missing sender must be prompted before password'
    Assert-True ($global:SmtpFixtureState.exported.Sender -eq 'sender@example.test') 'Prompted sender was not saved'
    Assert-True ($global:SmtpFixtureState.exported.Password -is [Security.SecureString]) 'Password must remain a SecureString at persistence boundary'
    Assert-True ($global:SmtpFixtureState.exported.ChallengeKey -is [Security.SecureString]) 'Challenge key must remain a SecureString'
    $script:passed++

    foreach ($parameterName in @('SenderEmail','Sender')) {
        Reset-Fixture
        $arguments = @{Action='Configure'; $parameterName='sender@example.test'}
        & $script:fixtureScript @arguments | Out-Null
        Assert-True ($global:SmtpFixtureState.prompts.Count -eq 1) 'Explicit sender should not be prompted again'
        Assert-True ($global:SmtpFixtureState.exported.Sender -eq 'sender@example.test') 'Sender field must retain compatibility'
        $script:passed++
    }
    foreach ($invalid in @('invalid','', 'Display Name <sender@example.test>')) {
        Reset-Fixture
        $global:SmtpFixtureState.senderAnswer = $invalid
        Expect-Failure { & $script:fixtureScript -Action Configure -SenderEmail $invalid } 'valid sender email'
        Assert-True ($null -eq $global:SmtpFixtureState.exported) 'Invalid sender must not write configuration'
        Assert-True (-not ($global:SmtpFixtureState.prompts -like '*password*')) 'Invalid sender must fail before password prompt'
        $script:passed++
    }
    Reset-Fixture; $global:SmtpFixtureState.exists = $true
    Expect-Failure { & $script:fixtureScript -Action Configure } 'Local configuration exists'
    Assert-True ($global:SmtpFixtureState.prompts.Count -eq 0 -and $null -eq $global:SmtpFixtureState.exported) 'Existing configuration/key must be preserved'
    $script:passed++

    Reset-Fixture; $global:SmtpFixtureState.exists = $true
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nif not `"%SMTP_USERNAME%`"==`"sender@example.test`" exit /b 2`r`nif not `"%GOOGLE_WEB_CLIENT_ID%`"==`"fixture-client`" exit /b 3`r`nexit /b 0`r`n")
    & $script:fixtureScript -Action Run | Out-Null
    Assert-True ($global:SmtpFixtureState.prompts.Count -eq 0) 'Run must reuse existing configuration without prompting sender'
    Assert-EnvironmentRestored
    $script:passed++

    # Real CLI entrypoint arguments, a fake DPAPI object and a non-Gradle command stub.
    Reset-Fixture; $global:SmtpFixtureState.providerExists = $true
    $env:CATALOGFLOW_API_KEY = $null; $env:CATALOGFLOW_BASE_URL = $null
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nif not `"%CATALOGFLOW_API_KEY%`"==`"fixture-catalog-key`" exit /b 2`r`nif not `"%CATALOGFLOW_BASE_URL%`"==`"https://crawl-data-rosy.vercel.app`" exit /b 3`r`nif not `"%~1`"==`"run`" exit /b 4`r`nif not `"%~2`"==`"--args`" exit /b 5`r`nif not `"%~3`"==`"--check-catalog`" exit /b 6`r`nexit /b 0`r`n")
    & $script:fixtureScript -Action CheckCatalog | Out-Null
    Assert-True ($global:SmtpFixtureState.prompts.Count -eq 0) 'Catalog check must not prompt for auth or database secrets'
    Assert-True ($global:SmtpFixtureState.imported.Count -eq 1 -and $global:SmtpFixtureState.imported[0].EndsWith('.env.catalogflow.local.xml')) 'Catalog check must only load provider configuration'
    Assert-True ([string]::IsNullOrEmpty($env:CATALOGFLOW_API_KEY) -and [string]::IsNullOrEmpty($env:CATALOGFLOW_BASE_URL)) 'Catalog environment must be restored'
    $script:passed++

    Reset-Fixture; $global:SmtpFixtureState.providerExists = $true
    $env:CATALOGFLOW_API_KEY = $null; $env:CATALOGFLOW_BASE_URL = 'https://different.example.test'
    Expect-Failure { & $script:fixtureScript -Action CheckCatalog } 'differs from the saved key origin'
    Assert-True ($env:CATALOGFLOW_BASE_URL -eq 'https://different.example.test' -and [string]::IsNullOrEmpty($env:CATALOGFLOW_API_KEY)) 'Conflicting origin must preserve environment'
    $script:passed++

    Reset-Fixture; $global:SmtpFixtureState.providerExists = $true
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nif not `"%CATALOGFLOW_API_KEY%`"==`"before-CATALOGFLOW_API_KEY`" exit /b 2`r`nexit /b 0`r`n")
    & $script:fixtureScript -Action CheckCatalog | Out-Null
    Assert-True ($global:SmtpFixtureState.imported.Count -eq 0) 'Explicit environment credentials must take precedence'
    Assert-EnvironmentRestored
    $script:passed++

    Reset-Fixture; $global:SmtpFixtureState.exists = $true; $global:SmtpFixtureState.providerExists = $true
    $env:CATALOGFLOW_API_KEY = $null; $env:CATALOGFLOW_BASE_URL = $null
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nif not `"%CATALOGFLOW_API_KEY%`"==`"fixture-catalog-key`" exit /b 2`r`nif not `"%SMTP_USERNAME%`"==`"sender@example.test`" exit /b 3`r`nexit /b 0`r`n")
    & $script:fixtureScript -Action Run | Out-Null
    Assert-True ($global:SmtpFixtureState.imported.Count -eq 2) 'Normal backend Run must load auth and provider configuration'
    Assert-True ([string]::IsNullOrEmpty($env:CATALOGFLOW_API_KEY) -and [string]::IsNullOrEmpty($env:CATALOGFLOW_BASE_URL)) 'Run must restore provider environment'
    $script:passed++

    Reset-Fixture; $global:SmtpFixtureState.providerExists = $true
    $env:CATALOGFLOW_API_KEY = $null; $env:CATALOGFLOW_BASE_URL = $null
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nexit /b 9`r`n")
    Expect-Failure { & $script:fixtureScript -Action CheckCatalog } 'Kotlin check failed'
    Assert-True ([string]::IsNullOrEmpty($env:CATALOGFLOW_API_KEY) -and [string]::IsNullOrEmpty($env:CATALOGFLOW_BASE_URL)) 'Failed catalog check must restore environment'
    $script:passed++

    Reset-Fixture; $env:CATALOGFLOW_API_KEY = $null; $env:CATALOGFLOW_BASE_URL = $null
    Expect-Failure { & $script:fixtureScript -Action CheckCatalog } 'Configure CatalogFlow'
    $script:passed++

    Reset-Fixture; $global:SmtpFixtureState.exists = $true
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nexit /b 9`r`n")
    Expect-Failure { & $script:fixtureScript -Action Run } 'Backend stopped with an error'
    Assert-EnvironmentRestored
    $script:passed++
    Reset-Fixture; $global:SmtpFixtureState.providerExists = $true
    $env:CATALOGFLOW_API_KEY = $null; $env:CATALOGFLOW_BASE_URL = $null
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nif not `"%~3`"==`"--sync-catalog`" exit /b 2`r`nif not `"%CATALOGFLOW_API_KEY%`"==`"fixture-catalog-key`" exit /b 3`r`nif not `"%DB_PASSWORD%`"==`"before-DB_PASSWORD`" exit /b 4`r`nexit /b 0`r`n")
    & $script:fixtureScript -Action SyncCatalog | Out-Null
    Assert-True ($global:SmtpFixtureState.prompts.Count -eq 0 -and $global:SmtpFixtureState.imported.Count -eq 1) 'Sync must reuse provider and DB environment without loading auth secrets'
    Assert-True ($env:DB_PASSWORD -eq 'before-DB_PASSWORD' -and [string]::IsNullOrEmpty($env:CATALOGFLOW_API_KEY)) 'Sync must restore secrets environment'
    $script:passed++
    Write-Output ("Passed {0} auth/provider configuration checks using fake config and a non-Gradle stub." -f $script:passed)
} finally {
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $prefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^smart-shopping-smtp-tests-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
