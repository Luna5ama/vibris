[CmdletBinding()]
param(
    [string] $VibrisRoot = (Join-Path $PSScriptRoot "..\.."),
    [string] $IrisRoot = (Join-Path $PSScriptRoot "..\..\..\Iris"),
    [Parameter(DontShow)] [switch] $FirstPublicationRecoveryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\..\tools\git-process.ps1")

$VibrisRoot = [System.IO.Path]::GetFullPath($VibrisRoot).TrimEnd('\', '/')
$IrisRoot = [System.IO.Path]::GetFullPath($IrisRoot).TrimEnd('\', '/')
$buildRoot = Join-Path $VibrisRoot "build"
$packageScript = Join-Path $VibrisRoot "tools\package-delivery.ps1"
$buildScript = Join-Path $VibrisRoot "tools\build-delivery.ps1"
$auditScript = Join-Path $VibrisRoot "integration-tests\scripts\source-package-audit.ps1"
$probeScript = $PSCommandPath
$releaseMcp = Join-Path $VibrisRoot "mcp\out\build\Release\vibris-mcp.exe"
$protocolJar = Join-Path $VibrisRoot "protocol-java\build\libs\vibris-protocol-java.jar"
$deliveryLockPath = Join-Path $buildRoot ".delivery.lock"
$deliveryLockExistedBefore = Test-Path -LiteralPath $deliveryLockPath
$buildLockPath = Join-Path $buildRoot ".build-delivery.lock"
$buildLockExistedBefore = Test-Path -LiteralPath $buildLockPath
$receiptRoot = Join-Path $buildRoot ".delivery-receipts"
$testHooksRoot = Join-Path $buildRoot ".delivery-test-hooks"
$runId = [guid]::NewGuid().ToString("N")
$scope = Join-Path $buildRoot ".verify-delivery-transaction-$runId"
$inputRoot = Join-Path $scope "inputs"
$output = Join-Path $scope "delivery"
$freshnessRoot = Join-Path $scope "freshness"
$outsideRoot = Join-Path $VibrisRoot ".vibris\tmp\verify-delivery-junction-$runId"
$junction = Join-Path $scope "junction"
$linkInput = Join-Path $scope "link-input"
$auditLinkDelivery = Join-Path $scope "audit-link-delivery"
$auditJunction = Join-Path $scope "audit-junction"
$sentinel = Join-Path $output "sentinel.txt"
$log = [System.Collections.Generic.List[string]]::new()
$targetKey = $null
$transactionRoot = $null
$overlapKey = $null
$overlapRoot = $null
$raceRoot = $null
$replacementJob = $null
$preRecordJob = $null
$preRecordHookSession = $null
$postAuditJob = $null
$postAuditHookSession = $null
$metadataRoots = [System.Collections.Generic.List[string]]::new()
$activeReceiptSessions = [System.Collections.Generic.List[object]]::new()
$probeVibrisSource = $null
$probeIrisSource = $null

function Get-TargetKey
{
    param([Parameter(Mandatory)] [string] $Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    if ($fullPath.Length -gt $root.Length) { $fullPath = $fullPath.TrimEnd('\', '/') }
    if (Test-Path -LiteralPath $fullPath)
    {
        $fullPath = (Get-Item -LiteralPath $fullPath -Force).FullName.TrimEnd('\', '/')
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try
    {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($fullPath.ToUpperInvariant())
        return [System.Convert]::ToHexString($sha.ComputeHash($bytes))
    }
    finally
    {
        $sha.Dispose()
    }
}

function Get-Record
{
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Name)

    $item = Get-Item -LiteralPath $Path -Force
    return [ordered] @{
        name = $Name
        length = [long] $item.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash
    }
}

function Get-ProbeFileIdentityState
{
    param([Parameter(Mandatory)] [string] $Path)

    $handle = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
    try
    {
        return [pscustomobject] @{
            identity = [Vibris.DeliveryFileIdentity]::GetIdentity($handle.SafeFileHandle)
            links = [Vibris.DeliveryFileIdentity]::GetLinkCount($handle.SafeFileHandle)
        }
    }
    finally
    {
        $handle.Dispose()
    }
}

function Assert-ExactDelivery
{
    param(
        [Parameter(Mandatory)] [string] $Directory,
        [Parameter(Mandatory)] [string] $McpHash,
        [Parameter(Mandatory)] [string] $JarName,
        [Parameter(Mandatory)] [string] $JarHash
    )

    $entries = @(Get-ChildItem -LiteralPath $Directory -Force)
    if ($entries.Count -ne 2 -or @($entries | Where-Object {
        $_.PSIsContainer -or $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint
    }).Count -ne 0)
    {
        throw "Delivery fixture is not exactly two ordinary files: $Directory"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Directory "vibris-mcp.exe")).Hash -cne
        $McpHash -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Directory $JarName)).Hash -cne $JarHash)
    {
        throw "Delivery fixture hashes do not match the explicit inputs: $Directory"
    }
}

function Invoke-Package
{
    param(
        [Parameter(Mandatory)] [string] $Mcp,
        [Parameter(Mandatory)] [string] $Jar,
        [Parameter(Mandatory)] [string] $Target,
        [string] $CleanupFailure = "None",
        [ValidateSet("None", "McpHash", "VibrisRoot", "StaleMcp", "StaleIris")]
        [string] $ReceiptMutation = "None",
        [string] $ReceiptMcp,
        [string] $ReceiptJar,
        [string] $BeforeRecordReady,
        [string] $BeforeRecordAck,
        [string] $AfterAuditReady,
        [string] $AfterAuditAck,
        [ValidateRange(-1, 1)] [int] $HardLinkFailureAfter = -1,
        [switch] $PublishIdentityMismatch,
        [switch] $PublishExtraHardLink
    )

    if (-not $ReceiptMcp) { $ReceiptMcp = $Mcp }
    if (-not $ReceiptJar) { $ReceiptJar = $Jar }
    $session = New-ProbeBuildSession -Mcp $ReceiptMcp -Jar $ReceiptJar `
        -Mutation $ReceiptMutation
    try
    {
        return @(& $packageScript -McpExe $Mcp -VibrisModJar $Jar `
            -BuildReceipt $session.receipt -OutputDirectory $Target `
            -TestCleanupFailure $CleanupFailure `
            -TestBeforeRecordReady $BeforeRecordReady `
            -TestBeforeRecordAck $BeforeRecordAck `
            -TestAfterAuditReady $AfterAuditReady `
            -TestAfterAuditAck $AfterAuditAck `
            -TestHardLinkFailureAfter $HardLinkFailureAfter `
            -TestPublishIdentityMismatch:$PublishIdentityMismatch `
            -TestPublishExtraHardLink:$PublishExtraHardLink 3>&1)
    }
    finally
    {
        Remove-ProbeBuildSession -Session $session
    }
}

function Write-ZipTextEntry
{
    param(
        [Parameter(Mandatory)] [System.IO.Compression.ZipArchive] $Archive,
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [string] $Content,
        [switch] $Replace
    )

    if ($Replace)
    {
        $existing = @($Archive.Entries | Where-Object { $_.FullName -ceq $Name })
        if ($existing.Count -ne 1)
        {
            throw "Expected exactly one JAR entry to replace: $Name"
        }
        $existing[0].Delete()
    }
    $entry = $Archive.CreateEntry($Name)
    $writer = [System.IO.StreamWriter]::new(
        $entry.Open(), [System.Text.UTF8Encoding]::new($false))
    try
    {
        $writer.Write($Content)
    }
    finally
    {
        $writer.Dispose()
    }
}

function Set-IrisEmbeddedProtocol
{
    param(
        [Parameter(Mandatory)] [string] $IrisJar,
        [Parameter(Mandatory)] [string] $ProtocolJar
    )

    $archive = [System.IO.Compression.ZipFile]::Open(
        $IrisJar, [System.IO.Compression.ZipArchiveMode]::Update)
    try
    {
        $embedded = @($archive.Entries | Where-Object {
            $_.FullName -ceq "META-INF/jars/vibris-protocol-java.jar"
        })
        if ($embedded.Count -ne 1)
        {
            throw "Iris fixture must contain exactly one embedded Vibris protocol JAR."
        }
        $embedded[0].Delete()
        [void] [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive,
            $ProtocolJar,
            "META-INF/jars/vibris-protocol-java.jar",
            [System.IO.Compression.CompressionLevel]::Optimal)
    }
    finally
    {
        $archive.Dispose()
    }
}

function Assert-ExpectedFailure
{
    param(
        [Parameter(Mandatory)] [scriptblock] $Action,
        [Parameter(Mandatory)] [string] $Pattern,
        [Parameter(Mandatory)] [string] $Scenario
    )

    try
    {
        & $Action | Out-Null
        throw "$Scenario unexpectedly succeeded."
    }
    catch
    {
        if ($_.Exception.Message -notmatch $Pattern) { throw }
        $script:log.Add("PASS scenario=$Scenario rejected=true message=$($_.Exception.Message)")
    }
}

function Remove-OwnedDirectory
{
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Root)

    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $relative = [System.IO.Path]::GetRelativePath($Root, $resolved)
    if ([System.IO.Path]::IsPathRooted($relative) -or $relative -eq ".." -or
        $relative.StartsWith("..\", [System.StringComparison]::Ordinal))
    {
        throw "Refusing cleanup outside owned root: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved -Force
    if (-not $item.PSIsContainer -or $item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "Refusing recursive cleanup of non-ordinary directory: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Remove-ProbeBuildSession
{
    param([Parameter(Mandatory)] [object] $Session)

    if ($null -ne $Session.lock)
    {
        $Session.lock.Dispose()
        $Session.lock = $null
    }
    foreach ($path in @($Session.next, $Session.receipt, $Session.lock_path))
    {
        if (-not $path) { continue }
        $resolved = [System.IO.Path]::GetFullPath($path)
        $relative = [System.IO.Path]::GetRelativePath($script:receiptRoot, $resolved)
        if ([System.IO.Path]::IsPathRooted($relative) -or $relative -eq ".." -or
            $relative.StartsWith("..\", [System.StringComparison]::Ordinal))
        {
            throw "Refusing cleanup outside probe receipt root: $resolved"
        }
        if (Test-Path -LiteralPath $resolved -PathType Leaf)
        {
            Remove-Item -LiteralPath $resolved -Force
        }
    }
    [void] $script:activeReceiptSessions.Remove($Session)
}

function New-ProbeBuildSession
{
    param(
        [Parameter(Mandatory)] [string] $Mcp,
        [Parameter(Mandatory)] [string] $Jar,
        [Parameter(Mandatory)] [string] $Mutation
    )

    [void] (New-Item -ItemType Directory -Path $script:receiptRoot -Force)
    $sessionId = [guid]::NewGuid().ToString("D")
    $receiptPath = Join-Path $script:receiptRoot "$sessionId.json"
    $nextPath = "$receiptPath.next"
    $lockPath = Join-Path $script:receiptRoot "$sessionId.lock"
    $session = [pscustomobject] @{
        receipt = $receiptPath
        next = $nextPath
        lock_path = $lockPath
        lock = $null
    }
    $script:activeReceiptSessions.Add($session)
    try
    {
        $session.lock = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
        $nativeArtifacts = @(
            Get-Item -LiteralPath $Mcp -Force
            Get-Item -LiteralPath (Join-Path $script:VibrisRoot `
                "mcp\out\build\Release\vibris-descriptor-dump.exe") -Force)
        $protocolArtifact = Get-Item -LiteralPath (Join-Path $script:VibrisRoot `
            "protocol-java\build\libs\vibris-protocol-java.jar") -Force
        $irisArtifact = Get-Item -LiteralPath $Jar -Force
        $nativeStarted = ($nativeArtifacts.LastWriteTimeUtc | Sort-Object | Select-Object -First 1).AddSeconds(-1)
        $nativeCompleted = ($nativeArtifacts.LastWriteTimeUtc | Sort-Object | Select-Object -Last 1).AddSeconds(1)
        $protocolStarted = $protocolArtifact.LastWriteTimeUtc.AddSeconds(-1)
        $protocolCompleted = $protocolArtifact.LastWriteTimeUtc.AddSeconds(1)
        $irisStarted = $irisArtifact.LastWriteTimeUtc.AddSeconds(-1)
        $irisCompleted = $irisArtifact.LastWriteTimeUtc.AddSeconds(1)
        if ($Mutation -ceq "StaleMcp")
        {
            $nativeStarted = $nativeArtifacts[0].LastWriteTimeUtc.AddSeconds(10)
            $nativeCompleted = $nativeStarted.AddSeconds(1)
        }
        elseif ($Mutation -ceq "StaleIris")
        {
            $irisStarted = $irisArtifact.LastWriteTimeUtc.AddSeconds(10)
            $irisCompleted = $irisStarted.AddSeconds(1)
        }
        $phaseStarts = @($nativeStarted, $protocolStarted, $irisStarted)
        $phaseCompletions = @($nativeCompleted, $protocolCompleted, $irisCompleted)
        $buildStarted = ($phaseStarts | Sort-Object | Select-Object -First 1).AddSeconds(-1)
        $buildCompleted = ($phaseCompletions | Sort-Object | Select-Object -Last 1).AddSeconds(1)
        $receipt = [ordered] @{
            schema_version = 2
            session_id = $sessionId
            vibris_root = $script:VibrisRoot
            iris_root = $script:IrisRoot
            build_started_utc = $buildStarted.ToString("o")
            build_completed_utc = $buildCompleted.ToString("o")
            session_lock_path = $lockPath
            source = [ordered] @{
                vibris = $script:probeVibrisSource
                iris = $script:probeIrisSource
            }
            phases = [ordered] @{
                native = [ordered] @{
                    started_utc = $nativeStarted.ToString("o")
                    completed_utc = $nativeCompleted.ToString("o")
                }
                protocol = [ordered] @{
                    started_utc = $protocolStarted.ToString("o")
                    completed_utc = $protocolCompleted.ToString("o")
                }
                mod = [ordered] @{
                    started_utc = $irisStarted.ToString("o")
                    completed_utc = $irisCompleted.ToString("o")
                }
            }
            scripts = [ordered] @{
                build = Get-BuildArtifactRecord -Path $script:buildScript
                package = Get-BuildArtifactRecord -Path $script:packageScript
                git_process = Get-BuildArtifactRecord -Path (Join-Path $script:VibrisRoot "tools\git-process.ps1")
            }
            artifacts = [ordered] @{
                mcp = Get-BuildArtifactRecord -Path $Mcp
                mod = Get-BuildArtifactRecord -Path $Jar
                java_descriptor = Get-BuildArtifactRecord -Path $script:protocolJar
                cpp_descriptor = Get-BuildArtifactRecord -Path (Join-Path $script:VibrisRoot `
                    "mcp\out\build\Release\vibris-descriptor-dump.exe")
                proto = Get-BuildArtifactRecord -Path (Join-Path $script:VibrisRoot `
                    "proto\vibris_control.proto")
            }
        }
        if ($Mutation -ceq "McpHash")
        {
            $receipt.artifacts.mcp.sha256 = "0" * 64
        }
        elseif ($Mutation -ceq "VibrisRoot")
        {
            $receipt.vibris_root = (Split-Path -Parent $script:VibrisRoot)
        }
        [System.IO.File]::WriteAllText(
            $nextPath,
            ($receipt | ConvertTo-Json -Depth 10),
            [System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::Move($nextPath, $receiptPath)
        return $session
    }
    catch
    {
        Remove-ProbeBuildSession -Session $session
        throw
    }
}

try
{
    foreach ($required in @($packageScript, $buildScript, $auditScript, $releaseMcp, $protocolJar))
    {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing fixture input: $required" }
    }
    if ((Test-Path -LiteralPath $scope) -or (Test-Path -LiteralPath $outsideRoot))
    {
        throw "Probe scope already exists."
    }
    [void] (New-Item -ItemType Directory -Path $inputRoot)
    [void] (New-Item -ItemType Directory -Path $outsideRoot)

    $scripts = @($packageScript, $buildScript, $auditScript, $probeScript)
    foreach ($script in $scripts)
    {
        $tokens = $null
        $errors = $null
        [void] [System.Management.Automation.Language.Parser]::ParseFile(
            $script, [ref] $tokens, [ref] $errors)
        if ($errors.Count -ne 0) { throw "PowerShell parser errors in $script" }
        $log.Add("PASS scenario=parser script=$script sha256=$((Get-FileHash -Algorithm SHA256 $script).Hash)")
    }

    $buildText = Get-Content -Raw -LiteralPath $buildScript
    if ($buildText -notmatch '-VibrisRoot I:\\code\\vibris -IrisRoot I:\\code\\Iris')
    {
        throw "build-delivery maintained example omits mandatory roots."
    }
    $log.Add("PASS scenario=maintained_usage mandatory_roots=true")

    $protocolCompletionMatches = [regex]::Matches(
        $buildText, '(?m)^\s*\$protocolBuildCompleted = \[datetime\]::UtcNow\s*$')
    $irisCompletionIndex = $buildText.IndexOf(
        '$modBuildCompleted = [datetime]::UtcNow', [System.StringComparison]::Ordinal)
    if ($protocolCompletionMatches.Count -ne 1 -or $irisCompletionIndex -lt 0 -or
        $protocolCompletionMatches[0].Index -le $irisCompletionIndex)
    {
        throw "Protocol receipt phase must include the independent mod's composite protocol rebuild."
    }
    $log.Add("PASS scenario=composite-protocol-phase receipt_covers_final_artifact=true")

    Assert-ExpectedFailure -Scenario "missing-mandatory-mod" -Pattern "VibrisModJar" -Action {
        & $packageScript -McpExe $releaseMcp -OutputDirectory (Join-Path $scope "missing-iris") | Out-Null
    }

    $buildLockHandle = [System.IO.File]::Open(
        $buildLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None)
    try
    {
        Assert-ExpectedFailure -Scenario "build-orchestration-lock" -Pattern "already running" -Action {
            & $buildScript -VibrisRoot $VibrisRoot -IrisRoot $IrisRoot | Out-Null
        }
    }
    finally
    {
        $buildLockHandle.Dispose()
    }

    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $buildScript, [ref] $null, [ref] $null)
    foreach ($name in @(
        "Invoke-GitText", "Get-RepositorySourceState", "Get-BuildArtifactRecord",
        "Get-ModBuildFingerprint", "Resolve-RebuiltModJar"))
    {
        $functionAst = @($ast.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -ceq $name
        }, $true))
        if ($functionAst.Count -ne 1) { throw "Missing unique build function $name" }
        Invoke-Expression $functionAst[0].Extent.Text
    }
    $probeVibrisSource = Get-RepositorySourceState -Root $VibrisRoot
    $probeIrisSource = Get-RepositorySourceState -Root $IrisRoot
    [void] (New-Item -ItemType Directory -Path $freshnessRoot)
    $freshJar = Join-Path $freshnessRoot "vibris-fabric-freshness-local.jar"
    [System.IO.File]::WriteAllText($freshJar, "unchanged")
    $beforeFingerprint = Get-ModBuildFingerprint -Directory $freshnessRoot
    Assert-ExpectedFailure -Scenario "unchanged-jar-not-fresh" -Pattern "resolved 0" -Action {
        Resolve-RebuiltModJar -Directory $freshnessRoot -Before $beforeFingerprint | Out-Null
    }
    $ambiguousBefore = Get-ModBuildFingerprint -Directory $freshnessRoot
    [System.IO.File]::WriteAllText((Join-Path $freshnessRoot "vibris-fabric-a-local.jar"), "a")
    [System.IO.File]::WriteAllText((Join-Path $freshnessRoot "vibris-fabric-b-local.jar"), "b")
    Assert-ExpectedFailure -Scenario "ambiguous-fresh-jars" -Pattern "resolved 2" -Action {
        Resolve-RebuiltModJar -Directory $freshnessRoot -Before $ambiguousBefore | Out-Null
    }

    $sourceModJar = @(Get-ChildItem -LiteralPath (Join-Path $VibrisRoot "mod-fabric\build\libs") `
        -File -Filter "vibris-fabric-*.jar" | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1)
    if ($sourceModJar.Count -ne 1) { throw "Probe requires an existing independent Vibris mod JAR." }
    $vibrisModJar = Join-Path $inputRoot $sourceModJar[0].Name
    Copy-Item -LiteralPath $sourceModJar[0].FullName -Destination $vibrisModJar
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $loomProtocolJar = Join-Path $inputRoot "vibris-protocol-java-loom.jar"
    Copy-Item -LiteralPath $protocolJar -Destination $loomProtocolJar
    $loomProtocolArchive = [System.IO.Compression.ZipFile]::Open(
        $loomProtocolJar, [System.IO.Compression.ZipArchiveMode]::Update)
    try
    {
        if ($null -ne $loomProtocolArchive.GetEntry("fabric.mod.json"))
        {
            throw "Protocol fixture unexpectedly already contains fabric.mod.json."
        }
        Write-ZipTextEntry -Archive $loomProtocolArchive -Name "fabric.mod.json" -Content '{
  "schemaVersion": 1,
  "id": "dev_luna5ama_vibris-protocol-java",
  "version": "0.0.1-SNAPSHOT",
  "name": "vibris-protocol-java",
  "custom": { "fabric-loom:generated": true }
}'
    }
    finally
    {
        $loomProtocolArchive.Dispose()
    }
    Set-IrisEmbeddedProtocol -IrisJar $vibrisModJar -ProtocolJar $loomProtocolJar
    $mcpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $releaseMcp).Hash
    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $vibrisModJar).Hash
    $jarName = Split-Path -Leaf $vibrisModJar

    Assert-ExpectedFailure -Scenario "build-root-output" -Pattern "must be below" -Action {
        Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $buildRoot | Out-Null
    }

    $first = Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target ($output + "\")
    $firstText = $first -join "`n"
    $targetKey = Get-TargetKey -Path $output
    $transactionRoot = Join-Path $buildRoot ".delivery-transactions\$targetKey"
    if ($firstText -notmatch "target_sha256=$targetKey" -or
        $firstText -notmatch "loom_generated_metadata=true" -or
        $firstText -notmatch "identity_bound=true" -or
        $firstText -notmatch "hard_link_publish=true" -or
        $targetKey.Length -ne 64)
    {
        throw "Canonical full target key was not reported."
    }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash
    $log.Add("PASS scenario=immutable-snapshot-publish target_sha256=$targetKey files=2")

    $divergentRecoveryAccepted = [System.Collections.Generic.List[string]]::new()
    foreach ($recoveryState in @("PUBLISH_INTENT", "PUBLISHED"))
    {
        foreach ($identityMode in @("same", "divergent"))
        {
            $recoveryName = "$($recoveryState.ToLowerInvariant())-$identityMode"
            $recoveryOutput = Join-Path $scope "first-recovery-$recoveryName-delivery"
            $recoveryKey = Get-TargetKey -Path $recoveryOutput
            $recoveryRoot = Join-Path $buildRoot ".delivery-transactions\$recoveryKey"
            $metadataRoots.Add($recoveryRoot)
            $recoveryNext = Join-Path $recoveryRoot "next"
            [void] (New-Item -ItemType Directory -Path $recoveryNext)
            [void] (New-Item -ItemType Directory -Path $recoveryOutput)
            Copy-Item -LiteralPath $releaseMcp -Destination $recoveryNext
            Copy-Item -LiteralPath $vibrisModJar -Destination $recoveryNext

            foreach ($name in @("vibris-mcp.exe", $jarName))
            {
                $snapshotPath = Join-Path $recoveryNext $name
                $outputPath = Join-Path $recoveryOutput $name
                if ($identityMode -ceq "same")
                {
                    [Vibris.DeliveryFileIdentity]::CreateHardLink($outputPath, $snapshotPath)
                }
                else
                {
                    Copy-Item -LiteralPath $snapshotPath -Destination $outputPath
                }
                $snapshotState = Get-ProbeFileIdentityState -Path $snapshotPath
                $outputState = Get-ProbeFileIdentityState -Path $outputPath
                $identitiesEqual = $snapshotState.identity -ceq $outputState.identity
                if (($identityMode -ceq "same" -and
                        (-not $identitiesEqual -or $snapshotState.links -ne 2 -or
                            $outputState.links -ne 2)) -or
                    ($identityMode -ceq "divergent" -and $identitiesEqual))
                {
                    throw "Invalid first-publication recovery identity fixture: $recoveryName/$name"
                }
            }

            $recoveryRecord = [ordered] @{
                present = $true
                mcp = Get-Record -Path (Join-Path $recoveryNext "vibris-mcp.exe") `
                    -Name "vibris-mcp.exe"
                mod = Get-Record -Path (Join-Path $recoveryNext $jarName) -Name $jarName
            }
            $recoveryManifest = [ordered] @{
                schema_version = 2
                transaction_id = [guid]::NewGuid().ToString()
                target_path = [System.IO.Path]::GetFullPath($recoveryOutput).TrimEnd('\', '/')
                target_sha256 = $recoveryKey
                previous = [ordered] @{ present = $false }
                new = $recoveryRecord
                audit = [ordered] @{}
            }
            [System.IO.File]::WriteAllText(
                (Join-Path $recoveryRoot "manifest.json"),
                ($recoveryManifest | ConvertTo-Json -Depth 8))
            [System.IO.File]::WriteAllText(
                (Join-Path $recoveryRoot "state.txt"), $recoveryState)

            $recoveryFailure = $null
            $recoveryResult = @()
            try
            {
                $recoveryResult = @(Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar `
                    -Target $recoveryOutput)
            }
            catch
            {
                $recoveryFailure = $_
            }

            if ($identityMode -ceq "same")
            {
                if ($null -ne $recoveryFailure) { throw $recoveryFailure }
                Assert-ExactDelivery -Directory $recoveryOutput -McpHash $mcpHash `
                    -JarName $jarName -JarHash $jarHash
                if (($recoveryResult -join "`n") -notmatch "RECOVERED first_delivery=")
                {
                    throw "Same-identity first-publication recovery did not use the recovery path: $recoveryState"
                }
                $log.Add("PASS scenario=first-publication-$($recoveryState.ToLowerInvariant())-" +
                    "same-identity-recovery committed=true")
                continue
            }

            if ($null -eq $recoveryFailure)
            {
                $divergentRecoveryAccepted.Add($recoveryState)
                continue
            }
            if ($recoveryFailure.Exception.Message -notmatch
                "First-publication recovery output identity differs from its audited snapshot")
            {
                throw $recoveryFailure
            }
            if ((Test-Path -LiteralPath $recoveryOutput) -or
                (Test-Path -LiteralPath $recoveryRoot))
            {
                throw "Divergent first-publication recovery retained owned state: $recoveryState"
            }
            [void] (Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $recoveryOutput)
            Assert-ExactDelivery -Directory $recoveryOutput -McpHash $mcpHash `
                -JarName $jarName -JarHash $jarHash
            $log.Add("PASS scenario=first-publication-$($recoveryState.ToLowerInvariant())-" +
                "identity-divergence rejected=true delivery_absent_before_retry=true " +
                "retry_succeeded=true")
        }
    }
    if ($divergentRecoveryAccepted.Count -ne 0)
    {
        throw "First-publication identity-divergence was committed in states: " +
            ($divergentRecoveryAccepted -join ",")
    }
    if ($FirstPublicationRecoveryOnly)
    {
        $log.Add("PASS scenario=focused-first-publication-recovery")
        $log | Write-Output
        return
    }

    foreach ($hardLinkFailureAfter in @(0, 1))
    {
        Assert-ExpectedFailure -Scenario "hard-link-failure-after-$hardLinkFailureAfter" `
            -Pattern "Injected hard-link publication failure" -Action {
                Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output `
                    -HardLinkFailureAfter $hardLinkFailureAfter | Out-Null
            }
        Assert-ExactDelivery -Directory $output -McpHash $mcpHash `
            -JarName $jarName -JarHash $jarHash
        if (Test-Path -LiteralPath $transactionRoot)
        {
            throw "Hard-link failure retained a private transaction root after $hardLinkFailureAfter links."
        }
        $log.Add("PASS scenario=hard-link-failure-after-$hardLinkFailureAfter " +
            "copy_fallback=false last_good_preserved=true")
    }

    Assert-ExpectedFailure -Scenario "published-identity-mismatch" `
        -Pattern "identity differs from its audited snapshot" -Action {
            Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output `
                -PublishIdentityMismatch | Out-Null
        }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash `
        -JarName $jarName -JarHash $jarHash
    if (Test-Path -LiteralPath $transactionRoot)
    {
        throw "Published identity mismatch retained a private transaction root."
    }
    $log.Add("PASS scenario=published-identity-mismatch-detected " +
        "last_good_preserved=true transient_untrusted_path_detected=true")

    Assert-ExpectedFailure -Scenario "published-link-count-mismatch" `
        -Pattern "invalid link count" -Action {
            Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output `
                -PublishExtraHardLink | Out-Null
        }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash `
        -JarName $jarName -JarHash $jarHash
    if (Test-Path -LiteralPath $transactionRoot)
    {
        throw "Published link-count mismatch retained a private transaction root."
    }
    $log.Add("PASS scenario=published-link-count-mismatch-detected " +
        "last_good_preserved=true bounded_cleanup=true")

    Assert-ExpectedFailure -Scenario "missing-build-receipt" -Pattern "BuildReceipt" -Action {
        & $packageScript -McpExe $releaseMcp -VibrisModJar $vibrisModJar `
            -OutputDirectory $output | Out-Null
    }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash
    $log.Add("PASS scenario=missing-build-receipt last_good_preserved=true")

    $inactiveSession = New-ProbeBuildSession -Mcp $releaseMcp -Jar $vibrisModJar -Mutation "None"
    $inactiveSession.lock.Dispose()
    $inactiveSession.lock = $null
    try
    {
        Assert-ExpectedFailure -Scenario "inactive-build-receipt" `
            -Pattern "session is not active" -Action {
                & $packageScript -McpExe $releaseMcp -VibrisModJar $vibrisModJar `
                    -BuildReceipt $inactiveSession.receipt -OutputDirectory $output | Out-Null
            }
    }
    finally
    {
        Remove-ProbeBuildSession -Session $inactiveSession
    }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash
    $log.Add("PASS scenario=inactive-build-receipt last_good_preserved=true reusable=false")

    $receiptFailureCases = @(
        [pscustomobject] @{
            scenario = "mismatched-build-receipt"
            mutation = "McpHash"
            pattern = "does not match|provenance"
        },
        [pscustomobject] @{
            scenario = "forged-root-build-receipt"
            mutation = "VibrisRoot"
            pattern = "different Vibris root"
        },
        [pscustomobject] @{
            scenario = "stale-mcp-build-receipt"
            mutation = "StaleMcp"
            pattern = "stale for the recorded build phase"
        },
        [pscustomobject] @{
            scenario = "stale-iris-build-receipt"
            mutation = "StaleIris"
            pattern = "stale for the recorded build phase"
        })
    foreach ($receiptFailure in $receiptFailureCases)
    {
        Assert-ExpectedFailure -Scenario $receiptFailure.scenario `
            -Pattern $receiptFailure.pattern -Action {
                Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output `
                    -ReceiptMutation $receiptFailure.mutation | Out-Null
            }
        Assert-ExactDelivery -Directory $output -McpHash $mcpHash `
            -JarName $jarName -JarHash $jarHash
        $log.Add("PASS scenario=$($receiptFailure.scenario) last_good_preserved=true")
    }

    $semanticCases = @(
        [pscustomobject] @{
            scenario = "changed-protocol-class"
            source = $loomProtocolJar
            pattern = "stale protocol content at entry"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive `
                    -Name "dev/vibris/protocol/v2/Action.class" `
                    -Content "changed-class" -Replace
            }
        },
        [pscustomobject] @{
            scenario = "missing-protocol-entry"
            source = $loomProtocolJar
            pattern = "stale protocol content.*missing=.*Action.class"
            mutate = {
                param($Archive)
                $entry = @($Archive.Entries | Where-Object {
                    $_.FullName -ceq "dev/vibris/protocol/v2/Action.class"
                })
                if ($entry.Count -ne 1) { throw "Missing-entry fixture source is invalid." }
                $entry[0].Delete()
            }
        },
        [pscustomobject] @{
            scenario = "arbitrary-protocol-extra"
            source = $loomProtocolJar
            pattern = "stale protocol content.*extra="
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive `
                    -Name "unexpected.bin" -Content "unexpected"
            }
        },
        [pscustomobject] @{
            scenario = "duplicate-protocol-entry"
            source = $loomProtocolJar
            pattern = "duplicate JAR entry"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive `
                    -Name "dev/vibris/protocol/v2/Action.class" -Content "duplicate"
            }
        },
        [pscustomobject] @{
            scenario = "noncanonical-double-slash"
            source = $loomProtocolJar
            pattern = "unsafe JAR entry path: dir//"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive -Name "dir//" -Content "unsafe"
            }
        },
        [pscustomobject] @{
            scenario = "unsafe-traversal-path"
            source = $loomProtocolJar
            pattern = "unsafe JAR entry path: dir/../escape.class"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive `
                    -Name "dir/../escape.class" -Content "unsafe"
            }
        },
        [pscustomobject] @{
            scenario = "unsafe-absolute-path"
            source = $loomProtocolJar
            pattern = "unsafe JAR entry path: /escape.class"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive -Name "/escape.class" -Content "unsafe"
            }
        },
        [pscustomobject] @{
            scenario = "unsafe-backslash-path"
            source = $loomProtocolJar
            pattern = "unsafe JAR entry path: dir\\escape.class"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive `
                    -Name "dir\escape.class" -Content "unsafe"
            }
        },
        [pscustomobject] @{
            scenario = "malformed-loom-metadata"
            source = $protocolJar
            pattern = "fabric.mod.json is malformed"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive `
                    -Name "fabric.mod.json" -Content '{ "schemaVersion":'
            }
        },
        [pscustomobject] @{
            scenario = "non-generated-loom-metadata"
            source = $protocolJar
            pattern = "not the expected Loom-generated"
            mutate = {
                param($Archive)
                Write-ZipTextEntry -Archive $Archive -Name "fabric.mod.json" -Content '{
  "schemaVersion": 1,
  "id": "dev_luna5ama_vibris-protocol-java",
  "version": "0.0.1-SNAPSHOT",
  "name": "vibris-protocol-java",
  "custom": { "fabric-loom:generated": false }
}'
            }
        }
    )
    foreach ($semanticCase in $semanticCases)
    {
        $variantProtocol = Join-Path $inputRoot "$($semanticCase.scenario)-protocol.jar"
        Copy-Item -LiteralPath $semanticCase.source -Destination $variantProtocol
        $variantArchive = [System.IO.Compression.ZipFile]::Open(
            $variantProtocol, [System.IO.Compression.ZipArchiveMode]::Update)
        try
        {
            & $semanticCase.mutate $variantArchive
        }
        finally
        {
            $variantArchive.Dispose()
        }

        $variantMod = Join-Path $inputRoot "vibris-fabric-$($semanticCase.scenario)-local.jar"
        Copy-Item -LiteralPath $vibrisModJar -Destination $variantMod
        Set-IrisEmbeddedProtocol -IrisJar $variantMod -ProtocolJar $variantProtocol
        Assert-ExpectedFailure -Scenario $semanticCase.scenario `
            -Pattern $semanticCase.pattern -Action {
                Invoke-Package -Mcp $releaseMcp -Jar $variantMod -Target $output | Out-Null
            }
        Assert-ExactDelivery `
            -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash
    }

    $badProto = Join-Path $inputRoot "vibris_control_bad.proto"
    $badProtoText = (Get-Content -Raw -LiteralPath (Join-Path $VibrisRoot "proto\vibris_control.proto")) `
        -replace 'string source_uuid\s*=', 'string changed_uuid ='
    [System.IO.File]::WriteAllText($badProto, $badProtoText)
    Assert-ExpectedFailure -Scenario "explicit-proto-snapshot" -Pattern "missing 'string source_uuid'" -Action {
        & $auditScript -McpExe (Join-Path $output "vibris-mcp.exe") `
            -VibrisModJar (Join-Path $output $jarName) -DeliveryDirectory $output `
            -ProtoPath $badProto | Out-Null
    }

    [System.IO.File]::WriteAllText($sentinel, "sentinel")
    try
    {
        Assert-ExpectedFailure -Scenario "audit-third-file" -Pattern "exactly two ordinary files" -Action {
            & $auditScript -McpExe (Join-Path $output "vibris-mcp.exe") `
                -VibrisModJar (Join-Path $output $jarName) -DeliveryDirectory $output | Out-Null
        }
    }
    finally
    {
        if (Test-Path -LiteralPath $sentinel) { Remove-Item -LiteralPath $sentinel -Force }
    }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash

    [void] (New-Item -ItemType Directory -Path $auditLinkDelivery)
    [void] (New-Item -ItemType SymbolicLink -Path (Join-Path $auditLinkDelivery "vibris-mcp.exe") `
        -Target (Join-Path $output "vibris-mcp.exe"))
    [void] (New-Item -ItemType SymbolicLink -Path (Join-Path $auditLinkDelivery $jarName) `
        -Target (Join-Path $output $jarName))
    try
    {
        Assert-ExpectedFailure -Scenario "audit-reparse-leaves" `
            -Pattern "reparse point|exactly two ordinary files" -Action {
                & $auditScript -McpExe (Join-Path $output "vibris-mcp.exe") `
                    -VibrisModJar (Join-Path $output $jarName) `
                    -DeliveryDirectory $auditLinkDelivery | Out-Null
            }
    }
    finally
    {
        foreach ($linkName in @("vibris-mcp.exe", $jarName))
        {
            $linkPath = Join-Path $auditLinkDelivery $linkName
            if (Test-Path -LiteralPath $linkPath) { Remove-Item -LiteralPath $linkPath -Force }
        }
        Remove-OwnedDirectory -Path $auditLinkDelivery -Root $scope
    }

    $auditOutsideDelivery = Join-Path $outsideRoot "audit-delivery"
    [void] (New-Item -ItemType Directory -Path $auditOutsideDelivery)
    Copy-Item -LiteralPath (Join-Path $output "vibris-mcp.exe") -Destination $auditOutsideDelivery
    Copy-Item -LiteralPath (Join-Path $output $jarName) -Destination $auditOutsideDelivery
    [void] (New-Item -ItemType Junction -Path $auditJunction -Target $outsideRoot)
    try
    {
        Assert-ExpectedFailure -Scenario "audit-reparse-ancestor" -Pattern "reparse point" -Action {
            & $auditScript -McpExe (Join-Path $output "vibris-mcp.exe") `
                -VibrisModJar (Join-Path $output $jarName) `
                -DeliveryDirectory (Join-Path $auditJunction "audit-delivery") | Out-Null
        }
    }
    finally
    {
        if (Test-Path -LiteralPath $auditJunction) { Remove-Item -LiteralPath $auditJunction -Force }
    }

    $mismatchedJar = Join-Path $inputRoot "vibris-fabric-mismatch-local.jar"
    Copy-Item -LiteralPath $vibrisModJar -Destination $mismatchedJar
    $mismatchArchive = [System.IO.Compression.ZipFile]::Open(
        $mismatchedJar, [System.IO.Compression.ZipArchiveMode]::Update)
    try
    {
        $mismatchProtocol = $mismatchArchive.GetEntry("META-INF/jars/vibris-protocol-java.jar")
        if ($null -eq $mismatchProtocol) { throw "Mismatch fixture lacks embedded protocol entry." }
        $mismatchProtocol.Delete()
    }
    finally
    {
        $mismatchArchive.Dispose()
    }
    Assert-ExpectedFailure -Scenario "mismatched-explicit-iris" `
        -Pattern "does not embed|must embed exactly one" -Action {
        Invoke-Package -Mcp $releaseMcp -Jar $mismatchedJar -Target $output | Out-Null
    }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash

    $preRecordJar = Join-Path $inputRoot "vibris-fabric-prerecord-local.jar"
    $preRecordReplacement = Join-Path $inputRoot "vibris-fabric-prerecord-replacement-local.jar"
    Copy-Item -LiteralPath $vibrisModJar -Destination $preRecordJar
    Copy-Item -LiteralPath $vibrisModJar -Destination $preRecordReplacement
    $preRecordArchive = [System.IO.Compression.ZipFile]::Open(
        $preRecordReplacement, [System.IO.Compression.ZipArchiveMode]::Update)
    try
    {
        Write-ZipTextEntry -Archive $preRecordArchive `
            -Name "vibris-prerecord-marker.txt" -Content "semantic-valid-byte-change"
    }
    finally
    {
        $preRecordArchive.Dispose()
    }
    $preRecordAuditDelivery = Join-Path $scope "prerecord-semantic-audit-delivery"
    [void] (New-Item -ItemType Directory -Path $preRecordAuditDelivery)
    Copy-Item -LiteralPath $releaseMcp -Destination $preRecordAuditDelivery
    Copy-Item -LiteralPath $preRecordReplacement -Destination $preRecordAuditDelivery
    $preRecordAuditResult = @(& $auditScript -McpExe $releaseMcp `
        -VibrisModJar (Join-Path $preRecordAuditDelivery `
            (Split-Path -Leaf $preRecordReplacement)) `
        -DeliveryDirectory $preRecordAuditDelivery)
    if (($preRecordAuditResult -join "`n") -notmatch "PASS source_audit=true")
    {
        throw "Pre-record replacement fixture did not pass the maintained semantic audit."
    }
    $testHooksRoot = Join-Path $buildRoot ".delivery-test-hooks"
    [void] (New-Item -ItemType Directory -Path $testHooksRoot -Force)
    $preRecordHookSession = Join-Path $testHooksRoot ([guid]::NewGuid().ToString("D"))
    [void] (New-Item -ItemType Directory -Path $preRecordHookSession)
    $preRecordReady = Join-Path $preRecordHookSession "ready.txt"
    $preRecordAck = Join-Path $preRecordHookSession "ack.txt"
    $preRecordJob = Start-Job -ScriptBlock {
        param($Ready, $Ack, $Replacement, $Source)
        $deadline = [datetime]::UtcNow.AddSeconds(20)
        while (-not (Test-Path -LiteralPath $Ready -PathType Leaf))
        {
            if ([datetime]::UtcNow -ge $deadline)
            {
                throw "Timed out waiting for pre-record replacement trigger."
            }
            Start-Sleep -Milliseconds 1
        }
        Copy-Item -LiteralPath $Replacement -Destination $Source -Force
        [System.IO.File]::WriteAllText($Ack, "REPLACED")
        Write-Output "REPLACED source=$Source"
    } -ArgumentList $preRecordReady, $preRecordAck, $preRecordReplacement, $preRecordJar
    try
    {
        Assert-ExpectedFailure -Scenario "pre-record-replacement-detected" `
            -Pattern "does not match its build receipt before immutable snapshot" -Action {
                Invoke-Package -Mcp $releaseMcp -Jar $preRecordJar -Target $output `
                    -BeforeRecordReady $preRecordReady -BeforeRecordAck $preRecordAck | Out-Null
            }
        [void] (Wait-Job -Job $preRecordJob -Timeout 25)
        $preRecordOutput = @(Receive-Job -Job $preRecordJob -ErrorAction Stop)
        if ($preRecordOutput -notmatch '^REPLACED source=')
        {
            throw "Pre-record replacement job did not run."
        }
        Remove-Job -Job $preRecordJob -Force
        $preRecordJob = $null
        Assert-ExactDelivery -Directory $output -McpHash $mcpHash `
            -JarName $jarName -JarHash $jarHash
        $log.Add("PASS scenario=pre-record-replacement-detected " +
            "semantic_audit_passed=true receipt_expected_bytes=true last_good_preserved=true")
    }
    finally
    {
        if ($null -ne $preRecordJob)
        {
            if ($preRecordJob.State -in @("Running", "NotStarted")) { Stop-Job -Job $preRecordJob }
            Remove-Job -Job $preRecordJob -Force
            $preRecordJob = $null
        }
        Remove-OwnedDirectory -Path $preRecordHookSession -Root $testHooksRoot
        $preRecordHookSession = $null
    }

    $postAuditHookSession = Join-Path $testHooksRoot ([guid]::NewGuid().ToString("D"))
    [void] (New-Item -ItemType Directory -Path $postAuditHookSession)
    $postAuditReady = Join-Path $postAuditHookSession "after-audit-ready.txt"
    $postAuditAck = Join-Path $postAuditHookSession "after-audit-ack.txt"
    $postAuditStagedJar = Join-Path $transactionRoot "next\$jarName"
    $postAuditJob = Start-Job -ScriptBlock {
        param($Ready, $Ack, $Replacement, $StagedJar)
        $deadline = [datetime]::UtcNow.AddSeconds(20)
        while (-not (Test-Path -LiteralPath $Ready -PathType Leaf))
        {
            if ([datetime]::UtcNow -ge $deadline)
            {
                throw "Timed out waiting for after-audit replacement trigger."
            }
            Start-Sleep -Milliseconds 1
        }
        $attempts = 0
        $blocked = 0
        $missing = 0
        $replacements = 0
        $stressDeadline = [datetime]::UtcNow.AddSeconds(3)
        try
        {
            while ([datetime]::UtcNow -lt $stressDeadline)
            {
                ++$attempts
                try
                {
                    Copy-Item -LiteralPath $Replacement -Destination $StagedJar `
                        -Force -ErrorAction Stop
                    ++$replacements
                    break
                }
                catch
                {
                    if (Test-Path -LiteralPath $StagedJar) { ++$blocked } else { ++$missing }
                }
                if (-not (Test-Path -LiteralPath $Ack))
                {
                    [System.IO.File]::WriteAllText($Ack, "ATTEMPTED")
                }
                Start-Sleep -Milliseconds 1
            }
        }
        finally
        {
            if (-not (Test-Path -LiteralPath $Ack))
            {
                [System.IO.File]::WriteAllText($Ack, "ATTEMPTED")
            }
        }
        Write-Output ("STRESS attempts=$attempts blocked=$blocked " +
            "missing=$missing replacements=$replacements")
    } -ArgumentList $postAuditReady, $postAuditAck, $preRecordReplacement, $postAuditStagedJar
    try
    {
        $postAuditPackageFailure = $null
        try
        {
            Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output `
                -AfterAuditReady $postAuditReady -AfterAuditAck $postAuditAck | Out-Null
        }
        catch
        {
            $postAuditPackageFailure = $_
        }
        [void] (Wait-Job -Job $postAuditJob -Timeout 25)
        $postAuditOutput = @(Receive-Job -Job $postAuditJob -ErrorAction Stop)
        Remove-Job -Job $postAuditJob -Force
        $postAuditJob = $null
        $postAuditStress = @($postAuditOutput | Where-Object { $_ -match '^STRESS ' })
        if ($postAuditStress.Count -ne 1 -or
            $postAuditStress[0] -notmatch 'attempts=([1-9][0-9]*)' -or
            $postAuditStress[0] -notmatch 'replacements=0$')
        {
            Assert-ExactDelivery -Directory $output -McpHash $mcpHash `
                -JarName $jarName -JarHash $jarHash
            throw "Post-audit replacement stress was not fully blocked: $($postAuditOutput -join '; ')"
        }
        if ($null -ne $postAuditPackageFailure) { throw $postAuditPackageFailure }
        Assert-ExactDelivery -Directory $output -McpHash $mcpHash `
            -JarName $jarName -JarHash $jarHash
        $log.Add("PASS scenario=post-audit-replacement-stress-blocked " +
            "$($postAuditStress[0]) " +
            "audit_bytes_published=true last_good_preserved=true")
    }
    finally
    {
        if ($null -ne $postAuditJob)
        {
            if ($postAuditJob.State -in @("Running", "NotStarted")) { Stop-Job -Job $postAuditJob }
            Remove-Job -Job $postAuditJob -Force
            $postAuditJob = $null
        }
        Remove-OwnedDirectory -Path $postAuditHookSession -Root $testHooksRoot
        $postAuditHookSession = $null
    }

    foreach ($parallelRound in 1..3)
    {
        $parallelJobs = [System.Collections.Generic.List[object]]::new()
        $parallelSessions = [System.Collections.Generic.List[object]]::new()
        $parallelTargets = [System.Collections.Generic.List[string]]::new()
        try
        {
            foreach ($parallelLane in 1..3)
            {
                $parallelTarget = Join-Path $scope "parallel-$parallelRound-$parallelLane-delivery"
                $metadataRoots.Add((Join-Path $buildRoot (
                    ".delivery-transactions\" + (Get-TargetKey -Path $parallelTarget))))
                $parallelSession = New-ProbeBuildSession `
                    -Mcp $releaseMcp -Jar $vibrisModJar -Mutation "None"
                $parallelSessions.Add($parallelSession)
                $parallelTargets.Add($parallelTarget)
                $parallelJobs.Add((Start-Job -ScriptBlock {
                    param($Lane, $PackageScript, $Mcp, $Jar, $Receipt, $Target)
                    try
                    {
                        $packageOutput = @(& $PackageScript `
                            -McpExe $Mcp -VibrisModJar $Jar `
                            -BuildReceipt $Receipt -OutputDirectory $Target 3>&1)
                        [pscustomobject] @{
                            lane = $Lane
                            status = "PASS"
                            text = $packageOutput -join "`n"
                        }
                    }
                    catch
                    {
                        [pscustomobject] @{
                            lane = $Lane
                            status = "FAIL"
                            text = $_.Exception.Message
                        }
                    }
                } -ArgumentList $parallelLane, $packageScript, $releaseMcp, $vibrisModJar,
                    $parallelSession.receipt, $parallelTarget))
            }
            [void] (Wait-Job -Job @($parallelJobs) -Timeout 90)
            if (@($parallelJobs | Where-Object State -in @("Running", "NotStarted")).Count -ne 0)
            {
                throw "Parallel package round timed out: $parallelRound"
            }
            $parallelResults = @($parallelJobs | ForEach-Object {
                Receive-Job -Job $_ -ErrorAction Stop
            })
            $parallelPasses = @($parallelResults | Where-Object status -ceq "PASS")
            $parallelFailures = @($parallelResults | Where-Object status -ceq "FAIL")
            if ($parallelPasses.Count -lt 1 -or
                @($parallelPasses | Where-Object { $_.text -notmatch "identity_bound=true" }).Count -ne 0 -or
                @($parallelFailures | Where-Object { $_.text -notmatch "already running" }).Count -ne 0)
            {
                throw "Parallel package round returned an unexpected result: " +
                    ($parallelResults | ConvertTo-Json -Compress)
            }
        }
        finally
        {
            foreach ($parallelJob in @($parallelJobs))
            {
                if ($parallelJob.State -in @("Running", "NotStarted"))
                {
                    Stop-Job -Job $parallelJob
                }
                Remove-Job -Job $parallelJob -Force
            }
            foreach ($parallelSession in @($parallelSessions))
            {
                Remove-ProbeBuildSession -Session $parallelSession
            }
        }

        foreach ($parallelLane in 1..3)
        {
            $parallelTarget = $parallelTargets[$parallelLane - 1]
            if (-not (Test-Path -LiteralPath $parallelTarget))
            {
                Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar `
                    -Target $parallelTarget | Out-Null
            }
            Assert-ExactDelivery -Directory $parallelTarget -McpHash $mcpHash `
                -JarName $jarName -JarHash $jarHash
        }
        $log.Add("PASS scenario=parallel-packagers round=$parallelRound " +
            "attempts=3 initial_passes=$($parallelPasses.Count) " +
            "bounded_lock_failures=$($parallelFailures.Count) retries_converged=true")
    }

    $raceInputRoot = Join-Path $scope "race-inputs"
    [void] (New-Item -ItemType Directory -Path $raceInputRoot)
    $raceMcp = Join-Path $raceInputRoot "vibris-mcp.exe"
    $raceJar = Join-Path $raceInputRoot "vibris-fabric-race-local.jar"
    $raceReplacement = Join-Path $raceInputRoot "vibris-fabric-race-replacement-local.jar"
    Copy-Item -LiteralPath $releaseMcp -Destination $raceMcp
    $raceMcpStream = [System.IO.File]::Open(
        $raceMcp, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try { $raceMcpStream.SetLength($raceMcpStream.Length + 67108864) }
    finally { $raceMcpStream.Dispose() }
    Copy-Item -LiteralPath $vibrisModJar -Destination $raceJar
    Copy-Item -LiteralPath $mismatchedJar -Destination $raceReplacement
    $raceOutput = Join-Path $scope "race-delivery"
    $raceKey = Get-TargetKey -Path $raceOutput
    $raceRoot = Join-Path $buildRoot ".delivery-transactions\$raceKey"
    $raceTrigger = Join-Path $raceRoot "next\vibris-mcp.exe"
    $replacementJob = Start-Job -ScriptBlock {
        param($Trigger, $Replacement, $Source)
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        while (-not (Test-Path -LiteralPath $Trigger))
        {
            if ([DateTime]::UtcNow -ge $deadline) { throw "Timed out waiting for snapshot copy trigger." }
            Start-Sleep -Milliseconds 1
        }
        while ($true)
        {
            try
            {
                Copy-Item -LiteralPath $Replacement -Destination $Source -Force
                Write-Output "REPLACED source=$Source"
                break
            }
            catch [System.IO.IOException]
            {
                if ([DateTime]::UtcNow -ge $deadline) { throw }
                Start-Sleep -Milliseconds 1
            }
        }
    } -ArgumentList $raceTrigger, $raceReplacement, $raceJar
    Assert-ExpectedFailure -Scenario "source-replacement-detected" `
        -Pattern "changed while creating|does not match|does not embed" -Action {
            Invoke-Package -Mcp $raceMcp -Jar $raceJar -Target $raceOutput | Out-Null
        }
    [void] (Wait-Job -Job $replacementJob -Timeout 25)
    $replacementOutput = @(Receive-Job -Job $replacementJob -ErrorAction Stop)
    if ($replacementOutput -notmatch '^REPLACED source=') { throw "Source replacement job did not run." }
    Remove-Job -Job $replacementJob -Force
    $replacementJob = $null
    if (Test-Path -LiteralPath $raceOutput) { throw "Failed first publication left a race delivery." }

    $pendingOutput = Join-Path $scope "metadata-cleanup-pending-delivery"
    $pendingKey = Get-TargetKey -Path $pendingOutput
    $pendingRoot = Join-Path $buildRoot ".delivery-transactions\$pendingKey"
    $metadataRoots.Add($pendingRoot)
    $pendingResult = @(Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar `
        -Target $pendingOutput -CleanupFailure "ManifestLocked")
    if (($pendingResult -join "`n") -notmatch "cleanup_pending=true")
    {
        throw "Committed metadata failure did not report cleanup_pending=true."
    }
    Assert-ExactDelivery -Directory $pendingOutput -McpHash $mcpHash `
        -JarName $jarName -JarHash $jarHash
    if ((Get-Content -Raw -LiteralPath (Join-Path $pendingRoot "state.txt")).Trim() -cne
        "CLEANUP_COMPLETE" -or
        -not (Test-Path -LiteralPath (Join-Path $pendingRoot "manifest.json") -PathType Leaf))
    {
        throw "Committed cleanup_pending publication did not retain terminal metadata."
    }
    $pendingRecovery = @(Invoke-Package `
        -Mcp $releaseMcp -Jar $vibrisModJar -Target $pendingOutput)
    Assert-ExactDelivery -Directory $pendingOutput -McpHash $mcpHash `
        -JarName $jarName -JarHash $jarHash
    if ((Test-Path -LiteralPath $pendingRoot) -or
        ($pendingRecovery -join "`n") -match "last_good|rollback")
    {
        throw "Committed cleanup_pending publication did not converge cleanup-only."
    }
    $log.Add("PASS scenario=cleanup-pending-pass-recovery " +
        "terminal=true output_preserved=true rollback_attempted=false converged=true")

    $metadataCases = @(
        [pscustomobject] @{
            name = "manifest-locked"
            failure = "ManifestLocked"
            pattern = "being used by another process|access.*denied|access the file"
            manifestAfter = $true
            stateAfter = $true
        },
        [pscustomobject] @{
            name = "state-locked"
            failure = "StateLocked"
            pattern = "being used by another process|access.*denied|access the file"
            manifestAfter = $false
            stateAfter = $true
        },
        [pscustomobject] @{
            name = "root-removal"
            failure = "RootRemoval"
            pattern = "Injected transaction root cleanup interruption"
            manifestAfter = $false
            stateAfter = $false
        }
    )
    foreach ($metadataCase in $metadataCases)
    {
        $metadataOutput = Join-Path $scope "metadata-$($metadataCase.name)-delivery"
        [void] (New-Item -ItemType Directory -Path $metadataOutput)
        Copy-Item -LiteralPath $releaseMcp -Destination $metadataOutput
        Copy-Item -LiteralPath $vibrisModJar -Destination $metadataOutput
        $metadataMcpHash = (Get-FileHash -Algorithm SHA256 `
            -LiteralPath (Join-Path $metadataOutput "vibris-mcp.exe")).Hash
        $metadataJarHash = (Get-FileHash -Algorithm SHA256 `
            -LiteralPath (Join-Path $metadataOutput $jarName)).Hash
        $metadataRecord = [ordered] @{
            present = $true
            mcp = Get-Record -Path (Join-Path $metadataOutput "vibris-mcp.exe") `
                -Name "vibris-mcp.exe"
            mod = Get-Record -Path (Join-Path $metadataOutput $jarName) -Name $jarName
        }
        $metadataKey = Get-TargetKey -Path $metadataOutput
        $metadataRoot = Join-Path $buildRoot ".delivery-transactions\$metadataKey"
        $metadataRoots.Add($metadataRoot)
        [void] (New-Item -ItemType Directory -Path $metadataRoot)
        $metadataPublish = Join-Path $metadataRoot "publish"
        [void] (New-Item -ItemType Directory -Path $metadataPublish)
        Copy-Item -LiteralPath (Join-Path $metadataOutput "vibris-mcp.exe") `
            -Destination $metadataPublish
        Copy-Item -LiteralPath (Join-Path $metadataOutput $jarName) `
            -Destination $metadataPublish
        $metadataManifest = [ordered] @{
            schema_version = 2
            transaction_id = [guid]::NewGuid().ToString()
            target_path = [System.IO.Path]::GetFullPath($metadataOutput).TrimEnd('\', '/')
            target_sha256 = $metadataKey
            previous = [ordered] @{ present = $false }
            new = $metadataRecord
            audit = [ordered] @{}
        }
        $metadataManifestPath = Join-Path $metadataRoot "manifest.json"
        $metadataStatePath = Join-Path $metadataRoot "state.txt"
        [System.IO.File]::WriteAllText(
            $metadataManifestPath,
            ($metadataManifest | ConvertTo-Json -Depth 8))
        [System.IO.File]::WriteAllText($metadataStatePath, "COMMITTED")

        Assert-ExpectedFailure -Scenario "cleanup-$($metadataCase.name)" `
            -Pattern $metadataCase.pattern -Action {
                Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar `
                    -Target $metadataOutput -CleanupFailure $metadataCase.failure | Out-Null
            }
        Assert-ExactDelivery -Directory $metadataOutput -McpHash $metadataMcpHash `
            -JarName $jarName -JarHash $metadataJarHash
        if ((Test-Path -LiteralPath $metadataManifestPath -PathType Leaf) -ne
            [bool] $metadataCase.manifestAfter -or
            (Test-Path -LiteralPath $metadataStatePath -PathType Leaf) -ne
            [bool] $metadataCase.stateAfter)
        {
            throw "Cleanup interruption left the wrong metadata shape: $($metadataCase.name)"
        }
        if ($metadataCase.stateAfter -and
            (Get-Content -Raw -LiteralPath $metadataStatePath).Trim() -cne "CLEANUP_COMPLETE")
        {
            throw "Cleanup interruption lost its irreversible terminal state."
        }
        if (@("previous", "next", "publish", "audit", "failed" | Where-Object {
            Test-Path -LiteralPath (Join-Path $metadataRoot $_)
        }).Count -ne 0)
        {
            throw "Cleanup interruption retained a payload directory: $($metadataCase.name)"
        }

        $metadataRecovery = @(Invoke-Package `
            -Mcp $releaseMcp -Jar $vibrisModJar -Target $metadataOutput)
        Assert-ExactDelivery -Directory $metadataOutput -McpHash $metadataMcpHash `
            -JarName $jarName -JarHash $metadataJarHash
        if (Test-Path -LiteralPath $metadataRoot)
        {
            throw "Cleanup recovery did not converge: $($metadataCase.name)"
        }
        if (($metadataRecovery -join "`n") -match "last_good|rollback")
        {
            throw "Cleanup-only recovery entered a rollback path: $($metadataCase.name)"
        }
        $log.Add("PASS scenario=cleanup-$($metadataCase.name)-recovery " +
            "terminal=true output_preserved=true rollback_attempted=false converged=true")
    }

    $abortStates = @(
        "PREPARING", "PREPARED", "OLD_MOVE_INTENT", "OLD_MOVED",
        "PUBLISH_INTENT", "PUBLISHED")
    foreach ($abortState in $abortStates)
    {
        $abortOutput = Join-Path $scope "abort-$($abortState.ToLowerInvariant())-delivery"
        [void] (New-Item -ItemType Directory -Path $abortOutput)
        Copy-Item -LiteralPath $releaseMcp -Destination $abortOutput
        Copy-Item -LiteralPath $vibrisModJar -Destination $abortOutput
        $abortRecord = [ordered] @{
            present = $true
            mcp = Get-Record -Path (Join-Path $abortOutput "vibris-mcp.exe") `
                -Name "vibris-mcp.exe"
            mod = Get-Record -Path (Join-Path $abortOutput $jarName) -Name $jarName
        }
        $abortKey = Get-TargetKey -Path $abortOutput
        $abortRoot = Join-Path $buildRoot ".delivery-transactions\$abortKey"
        $metadataRoots.Add($abortRoot)
        [void] (New-Item -ItemType Directory -Path $abortRoot)
        if ($abortState -cne "PUBLISHED")
        {
            $abortPublish = Join-Path $abortRoot "publish"
            [void] (New-Item -ItemType Directory -Path $abortPublish)
            Copy-Item -LiteralPath $releaseMcp -Destination $abortPublish
            Copy-Item -LiteralPath $vibrisModJar -Destination $abortPublish
        }
        $abortPrevious = Join-Path $abortRoot "previous"
        if ($abortState -in @("OLD_MOVED", "PUBLISH_INTENT"))
        {
            [System.IO.Directory]::Move($abortOutput, $abortPrevious)
        }
        elseif ($abortState -ceq "PUBLISHED")
        {
            [void] (New-Item -ItemType Directory -Path $abortPrevious)
            Copy-Item -LiteralPath (Join-Path $abortOutput "vibris-mcp.exe") `
                -Destination $abortPrevious
            Copy-Item -LiteralPath (Join-Path $abortOutput $jarName) `
                -Destination $abortPrevious
        }
        $abortManifest = [ordered] @{
            schema_version = 2
            transaction_id = [guid]::NewGuid().ToString()
            target_path = [System.IO.Path]::GetFullPath($abortOutput).TrimEnd('\', '/')
            target_sha256 = $abortKey
            previous = $abortRecord
            new = $abortRecord
            audit = [ordered] @{}
        }
        $abortManifestPath = Join-Path $abortRoot "manifest.json"
        $abortStatePath = Join-Path $abortRoot "state.txt"
        [System.IO.File]::WriteAllText(
            $abortManifestPath,
            ($abortManifest | ConvertTo-Json -Depth 8))
        [System.IO.File]::WriteAllText($abortStatePath, $abortState)

        Assert-ExpectedFailure -Scenario "abort-$($abortState.ToLowerInvariant())-state-locked" `
            -Pattern "being used by another process|access.*denied|access the file" -Action {
                Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar `
                    -Target $abortOutput -CleanupFailure "StateLocked" | Out-Null
            }
        Assert-ExactDelivery -Directory $abortOutput -McpHash $mcpHash `
            -JarName $jarName -JarHash $jarHash
        if (Test-Path -LiteralPath $abortManifestPath -PathType Leaf)
        {
            throw "Aborted cleanup retained its manifest after state denial: $abortState"
        }
        if (-not (Test-Path -LiteralPath $abortStatePath -PathType Leaf) -or
            (Get-Content -Raw -LiteralPath $abortStatePath).Trim() -cne
            "ABORT_CLEANUP_COMPLETE")
        {
            throw "Aborted cleanup did not retain its terminal state: $abortState"
        }
        if (@("previous", "next", "publish", "audit", "failed" | Where-Object {
            Test-Path -LiteralPath (Join-Path $abortRoot $_)
        }).Count -ne 0)
        {
            throw "Aborted cleanup retained a payload directory: $abortState"
        }

        $abortRecovery = @(Invoke-Package `
            -Mcp $releaseMcp -Jar $vibrisModJar -Target $abortOutput)
        Assert-ExactDelivery -Directory $abortOutput -McpHash $mcpHash `
            -JarName $jarName -JarHash $jarHash
        if ((Test-Path -LiteralPath $abortRoot) -or
            ($abortRecovery -join "`n") -match "last_good|rollback")
        {
            throw "Aborted cleanup did not converge cleanup-only: $abortState"
        }
        $log.Add("PASS scenario=abort-$($abortState.ToLowerInvariant())-state-locked-recovery " +
            "terminal=true output_preserved=true rollback_attempted=false converged=true")
    }

    $failureCleanupOutput = Join-Path $scope "abort-failure-cleanup-delivery"
    [void] (New-Item -ItemType Directory -Path $failureCleanupOutput)
    Copy-Item -LiteralPath $releaseMcp -Destination $failureCleanupOutput
    Copy-Item -LiteralPath $vibrisModJar -Destination $failureCleanupOutput
    $failureCleanupKey = Get-TargetKey -Path $failureCleanupOutput
    $failureCleanupRoot = Join-Path $buildRoot ".delivery-transactions\$failureCleanupKey"
    $metadataRoots.Add($failureCleanupRoot)
    Assert-ExpectedFailure -Scenario "abort-failure-cleanup-state-locked" `
        -Pattern "last-good recovery both failed|being used by another process|access.*denied|access the file" `
        -Action {
            Invoke-Package -Mcp $releaseMcp -Jar $mismatchedJar `
                -Target $failureCleanupOutput -CleanupFailure "StateLocked" | Out-Null
        }
    Assert-ExactDelivery -Directory $failureCleanupOutput -McpHash $mcpHash `
        -JarName $jarName -JarHash $jarHash
    $failureCleanupManifest = Join-Path $failureCleanupRoot "manifest.json"
    $failureCleanupState = Join-Path $failureCleanupRoot "state.txt"
    if ((Test-Path -LiteralPath $failureCleanupManifest -PathType Leaf) -or
        -not (Test-Path -LiteralPath $failureCleanupState -PathType Leaf) -or
        (Get-Content -Raw -LiteralPath $failureCleanupState).Trim() -cne
        "ABORT_CLEANUP_COMPLETE")
    {
        throw "Failure-path cleanup did not retain only its abort terminal state."
    }
    if (@("previous", "next", "publish", "audit", "failed" | Where-Object {
        Test-Path -LiteralPath (Join-Path $failureCleanupRoot $_)
    }).Count -ne 0)
    {
        throw "Failure-path cleanup retained a payload directory."
    }
    $failureCleanupRecovery = @(Invoke-Package `
        -Mcp $releaseMcp -Jar $vibrisModJar -Target $failureCleanupOutput)
    Assert-ExactDelivery -Directory $failureCleanupOutput -McpHash $mcpHash `
        -JarName $jarName -JarHash $jarHash
    if ((Test-Path -LiteralPath $failureCleanupRoot) -or
        ($failureCleanupRecovery -join "`n") -match "last_good|rollback")
    {
        throw "Failure-path abort cleanup did not converge cleanup-only."
    }
    $log.Add("PASS scenario=abort-failure-cleanup-state-locked-recovery " +
        "terminal=true output_preserved=true rollback_attempted=false converged=true")

    [void] (New-Item -ItemType Directory -Path $transactionRoot -Force)
    $previous = [ordered] @{
        present = $true
        mcp = Get-Record -Path (Join-Path $output "vibris-mcp.exe") -Name "vibris-mcp.exe"
        mod = Get-Record -Path (Join-Path $output $jarName) -Name $jarName
    }
    $committedManifest = [ordered] @{
        schema_version = 2
        transaction_id = [guid]::NewGuid().ToString()
        target_path = [System.IO.Path]::GetFullPath($output).TrimEnd('\', '/')
        target_sha256 = $targetKey
        previous = $previous
        new = $previous
        audit = [ordered] @{}
    }
    $previousDirectory = Join-Path $transactionRoot "previous"
    [void] (New-Item -ItemType Directory -Path $previousDirectory)
    Copy-Item -LiteralPath (Join-Path $output "vibris-mcp.exe") -Destination $previousDirectory
    Copy-Item -LiteralPath (Join-Path $output $jarName) -Destination $previousDirectory
    [System.IO.File]::WriteAllText(
        (Join-Path $transactionRoot "manifest.json"),
        ($committedManifest | ConvertTo-Json -Depth 8))
    [System.IO.File]::WriteAllText((Join-Path $transactionRoot "state.txt"), "COMMITTED")

    $oldHandle = [System.IO.File]::Open(
        (Join-Path $previousDirectory "vibris-mcp.exe"),
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
    try
    {
        Assert-ExpectedFailure -Scenario "locked-postcommit-cleanup" `
            -Pattern "being used by another process|access the file" -Action {
                Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output | Out-Null
            }
    }
    finally
    {
        $oldHandle.Dispose()
    }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash
    if ((Get-Content -Raw -LiteralPath (Join-Path $transactionRoot "state.txt")).Trim() -cne "COMMITTED")
    {
        throw "Post-commit cleanup failure did not retain COMMITTED state."
    }
    $log.Add("PASS scenario=locked-postcommit-cleanup output_complete=true rollback_not_restored=true")

    $partialJar = Join-Path $previousDirectory $jarName
    if (Test-Path -LiteralPath $partialJar) { Remove-Item -LiteralPath $partialJar -Force }
    [System.IO.File]::WriteAllText((Join-Path $transactionRoot "state.txt"), "OLD_MOVED")
    Assert-ExpectedFailure -Scenario "partial-rollback-not-restored" `
        -Pattern "must contain exactly two ordinary files|does not match" -Action {
            Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output | Out-Null
        }
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash
    [System.IO.File]::WriteAllText((Join-Path $transactionRoot "state.txt"), "COMMITTED")
    [void] (Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target $output)

    [void] (New-Item -ItemType Directory -Path $transactionRoot -Force)
    $preparingManifest = [ordered] @{
        schema_version = 2
        transaction_id = [guid]::NewGuid().ToString()
        target_path = [System.IO.Path]::GetFullPath($output).TrimEnd('\', '/')
        target_sha256 = $targetKey
        previous = $previous
        new = $previous
        audit = [ordered] @{}
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $transactionRoot "manifest.json"),
        ($preparingManifest | ConvertTo-Json -Depth 8))
    [System.IO.File]::WriteAllText((Join-Path $transactionRoot "state.txt"), "PREPARING")
    $partialNext = Join-Path $transactionRoot "next"
    [void] (New-Item -ItemType Directory -Path $partialNext)
    Copy-Item -LiteralPath $releaseMcp -Destination (Join-Path $partialNext "vibris-mcp.exe")
    [void] (Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target ($output + "\"))
    Assert-ExactDelivery -Directory $output -McpHash $mcpHash -JarName $jarName -JarHash $jarHash
    $log.Add("PASS scenario=fixed-validation-recovery partial_preparing_snapshot=true")

    $overlapOutput = Join-Path $scope "overlap-delivery"
    $overlapKey = Get-TargetKey -Path $overlapOutput
    $overlapRoot = Join-Path $buildRoot ".delivery-transactions\$overlapKey"
    [void] (New-Item -ItemType Directory -Path $overlapRoot -Force)
    $overlapMcp = Join-Path $overlapRoot "vibris-mcp.exe"
    $overlapJar = Join-Path $overlapRoot $jarName
    Copy-Item -LiteralPath $releaseMcp -Destination $overlapMcp
    Copy-Item -LiteralPath $vibrisModJar -Destination $overlapJar
    Assert-ExpectedFailure -Scenario "transaction-input-overlap" -Pattern "inside its private transaction root" `
        -Action { Invoke-Package -Mcp $overlapMcp -Jar $overlapJar -Target $overlapOutput | Out-Null }
    if (-not (Test-Path -LiteralPath $overlapMcp -PathType Leaf) -or
        -not (Test-Path -LiteralPath $overlapJar -PathType Leaf))
    {
        throw "Overlap rejection deleted an explicit input."
    }

    [void] (New-Item -ItemType Junction -Path $junction -Target $outsideRoot)
    Assert-ExpectedFailure -Scenario "nested-junction-output" -Pattern "reparse point" -Action {
        Invoke-Package -Mcp $releaseMcp -Jar $vibrisModJar -Target (Join-Path $junction "delivery") | Out-Null
    }

    [void] (New-Item -ItemType Directory -Path $linkInput)
    $linkedMcp = Join-Path $linkInput "vibris-mcp.exe"
    [void] (New-Item -ItemType SymbolicLink -Path $linkedMcp -Target $releaseMcp)
    Assert-ExpectedFailure -Scenario "reparse-leaf-input" -Pattern "reparse point" -Action {
        Invoke-Package -Mcp $linkedMcp -Jar $vibrisModJar `
            -ReceiptMcp $releaseMcp -Target (Join-Path $scope "link-delivery") | Out-Null
    }

    $lockHandle = [System.IO.File]::Open(
        $deliveryLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None)
    try
    {
        Assert-ExpectedFailure -Scenario "standalone-audit-lock" -Pattern "already running" -Action {
            & $auditScript -McpExe (Join-Path $output "vibris-mcp.exe") `
                -VibrisModJar (Join-Path $output $jarName) -DeliveryDirectory $output | Out-Null
        }
    }
    finally
    {
        $lockHandle.Dispose()
    }

    $log.Add("PASS scenario=all-delivery-transaction-regressions")
    $log | Write-Output
}
finally
{
    foreach ($receiptSession in @($activeReceiptSessions))
    {
        Remove-ProbeBuildSession -Session $receiptSession
    }
    if ($null -ne $preRecordJob)
    {
        if ($preRecordJob.State -in @("Running", "NotStarted")) { Stop-Job -Job $preRecordJob }
        Remove-Job -Job $preRecordJob -Force
        $preRecordJob = $null
    }
    if ($preRecordHookSession)
    {
        Remove-OwnedDirectory -Path $preRecordHookSession -Root $testHooksRoot
        $preRecordHookSession = $null
    }
    if ($null -ne $postAuditJob)
    {
        if ($postAuditJob.State -in @("Running", "NotStarted")) { Stop-Job -Job $postAuditJob }
        Remove-Job -Job $postAuditJob -Force
        $postAuditJob = $null
    }
    if ($postAuditHookSession)
    {
        Remove-OwnedDirectory -Path $postAuditHookSession -Root $testHooksRoot
        $postAuditHookSession = $null
    }
    if ($null -ne $replacementJob)
    {
        if ($replacementJob.State -in @("Running", "NotStarted")) { Stop-Job -Job $replacementJob }
        Remove-Job -Job $replacementJob -Force
        $replacementJob = $null
    }
    if (Test-Path -LiteralPath $sentinel) { Remove-Item -LiteralPath $sentinel -Force }
    if (Test-Path -LiteralPath $auditJunction)
    {
        $auditJunctionItem = Get-Item -LiteralPath $auditJunction -Force
        if (-not ($auditJunctionItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint))
        {
            throw "Probe audit junction lost its reparse-point identity."
        }
        Remove-Item -LiteralPath $auditJunction -Force
    }
    if (Test-Path -LiteralPath $auditLinkDelivery)
    {
        foreach ($entry in @(Get-ChildItem -LiteralPath $auditLinkDelivery -Force))
        {
            if (-not ($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint))
            {
                throw "Probe audit-link delivery contains an unexpected ordinary entry: $($entry.FullName)"
            }
            Remove-Item -LiteralPath $entry.FullName -Force
        }
        Remove-OwnedDirectory -Path $auditLinkDelivery -Root $scope
    }
    if (Test-Path -LiteralPath $junction)
    {
        $junctionItem = Get-Item -LiteralPath $junction -Force
        if (-not ($junctionItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint))
        {
            throw "Probe junction lost its reparse-point identity."
        }
        Remove-Item -LiteralPath $junction -Force
    }
    if (Test-Path -LiteralPath $linkInput)
    {
        $link = Join-Path $linkInput "vibris-mcp.exe"
        if (Test-Path -LiteralPath $link) { Remove-Item -LiteralPath $link -Force }
        Remove-OwnedDirectory -Path $linkInput -Root $scope
    }
    $transactionsRoot = Join-Path $buildRoot ".delivery-transactions"
    foreach ($ownedTransaction in @($transactionRoot, $overlapRoot, $raceRoot) + @($metadataRoots))
    {
        if ($ownedTransaction -and (Test-Path -LiteralPath $ownedTransaction))
        {
            $resolvedTransaction = [System.IO.Path]::GetFullPath($ownedTransaction)
            if (-not $resolvedTransaction.StartsWith(
                $transactionsRoot + "\",
                [System.StringComparison]::OrdinalIgnoreCase))
            {
                throw "Refusing cleanup of unexpected transaction root: $resolvedTransaction"
            }
            Remove-OwnedDirectory -Path $resolvedTransaction -Root $transactionsRoot
        }
    }
    Remove-OwnedDirectory -Path $scope -Root $buildRoot
    Remove-OwnedDirectory -Path $outsideRoot -Root (Join-Path $VibrisRoot ".vibris\tmp")
    Write-Output ("CLEANUP scope=$scope removed=$(-not (Test-Path -LiteralPath $scope)) " +
        "owned_receipts_active=$($activeReceiptSessions.Count) " +
        "delivery_lock_baseline=$deliveryLockExistedBefore " +
        "delivery_lock_preserved=$([bool] (Test-Path -LiteralPath $deliveryLockPath)) " +
        "build_lock_baseline=$buildLockExistedBefore " +
        "build_lock_preserved=$([bool] (Test-Path -LiteralPath $buildLockPath))")
}
