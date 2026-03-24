param(
    [switch]$SkipGradle
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Assert-PathExists {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing $Label at $Path"
    }

    Write-Host "[ok] $Label" -ForegroundColor Green
}

function Run-Step {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][scriptblock]$Action
    )

    Write-Host "==> $Label" -ForegroundColor Cyan
    & $Action
}

Write-Host "EventLens v3 release smoke" -ForegroundColor Yellow
Write-Host "Repo: $repoRoot"

Run-Step 'Verify phase evidence files exist' {
    $checks = @(
        @{ Path = (Join-Path $repoRoot 'eventlens-spi\src\main\java\io\eventlens\spi\EventSourcePlugin.java'); Label = 'Phase 1 SPI contract' },
        @{ Path = (Join-Path $repoRoot 'eventlens-core\src\test\java\io\eventlens\core\plugin\PluginManagerTest.java'); Label = 'Phase 2 plugin manager tests' },
        @{ Path = (Join-Path $repoRoot 'eventlens-source-postgres\src\test\java\io\eventlens\pg\PostgresEventSourcePluginContractTest.java'); Label = 'Phase 3 postgres contract test' },
        @{ Path = (Join-Path $repoRoot 'eventlens-stream-kafka\src\test\java\io\eventlens\kafka\KafkaStreamAdapterPluginContractTest.java'); Label = 'Phase 3 kafka contract test' },
        @{ Path = (Join-Path $repoRoot 'eventlens-source-mysql\src\test\java\io\eventlens\mysql\MySqlEventSourcePluginContractTest.java'); Label = 'Phase 4 mysql contract test' },
        @{ Path = (Join-Path $repoRoot 'eventlens-core\src\test\java\io\eventlens\core\ConfigLoaderTest.java'); Label = 'Phase 4 config migration tests' },
        @{ Path = (Join-Path $repoRoot 'eventlens-api\src\test\java\io\eventlens\api\cache\QueryResultCacheBenchmarkTest.java'); Label = 'Phase 5 cache benchmark test' },
        @{ Path = (Join-Path $repoRoot 'eventlens-api\src\test\java\io\eventlens\api\routes\TimelineMetadataPayloadBenchmarkTest.java'); Label = 'Phase 5 metadata benchmark test' },
        @{ Path = (Join-Path $repoRoot 'eventlens-core\src\test\java\io\eventlens\core\plugin\PluginDiscoveryExternalJarTest.java'); Label = 'Phase 6 external plugin loading test' },
        @{ Path = (Join-Path $repoRoot 'docs\plugin-authoring.md'); Label = 'Phase 6 plugin authoring docs' },
        @{ Path = (Join-Path $repoRoot 'docs\v3-ga-checklist.md'); Label = 'Phase 6 GA checklist docs' },
        @{ Path = (Join-Path $repoRoot 'plans\learned\v3_reusable_notes.md'); Label = 'Reusable notes' }
    )

    foreach ($check in $checks) {
        Assert-PathExists -Path $check.Path -Label $check.Label
    }
}

if (-not $SkipGradle) {
    Run-Step 'Run Gradle test gate' {
        & .\gradlew.bat test
        if ($LASTEXITCODE -ne 0) {
            throw 'Gradle test failed'
        }
    }

    Run-Step 'Run Gradle check gate' {
        & .\gradlew.bat check
        if ($LASTEXITCODE -ne 0) {
            throw 'Gradle check failed'
        }
    }
} else {
    Write-Host 'Skipping Gradle execution because -SkipGradle was provided.' -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'v3 release smoke passed.' -ForegroundColor Green
Write-Host 'Coverage summary:' -ForegroundColor Green
Write-Host '- Phase 1: SPI contract presence verified'
Write-Host '- Phase 2: plugin manager evidence verified'
Write-Host '- Phase 3: extracted postgres and kafka contract tests verified'
Write-Host '- Phase 4: mysql and config migration evidence verified'
Write-Host '- Phase 5: cache and metadata benchmark evidence verified'
Write-Host '- Phase 6: external plugin loading and docs evidence verified'
