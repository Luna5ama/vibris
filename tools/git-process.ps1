Set-StrictMode -Version Latest

if (-not (Get-Variable -Name TrustedGitExecutable -Scope Script -ErrorAction SilentlyContinue))
{
    $script:TrustedGitExecutable = $null
}

if (-not ("Vibris.BoundedCaptureStream" -as [type]))
{
    Add-Type -TypeDefinition @'
using System;
using System.IO;

namespace Vibris
{
    public sealed class BoundedCaptureStream : MemoryStream
    {
        private readonly long limit;

        public BoundedCaptureStream(long limit)
        {
            this.limit = limit;
        }

        public override void Write(byte[] buffer, int offset, int count)
        {
            if (Length + count > limit) throw new InvalidDataException("Git output exceeded its limit.");
            base.Write(buffer, offset, count);
        }

        public override void Write(ReadOnlySpan<byte> buffer)
        {
            if (Length + buffer.Length > limit) throw new InvalidDataException("Git output exceeded its limit.");
            base.Write(buffer);
        }
    }
}
'@
}

function Resolve-TrustedGitExecutable
{
    if ($script:TrustedGitExecutable)
    {
        return $script:TrustedGitExecutable
    }

    $roots = [System.Collections.Generic.List[string]]::new()
    foreach ($key in @("HKEY_LOCAL_MACHINE\SOFTWARE\GitForWindows", "HKEY_CURRENT_USER\SOFTWARE\GitForWindows"))
    {
        $value = [Microsoft.Win32.Registry]::GetValue($key, "InstallPath", $null)
        if ($null -ne $value) { $roots.Add([string] $value) }
    }
    if ($env:ProgramFiles) { $roots.Add((Join-Path $env:ProgramFiles "Git")) }
    if (${env:ProgramFiles(x86)}) { $roots.Add((Join-Path ${env:ProgramFiles(x86)} "Git")) }
    if ($env:LOCALAPPDATA) { $roots.Add((Join-Path $env:LOCALAPPDATA "Programs\Git")) }

    foreach ($root in $roots)
    {
        foreach ($relative in @("mingw64\bin\git.exe", "cmd\git.exe", "bin\git.exe"))
        {
            $candidate = [System.IO.Path]::GetFullPath((Join-Path $root $relative))
            if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
            $item = Get-Item -LiteralPath $candidate -Force
            if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { continue }
            if ($item.VersionInfo.OriginalFilename -cne "git.exe" -or
                $item.VersionInfo.ProductName -cne "Git") { continue }
            $script:TrustedGitExecutable = $item.FullName
            return $script:TrustedGitExecutable
        }
    }
    throw "GIT_TRUST_FAILURE: no trusted Git for Windows application was found."
}

function Stop-OwnedGitProcess
{
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [datetime] $StartTimeUtc
    )

    if ($Process.HasExited) { return }
    if ($Process.StartTime.ToUniversalTime() -ne $StartTimeUtc)
    {
        throw "GIT_CLEANUP_FAILURE: owned Git process identity changed before termination."
    }
    try { $Process.Kill($true) } catch { if (-not $Process.HasExited) { throw } }
    if (-not $Process.WaitForExit(1000))
    {
        throw "GIT_CLEANUP_FAILURE: owned Git process did not exit after termination."
    }
}

function Invoke-TrustedGitText
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string[]] $Arguments,
        [Parameter(Mandatory)] [string] $Label,
        [ValidateRange(1, 30)] [int] $TimeoutSeconds = 5,
        [ValidateRange(1024, 67108864)] [int] $MaxOutputBytes = 33554432
    )

    $git = Resolve-TrustedGitExecutable
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $git
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    [void] $start.ArgumentList.Add("-c")
    [void] $start.ArgumentList.Add("core.quotePath=false")
    [void] $start.ArgumentList.Add("-C")
    [void] $start.ArgumentList.Add([System.IO.Path]::GetFullPath($Root))
    foreach ($argument in $Arguments) { [void] $start.ArgumentList.Add($argument) }
    foreach ($name in @($start.Environment.Keys | Where-Object { $_ -like "GIT_*" }))
    {
        [void] $start.Environment.Remove($name)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    $stdout = [Vibris.BoundedCaptureStream]::new($MaxOutputBytes)
    $stderr = [Vibris.BoundedCaptureStream]::new(32768)
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $processStarted = $false
    $startTimeUtc = [datetime]::MinValue
    try
    {
        if (-not $process.Start()) { throw "GIT_START_FAILURE: $Label could not start $git." }
        $processStarted = $true
        $startTimeUtc = $process.StartTime.ToUniversalTime()
        $process.StandardInput.Close()
        $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
        $stderrTask = $process.StandardError.BaseStream.CopyToAsync($stderr)
        while (-not $process.WaitForExit(25))
        {
            if ($stdoutTask.IsFaulted -or $stderrTask.IsFaulted)
            {
                Stop-OwnedGitProcess -Process $process -StartTimeUtc $startTimeUtc
                throw "GIT_OUTPUT_FAILURE: $Label exceeded its bounded output capture."
            }
            if ($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds)
            {
                Stop-OwnedGitProcess -Process $process -StartTimeUtc $startTimeUtc
                throw "GIT_TIMEOUT: $Label exceeded its $TimeoutSeconds-second deadline."
            }
        }
        [void] [System.Threading.Tasks.Task]::WaitAll(@($stdoutTask, $stderrTask), 1000)
        if (-not $stdoutTask.IsCompletedSuccessfully -or -not $stderrTask.IsCompletedSuccessfully)
        {
            throw "GIT_OUTPUT_FAILURE: $Label output capture did not complete."
        }
        $stdoutText = [System.Text.Encoding]::UTF8.GetString($stdout.ToArray())
        $stderrText = [System.Text.Encoding]::UTF8.GetString($stderr.ToArray())
        if ($process.ExitCode -ne 0)
        {
            throw "GIT_EXIT_FAILURE: $Label exited $($process.ExitCode) for $Root. $($stderrText.Trim())"
        }
        return $stdoutText.Replace("`r`n", "`n").TrimEnd("`n")
    }
    finally
    {
        if ($processStarted -and -not $process.HasExited)
        {
            Stop-OwnedGitProcess -Process $process -StartTimeUtc $startTimeUtc
        }
        $timer.Stop()
        $stdout.Dispose()
        $stderr.Dispose()
        $process.Dispose()
    }
}
