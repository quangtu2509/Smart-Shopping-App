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
$script:names = @('GOOGLE_WEB_CLIENT_ID','SMTP_HOST','SMTP_PORT','SMTP_USERNAME','SMTP_PASSWORD','AUTH_CHALLENGE_KEY','DB_PASSWORD')
function Assert-True([bool]$Condition, [string]$Label) { if (-not $Condition) { throw $Label } }
function Reset-Fixture {
    $global:SmtpFixtureState = @{}
    $global:SmtpFixtureState.exists = $false; $global:SmtpFixtureState.exported = $null; $global:SmtpFixtureState.prompts = @(); $global:SmtpFixtureState.senderAnswer = 'sender@example.test'
    $global:SmtpFixtureState.legacyConfig = [pscustomobject]@{Sender='sender@example.test';WebClientId='fixture-client';
        Password=(ConvertTo-SecureString 'fixture-app-secret' -AsPlainText -Force);
        ChallengeKey=(ConvertTo-SecureString ([Convert]::ToBase64String((New-Object byte[] 32))) -AsPlainText -Force)}
    foreach ($name in $script:names) { [Environment]::SetEnvironmentVariable($name,('before-' + $name),'Process') }
}
function Test-Path { param([string]$LiteralPath) return $global:SmtpFixtureState.exists }
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
function Import-Clixml { param([string]$LiteralPath) return $global:SmtpFixtureState.legacyConfig }
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

    Reset-Fixture; $global:SmtpFixtureState.exists = $true
    [IO.File]::WriteAllText($script:stubPath, "@echo off`r`nexit /b 9`r`n")
    Expect-Failure { & $script:fixtureScript -Action Run } 'Backend stopped with an error'
    Assert-EnvironmentRestored
    $script:passed++
    Write-Output ("Passed {0} SMTP configuration checks using fake config and a non-Gradle stub." -f $script:passed)
} finally {
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $prefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^smart-shopping-smtp-tests-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
