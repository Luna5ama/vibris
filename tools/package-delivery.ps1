[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateNotNullOrEmpty()] [string] $McpExe,
    [Parameter(Mandatory)] [ValidateNotNullOrEmpty()] [string] $VibrisModJar,
    [Parameter(Mandatory)] [ValidateNotNullOrEmpty()] [string] $BuildReceipt,
    [string] $OutputDirectory,
    [Parameter(DontShow)]
    [ValidateSet("None", "ManifestLocked", "StateLocked", "RootRemoval")]
    [string] $TestCleanupFailure = "None",
    [Parameter(DontShow)] [string] $TestBeforeRecordReady,
    [Parameter(DontShow)] [string] $TestBeforeRecordAck,
    [Parameter(DontShow)] [string] $TestAfterAuditReady,
    [Parameter(DontShow)] [string] $TestAfterAuditAck,
    [Parameter(DontShow)] [ValidateRange(-1, 1)] [int] $TestHardLinkFailureAfter = -1,
    [Parameter(DontShow)] [switch] $TestPublishIdentityMismatch,
    [Parameter(DontShow)] [switch] $TestPublishExtraHardLink
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "git-process.ps1")

if (-not ("Vibris.DeliveryFileIdentity" -as [type]))
{
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace Vibris
{
    public static class DeliveryFileIdentity
    {
        private const int FileIdInfo = 18;

        [StructLayout(LayoutKind.Sequential)]
        private struct FileId128
        {
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)]
            public byte[] Identifier;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct FileIdInformation
        {
            public ulong VolumeSerialNumber;
            public FileId128 FileId;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct FileStandardInformation
        {
            public long AllocationSize;
            public long EndOfFile;
            public uint NumberOfLinks;
            public byte DeletePending;
            public byte Directory;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CreateHardLinkW(
            string newFileName,
            string existingFileName,
            IntPtr securityAttributes);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetFileInformationByHandleEx(
            SafeFileHandle file,
            int informationClass,
            out FileIdInformation information,
            uint bufferSize);

        [DllImport("kernel32.dll", EntryPoint = "GetFileInformationByHandleEx", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetFileStandardInformation(
            SafeFileHandle file,
            int informationClass,
            out FileStandardInformation information,
            uint bufferSize);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetVolumePathNameW(
            string fileName,
            StringBuilder volumePathName,
            uint bufferLength);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetVolumeInformationW(
            string rootPathName,
            StringBuilder volumeNameBuffer,
            uint volumeNameSize,
            out uint volumeSerialNumber,
            out uint maximumComponentLength,
            out uint fileSystemFlags,
            StringBuilder fileSystemNameBuffer,
            uint fileSystemNameSize);

        public static void CreateHardLink(string newFileName, string existingFileName)
        {
            RequireNtfs(existingFileName);
            if (!CreateHardLinkW(newFileName, existingFileName, IntPtr.Zero))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
        }

        private static void RequireNtfs(string path)
        {
            var volumePath = new StringBuilder(1024);
            if (!GetVolumePathNameW(path, volumePath, (uint)volumePath.Capacity))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            var volumeName = new StringBuilder(1024);
            var fileSystemName = new StringBuilder(1024);
            uint serial;
            uint maximumComponentLength;
            uint flags;
            if (!GetVolumeInformationW(
                volumePath.ToString(),
                volumeName,
                (uint)volumeName.Capacity,
                out serial,
                out maximumComponentLength,
                out flags,
                fileSystemName,
                (uint)fileSystemName.Capacity))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            if (!string.Equals(fileSystemName.ToString(), "NTFS", StringComparison.OrdinalIgnoreCase))
            {
                throw new NotSupportedException(
                    "Identity-bound delivery publication requires NTFS; found " +
                    fileSystemName + ".");
            }
        }

        public static string GetIdentity(SafeFileHandle file)
        {
            FileIdInformation information;
            uint size = (uint)Marshal.SizeOf<FileIdInformation>();
            if (!GetFileInformationByHandleEx(file, FileIdInfo, out information, size))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            return information.VolumeSerialNumber.ToString("X16") + ":" +
                BitConverter.ToString(information.FileId.Identifier).Replace("-", "");
        }

        public static uint GetLinkCount(SafeFileHandle file)
        {
            FileStandardInformation information;
            uint size = (uint)Marshal.SizeOf<FileStandardInformation>();
            if (!GetFileStandardInformation(file, 1, out information, size))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            return information.NumberOfLinks;
        }
    }
}
'@
}

function ConvertTo-NormalizedPath
{
    param([Parameter(Mandatory)] [string] $Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    if ($fullPath.Length -gt $root.Length)
    {
        $fullPath = $fullPath.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar)
    }
    if (Test-Path -LiteralPath $fullPath)
    {
        $fullPath = (Get-Item -LiteralPath $fullPath -Force).FullName
        $root = [System.IO.Path]::GetPathRoot($fullPath)
        if ($fullPath.Length -gt $root.Length)
        {
            $fullPath = $fullPath.TrimEnd(
                [System.IO.Path]::DirectorySeparatorChar,
                [System.IO.Path]::AltDirectorySeparatorChar)
        }
    }
    return $fullPath
}

function Assert-NoReparseComponents
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label,
        [switch] $RequireExisting
    )

    $fullPath = ConvertTo-NormalizedPath -Path $Path
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    $rootItem = Get-Item -LiteralPath $root -Force
    if ($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "$Label traverses a reparse-point root: $root"
    }

    $current = $root
    $missing = $false
    $relative = $fullPath.Substring($root.Length)
    foreach ($component in @($relative -split '[\\/]' | Where-Object { $_.Length -ne 0 }))
    {
        $current = Join-Path $current $component
        if ($missing -or -not (Test-Path -LiteralPath $current))
        {
            $missing = $true
            continue
        }
        $item = Get-Item -LiteralPath $current -Force
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
        {
            throw "$Label traverses a reparse point: $($item.FullName)"
        }
    }
    if ($RequireExisting -and $missing)
    {
        throw "$Label does not exist: $fullPath"
    }
    return $fullPath
}

function Ensure-OrdinaryDirectory
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    $fullPath = Assert-NoReparseComponents -Path $Path -Label $Label
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    $current = $root
    $relative = $fullPath.Substring($root.Length)
    foreach ($component in @($relative -split '[\\/]' | Where-Object { $_.Length -ne 0 }))
    {
        $current = Join-Path $current $component
        if (-not (Test-Path -LiteralPath $current))
        {
            [void] (New-Item -ItemType Directory -Path $current)
        }
        $item = Get-Item -LiteralPath $current -Force
        if (-not $item.PSIsContainer -or
            $item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
        {
            throw "$Label must contain only ordinary directories: $($item.FullName)"
        }
    }
    return (ConvertTo-NormalizedPath -Path $fullPath)
}

function Assert-ContainedPath
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Candidate,
        [Parameter(Mandatory)] [string] $Label
    )

    $relative = [System.IO.Path]::GetRelativePath($Root, $Candidate)
    if ($relative -eq "." -or [System.IO.Path]::IsPathRooted($relative) -or
        $relative -eq ".." -or
        $relative.StartsWith("..\", [System.StringComparison]::Ordinal) -or
        $relative.StartsWith("../", [System.StringComparison]::Ordinal))
    {
        throw "$Label must be below $Root`: $Candidate"
    }
}

function Test-IsSameOrDescendant
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Candidate
    )

    if ([string]::Equals($Root, $Candidate, [System.StringComparison]::OrdinalIgnoreCase))
    {
        return $true
    }
    $relative = [System.IO.Path]::GetRelativePath($Root, $Candidate)
    return -not [System.IO.Path]::IsPathRooted($relative) -and $relative -ne ".." -and
        -not $relative.StartsWith("..\", [System.StringComparison]::Ordinal) -and
        -not $relative.StartsWith("../", [System.StringComparison]::Ordinal)
}

function Resolve-OrdinaryFile
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    $fullPath = Assert-NoReparseComponents -Path $Path -Label $Label -RequireExisting
    $item = Get-Item -LiteralPath $fullPath -Force
    if ($item.PSIsContainer -or $item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "$Label must be an ordinary file: $fullPath"
    }
    return $item.FullName
}

function Get-FileRecord
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Name
    )

    $item = Get-Item -LiteralPath $Path -Force
    return [ordered] @{
        name = $Name
        length = [long] $item.Length
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash
    }
}

function Assert-RecordMatches
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [object] $Record,
        [Parameter(Mandatory)] [string] $Label
    )

    $file = Resolve-OrdinaryFile -Path $Path -Label $Label
    $item = Get-Item -LiteralPath $file -Force
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash
    if ($item.Length -ne [long] $Record.length -or $hash -cne [string] $Record.sha256)
    {
        throw "$Label does not match its transaction record: $file"
    }
}

function Get-ExistingDeliveryRecord
{
    param([Parameter(Mandatory)] [string] $Directory)

    if (-not (Test-Path -LiteralPath $Directory))
    {
        return [ordered] @{ present = $false }
    }
    [void] (Assert-NoReparseComponents -Path $Directory -Label "Existing delivery" -RequireExisting)
    $directoryItem = Get-Item -LiteralPath $Directory -Force
    if (-not $directoryItem.PSIsContainer)
    {
        throw "Existing delivery is not a directory: $Directory"
    }
    $entries = @(Get-ChildItem -LiteralPath $Directory -Force)
    if ($entries.Count -ne 2 -or @($entries | Where-Object {
        $_.PSIsContainer -or $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint
    }).Count -ne 0)
    {
        throw "Existing delivery must contain exactly two ordinary files: $Directory"
    }
    $mcp = @($entries | Where-Object { $_.Name -ceq "vibris-mcp.exe" })
    $jar = @($entries | Where-Object { $_.Name -cmatch '^vibris-fabric-.+[.]jar$' })
    if ($mcp.Count -ne 1 -or $jar.Count -ne 1)
    {
        throw "Existing delivery has unexpected file names: $Directory"
    }
    return [ordered] @{
        present = $true
        mcp = Get-FileRecord -Path $mcp[0].FullName -Name $mcp[0].Name
        mod = Get-FileRecord -Path $jar[0].FullName -Name $jar[0].Name
    }
}

function Assert-DeliveryRecordShape
{
    param(
        [Parameter(Mandatory)] [object] $Record,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not [bool] $Record.present) { return }
    $mcpName = [string] $Record.mcp.name
    $modName = [string] $Record.mod.name
    if ($mcpName -cne "vibris-mcp.exe" -or
        $modName -cnotmatch '^vibris-fabric-.+[.]jar$' -or
        [string]::Equals($mcpName, $modName, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "$Label has invalid or duplicate delivery record names."
    }
    foreach ($fileRecord in @($Record.mcp, $Record.mod))
    {
        if ([long] $fileRecord.length -lt 0 -or
            [string] $fileRecord.sha256 -cnotmatch '^[0-9A-F]{64}$')
        {
            throw "$Label has an invalid delivery file record."
        }
    }
}

function Assert-DeliveryMatches
{
    param(
        [Parameter(Mandatory)] [string] $Directory,
        [Parameter(Mandatory)] [object] $Record,
        [Parameter(Mandatory)] [string] $Label
    )

    Assert-DeliveryRecordShape -Record $Record -Label $Label
    if (-not [bool] $Record.present)
    {
        if (Test-Path -LiteralPath $Directory) { throw "$Label must be absent: $Directory" }
        return
    }
    [void] (Assert-NoReparseComponents -Path $Directory -Label $Label -RequireExisting)
    $entries = @(Get-ChildItem -LiteralPath $Directory -Force)
    if ($entries.Count -ne 2 -or @($entries | Where-Object {
        $_.PSIsContainer -or $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint
    }).Count -ne 0)
    {
        throw "$Label must contain exactly two ordinary files: $Directory"
    }
    $names = @($entries | ForEach-Object Name)
    if ($names -cnotcontains [string] $Record.mcp.name -or
        $names -cnotcontains [string] $Record.mod.name)
    {
        throw "$Label file names do not match its transaction record: $Directory"
    }
    Assert-RecordMatches -Path (Join-Path $Directory ([string] $Record.mcp.name)) `
        -Record $Record.mcp -Label "$Label MCP"
    Assert-RecordMatches -Path (Join-Path $Directory ([string] $Record.mod.name)) `
        -Record $Record.mod -Label "$Label Vibris mod JAR"
}

function Get-DeliveryRecordNames
{
    param([Parameter(Mandatory)] [object] $Record)

    if (-not [bool] $Record.present) { return @() }
    return @([string] $Record.mcp.name, [string] $Record.mod.name)
}

function Test-DeliveryMatches
{
    param(
        [Parameter(Mandatory)] [string] $Directory,
        [Parameter(Mandatory)] [object] $Record
    )

    try
    {
        Assert-DeliveryMatches -Directory $Directory -Record $Record -Label "Delivery"
        return $true
    }
    catch
    {
        return $false
    }
}

function Write-AtomicText
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Text
    )

    $nextPath = "$Path.next"
    if (Test-Path -LiteralPath $nextPath)
    {
        [void] (Assert-NoReparseComponents -Path $nextPath -Label "Transaction metadata temp" -RequireExisting)
        Remove-Item -LiteralPath $nextPath -Force
    }
    $encoding = [System.Text.UTF8Encoding]::new($false)
    $bytes = $encoding.GetBytes($Text)
    $stream = [System.IO.File]::Open(
        $nextPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None)
    try
    {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    finally
    {
        $stream.Dispose()
    }
    [System.IO.File]::Move($nextPath, $Path, $true)
}

function Remove-ExactFlatDirectory
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string[]] $AllowedNames,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not (Test-Path -LiteralPath $Path)) { return }
    [void] (Assert-NoReparseComponents -Path $Path -Label $Label -RequireExisting)
    $item = Get-Item -LiteralPath $Path -Force
    if (-not $item.PSIsContainer) { throw "$Label is not a directory: $Path" }
    foreach ($entry in @(Get-ChildItem -LiteralPath $Path -Force))
    {
        if ($entry.PSIsContainer -or
            $entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint -or
            $AllowedNames -cnotcontains $entry.Name)
        {
            throw "$Label contains an unexpected entry: $($entry.FullName)"
        }
    }
    Remove-Item -LiteralPath $Path -Recurse -Force
}

function Move-OrdinaryDirectoryAside
{
    param(
        [Parameter(Mandatory)] [string] $Source,
        [Parameter(Mandatory)] [string] $Destination,
        [Parameter(Mandatory)] [string] $Label
    )

    [void] (Assert-NoReparseComponents -Path $Source -Label $Label -RequireExisting)
    $item = Get-Item -LiteralPath $Source -Force
    if (-not $item.PSIsContainer -or
        $item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "$Label is not an ordinary directory: $Source"
    }
    if (Test-Path -LiteralPath $Destination)
    {
        throw "$Label destination already exists: $Destination"
    }
    [System.IO.Directory]::Move($Source, $Destination)
}

function Assert-TransactionRootShape
{
    param([Parameter(Mandatory)] [string] $Path)

    $allowed = @(
        "manifest.json", "manifest.json.next", "state.txt", "state.txt.next",
        "next", "publish", "audit", "previous", "failed")
    foreach ($entry in @(Get-ChildItem -LiteralPath $Path -Force))
    {
        if ($allowed -cnotcontains $entry.Name -or
            $entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
        {
            throw "Transaction root contains an unexpected entry: $($entry.FullName)"
        }
    }
}

function Remove-TransactionMetadata
{
    param([Parameter(Mandatory)] [string] $Root)

    foreach ($name in @("manifest.json.next", "manifest.json", "state.txt.next", "state.txt"))
    {
        $path = Join-Path $Root $name
        if (Test-Path -LiteralPath $path)
        {
            [void] (Assert-NoReparseComponents -Path $path -Label "Transaction metadata" -RequireExisting)
            Remove-Item -LiteralPath $path -Force
        }
    }
    $remaining = @(Get-ChildItem -LiteralPath $Root -Force)
    if ($remaining.Count -ne 0)
    {
        throw "Transaction root is not empty after metadata cleanup: $Root"
    }
    Remove-Item -LiteralPath $Root -Force
}

function Remove-TerminalTransaction
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Manifest,
        [Parameter(Mandatory)] [string] $State,
        [Parameter(Mandatory)] [string] $TerminalState,
        [Parameter(Mandatory)] [string] $Failure
    )

    # Payload retirement happens before this durable terminal marker. Once it
    # exists, recovery is cleanup-only even if either metadata file disappears.
    Write-AtomicText -Path $State -Text $TerminalState

    $manifestNext = "$Manifest.next"
    if (Test-Path -LiteralPath $manifestNext)
    {
        [void] (Assert-NoReparseComponents -Path $manifestNext `
            -Label "Transaction manifest temp" -RequireExisting)
        Remove-Item -LiteralPath $manifestNext -Force
    }
    if (Test-Path -LiteralPath $Manifest)
    {
        [void] (Assert-NoReparseComponents -Path $Manifest `
            -Label "Transaction manifest" -RequireExisting)
        if ($Failure -ceq "ManifestLocked")
        {
            $lockedManifest = [System.IO.File]::Open(
                $Manifest,
                [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read,
                [System.IO.FileShare]::Read)
            try
            {
                Remove-Item -LiteralPath $Manifest -Force
            }
            finally
            {
                $lockedManifest.Dispose()
            }
        }
        else
        {
            Remove-Item -LiteralPath $Manifest -Force
        }
    }

    $stateNext = "$State.next"
    if (Test-Path -LiteralPath $stateNext)
    {
        [void] (Assert-NoReparseComponents -Path $stateNext `
            -Label "Transaction state temp" -RequireExisting)
        Remove-Item -LiteralPath $stateNext -Force
    }
    if (Test-Path -LiteralPath $State)
    {
        [void] (Assert-NoReparseComponents -Path $State `
            -Label "Transaction state" -RequireExisting)
        if ($Failure -ceq "StateLocked")
        {
            $lockedState = [System.IO.File]::Open(
                $State,
                [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read,
                [System.IO.FileShare]::Read)
            try
            {
                Remove-Item -LiteralPath $State -Force
            }
            finally
            {
                $lockedState.Dispose()
            }
        }
        else
        {
            Remove-Item -LiteralPath $State -Force
        }
    }

    $remaining = @(Get-ChildItem -LiteralPath $Root -Force)
    if ($remaining.Count -ne 0)
    {
        throw "Terminal transaction root is not empty after cleanup: $Root"
    }
    if ($Failure -ceq "RootRemoval")
    {
        throw "Injected transaction root cleanup interruption: $Root"
    }
    Remove-Item -LiteralPath $Root -Force
}

function Open-ImmutableReadHandle
{
    param([Parameter(Mandatory)] [string] $Path)

    return [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
}

function Get-OpenFileIdentity
{
    param([Parameter(Mandatory)] [System.IO.FileStream] $Handle)

    return [Vibris.DeliveryFileIdentity]::GetIdentity($Handle.SafeFileHandle)
}

function Get-OpenFileLinkCount
{
    param([Parameter(Mandatory)] [System.IO.FileStream] $Handle)

    return [uint32] [Vibris.DeliveryFileIdentity]::GetLinkCount($Handle.SafeFileHandle)
}

function New-VerifiedHardLink
{
    param(
        [Parameter(Mandatory)] [string] $Source,
        [Parameter(Mandatory)] [string] $Target,
        [Parameter(Mandatory)] [object] $Record,
        [Parameter(Mandatory)] [string] $ExpectedIdentity,
        [Parameter(Mandatory)] [string] $Label
    )

    $sourceFile = Resolve-OrdinaryFile -Path $Source -Label "$Label source"
    if (Test-Path -LiteralPath $Target)
    {
        throw "$Label target already exists: $Target"
    }
    $targetParent = Split-Path -Parent $Target
    [void] (Assert-NoReparseComponents -Path $targetParent `
        -Label "$Label parent" -RequireExisting)
    try
    {
        [Vibris.DeliveryFileIdentity]::CreateHardLink($Target, $sourceFile)
    }
    catch
    {
        throw "$Label requires a same-volume hard link and has no copy fallback: $($_.Exception.Message)"
    }

    $targetHandle = $null
    try
    {
        $targetFile = Resolve-OrdinaryFile -Path $Target -Label "$Label target"
        $targetHandle = Open-ImmutableReadHandle -Path $targetFile
        $targetIdentity = Get-OpenFileIdentity -Handle $targetHandle
        if ($targetIdentity -cne $ExpectedIdentity)
        {
            throw "$Label did not preserve the audited file identity."
        }
        if ((Get-OpenFileLinkCount -Handle $targetHandle) -ne 2)
        {
            throw "$Label has an invalid link count; expected 2."
        }
        Assert-RecordMatches -Path $targetFile -Record $Record -Label $Label
    }
    finally
    {
        if ($null -ne $targetHandle) { $targetHandle.Dispose() }
    }
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

    $root = ConvertTo-NormalizedPath -Path $Root
    [void] (Assert-NoReparseComponents -Path $root -Label "Receipt repository root" -RequireExisting)
    $gitRoot = Invoke-GitText -Root $root -Arguments @(
        "rev-parse", "--show-toplevel") -Label "Git root read"
    $gitRoot = ConvertTo-NormalizedPath -Path $gitRoot
    if (-not [string]::Equals($root, $gitRoot, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Receipt repository root is not an exact Git worktree root: $root"
    }

    $head = Invoke-GitText -Root $root -Arguments @("rev-parse", "HEAD") -Label "Git HEAD read"
    $diff = Invoke-GitText -Root $root -Arguments @(
        "diff", "--no-ext-diff", "--binary", "HEAD", "--") -Label "Git source diff"
    $submodules = Invoke-GitText -Root $root -Arguments @(
        "submodule", "status", "--recursive") -Label "Git submodule state"
    $untrackedText = Invoke-GitText -Root $root -Arguments @(
        "ls-files", "-z", "--others", "--exclude-standard", "--", ".", ":(exclude).vibris/**") -Label "Git untracked source list"
    $untracked = [System.Collections.Generic.List[object]]::new()
    foreach ($relative in @($untrackedText -split [char] 0 | Where-Object { $_.Length -ne 0 } |
        Sort-Object -CaseSensitive))
    {
        $path = Join-Path $root $relative
        $path = Resolve-OrdinaryFile -Path $path -Label "Git untracked source"
        $item = Get-Item -LiteralPath $path -Force
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

function ConvertTo-ReceiptUtc
{
    param([Parameter(Mandatory)] [object] $Value, [Parameter(Mandatory)] [string] $Label)

    try
    {
        if ($Value -is [datetime])
        {
            return ([datetime] $Value).ToUniversalTime()
        }
        if ($Value -is [datetimeoffset])
        {
            return ([datetimeoffset] $Value).UtcDateTime
        }
        return [datetime]::ParseExact(
            [string] $Value,
            "o",
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
    }
    catch
    {
        throw "$Label is not a round-trip UTC timestamp."
    }
}

function Assert-ReceiptArtifact
{
    param(
        [Parameter(Mandatory)] [object] $Record,
        [Parameter(Mandatory)] [string] $ExpectedPath,
        [Parameter(Mandatory)] [string] $Label,
        [datetime] $ProducedAfterUtc,
        [datetime] $ProducedBeforeUtc
    )

    $path = Resolve-OrdinaryFile -Path $ExpectedPath -Label $Label
    $recordPath = ConvertTo-NormalizedPath -Path ([string] $Record.path)
    if (-not [string]::Equals($path, $recordPath, [System.StringComparison]::OrdinalIgnoreCase) -or
        [long] $Record.length -lt 0 -or
        [string] $Record.sha256 -cnotmatch '^[0-9A-F]{64}$')
    {
        throw "$Label has invalid or mismatched build provenance."
    }
    Assert-RecordMatches -Path $path -Record $Record -Label $Label
    $recordWriteUtc = ConvertTo-ReceiptUtc -Value $Record.last_write_utc `
        -Label "$Label last_write_utc"
    $actualWriteUtc = (Get-Item -LiteralPath $path -Force).LastWriteTimeUtc
    if ($recordWriteUtc.Ticks -ne $actualWriteUtc.Ticks)
    {
        throw "$Label write time does not match its build receipt."
    }
    if ($PSBoundParameters.ContainsKey("ProducedAfterUtc") -and
        ($actualWriteUtc -lt $ProducedAfterUtc.AddSeconds(-2) -or
            $actualWriteUtc -gt $ProducedBeforeUtc.AddSeconds(2)))
    {
        throw "$Label is stale for the recorded build phase."
    }
}

function Assert-ReceiptSourceCurrent
{
    param(
        [Parameter(Mandatory)] [object] $Receipt,
        [Parameter(Mandatory)] [string] $VibrisRoot,
        [Parameter(Mandatory)] [string] $IrisRoot
    )

    $currentVibris = Get-RepositorySourceState -Root $VibrisRoot
    $currentIris = Get-RepositorySourceState -Root $IrisRoot
    if ([string] $Receipt.source.vibris.head -cne $currentVibris.head -or
        [string] $Receipt.source.vibris.fingerprint -cne $currentVibris.fingerprint -or
        [string] $Receipt.source.iris.head -cne $currentIris.head -or
        [string] $Receipt.source.iris.fingerprint -cne $currentIris.fingerprint)
    {
        throw "Repository source does not match the recorded delivery build session."
    }
}

function Assert-BuildSessionLockActive
{
    param([Parameter(Mandatory)] [string] $Path)

    $probe = $null
    try
    {
        $probe = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
    }
    catch [System.IO.IOException]
    {
        return
    }
    finally
    {
        if ($null -ne $probe) { $probe.Dispose() }
    }
    throw "Build receipt session is not active: $Path"
}

$repoRoot = ConvertTo-NormalizedPath -Path (Join-Path $PSScriptRoot "..")
$buildRoot = Ensure-OrdinaryDirectory -Path (Join-Path $repoRoot "build") -Label "Vibris build root"
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $buildRoot "delivery" }
$OutputDirectory = ConvertTo-NormalizedPath -Path $OutputDirectory
[void] (Assert-ContainedPath -Root $buildRoot -Candidate $OutputDirectory -Label "OutputDirectory")
[void] (Assert-NoReparseComponents -Path $OutputDirectory -Label "OutputDirectory")
$outputParent = Ensure-OrdinaryDirectory -Path (Split-Path -Parent $OutputDirectory) -Label "Delivery parent"

$McpExe = Resolve-OrdinaryFile -Path $McpExe -Label "MCP executable"
$VibrisModJar = Resolve-OrdinaryFile -Path $VibrisModJar -Label "Vibris mod JAR"
if ((Split-Path -Leaf $McpExe) -cne "vibris-mcp.exe")
{
    throw "MCP executable must be named vibris-mcp.exe: $McpExe"
}
if ((Split-Path -Leaf $VibrisModJar) -cnotmatch '^vibris-fabric-.+[.]jar$')
{
    throw "Vibris mod JAR must be an vibris-fabric-*.jar artifact: $VibrisModJar"
}

$receiptRoot = ConvertTo-NormalizedPath -Path (Join-Path $buildRoot ".delivery-receipts")
[void] (Assert-NoReparseComponents -Path $receiptRoot -Label "Build receipt root" -RequireExisting)
$receiptRootItem = Get-Item -LiteralPath $receiptRoot -Force
if (-not $receiptRootItem.PSIsContainer)
{
    throw "Build receipt root is not a directory: $receiptRoot"
}
$BuildReceipt = Resolve-OrdinaryFile -Path $BuildReceipt -Label "Build receipt"
[void] (Assert-ContainedPath -Root $receiptRoot -Candidate $BuildReceipt -Label "BuildReceipt")
try
{
    $receipt = Get-Content -Raw -LiteralPath $BuildReceipt | ConvertFrom-Json
}
catch
{
    throw "Build receipt is malformed: $BuildReceipt"
}
if ([int] $receipt.schema_version -ne 2)
{
    throw "Build receipt has an unsupported schema: $BuildReceipt"
}
$sessionId = [guid]::Empty
if (-not [guid]::TryParseExact([string] $receipt.session_id, "D", [ref] $sessionId) -or
    (Split-Path -Leaf $BuildReceipt) -cne "$($sessionId.ToString("D")).json")
{
    throw "Build receipt has an invalid session identity: $BuildReceipt"
}
$receiptVibrisRoot = ConvertTo-NormalizedPath -Path ([string] $receipt.vibris_root)
$receiptIrisRoot = ConvertTo-NormalizedPath -Path ([string] $receipt.iris_root)
if (-not [string]::Equals($repoRoot, $receiptVibrisRoot,
        [System.StringComparison]::OrdinalIgnoreCase))
{
    throw "Build receipt belongs to a different Vibris root."
}
$sessionLockPath = Resolve-OrdinaryFile -Path ([string] $receipt.session_lock_path) `
    -Label "Build receipt session lock"
$expectedSessionLock = Join-Path $receiptRoot "$($sessionId.ToString("D")).lock"
if (-not [string]::Equals($sessionLockPath, $expectedSessionLock,
        [System.StringComparison]::OrdinalIgnoreCase))
{
    throw "Build receipt session lock path is invalid."
}
Assert-BuildSessionLockActive -Path $sessionLockPath

$buildStarted = ConvertTo-ReceiptUtc -Value $receipt.build_started_utc `
    -Label "Build receipt build_started_utc"
$buildCompleted = ConvertTo-ReceiptUtc -Value $receipt.build_completed_utc `
    -Label "Build receipt build_completed_utc"
$phaseTimes = @{}
foreach ($phaseName in @("native", "protocol", "mod"))
{
    $phase = $receipt.phases.$phaseName
    $started = ConvertTo-ReceiptUtc -Value $phase.started_utc `
        -Label "Build receipt $phaseName started_utc"
    $completed = ConvertTo-ReceiptUtc -Value $phase.completed_utc `
        -Label "Build receipt $phaseName completed_utc"
    if ($started -lt $buildStarted -or $completed -lt $started -or
        $completed -gt $buildCompleted)
    {
        throw "Build receipt has an invalid $phaseName phase interval."
    }
    $phaseTimes[$phaseName] = [pscustomobject] @{ started = $started; completed = $completed }
}
if ($buildCompleted -lt $buildStarted -or $buildCompleted -gt [datetime]::UtcNow.AddMinutes(2))
{
    throw "Build receipt has an invalid build interval."
}

$validatedAuditSources = [ordered] @{
    java = Resolve-OrdinaryFile -Path (Join-Path $repoRoot `
        "protocol-java\build\libs\vibris-protocol-java.jar") -Label "Java protocol JAR"
    cpp = Resolve-OrdinaryFile -Path (Join-Path $repoRoot `
        "mcp\out\build\Release\vibris-descriptor-dump.exe") -Label "C++ descriptor dump"
    proto = Resolve-OrdinaryFile -Path (Join-Path $repoRoot `
        "proto\vibris_control.proto") -Label "Protocol schema"
}
$buildScript = Resolve-OrdinaryFile -Path (Join-Path $repoRoot "tools\build-delivery.ps1") `
    -Label "Delivery build script"
$gitProcessScript = Resolve-OrdinaryFile -Path (Join-Path $repoRoot "tools\git-process.ps1") `
    -Label "Git process script"
$packageScript = Resolve-OrdinaryFile -Path $PSCommandPath -Label "Delivery package script"
Assert-ReceiptSourceCurrent -Receipt $receipt -VibrisRoot $receiptVibrisRoot `
    -IrisRoot $receiptIrisRoot
Assert-ReceiptArtifact -Record $receipt.scripts.build -ExpectedPath $buildScript `
    -Label "Build script provenance"
Assert-ReceiptArtifact -Record $receipt.scripts.git_process -ExpectedPath $gitProcessScript `
    -Label "Git process script provenance"
Assert-ReceiptArtifact -Record $receipt.scripts.package -ExpectedPath $packageScript `
    -Label "Package script provenance"
Assert-ReceiptArtifact -Record $receipt.artifacts.mcp -ExpectedPath $McpExe `
    -Label "MCP build provenance" -ProducedAfterUtc $phaseTimes.native.started `
    -ProducedBeforeUtc $phaseTimes.native.completed
Assert-ReceiptArtifact -Record $receipt.artifacts.mod -ExpectedPath $VibrisModJar `
    -Label "Vibris mod build provenance" -ProducedAfterUtc $phaseTimes.mod.started `
    -ProducedBeforeUtc $phaseTimes.mod.completed
Assert-ReceiptArtifact -Record $receipt.artifacts.java_descriptor `
    -ExpectedPath $validatedAuditSources.java -Label "Java descriptor provenance" `
    -ProducedAfterUtc $phaseTimes.protocol.started -ProducedBeforeUtc $phaseTimes.protocol.completed
Assert-ReceiptArtifact -Record $receipt.artifacts.cpp_descriptor `
    -ExpectedPath $validatedAuditSources.cpp -Label "C++ descriptor provenance" `
    -ProducedAfterUtc $phaseTimes.native.started -ProducedBeforeUtc $phaseTimes.native.completed
Assert-ReceiptArtifact -Record $receipt.artifacts.proto `
    -ExpectedPath $validatedAuditSources.proto -Label "Protocol schema provenance"
$receiptRecord = Get-FileRecord -Path $BuildReceipt -Name (Split-Path -Leaf $BuildReceipt)

$testBeforeRecord = [bool] $TestBeforeRecordReady -or [bool] $TestBeforeRecordAck
if ($testBeforeRecord)
{
    if (-not $TestBeforeRecordReady -or -not $TestBeforeRecordAck)
    {
        throw "Both pre-record test hook paths are required."
    }
    $testHookRoot = ConvertTo-NormalizedPath -Path (Join-Path $buildRoot ".delivery-test-hooks")
    [void] (Assert-NoReparseComponents -Path $testHookRoot `
        -Label "Pre-record test hook root" -RequireExisting)
    $TestBeforeRecordReady = ConvertTo-NormalizedPath -Path $TestBeforeRecordReady
    $TestBeforeRecordAck = ConvertTo-NormalizedPath -Path $TestBeforeRecordAck
    $testHookSession = Split-Path -Parent $TestBeforeRecordReady
    $testHookSessionId = [guid]::Empty
    if (-not [string]::Equals($testHookSession, (Split-Path -Parent $TestBeforeRecordAck),
            [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [guid]::TryParseExact(
            (Split-Path -Leaf $testHookSession), "D", [ref] $testHookSessionId) -or
        (Split-Path -Leaf $TestBeforeRecordReady) -cne "ready.txt" -or
        (Split-Path -Leaf $TestBeforeRecordAck) -cne "ack.txt")
    {
        throw "Pre-record test hooks must use one GUID session with ready.txt and ack.txt."
    }
    [void] (Assert-ContainedPath -Root $testHookRoot -Candidate $testHookSession `
        -Label "Pre-record test hook session")
    [void] (Assert-NoReparseComponents -Path $testHookSession `
        -Label "Pre-record test hook session" -RequireExisting)
    if ((Test-Path -LiteralPath $TestBeforeRecordReady) -or
        (Test-Path -LiteralPath $TestBeforeRecordAck))
    {
        throw "Pre-record test hook files already exist."
    }
}

$testAfterAudit = [bool] $TestAfterAuditReady -or [bool] $TestAfterAuditAck
if ($testAfterAudit)
{
    if (-not $TestAfterAuditReady -or -not $TestAfterAuditAck)
    {
        throw "Both after-audit test hook paths are required."
    }
    $testHookRoot = ConvertTo-NormalizedPath -Path (Join-Path $buildRoot ".delivery-test-hooks")
    [void] (Assert-NoReparseComponents -Path $testHookRoot `
        -Label "After-audit test hook root" -RequireExisting)
    $TestAfterAuditReady = ConvertTo-NormalizedPath -Path $TestAfterAuditReady
    $TestAfterAuditAck = ConvertTo-NormalizedPath -Path $TestAfterAuditAck
    $testHookSession = Split-Path -Parent $TestAfterAuditReady
    $testHookSessionId = [guid]::Empty
    if (-not [string]::Equals($testHookSession, (Split-Path -Parent $TestAfterAuditAck),
            [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [guid]::TryParseExact(
            (Split-Path -Leaf $testHookSession), "D", [ref] $testHookSessionId) -or
        (Split-Path -Leaf $TestAfterAuditReady) -cne "after-audit-ready.txt" -or
        (Split-Path -Leaf $TestAfterAuditAck) -cne "after-audit-ack.txt")
    {
        throw "After-audit test hooks must use one GUID session with after-audit-ready.txt and after-audit-ack.txt."
    }
    [void] (Assert-ContainedPath -Root $testHookRoot -Candidate $testHookSession `
        -Label "After-audit test hook session")
    [void] (Assert-NoReparseComponents -Path $testHookSession `
        -Label "After-audit test hook session" -RequireExisting)
    if ((Test-Path -LiteralPath $TestAfterAuditReady) -or
        (Test-Path -LiteralPath $TestAfterAuditAck))
    {
        throw "After-audit test hook files already exist."
    }
}

if ((Test-IsSameOrDescendant -Root $OutputDirectory -Candidate $McpExe) -or
    (Test-IsSameOrDescendant -Root $OutputDirectory -Candidate $VibrisModJar) -or
    (Test-IsSameOrDescendant -Root $OutputDirectory -Candidate $BuildReceipt) -or
    (Test-IsSameOrDescendant -Root $receiptRoot -Candidate $OutputDirectory))
{
    throw "Explicit delivery inputs and receipt storage must not overlap OutputDirectory."
}

$targetHasher = [System.Security.Cryptography.SHA256]::Create()
try
{
    $targetBytes = [System.Text.Encoding]::UTF8.GetBytes($OutputDirectory.ToUpperInvariant())
    $targetKey = [System.Convert]::ToHexString($targetHasher.ComputeHash($targetBytes))
}
finally
{
    $targetHasher.Dispose()
}
$transactionsRoot = Ensure-OrdinaryDirectory -Path (Join-Path $buildRoot ".delivery-transactions") `
    -Label "Delivery transaction root"
if (Test-IsSameOrDescendant -Root $transactionsRoot -Candidate $OutputDirectory)
{
    throw "OutputDirectory must not be inside the private transaction root."
}
$transactionRoot = Ensure-OrdinaryDirectory -Path (Join-Path $transactionsRoot $targetKey) `
    -Label "Target transaction root"
foreach ($input in @($McpExe, $VibrisModJar, $BuildReceipt, $sessionLockPath))
{
    if (Test-IsSameOrDescendant -Root $transactionRoot -Candidate $input)
    {
        throw "Explicit input must not be inside its private transaction root: $input"
    }
}

$manifestPath = Join-Path $transactionRoot "manifest.json"
$statePath = Join-Path $transactionRoot "state.txt"
$nextDirectory = Join-Path $transactionRoot "next"
$publishDirectory = Join-Path $transactionRoot "publish"
$auditDirectory = Join-Path $transactionRoot "audit"
$previousDirectory = Join-Path $transactionRoot "previous"
$failedDirectory = Join-Path $transactionRoot "failed"
$lockPath = Join-Path $buildRoot ".delivery.lock"
[void] (Assert-NoReparseComponents -Path $lockPath -Label "Delivery lock")
$deliveryLock = $null
$immutableHandles = [System.Collections.Generic.List[System.IDisposable]]::new()
$publishSources = [System.Collections.Generic.List[object]]::new()
$publishedHandles = [System.Collections.Generic.List[System.IDisposable]]::new()
$committed = $false
$cleanupOnly = $false
$cleanupPending = $false
$manifest = $null
$testExtraLinkPath = $null
$recoveryFailure = $null

try
{
    try
    {
        $deliveryLock = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
    }
    catch [System.IO.IOException]
    {
        throw "Another delivery publication is already running: $lockPath"
    }
    [void] (Assert-NoReparseComponents -Path $lockPath -Label "Delivery lock" -RequireExisting)
    Assert-TransactionRootShape -Path $transactionRoot

    if (Test-Path -LiteralPath $manifestPath)
    {
        $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
        if ([int] $manifest.schema_version -ne 2 -or
            [string] $manifest.target_path -cne $OutputDirectory -or
            [string] $manifest.target_sha256 -cne $targetKey)
        {
            throw "Transaction manifest does not belong to OutputDirectory: $manifestPath"
        }
        Assert-DeliveryRecordShape -Record $manifest.new -Label "Manifest new delivery"
        Assert-DeliveryRecordShape -Record $manifest.previous -Label "Manifest previous delivery"
        if (-not (Test-Path -LiteralPath $statePath -PathType Leaf))
        {
            throw "Transaction state marker is missing: $statePath"
        }
        $state = (Get-Content -Raw -LiteralPath $statePath).Trim()
        if ($state -ceq "COMMITTED" -or $state -ceq "CLEANUP_COMPLETE")
        {
            # Both states are irreversible. CLEANUP_COMPLETE additionally says
            # all payload directories were retired before metadata teardown.
            $committed = $true
            Assert-DeliveryMatches -Directory $OutputDirectory -Record $manifest.new -Label "Committed delivery"
            if ([bool] $manifest.previous.present)
            {
                Remove-ExactFlatDirectory -Path $previousDirectory `
                    -AllowedNames @(Get-DeliveryRecordNames -Record $manifest.previous) `
                    -Label "Committed rollback"
            }
            Remove-ExactFlatDirectory -Path $nextDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Committed next snapshot"
            Remove-ExactFlatDirectory -Path $publishDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Committed publish snapshot"
            Remove-ExactFlatDirectory -Path $auditDirectory `
                -AllowedNames @("vibris-protocol-java.jar", "vibris-descriptor-dump.exe", "vibris_control.proto") `
                -Label "Committed audit snapshot"
            Remove-ExactFlatDirectory -Path $failedDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Committed failed snapshot"
            Remove-TerminalTransaction -Root $transactionRoot `
                -Manifest $manifestPath -State $statePath -TerminalState "CLEANUP_COMPLETE" `
                -Failure $TestCleanupFailure
            Write-Output "RECOVERED committed_delivery=$OutputDirectory"
        }
        elseif ($state -ceq "ABORT_CLEANUP_COMPLETE")
        {
            $cleanupOnly = $true
            Assert-DeliveryMatches -Directory $OutputDirectory -Record $manifest.previous `
                -Label "Aborted last-good delivery"
            Remove-ExactFlatDirectory -Path $nextDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Aborted terminal next snapshot"
            Remove-ExactFlatDirectory -Path $publishDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Aborted terminal publish snapshot"
            Remove-ExactFlatDirectory -Path $auditDirectory `
                -AllowedNames @("vibris-protocol-java.jar", "vibris-descriptor-dump.exe", "vibris_control.proto") `
                -Label "Aborted terminal audit snapshot"
            Remove-ExactFlatDirectory -Path $previousDirectory `
                -AllowedNames @(Get-DeliveryRecordNames -Record $manifest.previous) `
                -Label "Aborted terminal rollback"
            Remove-ExactFlatDirectory -Path $failedDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Aborted terminal failed snapshot"
            Remove-TerminalTransaction -Root $transactionRoot `
                -Manifest $manifestPath -State $statePath `
                -TerminalState "ABORT_CLEANUP_COMPLETE" -Failure $TestCleanupFailure
            Write-Output "RECOVERED abort_cleanup_complete=$OutputDirectory"
            $cleanupOnly = $false
        }
        else
        {
            $knownStates = @(
                "PREPARING", "PREPARED", "OLD_MOVE_INTENT", "OLD_MOVED",
                "PUBLISH_INTENT", "PUBLISHED")
            if ($knownStates -cnotcontains $state)
            {
                throw "Unknown delivery transaction state '$state'."
            }
            if ([bool] $manifest.previous.present)
            {
                if (Test-Path -LiteralPath $previousDirectory)
                {
                    Assert-DeliveryMatches -Directory $previousDirectory -Record $manifest.previous `
                        -Label "Recoverable rollback"
                    if (Test-Path -LiteralPath $OutputDirectory)
                    {
                        Move-OrdinaryDirectoryAside -Source $OutputDirectory `
                            -Destination $failedDirectory -Label "Recoverable current delivery"
                    }
                    [System.IO.Directory]::Move($previousDirectory, $OutputDirectory)
                    Assert-DeliveryMatches -Directory $OutputDirectory -Record $manifest.previous `
                        -Label "Restored last-good delivery"
                }
                elseif (-not (Test-DeliveryMatches -Directory $OutputDirectory -Record $manifest.previous))
                {
                    throw "Recoverable transaction has neither a complete rollback nor the last-good target."
                }
            }
            elseif (Test-Path -LiteralPath $OutputDirectory)
            {
                try
                {
                    if ($state -cnotin @("PUBLISH_INTENT", "PUBLISHED"))
                    {
                        throw "First-publication recovery lacks a published identity handoff."
                    }
                    Assert-DeliveryMatches -Directory $nextDirectory -Record $manifest.new `
                        -Label "First-publication audited snapshot"
                    Assert-DeliveryMatches -Directory $OutputDirectory -Record $manifest.new `
                        -Label "First-publication recovery output"
                    $recoveryFiles = @(
                        [pscustomobject] @{
                            name = [string] $manifest.new.mcp.name
                            record = $manifest.new.mcp
                        },
                        [pscustomobject] @{
                            name = [string] $manifest.new.mod.name
                            record = $manifest.new.mod
                        })
                    foreach ($file in $recoveryFiles)
                    {
                        $snapshotPath = Join-Path $nextDirectory ([string] $file.name)
                        $outputPath = Join-Path $OutputDirectory ([string] $file.name)
                        $snapshotHandle = Open-ImmutableReadHandle -Path $snapshotPath
                        $immutableHandles.Add($snapshotHandle)
                        $outputHandle = Open-ImmutableReadHandle -Path $outputPath
                        $publishedHandles.Add($outputHandle)
                        if ((Get-OpenFileIdentity -Handle $snapshotHandle) -cne
                            (Get-OpenFileIdentity -Handle $outputHandle))
                        {
                            throw "First-publication recovery output identity differs from its audited snapshot: $outputPath"
                        }
                        if ((Get-OpenFileLinkCount -Handle $snapshotHandle) -ne 2 -or
                            (Get-OpenFileLinkCount -Handle $outputHandle) -ne 2)
                        {
                            throw "First-publication recovery identity has an invalid link count: $outputPath"
                        }
                    }
                    Assert-DeliveryMatches -Directory $nextDirectory -Record $manifest.new `
                        -Label "Held first-publication audited snapshot"
                    Assert-DeliveryMatches -Directory $OutputDirectory -Record $manifest.new `
                        -Label "Held first-publication recovery output"

                    foreach ($handle in $immutableHandles) { $handle.Dispose() }
                    $immutableHandles.Clear()
                    Remove-ExactFlatDirectory -Path $nextDirectory `
                        -AllowedNames @(
                            [string] $manifest.new.mcp.name,
                            [string] $manifest.new.mod.name) `
                        -Label "Retired first-publication audited snapshot"
                    for ($index = 0; $index -lt $recoveryFiles.Count; ++$index)
                    {
                        $file = $recoveryFiles[$index]
                        $outputPath = Join-Path $OutputDirectory ([string] $file.name)
                        if ((Get-OpenFileLinkCount -Handle $publishedHandles[$index]) -ne 1)
                        {
                            throw "First-publication recovery output has an invalid committed link count: $outputPath"
                        }
                        Assert-RecordMatches -Path $outputPath -Record $file.record `
                            -Label "Committed first-publication recovery output"
                    }
                    Write-AtomicText -Path $statePath -Text "COMMITTED"
                    $state = "COMMITTED"
                    $committed = $true
                }
                catch
                {
                    $recoveryFailure = $_
                    foreach ($handle in $publishedHandles) { $handle.Dispose() }
                    $publishedHandles.Clear()
                    foreach ($handle in $immutableHandles) { $handle.Dispose() }
                    $immutableHandles.Clear()
                    if (Test-Path -LiteralPath $OutputDirectory)
                    {
                        Move-OrdinaryDirectoryAside -Source $OutputDirectory `
                            -Destination $failedDirectory -Label "Failed first publication recovery"
                    }
                }
                foreach ($handle in $publishedHandles) { $handle.Dispose() }
                $publishedHandles.Clear()
            }

            if ($state -cne "COMMITTED")
            {
                Remove-ExactFlatDirectory -Path $nextDirectory `
                    -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                    -Label "Aborted next snapshot"
                Remove-ExactFlatDirectory -Path $publishDirectory `
                    -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                    -Label "Aborted publish snapshot"
                Remove-ExactFlatDirectory -Path $auditDirectory `
                    -AllowedNames @("vibris-protocol-java.jar", "vibris-descriptor-dump.exe", "vibris_control.proto") `
                    -Label "Aborted audit snapshot"
                Remove-ExactFlatDirectory -Path $failedDirectory `
                    -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                    -Label "Aborted failed snapshot"
                $cleanupOnly = $true
                Remove-TerminalTransaction -Root $transactionRoot `
                    -Manifest $manifestPath -State $statePath `
                    -TerminalState "ABORT_CLEANUP_COMPLETE" -Failure $TestCleanupFailure
                if ($null -ne $recoveryFailure)
                {
                    Write-Output "RECOVERED rejected_first_delivery=$OutputDirectory"
                }
                else
                {
                    Write-Output "RECOVERED last_good_delivery=$OutputDirectory"
                }
                $cleanupOnly = $false
            }
            else
            {
                Remove-ExactFlatDirectory -Path $nextDirectory `
                    -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                    -Label "Recovered committed next snapshot"
                Remove-ExactFlatDirectory -Path $publishDirectory `
                    -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                    -Label "Recovered committed publish snapshot"
                Remove-ExactFlatDirectory -Path $auditDirectory `
                    -AllowedNames @("vibris-protocol-java.jar", "vibris-descriptor-dump.exe", "vibris_control.proto") `
                    -Label "Recovered committed audit snapshot"
                Remove-ExactFlatDirectory -Path $failedDirectory `
                    -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                    -Label "Recovered committed failed snapshot"
                Remove-TerminalTransaction -Root $transactionRoot `
                    -Manifest $manifestPath -State $statePath -TerminalState "CLEANUP_COMPLETE" `
                    -Failure $TestCleanupFailure
                Write-Output "RECOVERED first_delivery=$OutputDirectory"
            }
        }
        $manifest = $null
        $committed = $false
        $cleanupOnly = $false
        if ($null -ne $recoveryFailure) { throw $recoveryFailure }
    }
    else
    {
        $orphanState = $null
        if (Test-Path -LiteralPath $statePath -PathType Leaf)
        {
            $orphanState = (Get-Content -Raw -LiteralPath $statePath).Trim()
        }
        if ($orphanState -ceq "CLEANUP_COMPLETE")
        {
            $committed = $true
            Remove-TerminalTransaction -Root $transactionRoot `
                -Manifest $manifestPath -State $statePath -TerminalState "CLEANUP_COMPLETE" `
                -Failure $TestCleanupFailure
            Write-Output "RECOVERED cleanup_complete=$OutputDirectory"
            $committed = $false
        }
        elseif ($orphanState -ceq "ABORT_CLEANUP_COMPLETE")
        {
            $cleanupOnly = $true
            Remove-TerminalTransaction -Root $transactionRoot `
                -Manifest $manifestPath -State $statePath `
                -TerminalState "ABORT_CLEANUP_COMPLETE" -Failure $TestCleanupFailure
            Write-Output "RECOVERED abort_cleanup_complete=$OutputDirectory"
            $cleanupOnly = $false
        }
        elseif ($null -ne $orphanState -and $orphanState -cne "PREPARING")
        {
            throw "Transaction manifest is missing for state '$orphanState'."
        }
        else
        {
            if (Test-Path -LiteralPath $previousDirectory)
            {
                throw "Transaction rollback exists without a manifest: $previousDirectory"
            }
            Remove-ExactFlatDirectory -Path $nextDirectory -AllowedNames @(
                "vibris-mcp.exe", (Split-Path -Leaf $VibrisModJar)) -Label "Uncommitted next snapshot"
            Remove-ExactFlatDirectory -Path $publishDirectory -AllowedNames @(
                "vibris-mcp.exe", (Split-Path -Leaf $VibrisModJar)) `
                -Label "Uncommitted publish snapshot"
            Remove-ExactFlatDirectory -Path $auditDirectory -AllowedNames @(
                "vibris-protocol-java.jar", "vibris-descriptor-dump.exe", "vibris_control.proto") `
                -Label "Uncommitted audit snapshot"
            if (Test-Path -LiteralPath $failedDirectory)
            {
                throw "Transaction failed directory exists without a manifest: $failedDirectory"
            }
            Remove-TransactionMetadata -Root $transactionRoot
        }
    }

    if (-not (Test-Path -LiteralPath $transactionRoot))
    {
        [void] (Ensure-OrdinaryDirectory -Path $transactionRoot -Label "Target transaction root")
    }

    if ($testBeforeRecord)
    {
        Write-AtomicText -Path $TestBeforeRecordReady -Text "READY"
        $deadline = [datetime]::UtcNow.AddSeconds(20)
        while (-not (Test-Path -LiteralPath $TestBeforeRecordAck -PathType Leaf))
        {
            if ([datetime]::UtcNow -ge $deadline)
            {
                throw "Timed out waiting for pre-record replacement acknowledgement."
            }
            Start-Sleep -Milliseconds 1
        }
        [void] (Assert-NoReparseComponents -Path $TestBeforeRecordAck `
            -Label "Pre-record test acknowledgement" -RequireExisting)
        if ((Get-Content -Raw -LiteralPath $TestBeforeRecordAck).Trim() -cne "REPLACED")
        {
            throw "Pre-record replacement acknowledgement is invalid."
        }
    }

    $before = Get-ExistingDeliveryRecord -Directory $OutputDirectory
    $auditSources = $validatedAuditSources
    foreach ($input in @($auditSources.java, $auditSources.cpp, $auditSources.proto))
    {
        if (Test-IsSameOrDescendant -Root $transactionRoot -Candidate $input)
        {
            throw "Audit input must not be inside its private transaction root: $input"
        }
    }

    $newRecord = [ordered] @{
        present = $true
        mcp = [ordered] @{
            name = "vibris-mcp.exe"
            length = [long] $receipt.artifacts.mcp.length
            sha256 = [string] $receipt.artifacts.mcp.sha256
        }
        mod = [ordered] @{
            name = Split-Path -Leaf $VibrisModJar
            length = [long] $receipt.artifacts.mod.length
            sha256 = [string] $receipt.artifacts.mod.sha256
        }
    }
    $auditRecord = [ordered] @{
        java = [ordered] @{
            name = "vibris-protocol-java.jar"
            length = [long] $receipt.artifacts.java_descriptor.length
            sha256 = [string] $receipt.artifacts.java_descriptor.sha256
        }
        cpp = [ordered] @{
            name = "vibris-descriptor-dump.exe"
            length = [long] $receipt.artifacts.cpp_descriptor.length
            sha256 = [string] $receipt.artifacts.cpp_descriptor.sha256
        }
        proto = [ordered] @{
            name = "vibris_control.proto"
            length = [long] $receipt.artifacts.proto.length
            sha256 = [string] $receipt.artifacts.proto.sha256
        }
    }
    $manifest = [ordered] @{
        schema_version = 2
        transaction_id = [guid]::NewGuid().ToString()
        target_path = $OutputDirectory
        target_sha256 = $targetKey
        previous = $before
        new = $newRecord
        audit = $auditRecord
    }
    Write-AtomicText -Path $statePath -Text "PREPARING"
    Write-AtomicText -Path $manifestPath -Text ($manifest | ConvertTo-Json -Depth 8)

    [void] (New-Item -ItemType Directory -Path $nextDirectory)
    [void] (New-Item -ItemType Directory -Path $auditDirectory)
    [void] (Assert-NoReparseComponents -Path $nextDirectory -Label "Next snapshot" -RequireExisting)
    [void] (Assert-NoReparseComponents -Path $auditDirectory -Label "Audit snapshot" -RequireExisting)

    $copies = @(
        [pscustomobject] @{
            source = $McpExe
            target = Join-Path $nextDirectory "vibris-mcp.exe"
            record = $newRecord.mcp
        },
        [pscustomobject] @{ source = $VibrisModJar; target = Join-Path $nextDirectory `
            (Split-Path -Leaf $VibrisModJar); record = $newRecord.mod },
        [pscustomobject] @{ source = $auditSources.java; target = Join-Path $auditDirectory `
            "vibris-protocol-java.jar"; record = $auditRecord.java },
        [pscustomobject] @{ source = $auditSources.cpp; target = Join-Path $auditDirectory `
            "vibris-descriptor-dump.exe"; record = $auditRecord.cpp },
        [pscustomobject] @{ source = $auditSources.proto; target = Join-Path $auditDirectory `
            "vibris_control.proto"; record = $auditRecord.proto })
    foreach ($copy in $copies)
    {
        $beforeItem = Get-Item -LiteralPath $copy.source -Force
        $beforeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $copy.source).Hash
        if ($beforeItem.Length -ne [long] $copy.record.length -or
            $beforeHash -cne [string] $copy.record.sha256)
        {
            throw "Input does not match its build receipt before immutable snapshot: $($copy.source)"
        }
        Copy-Item -LiteralPath $copy.source -Destination $copy.target
        $snapshotItem = Get-Item -LiteralPath $copy.target -Force
        $snapshotHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $copy.target).Hash
        $afterItem = Get-Item -LiteralPath $copy.source -Force
        $afterHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $copy.source).Hash
        if ($snapshotItem.Length -ne [long] $copy.record.length -or
            $afterItem.Length -ne [long] $copy.record.length -or
            $snapshotHash -cne [string] $copy.record.sha256 -or
            $afterHash -cne [string] $copy.record.sha256)
        {
            throw "Input changed while creating immutable snapshot: $($copy.source)"
        }
        [void] (Resolve-OrdinaryFile -Path $copy.target -Label "Immutable transaction snapshot")
    }

    foreach ($copy in $copies)
    {
        $handle = Open-ImmutableReadHandle -Path $copy.target
        $immutableHandles.Add($handle)
        if (Test-IsSameOrDescendant -Root $nextDirectory -Candidate $copy.target)
        {
            if ((Get-OpenFileLinkCount -Handle $handle) -ne 1)
            {
                throw "Immutable next snapshot has an invalid initial link count: $($copy.target)"
            }
            $publishSources.Add([pscustomobject] @{
                source = $copy.target
                name = Split-Path -Leaf $copy.target
                record = $copy.record
                identity = Get-OpenFileIdentity -Handle $handle
            })
        }
    }
    Assert-DeliveryMatches -Directory $nextDirectory -Record $newRecord -Label "Immutable next snapshot"

    $auditOutput = @(& (Join-Path $repoRoot "integration-tests\scripts\source-package-audit.ps1") `
        -McpExe (Join-Path $nextDirectory "vibris-mcp.exe") `
        -VibrisModJar (Join-Path $nextDirectory ([string] $newRecord.mod.name)) `
        -DeliveryDirectory $nextDirectory `
        -JavaDescriptor (Join-Path $auditDirectory "vibris-protocol-java.jar") `
        -CppDescriptorDump (Join-Path $auditDirectory "vibris-descriptor-dump.exe") `
        -ProtoPath (Join-Path $auditDirectory "vibris_control.proto") `
        -CallerHoldsDeliveryLock)
    if ($testAfterAudit)
    {
        Write-AtomicText -Path $TestAfterAuditReady -Text "READY"
        $deadline = [datetime]::UtcNow.AddSeconds(20)
        while (-not (Test-Path -LiteralPath $TestAfterAuditAck -PathType Leaf))
        {
            if ([datetime]::UtcNow -ge $deadline)
            {
                throw "Timed out waiting for after-audit replacement acknowledgement."
            }
            Start-Sleep -Milliseconds 1
        }
        [void] (Assert-NoReparseComponents -Path $TestAfterAuditAck `
            -Label "After-audit test acknowledgement" -RequireExisting)
        if ((Get-Content -Raw -LiteralPath $TestAfterAuditAck).Trim() -cne "ATTEMPTED")
        {
            throw "After-audit replacement acknowledgement is invalid."
        }
    }
    Assert-DeliveryMatches -Directory $nextDirectory -Record $newRecord -Label "Audited next snapshot"
    Assert-RecordMatches -Path (Join-Path $auditDirectory "vibris-protocol-java.jar") `
        -Record $auditRecord.java -Label "Audited Java protocol snapshot"
    Assert-RecordMatches -Path (Join-Path $auditDirectory "vibris-descriptor-dump.exe") `
        -Record $auditRecord.cpp -Label "Audited C++ descriptor snapshot"
    Assert-RecordMatches -Path (Join-Path $auditDirectory "vibris_control.proto") `
        -Record $auditRecord.proto -Label "Audited protocol schema snapshot"
    Assert-RecordMatches -Path $BuildReceipt -Record $receiptRecord -Label "Build receipt"
    Assert-BuildSessionLockActive -Path $sessionLockPath
    Assert-ReceiptSourceCurrent -Receipt $receipt -VibrisRoot $receiptVibrisRoot `
        -IrisRoot $receiptIrisRoot
    Assert-ReceiptArtifact -Record $receipt.artifacts.mcp -ExpectedPath $McpExe `
        -Label "MCP build provenance" -ProducedAfterUtc $phaseTimes.native.started `
        -ProducedBeforeUtc $phaseTimes.native.completed
    Assert-ReceiptArtifact -Record $receipt.artifacts.mod -ExpectedPath $VibrisModJar `
        -Label "Vibris mod build provenance" -ProducedAfterUtc $phaseTimes.mod.started `
        -ProducedBeforeUtc $phaseTimes.mod.completed
    Assert-ReceiptArtifact -Record $receipt.artifacts.java_descriptor `
        -ExpectedPath $auditSources.java -Label "Java descriptor provenance" `
        -ProducedAfterUtc $phaseTimes.protocol.started -ProducedBeforeUtc $phaseTimes.protocol.completed
    Assert-ReceiptArtifact -Record $receipt.artifacts.cpp_descriptor `
        -ExpectedPath $auditSources.cpp -Label "C++ descriptor provenance" `
        -ProducedAfterUtc $phaseTimes.native.started -ProducedBeforeUtc $phaseTimes.native.completed
    Assert-ReceiptArtifact -Record $receipt.artifacts.proto `
        -ExpectedPath $auditSources.proto -Label "Protocol schema provenance"
    Write-AtomicText -Path $statePath -Text "PREPARED"

    [void] (New-Item -ItemType Directory -Path $publishDirectory)
    [void] (Assert-NoReparseComponents -Path $publishDirectory `
        -Label "Publish snapshot" -RequireExisting)
    $hardLinkCount = 0
    foreach ($source in $publishSources)
    {
        if ($TestHardLinkFailureAfter -eq $hardLinkCount)
        {
            throw "Injected hard-link publication failure after $hardLinkCount links."
        }
        New-VerifiedHardLink -Source ([string] $source.source) `
            -Target (Join-Path $publishDirectory ([string] $source.name)) `
            -Record $source.record -ExpectedIdentity ([string] $source.identity) `
            -Label "Publish hard link"
        ++$hardLinkCount
    }
    if ($TestPublishExtraHardLink)
    {
        $testExtraLinkPath = Join-Path $publishDirectory ".test-extra-hard-link"
        [Vibris.DeliveryFileIdentity]::CreateHardLink(
            $testExtraLinkPath,
            (Join-Path $publishDirectory ([string] $newRecord.mcp.name)))
    }
    foreach ($source in $publishSources)
    {
        $linkCountProbe = $null
        try
        {
            $linkCountProbe = Open-ImmutableReadHandle -Path (
                Join-Path $publishDirectory ([string] $source.name))
            if ((Get-OpenFileLinkCount -Handle $linkCountProbe) -ne 2)
            {
                throw "Publish hard link has an invalid link count; expected 2."
            }
        }
        finally
        {
            if ($null -ne $linkCountProbe) { $linkCountProbe.Dispose() }
        }
    }
    Assert-DeliveryMatches -Directory $publishDirectory -Record $newRecord `
        -Label "Identity-bound publish snapshot"

    Write-AtomicText -Path $statePath -Text "OLD_MOVE_INTENT"
    if ([bool] $before.present)
    {
        [System.IO.Directory]::Move($OutputDirectory, $previousDirectory)
        Assert-DeliveryMatches -Directory $previousDirectory -Record $before -Label "Rollback snapshot"
    }
    Write-AtomicText -Path $statePath -Text "OLD_MOVED"

    Write-AtomicText -Path $statePath -Text "PUBLISH_INTENT"
    [System.IO.Directory]::Move($publishDirectory, $OutputDirectory)
    Write-AtomicText -Path $statePath -Text "PUBLISHED"

    if ($TestPublishIdentityMismatch)
    {
        $mismatchPath = Join-Path $OutputDirectory ([string] $newRecord.mod.name)
        Remove-Item -LiteralPath $mismatchPath -Force
        [System.IO.File]::WriteAllText($mismatchPath, "INJECTED_IDENTITY_MISMATCH")
    }

    foreach ($source in $publishSources)
    {
        $publishedPath = Join-Path $OutputDirectory ([string] $source.name)
        $publishedHandle = Open-ImmutableReadHandle -Path $publishedPath
        $publishedHandles.Add($publishedHandle)
        if ((Get-OpenFileIdentity -Handle $publishedHandle) -cne [string] $source.identity)
        {
            throw "Published delivery file identity differs from its audited snapshot: $publishedPath"
        }
        if ((Get-OpenFileLinkCount -Handle $publishedHandle) -ne 2)
        {
            throw "Published delivery file has an invalid pre-retirement link count: $publishedPath"
        }
    }
    Assert-DeliveryMatches -Directory $OutputDirectory -Record $newRecord -Label "Published delivery"

    foreach ($handle in $immutableHandles) { $handle.Dispose() }
    $immutableHandles.Clear()
    Remove-ExactFlatDirectory -Path $nextDirectory `
        -AllowedNames @([string] $newRecord.mcp.name, [string] $newRecord.mod.name) `
        -Label "Retired audited next snapshot"

    foreach ($source in $publishSources)
    {
        $publishedPath = Join-Path $OutputDirectory ([string] $source.name)
        $identityProbe = $null
        try
        {
            $identityProbe = Open-ImmutableReadHandle -Path $publishedPath
            if ((Get-OpenFileIdentity -Handle $identityProbe) -cne [string] $source.identity)
            {
                throw "Published delivery path no longer names its audited file identity: $publishedPath"
            }
            if ((Get-OpenFileLinkCount -Handle $identityProbe) -ne 1 -or
                (Get-OpenFileLinkCount -Handle $publishedHandles[$publishSources.IndexOf($source)]) -ne 1)
            {
                throw "Published delivery file has an invalid committed link count: $publishedPath"
            }
            Assert-RecordMatches -Path $publishedPath -Record $source.record `
                -Label "Committed identity-bound delivery"
        }
        finally
        {
            if ($null -ne $identityProbe) { $identityProbe.Dispose() }
        }
    }
    Write-AtomicText -Path $statePath -Text "COMMITTED"
    $committed = $true

    foreach ($handle in $publishedHandles) { $handle.Dispose() }
    $publishedHandles.Clear()
    try
    {
        if ([bool] $before.present)
        {
            Remove-ExactFlatDirectory -Path $previousDirectory `
                -AllowedNames @(Get-DeliveryRecordNames -Record $before) `
                -Label "Retired rollback"
        }
        Remove-ExactFlatDirectory -Path $auditDirectory `
            -AllowedNames @("vibris-protocol-java.jar", "vibris-descriptor-dump.exe", "vibris_control.proto") `
            -Label "Retired audit snapshot"
        Remove-ExactFlatDirectory -Path $failedDirectory `
            -AllowedNames @([string] $newRecord.mcp.name, [string] $newRecord.mod.name) `
            -Label "Retired failed snapshot"
        Remove-TerminalTransaction -Root $transactionRoot `
            -Manifest $manifestPath -State $statePath -TerminalState "CLEANUP_COMPLETE" `
            -Failure $TestCleanupFailure
    }
    catch
    {
        $cleanupPending = $true
        Write-Warning "Committed delivery cleanup remains pending: $($_.Exception.Message)"
    }

    Write-Output ($auditOutput | Where-Object { $_ -like "PASS *" })
    Write-Output ("PASS delivery_files=2 output=$OutputDirectory " +
        "mcp_sha256=$($newRecord.mcp.sha256) mod_sha256=$($newRecord.mod.sha256) " +
        "target_sha256=$targetKey identity_bound=true hard_link_publish=true " +
        "cleanup_pending=$($cleanupPending.ToString().ToLowerInvariant())")
}
catch
{
    $primaryFailure = $_
    if (-not $committed -and -not $cleanupOnly -and $null -ne $manifest)
    {
        try
        {
            foreach ($handle in $publishedHandles) { $handle.Dispose() }
            $publishedHandles.Clear()
            foreach ($handle in $immutableHandles) { $handle.Dispose() }
            $immutableHandles.Clear()
            if ($TestPublishExtraHardLink -and
                $testExtraLinkPath -and
                (Test-Path -LiteralPath $testExtraLinkPath -PathType Leaf))
            {
                [void] (Resolve-OrdinaryFile -Path $testExtraLinkPath `
                    -Label "Injected extra hard link")
                Remove-Item -LiteralPath $testExtraLinkPath -Force
            }
            if ([bool] $manifest.previous.present -and (Test-Path -LiteralPath $previousDirectory))
            {
                Assert-DeliveryMatches -Directory $previousDirectory -Record $manifest.previous `
                    -Label "Failure rollback"
                if (Test-Path -LiteralPath $OutputDirectory)
                {
                    Move-OrdinaryDirectoryAside -Source $OutputDirectory `
                        -Destination $failedDirectory -Label "Failure current delivery"
                }
                [System.IO.Directory]::Move($previousDirectory, $OutputDirectory)
                Assert-DeliveryMatches -Directory $OutputDirectory -Record $manifest.previous `
                    -Label "Failure-restored last-good delivery"
            }
            elseif ([bool] $manifest.previous.present)
            {
                Assert-DeliveryMatches -Directory $OutputDirectory -Record $manifest.previous `
                    -Label "Unchanged last-good delivery"
            }
            elseif (Test-Path -LiteralPath $OutputDirectory)
            {
                Move-OrdinaryDirectoryAside -Source $OutputDirectory `
                    -Destination $failedDirectory -Label "Failed first publication"
            }

            Remove-ExactFlatDirectory -Path $nextDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Failed next snapshot"
            Remove-ExactFlatDirectory -Path $publishDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Failed publish snapshot"
            Remove-ExactFlatDirectory -Path $auditDirectory `
                -AllowedNames @("vibris-protocol-java.jar", "vibris-descriptor-dump.exe", "vibris_control.proto") `
                -Label "Failed audit snapshot"
            Remove-ExactFlatDirectory -Path $failedDirectory `
                -AllowedNames @([string] $manifest.new.mcp.name, [string] $manifest.new.mod.name) `
                -Label "Failed published snapshot"
            $cleanupOnly = $true
            Remove-TerminalTransaction -Root $transactionRoot `
                -Manifest $manifestPath -State $statePath `
                -TerminalState "ABORT_CLEANUP_COMPLETE" -Failure $TestCleanupFailure
            $cleanupOnly = $false
        }
        catch
        {
            throw [System.AggregateException]::new(
                "Delivery publication and last-good recovery both failed.",
                @($primaryFailure.Exception, $_.Exception))
        }
    }
    throw $primaryFailure
}
finally
{
    foreach ($handle in $publishedHandles) { $handle.Dispose() }
    $publishedHandles.Clear()
    foreach ($handle in $immutableHandles) { $handle.Dispose() }
    $immutableHandles.Clear()
    if ($null -ne $deliveryLock)
    {
        $deliveryLock.Dispose()
        $deliveryLock = $null
    }
}
