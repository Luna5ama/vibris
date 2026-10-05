[CmdletBinding()]
param(
    [string] $McpExe = (Join-Path $PSScriptRoot "..\..\mcp\out\build\Release\vibris-mcp.exe"),
    [string] $VibrisModJar =
        (Join-Path $PSScriptRoot "..\..\mod-fabric\build\libs\vibris-fabric-*.jar"),
    [string] $DeliveryDirectory = (Join-Path $PSScriptRoot "..\..\build\delivery"),
    [string] $JavaDescriptor = (Join-Path $PSScriptRoot "..\..\protocol-java\build\libs\vibris-protocol-java.jar"),
    [string] $CppDescriptorDump =
        (Join-Path $PSScriptRoot "..\..\mcp\out\build\Release\vibris-descriptor-dump.exe"),
    [Alias("Proto")] [string] $ProtoPath = (Join-Path $PSScriptRoot "..\..\proto\vibris_control.proto"),
    [switch] $CallerHoldsDeliveryLock
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\..\tools\git-process.ps1")

$script:VibrisRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$script:IrisRoot = [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot "..\Iris"))

function Resolve-AuditArtifact
{
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Label)

    $items = @(Get-Item -Path $Path -ErrorAction Stop | Where-Object { -not $_.PSIsContainer })
    if ($items.Count -ne 1)
    {
        throw "$Label must resolve to exactly one file; resolved $($items.Count): $Path"
    }
    return $items[0].FullName
}

function Resolve-AuditDirectory
{
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Label)

    $item = Get-Item -LiteralPath ([System.IO.Path]::GetFullPath($Path)) -ErrorAction Stop
    if (-not $item.PSIsContainer) { throw "$Label is not a directory: $Path" }
    return $item.FullName
}

function Resolve-VibrisModJar
{
    param([Parameter(Mandatory)] [string] $Path)

    $jar = Resolve-AuditArtifact -Path $Path -Label "VibrisModJar"
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try
    {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
        $required = [ordered] @{
            "Vibris API" = '^META-INF/jars/vibris-api.*[.]jar$'
            "Vibris core" = '^META-INF/jars/vibris-core.*[.]jar$'
            "Vibris protocol" = '^META-INF/jars/vibris-protocol-java[.]jar$'
            "gRPC API" = '^META-INF/jars/grpc-api-.*[.]jar$'
            "gRPC core" = '^META-INF/jars/grpc-core-.*[.]jar$'
            "gRPC transport" = '^META-INF/jars/grpc-netty-shaded-.*[.]jar$'
            "gRPC protobuf" = '^META-INF/jars/grpc-protobuf-[0-9].*[.]jar$'
            "gRPC stub" = '^META-INF/jars/grpc-stub-.*[.]jar$'
        }
        foreach ($entry in $required.GetEnumerator())
        {
            if (@($names | Where-Object { $_ -match $entry.Value }).Count -ne 1)
            {
                throw "VibrisModJar must embed exactly one $($entry.Key) JAR: $jar"
            }
        }
        if ($names -notcontains "fabric.mod.json")
        {
            throw "Vibris mod JAR does not contain fabric.mod.json: $jar"
        }
        $metadataReader = [IO.StreamReader]::new($archive.GetEntry("fabric.mod.json").Open())
        try { $metadata = $metadataReader.ReadToEnd() | ConvertFrom-Json }
        finally { $metadataReader.Dispose() }
        if ($metadata.id -cne "vibris") { throw "Delivery JAR must contain the independent vibris mod." }
    }
    finally
    {
        $archive.Dispose()
    }
    return $jar
}

function Assert-AuditPatternAbsent
{
    param(
        [Parameter(Mandatory)] [System.IO.FileInfo[]] $Files,
        [Parameter(Mandatory)] [string] $Pattern,
        [Parameter(Mandatory)] [string] $Label
    )

    $matches = @($Files | Select-String -Pattern $Pattern)
    if ($matches.Count -ne 0)
    {
        $locations = @($matches | Select-Object -First 5 | ForEach-Object {
            "$($_.Path):$($_.LineNumber)"
        })
        throw "$Label found at $([string]::Join(', ', $locations))."
    }
}

function Assert-AuditPathHasNoReparse
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label,
        [switch] $RequireExisting
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    $current = $root
    $missing = $false
    $rootItem = Get-Item -LiteralPath $root -Force
    if ($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "$Label traverses a reparse-point root: $root"
    }
    foreach ($component in @($fullPath.Substring($root.Length) -split '[\\/]' |
        Where-Object { $_.Length -ne 0 }))
    {
        $current = Join-Path $current $component
        if ($missing) { continue }
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -eq $item)
        {
            $missing = $true
            continue
        }
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

function Get-JarContentInventory
{
    param(
        [Parameter(Mandatory)] [System.IO.Compression.ZipArchive] $Archive,
        [Parameter(Mandatory)] [string] $Label
    )

    $inventory = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($entry in $Archive.Entries)
    {
        $name = $entry.FullName
        $isDirectory = $name.EndsWith('/', [System.StringComparison]::Ordinal)
        $pathName = if ($isDirectory)
        {
            $name.Substring(0, $name.Length - 1)
        }
        else
        {
            $name
        }
        $segments = @($pathName.Split(
            [char] '/',
            [System.StringSplitOptions]::None))
        if ([string]::IsNullOrWhiteSpace($name) -or $name.Contains('\') -or
            $name.IndexOf([char] 0) -ge 0 -or
            $name.StartsWith('/', [System.StringComparison]::Ordinal) -or
            $name.Contains(':') -or
            @($segments | Where-Object { $_ -eq "" -or $_ -eq "." -or $_ -eq ".." }).Count -ne 0)
        {
            throw "$Label contains an unsafe JAR entry path: $name"
        }
        if ($inventory.ContainsKey($name))
        {
            throw "$Label contains a duplicate JAR entry: $name"
        }

        $hash = ""
        if (-not $isDirectory)
        {
            $stream = $entry.Open()
            $sha256 = [System.Security.Cryptography.SHA256]::Create()
            try
            {
                $hash = [System.Convert]::ToHexString($sha256.ComputeHash($stream))
            }
            finally
            {
                $sha256.Dispose()
                $stream.Dispose()
            }
        }
        $inventory.Add($name, [pscustomobject] @{
            kind = if ($isDirectory) { "directory" } else { "file" }
            length = [long] $entry.Length
            sha256 = $hash
        })
    }
    return ,$inventory
}

function Assert-LoomGeneratedProtocolMetadata
{
    param([Parameter(Mandatory)] [System.IO.Compression.ZipArchiveEntry] $Entry)

    if ($Entry.Length -gt 4096)
    {
        throw "Loom-generated fabric.mod.json is unexpectedly large."
    }

    $stream = $Entry.Open()
    $document = $null
    try
    {
        $document = [System.Text.Json.JsonDocument]::Parse($stream)
        $root = $document.RootElement
        if ($root.ValueKind -ne [System.Text.Json.JsonValueKind]::Object)
        {
            throw "Embedded fabric.mod.json root must be an object."
        }

        $rootProperties = @($root.EnumerateObject())
        $rootNames = @($rootProperties | ForEach-Object { $_.Name } | Sort-Object)
        $custom = $root.GetProperty("custom")
        if ($custom.ValueKind -ne [System.Text.Json.JsonValueKind]::Object)
        {
            throw "Embedded fabric.mod.json custom field must be an object."
        }
        $customProperties = @($custom.EnumerateObject())
        $customNames = @($customProperties | ForEach-Object { $_.Name } | Sort-Object)
        $schemaVersion = $root.GetProperty("schemaVersion")
        $id = $root.GetProperty("id")
        $name = $root.GetProperty("name")
        $version = $root.GetProperty("version")
        $generated = $custom.GetProperty("fabric-loom:generated")
        if ([string]::Join(',', $rootNames) -cne "custom,id,name,schemaVersion,version" -or
            [string]::Join(',', $customNames) -cne "fabric-loom:generated" -or
            $schemaVersion.ValueKind -ne [System.Text.Json.JsonValueKind]::Number -or
            $schemaVersion.GetInt32() -ne 1 -or
            $id.ValueKind -ne [System.Text.Json.JsonValueKind]::String -or
            $id.GetString() -cne "dev_luna5ama_vibris-protocol-java" -or
            $name.ValueKind -ne [System.Text.Json.JsonValueKind]::String -or
            $name.GetString() -cne "vibris-protocol-java" -or
            $version.ValueKind -ne [System.Text.Json.JsonValueKind]::String -or
            [string]::IsNullOrWhiteSpace($version.GetString()) -or
            $generated.ValueKind -ne [System.Text.Json.JsonValueKind]::True)
        {
            throw "Embedded fabric.mod.json is not the expected Loom-generated protocol metadata."
        }
    }
    catch [System.Text.Json.JsonException]
    {
        throw "Embedded fabric.mod.json is malformed: $($_.Exception.Message)"
    }
    finally
    {
        if ($null -ne $document) { $document.Dispose() }
        $stream.Dispose()
    }
}

function Get-JarInventoryHash
{
    param([Parameter(Mandatory)] [System.Collections.Generic.Dictionary[string, object]] $Inventory)

    $builder = [System.Text.StringBuilder]::new()
    foreach ($name in @($Inventory.Keys | Sort-Object))
    {
        $record = $Inventory[$name]
        [void] $builder.Append($name)
        [void] $builder.Append("`0")
        [void] $builder.Append($record.kind)
        [void] $builder.Append("`0")
        [void] $builder.Append($record.length)
        [void] $builder.Append("`0")
        [void] $builder.Append($record.sha256)
        [void] $builder.Append("`n")
    }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try
    {
        return [System.Convert]::ToHexString(
            $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($builder.ToString())))
    }
    finally
    {
        $sha256.Dispose()
    }
}

$auditLock = $null
if (-not $CallerHoldsDeliveryLock)
{
    $auditLockPath = Join-Path $script:VibrisRoot "build\.delivery.lock"
    $auditLockParent = Split-Path -Parent $auditLockPath
    [void] (New-Item -ItemType Directory -Path $auditLockParent -Force)
    [void] (Assert-AuditPathHasNoReparse -Path $auditLockParent `
        -Label "Delivery audit lock parent" -RequireExisting)
    [void] (Assert-AuditPathHasNoReparse -Path $auditLockPath -Label "Delivery audit lock")
    try
    {
        $auditLock = [System.IO.File]::Open(
            $auditLockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
    }
    catch [System.IO.IOException]
    {
        throw "Delivery publication or audit is already running: $auditLockPath"
    }
    [void] (Assert-AuditPathHasNoReparse -Path $auditLockPath `
        -Label "Delivery audit lock" -RequireExisting)
}

try
{
    $productionRoots = @(
        (Join-Path $script:VibrisRoot "api\src\main"),
        (Join-Path $script:VibrisRoot "core\src\main"),
        (Join-Path $script:VibrisRoot "mcp\src\main\cpp"),
        (Join-Path $script:VibrisRoot "protocol-java\src\main")
    )
    $productionFiles = @($productionRoots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object {
        Get-ChildItem -LiteralPath $_ -Recurse -File
    })
    Assert-AuditPatternAbsent -Files $productionFiles `
        -Pattern '(?i)\b(?:https?|wss?)://|\bHttpClient\b|\bHttpServer\b|\bWebSocket\b' `
        -Label "Production HTTP/WebSocket transport"
    Assert-AuditPatternAbsent -Files $productionFiles -Pattern '(?i)renderdoc' `
        -Label "Production RenderDoc dependency"

    $irisRuntimeFiles = @(@("common", "fabric", "neoforge") | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $script:IrisRoot "$_\src\main") -Recurse -File
    })
    $irisRuntimeFiles += @("common", "fabric", "neoforge") | ForEach-Object {
        Get-Item -LiteralPath (Join-Path $script:IrisRoot "$_\build.gradle.kts")
    }
    Assert-AuditPatternAbsent -Files $irisRuntimeFiles `
        -Pattern '(?i)LD_PRELOAD[^\r\n]*renderdoc|[/\\][^\s"'']*renderdoc' `
        -Label "Iris RenderDoc path"

    $trackedMainText = Invoke-TrustedGitText -Root $script:VibrisRoot -Arguments @(
        "ls-files", "-z", "--", "*/src/main/**") -Label "Tracked production source enumeration"
    $trackedMainPaths = @($trackedMainText -split [char] 0 | Where-Object { $_.Length -ne 0 })
    $trackedMainFiles = @($trackedMainPaths | ForEach-Object {
        $path = Join-Path $script:VibrisRoot $_
        if (Test-Path -LiteralPath $path -PathType Leaf) { Get-Item -LiteralPath $path }
    })
    $trackedJava = @($trackedMainFiles | Where-Object { $_.Extension -ieq ".java" -and
        $_.FullName -notmatch '[\\/](?:mod-common|mod-fabric|test-runtime)[\\/]' })
    if ($trackedJava.Count -ne 0)
    {
        throw "Handwritten API/Core production source must be Kotlin: $($trackedJava[0].FullName)"
    }

    $mcpJvmSources = @(Get-ChildItem -LiteralPath (Join-Path $script:VibrisRoot "mcp\src\main") `
        -Recurse -File | Where-Object { $_.Extension -iin @(".java", ".kt") })
    if ($mcpJvmSources.Count -ne 0)
    {
        throw "Native MCP production source must remain C++: $($mcpJvmSources[0].FullName)"
    }

    $coreKotlin = @(Get-ChildItem -LiteralPath (Join-Path $script:VibrisRoot "core\src\main") `
        -Recurse -File -Filter "*.kt")
    Assert-AuditPatternAbsent -Files $coreKotlin `
        -Pattern '(?m)^\s*import\s+(?:net\.irisshaders|org\.eclipse\.jgit)\.' `
        -Label "Kotlin core Git/Iris import"

    $protoSchema = Resolve-AuditArtifact -Path $ProtoPath -Label "Protocol schema"
    $protoSchema = Assert-AuditPathHasNoReparse -Path $protoSchema `
        -Label "Protocol schema" -RequireExisting
    $protoSchemaText = Get-Content -Raw -LiteralPath $protoSchema
    $protoSchemaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $protoSchema).Hash
    if ($protoSchemaText -match '(?m)^\s*bytes\s+\w+\s*=')
    {
        throw "Protocol must transfer prepared-source references, not source byte fields: $protoSchema"
    }
    foreach ($requiredField in @("string source_uuid", "uint64 file_count", "uint64 total_bytes"))
    {
        if (-not $protoSchemaText.Contains($requiredField))
        {
            throw "PreparedSourceRef is missing '$requiredField'."
        }
    }

    $toolSource = (@(
        "mcp\src\main\cpp\tool_registry.cpp"
    ) | ForEach-Object {
        Get-Content -Raw -LiteralPath (Join-Path $script:VibrisRoot $_)
    }) -join "`n"
    $tools = @([regex]::Matches($toolSource, 'definition\("((?:vibris_|mcp_vibiris_)[^"]+)"') | ForEach-Object {
        $_.Groups[1].Value
    })
    $expectedTools = @(
        "vibris_get_status",
        "vibris_restart",
        "vibris_list_presets",
        "vibris_list_resources",
        "mcp_vibiris_nsight_analyze",
        "vibris_run_recipe",
        "vibris_run_actions",
        "vibris_run_matrix",
        "vibris_job",
        "vibris_artifacts",
        "vibris_compare",
        "vibris_preview_presets"
    )
    if ([string]::Join("`n", $tools) -cne [string]::Join("`n", $expectedTools) -or
        @($tools | Select-Object -Unique).Count -ne $expectedTools.Count)
    {
        throw "Native MCP tool registry does not expose exactly the expected 12-tool surface."
    }
    foreach ($legacy in @(
        "mcp\src\main\cpp\debug_tools.cpp",
        "mcp\src\main\cpp\debug_protocol.cpp"
    ))
    {
        if (Test-Path -LiteralPath (Join-Path $script:VibrisRoot $legacy))
        {
            throw "Legacy feature-specific MCP transport remains: $legacy"
        }
    }
    if ($protoSchemaText -match '\bDebugControl\b|supported_debug_controls|CAPABILITY_DEBUG_CONTROL')
    {
        throw "Protocol still exposes the legacy DebugControl transport."
    }

    $javaRoot = Join-Path $script:VibrisRoot "mod-common\src\main\java"
    $vibrisMixins = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot "dev\vibris\mod\mixin") -Recurse -File |
        Where-Object { (Get-Content -LiteralPath $_.FullName -Raw) -match '@Mixin\b' })
    $expectedVibrisMixins = @(Get-ChildItem -LiteralPath (Join-Path $script:VibrisRoot "mod-common\src\main\resources") `
        -Filter "mixins.vibris*.json" -File | ForEach-Object {
        $configuration = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
        foreach ($section in @("mixins", "client", "server")) {
            if ($configuration.PSObject.Properties.Name -notcontains $section) { continue }
            foreach ($name in $configuration.$section) {
                $relative = ($configuration.package + "." + $name).Replace(".", "\") + ".java"
                [IO.Path]::GetFullPath((Join-Path $javaRoot $relative))
            }
        }
    } | Sort-Object)
    $actualVibrisMixins = @($vibrisMixins | ForEach-Object {
        [System.IO.Path]::GetFullPath($_.FullName)
    } | Sort-Object)
    if ($actualVibrisMixins.Count -ne $expectedVibrisMixins.Count -or
        -not [string]::Equals(
            [string]::Join("`n", $actualVibrisMixins),
            [string]::Join("`n", $expectedVibrisMixins),
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Vibris mod mixin sources differ from their registered set: " +
            [string]::Join(", ", $expectedVibrisMixins)
    }

    $exe = Resolve-AuditArtifact -Path $McpExe -Label "Release native MCP"
    $exe = Assert-AuditPathHasNoReparse -Path $exe -Label "Release native MCP" -RequireExisting
    if ((Split-Path -Leaf $exe) -cne "vibris-mcp.exe")
    {
        throw "Native package executable must be named vibris-mcp.exe: $exe"
    }
    $jar = Resolve-VibrisModJar -Path $VibrisModJar
    $jar = Assert-AuditPathHasNoReparse -Path $jar -Label "Vibris mod JAR" -RequireExisting

    $delivery = Resolve-AuditDirectory -Path $DeliveryDirectory -Label "DeliveryDirectory"
    $delivery = Assert-AuditPathHasNoReparse -Path $delivery -Label "DeliveryDirectory" -RequireExisting
    $deliveryEntries = @(Get-ChildItem -LiteralPath $delivery -Force)
    if ($deliveryEntries.Count -ne 2 -or @($deliveryEntries | Where-Object {
        $_.PSIsContainer -or $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint
    }).Count -ne 0)
    {
        throw "DeliveryDirectory must contain exactly two ordinary files: $delivery"
    }
    if (@($deliveryEntries | Where-Object { $_.Name -like "*.running-old" }).Count -ne 0)
    {
        throw "DeliveryDirectory contains a forbidden .running-old artifact: $delivery"
    }

    $deliveredExe = @($deliveryEntries | Where-Object { $_.Name -ceq "vibris-mcp.exe" })
    $deliveredJar = @($deliveryEntries | Where-Object { $_.Name -ceq (Split-Path -Leaf $jar) })
    if ($deliveredExe.Count -ne 1 -or $deliveredJar.Count -ne 1)
    {
        throw "DeliveryDirectory does not contain the exact requested MCP executable and Vibris mod JAR."
    }

    $releaseMcpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $exe).Hash
    $deliveryMcpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $deliveredExe[0].FullName).Hash
    if ($releaseMcpHash -cne $deliveryMcpHash)
    {
        throw "Delivered MCP executable hash mismatch: release=$releaseMcpHash delivery=$deliveryMcpHash"
    }
    $requestedModHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash
    $deliveryModHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $deliveredJar[0].FullName).Hash
    if ($requestedModHash -cne $deliveryModHash)
    {
        throw "Delivered Vibris mod JAR hash mismatch: requested=$requestedModHash delivery=$deliveryModHash"
    }

    $javaJar = Resolve-AuditArtifact -Path $JavaDescriptor -Label "Java protocol JAR"
    $javaJar = Assert-AuditPathHasNoReparse -Path $javaJar -Label "Java protocol JAR" -RequireExisting
    $descriptorDump = Resolve-AuditArtifact -Path $CppDescriptorDump -Label "C++ descriptor dump"
    $descriptorDump = Assert-AuditPathHasNoReparse -Path $descriptorDump `
        -Label "C++ descriptor dump" -RequireExisting
    $descriptorOutput = @(& (Join-Path $PSScriptRoot "protocol-descriptor-smoke.ps1") `
        -Proto $protoSchema -JavaJar $javaJar -CppDump $descriptorDump)

    $javaProtocolHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $javaJar).Hash
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
    $embeddedBytes = [System.IO.MemoryStream]::new()
    try
    {
        $embeddedEntries = @($archive.Entries | Where-Object {
            $_.FullName -ceq "META-INF/jars/vibris-protocol-java.jar"
        })
        if ($embeddedEntries.Count -ne 1)
        {
            throw "Vibris mod JAR must embed exactly one vibris-protocol-java.jar: $jar"
        }
        $stream = $embeddedEntries[0].Open()
        try
        {
            $stream.CopyTo($embeddedBytes)
        }
        finally
        {
            $stream.Dispose()
        }
    }
    catch
    {
        $embeddedBytes.Dispose()
        throw
    }
    finally
    {
        $archive.Dispose()
    }

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try
    {
        $embeddedProtocolHash = [System.Convert]::ToHexString(
            $sha256.ComputeHash($embeddedBytes.ToArray()))
    }
    finally
    {
        $sha256.Dispose()
    }
    $embeddedBytes.Position = 0

    $standaloneArchive = $null
    $embeddedArchive = $null
    try
    {
        $standaloneArchive = [System.IO.Compression.ZipFile]::OpenRead($javaJar)
        $embeddedArchive = [System.IO.Compression.ZipArchive]::new(
            $embeddedBytes,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $true)
        $standaloneInventory = Get-JarContentInventory `
            -Archive $standaloneArchive -Label "Built protocol JAR"
        $embeddedInventory = Get-JarContentInventory `
            -Archive $embeddedArchive -Label "Embedded protocol JAR"
        $loomGeneratedMetadata = $false
        if (-not $standaloneInventory.ContainsKey("fabric.mod.json") -and
            $embeddedInventory.ContainsKey("fabric.mod.json"))
        {
            Assert-LoomGeneratedProtocolMetadata `
                -Entry $embeddedArchive.GetEntry("fabric.mod.json")
            [void] $embeddedInventory.Remove("fabric.mod.json")
            $loomGeneratedMetadata = $true
        }

        $missingEntries = @($standaloneInventory.Keys | Where-Object {
            -not $embeddedInventory.ContainsKey($_)
        })
        $extraEntries = @($embeddedInventory.Keys | Where-Object {
            -not $standaloneInventory.ContainsKey($_)
        })
        if ($missingEntries.Count -ne 0 -or $extraEntries.Count -ne 0)
        {
            throw ("Vibris mod JAR embeds stale protocol content: missing=[{0}] extra=[{1}]" -f
                [string]::Join(',', @($missingEntries | Sort-Object)),
                [string]::Join(',', @($extraEntries | Sort-Object)))
        }
        foreach ($name in $standaloneInventory.Keys)
        {
            $builtRecord = $standaloneInventory[$name]
            $embeddedRecord = $embeddedInventory[$name]
            if ($builtRecord.kind -cne $embeddedRecord.kind -or
                $builtRecord.length -ne $embeddedRecord.length -or
                $builtRecord.sha256 -cne $embeddedRecord.sha256)
            {
                throw "Vibris mod JAR embeds stale protocol content at entry: $name"
            }
        }

        $protocolContentHash = Get-JarInventoryHash -Inventory $standaloneInventory
        $embeddedContentHash = Get-JarInventoryHash -Inventory $embeddedInventory
        if ($protocolContentHash -cne $embeddedContentHash)
        {
            throw ("Vibris mod JAR protocol inventory hash mismatch: " +
                "built=$protocolContentHash embedded=$embeddedContentHash")
        }
    }
    finally
    {
        if ($null -ne $embeddedArchive) { $embeddedArchive.Dispose() }
        if ($null -ne $standaloneArchive) { $standaloneArchive.Dispose() }
        $embeddedBytes.Dispose()
    }

    Write-Output ($descriptorOutput | Where-Object { $_ -like "PASS *" })
    Write-Output ("PASS source_audit=true transport=grpc source_payload=reference tools=$($expectedTools.Count) " +
        "core_language=kotlin mod_language=java native_mcp=cpp core_iris_jgit_imports=0 " +
        "vibris_mixins=$($actualVibrisMixins.Count) " +
        "renderdoc_dependencies=0 package_exe=1 package_vibris_mod_jar=1 " +
        "delivery_files=2 release_mcp_sha256=$releaseMcpHash requested_mod_sha256=$requestedModHash " +
        "built_protocol_sha256=$javaProtocolHash embedded_protocol_sha256=$embeddedProtocolHash " +
        "protocol_content_sha256=$protocolContentHash " +
        "loom_generated_metadata=$($loomGeneratedMetadata.ToString().ToLowerInvariant()) " +
        "protocol_schema_sha256=$protoSchemaHash " +
        "descriptor_parity=true")
}
finally
{
    if ($null -ne $auditLock)
    {
        $auditLock.Dispose()
        $auditLock = $null
    }
}
