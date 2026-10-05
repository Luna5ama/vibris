[CmdletBinding()]
param(
    [Parameter(Mandatory)] [Alias("ClientExe")] [string] $Client,
    [Parameter(Mandatory)] [string] $ServerJar,
    [Parameter(Mandatory)] [string] $Listen,
    [string] $PingId,
    [uint32] $ProtocolMajor = 3,
    [uint32] $ProtocolMinor = 1,
    [uint32[]] $Capability = @(1),
    [ValidateSet("hello-ping-pong", "reject-major-mismatch")] [string] $Scenario,
    [int] $TimeoutSeconds = 15
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ownedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$tempBase = Join-Path $repoRoot ".vibris\tmp"
$tempRoot = $null
$tempCreated = $false
$port = $null
$hostName = $null

function Test-PortOpen
{
    param([string] $HostName, [int] $Port)

    $probe = [System.Net.Sockets.TcpClient]::new()
    try
    {
        $connect = $probe.ConnectAsync($HostName, $Port)
        return $connect.Wait(200) -and $probe.Connected
    }
    catch
    {
        return $false
    }
    finally
    {
        $probe.Dispose()
    }
}

function Wait-ForPort
{
    param([int] $Port, [System.Diagnostics.Process] $Server, [datetime] $Deadline)

    while ([datetime]::UtcNow -lt $Deadline)
    {
        if ($Server.HasExited)
        {
            throw "Fake server exited before accepting connections (exit $($Server.ExitCode))."
        }
        $probe = [System.Net.Sockets.TcpClient]::new()
        try
        {
            $connect = $probe.ConnectAsync("127.0.0.1", $Port)
            if ($connect.Wait(100) -and $probe.Connected)
            {
                return
            }
        }
        catch [System.AggregateException]
        {
        }
        finally
        {
            $probe.Dispose()
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Fake server did not listen on 127.0.0.1:$Port within $TimeoutSeconds seconds."
}

function Invoke-SmokeClient
{
    param(
        [uint32] $Major,
        [uint32] $Minor,
        [uint32[]] $Capabilities,
        [string] $MessageId,
        [string] $Mode,
        [bool] $AllowProtocolRejection = $false
    )

    $index = $ownedProcesses.Count
    $arguments = @(
        "--host", $hostName,
        "--port", $port,
        "--protocol-major", $Major,
        "--protocol-minor", $Minor,
        "--message-id", $MessageId,
        "--scenario", $Mode
    )
    foreach ($item in $Capabilities)
    {
        $arguments += @("--capability", $item)
    }
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Client
    $startInfo.Arguments = $arguments -join " "
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $ownedProcesses.Add($process)
    if (-not $process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Native protocol client timed out after $TimeoutSeconds seconds (PID $($process.Id))."
    }
    $process.WaitForExit()
    $outputText = $stdoutTask.Result
    $errorText = $stderrTask.Result
    if (-not [string]::IsNullOrWhiteSpace($errorText))
    {
        if (-not $AllowProtocolRejection)
        {
            throw "Native protocol client wrote non-protocol stderr: $errorText"
        }
        $outputText = $outputText + "`n" + $errorText
    }
    if ($process.ExitCode -ne 0)
    {
        throw "Native protocol client exited $($process.ExitCode)."
    }
    $lines = @($outputText -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -eq 0)
    {
        throw "Native protocol client produced no protocol messages."
    }
    return @($lines | ForEach-Object { $_ | ConvertFrom-Json })
}

function Assert-V2HelloPingPong
{
    param([object[]] $Messages, [string] $MessageId)

    $hello = @($Messages | Where-Object { $_.type -eq "ServerHello" })
    if ($hello.Count -ne 1 -or $hello[0].protocol_major -ne 3 -or $hello[0].protocol_minor -ne 1)
    {
        throw "V2 client did not observe exactly one negotiated protocol 2.0 ServerHello."
    }
    $pong = @($Messages | Where-Object { $_.type -eq "Pong" -and $_.message_id -eq $MessageId })
    if ($pong.Count -ne 1)
    {
        throw "V2 client did not observe Pong with caller message ID '$MessageId'."
    }
    if ([array]::IndexOf($Messages, $hello[0]) -ge [array]::IndexOf($Messages, $pong[0]))
    {
        throw "Pong was not observed after ServerHello."
    }
}

try
{
    if ($Listen -notmatch "^(127\.0\.0\.1|localhost):([0-9]+)$")
    {
        throw "Listen must be a loopback host and port, for example 127.0.0.1:55051."
    }
    $hostName = "127.0.0.1"
    $port = [int] $Matches[2]
    if ($port -lt 1 -or $port -gt 65535)
    {
        throw "Listen port must be between 1 and 65535."
    }
    if ([string]::IsNullOrWhiteSpace($PingId))
    {
        $PingId = if ($port -eq 55051) {
            "protocol-c001"
        } elseif ($port -eq 55052) {
            "protocol-c002"
        } else {
            "protocol-smoke-ping"
        }
    }
    if ($PingId -notmatch "^[A-Za-z0-9._:-]+$")
    {
        throw "PingId contains unsupported command-line characters."
    }
    if ($ProtocolMajor -eq 0)
    {
        throw "ProtocolMajor must be greater than zero."
    }
    if ($Capability.Count -eq 0)
    {
        throw "At least one Capability is required."
    }
    if (-not (Test-Path -LiteralPath $ServerJar -PathType Leaf))
    {
        throw "Missing fake server JAR: $ServerJar"
    }
    if (-not (Test-Path -LiteralPath $Client -PathType Leaf))
    {
        throw "Missing native protocol smoke executable: $Client"
    }

    [void] (New-Item -ItemType Directory -Path $tempBase -Force)
    $tempName = if ($port -eq 55051) {
        "vibris-v2-protocol-c001"
    } elseif ($port -eq 55052) {
        "vibris-v2-protocol-c002"
    } else {
        "vibris-protocol-smoke-$port-$([guid]::NewGuid())"
    }
    $tempRoot = Join-Path $tempBase $tempName
    if (Test-Path -LiteralPath $tempRoot)
    {
        throw "Scoped temp path already exists: $tempRoot"
    }
    [void] (New-Item -ItemType Directory -Path $tempRoot)
    $tempCreated = $true

    $serverStdout = Join-Path $tempRoot "server.stdout"
    $serverStderr = Join-Path $tempRoot "server.stderr"
    $serverArguments = @("-jar", $ServerJar, "--port", $port, "--work-root", $tempRoot)
    $javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java.exe" }
    $server = Start-Process -FilePath $javaExecutable -ArgumentList $serverArguments -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $serverStdout -RedirectStandardError $serverStderr
    $ownedProcesses.Add($server)
    Wait-ForPort -Port $port -Server $server -Deadline ([datetime]::UtcNow.AddSeconds($TimeoutSeconds))

    $effectiveScenario = if ($Scenario) {
        $Scenario
    } elseif ($ProtocolMajor -eq 2) {
        "hello-ping-pong"
    } else {
        "reject-major-mismatch"
    }
    if ($effectiveScenario -eq "reject-major-mismatch")
    {
        $rejected = @(Invoke-SmokeClient -Major $ProtocolMajor -Minor $ProtocolMinor -Capabilities $Capability `
            -MessageId $PingId -Mode "hello" -AllowProtocolRejection $true)
        if ($rejected | Where-Object { $_.type -eq "ServerHello" })
        {
            throw "Major-version mismatch unexpectedly received ServerHello."
        }
        $protocolRejected = @($rejected | Where-Object {
            $_.type -eq "ProtocolRejected" -and $_.code -eq "UNSUPPORTED_VERSION"
        })
        if ($protocolRejected.Count -ne 1)
        {
            throw "Major-version mismatch was not rejected exactly once with UNSUPPORTED_VERSION."
        }
        $rejected | ForEach-Object { $_ | ConvertTo-Json -Compress }
        if ($server.HasExited) { throw "Fake server exited after rejecting a major-version mismatch." }
        $messages = @(Invoke-SmokeClient -Major 3 -Minor 1 -Capabilities @(1) -MessageId $PingId `
            -Mode "hello-ping-pong")
    }
    else
    {
        $messages = @(Invoke-SmokeClient -Major $ProtocolMajor -Minor $ProtocolMinor -Capabilities $Capability `
            -MessageId $PingId -Mode "hello-ping-pong")
    }
    Assert-V2HelloPingPong -Messages $messages -MessageId $PingId
    $messages | ForEach-Object { $_ | ConvertTo-Json -Compress }
    if ($server.HasExited) { throw "Fake server exited before healthy v2 verification completed." }
    Write-Output "PASS scenario=$effectiveScenario listen=$hostName`:$port ping_id=$PingId"
}
finally
{
    foreach ($process in @($ownedProcesses))
    {
        if (-not $process.HasExited)
        {
            Stop-Process -Id $process.Id -Force
            [void] $process.WaitForExit(2000)
        }
        $process.Dispose()
    }
    if ($tempCreated -and (Test-Path -LiteralPath $tempRoot))
    {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
        $resolvedTempBase = [System.IO.Path]::GetFullPath($tempBase).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
        if (-not $resolvedTempRoot.StartsWith($resolvedTempBase + [System.IO.Path]::DirectorySeparatorChar, `
                [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "Refusing to clean temp path outside criterion temp root: $resolvedTempRoot"
        }
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force
    }
    if ($null -ne $port -and $null -ne $hostName -and (Test-PortOpen -HostName $hostName -Port $port))
    {
        throw "Cleanup left a listener on $hostName`:$port."
    }
    Write-Output ("CLEANUP owned_processes=$($ownedProcesses.Count) temp_removed=$tempCreated " +
        "listen=$hostName`:$port listener_closed=true")
}
