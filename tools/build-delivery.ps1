<#
.SYNOPSIS
Rebuilds and publishes one verified Vibris delivery.

.EXAMPLE
.\tools\build-delivery.ps1 -VibrisRoot I:\code\vibris -IrisRoot I:\code\Iris
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateNotNullOrEmpty()] [string] $VibrisRoot,
    [Parameter(Mandatory)] [ValidateNotNullOrEmpty()] [string] $IrisRoot,
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "git-process.ps1")

function Resolve-RepositoryRoot
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label,
        [Parameter(Mandatory)] [string[]] $Markers
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $fullPath -PathType Container))
    {
        throw "$Label is not a directory: $fullPath"
    }
    foreach ($marker in $Markers)
    {
        if (-not (Test-Path -LiteralPath (Join-Path $fullPath $marker)))
        {
            throw "$Label is missing required marker '$marker': $fullPath"
        }
    }
    $gitRoot = Invoke-TrustedGitText -Root $fullPath -Arguments @(
        "rev-parse", "--path-format=absolute", "--show-toplevel") -Label "$Label Git worktree probe"
    $gitRoot = [System.IO.Path]::GetFullPath($gitRoot)
    if (-not [string]::Equals($gitRoot, $fullPath, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "$Label must name the Git worktree root exactly: requested=$fullPath actual=$gitRoot"
    }
    return $fullPath
}

function Invoke-CheckedProcess
{
    param(
        [Parameter(Mandatory)] [string] $FilePath,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [Parameter(Mandatory)] [string] $WorkingDirectory,
        [Parameter(Mandatory)] [string] $Label
    )

    Push-Location -LiteralPath $WorkingDirectory
    try
    {
        Write-Output "RUN $Label"
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0)
        {
            throw "$Label exited with code $LASTEXITCODE."
        }
    }
    finally
    {
        Pop-Location
    }
}

function Get-ModBuildFingerprint
{
    param([Parameter(Mandatory)] [string] $Directory)

    $fingerprints = @{}
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { return $fingerprints }
    foreach ($file in Get-ChildItem -LiteralPath $Directory -File -Filter "vibris-fabric-*.jar")
    {
        $fingerprints[$file.FullName] = [pscustomobject] @{
            Length = $file.Length
            LastWriteTimeUtc = $file.LastWriteTimeUtc
            Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
        }
    }
    return $fingerprints
}

function Resolve-RebuiltModJar
{
    param(
        [Parameter(Mandatory)] [string] $Directory,
        [Parameter(Mandatory)] [hashtable] $Before
    )

    $candidates = @(Get-ChildItem -LiteralPath $Directory -File -Filter "vibris-fabric-*.jar" |
        Where-Object {
            $previous = $Before[$_.FullName]
            $currentHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
            $null -eq $previous -or
                $_.Length -ne $previous.Length -or
                $_.LastWriteTimeUtc -gt $previous.LastWriteTimeUtc -or
                $currentHash -cne $previous.Hash
        })
    if ($candidates.Count -ne 1)
    {
        $paths = @($candidates | ForEach-Object FullName)
        throw "Vibris remapJar must produce exactly one fresh mod JAR; resolved $($candidates.Count): " +
            [string]::Join(", ", $paths)
    }
    return $candidates[0].FullName
}

function Invoke-GitText
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [Parameter(Mandatory)] [string] $Label
    )

    return Invoke-TrustedGitText -Root $Root -Arguments $Arguments -Label $Label -TimeoutSeconds 30
}

function Get-RepositorySourceState
{
    param([Parameter(Mandatory)] [string] $Root)

    $head = Invoke-GitText -Root $Root -Arguments @("rev-parse", "HEAD") -Label "Git HEAD read"
    $diff = Invoke-GitText -Root $Root -Arguments @(
        "diff", "--no-ext-diff", "--binary", "HEAD", "--") -Label "Git source diff"
    $submodules = Invoke-GitText -Root $Root -Arguments @(
        "submodule", "status", "--recursive") -Label "Git submodule state"
    $untrackedText = Invoke-GitText -Root $Root -Arguments @(
        "ls-files", "-z", "--others", "--exclude-standard", "--", ".", ":(exclude).vibris/**") -Label "Git untracked source list"
    $untracked = [System.Collections.Generic.List[object]]::new()
    foreach ($relative in @($untrackedText -split [char] 0 | Where-Object { $_.Length -ne 0 } |
        Sort-Object -CaseSensitive))
    {
        $path = Join-Path $Root $relative
        $item = Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer -or
            $item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
        {
            throw "Git untracked source must be an ordinary file: $path"
        }
        $untracked.Add([ordered] @{
            path = $relative.Replace('\', '/')
            length = [long] $item.Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash
        })
    }
    $payload = [ordered] @{
        head = $head
        diff = $diff
        submodules = $submodules
        untracked = @($untracked)
    } | ConvertTo-Json -Depth 6 -Compress
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try
    {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
        $fingerprint = [System.Convert]::ToHexString($hasher.ComputeHash($bytes))
    }
    finally
    {
        $hasher.Dispose()
    }
    return [ordered] @{ head = $head; fingerprint = $fingerprint }
}

function Get-BuildArtifactRecord
{
    param([Parameter(Mandatory)] [string] $Path)

    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or
        $item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "Build artifact must be an ordinary file: $Path"
    }
    return [ordered] @{
        path = [System.IO.Path]::GetFullPath($item.FullName)
        length = [long] $item.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash
        last_write_utc = $item.LastWriteTimeUtc.ToString("o")
    }
}

function Write-AtomicJson
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [object] $Value
    )

    $next = "$Path.next"
    if ((Test-Path -LiteralPath $Path) -or (Test-Path -LiteralPath $next))
    {
        throw "Build receipt path already exists: $Path"
    }
    [System.IO.File]::WriteAllText(
        $next,
        ($Value | ConvertTo-Json -Depth 10),
        [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::Move($next, $Path)
}

$VibrisRoot = Resolve-RepositoryRoot -Path $VibrisRoot -Label "VibrisRoot" -Markers @(
    "gradlew.bat",
    "mcp\CMakePresets.json",
    "proto\vibris_control.proto",
    "tools\git-process.ps1",
    "tools\package-delivery.ps1",
    "integration-tests\scripts\protocol-descriptor-smoke.ps1")
$IrisRoot = Resolve-RepositoryRoot -Path $IrisRoot -Label "IrisRoot" -Markers @(
    "gradlew.bat",
    "settings.gradle.kts",
    "fabric\build.gradle.kts")

if ([string]::Equals($VibrisRoot, $IrisRoot, [System.StringComparison]::OrdinalIgnoreCase))
{
    throw "VibrisRoot and IrisRoot must be different repositories."
}
$compositeIrisRoot = [System.IO.Path]::GetFullPath((Join-Path $VibrisRoot "..\Iris"))
if (-not [string]::Equals($compositeIrisRoot, $IrisRoot, [System.StringComparison]::OrdinalIgnoreCase))
{
    throw "Vibris includeBuild('../Iris') does not resolve to the requested IrisRoot."
}
$vibrisSettings = Get-Content -Raw -LiteralPath (Join-Path $VibrisRoot "settings.gradle.kts")
if ($vibrisSettings -notmatch 'includeBuild\(["'']\.\./Iris["'']\)')
{
    throw "Vibris settings.gradle.kts must includeBuild('../Iris') for this delivery."
}

$cmake = (Get-Command cmake -CommandType Application -ErrorAction Stop).Source
$vibrisGradle = Join-Path $VibrisRoot "gradlew.bat"
$nativeBuildRoot = Join-Path $VibrisRoot "mcp\out\build"
$releaseRoot = Join-Path $nativeBuildRoot "Release"
$releaseMcp = Join-Path $releaseRoot "vibris-mcp.exe"
$descriptorDump = Join-Path $releaseRoot "vibris-descriptor-dump.exe"
$protocolJar = Join-Path $VibrisRoot "protocol-java\build\libs\vibris-protocol-java.jar"
$proto = Join-Path $VibrisRoot "proto\vibris_control.proto"
$modLibs = Join-Path $VibrisRoot "mod-fabric\build\libs"
$packageScript = Join-Path $VibrisRoot "tools\package-delivery.ps1"
$descriptorProbe = Join-Path $VibrisRoot "integration-tests\scripts\protocol-descriptor-smoke.ps1"
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $VibrisRoot "build\delivery" }
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$deliveryBuildRoot = [System.IO.Path]::GetFullPath((Join-Path $VibrisRoot "build"))
$deliveryBuildPrefix = $deliveryBuildRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
    [System.IO.Path]::DirectorySeparatorChar
if (-not $OutputDirectory.StartsWith($deliveryBuildPrefix, [System.StringComparison]::OrdinalIgnoreCase))
{
    throw "OutputDirectory must be below $deliveryBuildRoot."
}
[void] (New-Item -ItemType Directory -Path $deliveryBuildRoot -Force)
$deliveryBuildItem = Get-Item -LiteralPath $deliveryBuildRoot -Force
if (-not $deliveryBuildItem.PSIsContainer -or
    $deliveryBuildItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
{
    throw "Vibris build root must be an ordinary directory: $deliveryBuildRoot"
}

$buildLockPath = Join-Path $deliveryBuildRoot ".build-delivery.lock"
$receiptRoot = Join-Path $deliveryBuildRoot ".delivery-receipts"
$receiptPath = $null
$receiptNextPath = $null
$sessionLockPath = $null
$sessionLock = $null
try
{
    $buildLock = [System.IO.File]::Open(
        $buildLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None)
}
catch [System.IO.IOException]
{
    throw "Another build-delivery orchestration is already running: $buildLockPath"
}

try
{
    $buildStarted = [datetime]::UtcNow
    $vibrisSourceBefore = Get-RepositorySourceState -Root $VibrisRoot
    $irisSourceBefore = Get-RepositorySourceState -Root $IrisRoot

    Invoke-CheckedProcess -FilePath $cmake -Arguments @("--preset", "windows-vs2022") `
        -WorkingDirectory (Join-Path $VibrisRoot "mcp") -Label "native CMake configure"
    $nativeBuildStarted = [datetime]::UtcNow
    Invoke-CheckedProcess -FilePath $cmake -Arguments @(
        "--build", "--preset", "release", "--clean-first", "--target", "vibris-mcp", "vibris-descriptor-dump") `
        -WorkingDirectory (Join-Path $VibrisRoot "mcp") -Label "native Release rebuild"
    $nativeBuildCompleted = [datetime]::UtcNow
    foreach ($nativeArtifact in @($releaseMcp, $descriptorDump))
    {
        if (-not (Test-Path -LiteralPath $nativeArtifact -PathType Leaf))
        {
            throw "Native rebuild did not produce $nativeArtifact"
        }
        if ((Get-Item -LiteralPath $nativeArtifact).LastWriteTimeUtc -lt $nativeBuildStarted.AddSeconds(-2))
        {
            throw "Native rebuild left a stale artifact: $nativeArtifact"
        }
    }

    $protocolBuildStarted = [datetime]::UtcNow
    Invoke-CheckedProcess -FilePath $vibrisGradle -Arguments @(
        "--no-daemon", "--no-build-cache", ":vibris-protocol-java:clean", ":vibris-protocol-java:jar") `
        -WorkingDirectory $VibrisRoot -Label "Java protocol artifact rebuild"
    if (-not (Test-Path -LiteralPath $protocolJar -PathType Leaf) -or
        (Get-Item -LiteralPath $protocolJar).LastWriteTimeUtc -lt $protocolBuildStarted.AddSeconds(-2))
    {
        throw "Java protocol rebuild did not produce a fresh artifact: $protocolJar"
    }

    & $descriptorProbe -Proto $proto -JavaJar $protocolJar -CppDump $descriptorDump

    $beforeMod = Get-ModBuildFingerprint -Directory $modLibs
    $modBuildStarted = [datetime]::UtcNow
    Invoke-CheckedProcess -FilePath $vibrisGradle -Arguments @(
        "--no-daemon", "--no-build-cache", "--rerun-tasks", ":vibris-mod-fabric:remapJar") `
        -WorkingDirectory $VibrisRoot -Label "independent Vibris Fabric mod remapJar"
    $vibrisModJar = Resolve-RebuiltModJar -Directory $modLibs -Before $beforeMod

    $modBuildCompleted = [datetime]::UtcNow
    $protocolBuildCompleted = [datetime]::UtcNow
    $vibrisSourceAfter = Get-RepositorySourceState -Root $VibrisRoot
    $irisSourceAfter = Get-RepositorySourceState -Root $IrisRoot
    if ($vibrisSourceBefore.head -cne $vibrisSourceAfter.head -or
        $vibrisSourceBefore.fingerprint -cne $vibrisSourceAfter.fingerprint -or
        $irisSourceBefore.head -cne $irisSourceAfter.head -or
        $irisSourceBefore.fingerprint -cne $irisSourceAfter.fingerprint)
    {
        throw "Repository source changed during the delivery build session."
    }

    $buildCompleted = [datetime]::UtcNow
    [void] (New-Item -ItemType Directory -Path $receiptRoot -Force)
    $receiptRootItem = Get-Item -LiteralPath $receiptRoot -Force
    if (-not $receiptRootItem.PSIsContainer -or
        $receiptRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "Build receipt root must be an ordinary directory: $receiptRoot"
    }
    $sessionId = [guid]::NewGuid().ToString("D")
    $receiptPath = Join-Path $receiptRoot "$sessionId.json"
    $receiptNextPath = "$receiptPath.next"
    $sessionLockPath = Join-Path $receiptRoot "$sessionId.lock"
    $sessionLock = [System.IO.File]::Open(
        $sessionLockPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None)
    $receipt = [ordered] @{
        schema_version = 2
        session_id = $sessionId
        vibris_root = $VibrisRoot
        iris_root = $IrisRoot
        build_started_utc = $buildStarted.ToString("o")
        build_completed_utc = $buildCompleted.ToString("o")
        session_lock_path = $sessionLockPath
        source = [ordered] @{
            vibris = $vibrisSourceAfter
            iris = $irisSourceAfter
        }
        phases = [ordered] @{
            native = [ordered] @{
                started_utc = $nativeBuildStarted.ToString("o")
                completed_utc = $nativeBuildCompleted.ToString("o")
            }
            protocol = [ordered] @{
                started_utc = $protocolBuildStarted.ToString("o")
                completed_utc = $protocolBuildCompleted.ToString("o")
            }
            mod = [ordered] @{
                started_utc = $modBuildStarted.ToString("o")
                completed_utc = $modBuildCompleted.ToString("o")
            }
        }
        scripts = [ordered] @{
            build = Get-BuildArtifactRecord -Path $PSCommandPath
            git_process = Get-BuildArtifactRecord -Path (Join-Path $VibrisRoot "tools\git-process.ps1")
            package = Get-BuildArtifactRecord -Path $packageScript
        }
        artifacts = [ordered] @{
            mcp = Get-BuildArtifactRecord -Path $releaseMcp
            mod = Get-BuildArtifactRecord -Path $vibrisModJar
            java_descriptor = Get-BuildArtifactRecord -Path $protocolJar
            cpp_descriptor = Get-BuildArtifactRecord -Path $descriptorDump
            proto = Get-BuildArtifactRecord -Path $proto
        }
    }
    Write-AtomicJson -Path $receiptPath -Value $receipt

    & $packageScript -McpExe $releaseMcp -VibrisModJar $vibrisModJar `
        -BuildReceipt $receiptPath -OutputDirectory $OutputDirectory
}
finally
{
    if ($null -ne $sessionLock)
    {
        $sessionLock.Dispose()
        $sessionLock = $null
    }
    foreach ($ownedPath in @($receiptNextPath, $receiptPath, $sessionLockPath))
    {
        if ($ownedPath -and (Test-Path -LiteralPath $ownedPath -PathType Leaf))
        {
            Remove-Item -LiteralPath $ownedPath -Force
        }
    }
    $buildLock.Dispose()
}
