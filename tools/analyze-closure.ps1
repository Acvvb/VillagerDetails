# Dependency closure for the three-way source split (Common / Client / Server).
#
# Reads compiled class files (bytecode constant pool via javap), so the result is
# exactly "what the client really needs to compile", not an import guess.
#
# Usage (from repo root, after a successful build):
#   powershell -ExecutionPolicy Bypass -File tools/analyze-closure.ps1 -Version v26.3
#
# Output:
#   KEPT  = classes that must stay in main (closed under references)
#   MOVED = classes that can live in the server source set
#
param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent),
    [string]$Version = 'v26.3',
    [string]$Javap = ''
)

$ErrorActionPreference = 'Stop'

if (-not $Javap) {
    $javaHome = [System.Environment]::GetEnvironmentVariable('JAVA_HOME')
    if ($javaHome -and (Test-Path (Join-Path $javaHome 'bin\javap.exe'))) {
        $Javap = Join-Path $javaHome 'bin\javap.exe'
    } else {
        $candidates = @()
        $candidates += Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue
        $candidates += Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue
        foreach ($candidate in ($candidates | Sort-Object Name -Descending)) {
            $probe = Join-Path $candidate.FullName 'bin\javap.exe'
            if (Test-Path $probe) { $Javap = $probe; break }
        }
    }
}
if (-not (Test-Path $Javap)) {
    throw "javap not found. Pass -Javap <path to javap.exe>."
}

$buildDir = Join-Path $Root "$Version\build\classes\java"
$mainDir = Join-Path $buildDir 'main'
$clientDir = Join-Path $buildDir 'client'
foreach ($d in @($mainDir, $clientDir)) {
    if (-not (Test-Path $d)) { throw "Missing compiled classes: $d (run a build first)" }
}
$cp = "$clientDir;$mainDir"

function Get-ClassNames($dir) {
    Get-ChildItem $dir -Recurse -Filter *.class |
        ForEach-Object { $_.FullName.Substring($dir.Length + 1).Replace('\', '.').Replace('.class', '') }
}

function Get-Refs($names, $classpath) {
    $map = @{}
    $batchSize = 60
    for ($i = 0; $i -lt $names.Count; $i += $batchSize) {
        $end = [Math]::Min($i + $batchSize - 1, $names.Count - 1)
        $batch = @($names[$i..$end])
        $out = & $script:Javap -v -p -cp $classpath @batch 2>$null
        $cur = $null
        foreach ($line in $out) {
            if ($line -match '^Classfile (.+)$') {
                $cur = $matches[1]
                if (-not $map.ContainsKey($cur)) {
                    $map[$cur] = New-Object 'System.Collections.Generic.HashSet[string]'
                }
            } elseif ($cur -and $line -match '= Class\s+#\d+\s+//\s*(\S+)') {
                [void]$map[$cur].Add($matches[1].Replace('/', '.'))
            }
        }
    }
    return $map
}

function Get-Fqn($classFileEntry, $marker) {
    $norm = $classFileEntry.Replace('\', '/')
    $idx = $norm.IndexOf($marker)
    if ($idx -lt 0) { return $null }
    return $norm.Substring($idx + $marker.Length).Replace('.class', '').Replace('/', '.')
}

$mainNames = @(Get-ClassNames $mainDir)
$clientNames = @(Get-ClassNames $clientDir)

$mainRefs = Get-Refs $mainNames $cp
$clientRefs = Get-Refs $clientNames $cp

$mainSet = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($n in $mainNames) { [void]$mainSet.Add($n) }

$refGraph = @{}
foreach ($k in $mainRefs.Keys) {
    $fqn = Get-Fqn $k '/classes/java/main/'
    if (-not $fqn) { continue }
    $refGraph[$fqn] = @($mainRefs[$k] | Where-Object { $mainSet.Contains($_) })
}

# Seed: main classes referenced directly by client bytecode.
$seed = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($k in $clientRefs.Keys) {
    $refs = $clientRefs[$k]
    if ($null -eq $refs) { continue }
    foreach ($r in $refs) { if ($mainSet.Contains($r)) { [void]$seed.Add($r) } }
}

# Transitive closure (inner classes collapse to their outer class).
$kept = New-Object 'System.Collections.Generic.HashSet[string]'
$queue = New-Object 'System.Collections.Generic.Queue[string]'
foreach ($s in $seed) { if ($kept.Add($s)) { $queue.Enqueue($s) } }
while ($queue.Count -gt 0) {
    $c = $queue.Dequeue()
    $deps = $refGraph[$c]
    if ($null -eq $deps) { continue }
    foreach ($d in $deps) { if ($kept.Add($d)) { $queue.Enqueue($d) } }
}

$keptBase = @($kept | ForEach-Object { ($_ -split '\$')[0] } | Sort-Object -Unique)
$allBase = @($mainNames | ForEach-Object { ($_ -split '\$')[0] } | Sort-Object -Unique)
$movedBase = @($allBase | Where-Object { $keptBase -notcontains $_ })

"=== must stay in main: $($keptBase.Count) source classes ==="
$keptBase | ForEach-Object { "  $_" }
"`n=== can move to server: $($movedBase.Count) source classes ==="
$movedBase | ForEach-Object { ($_ -split '\.')[2] } | Group-Object | ForEach-Object { "  $($_.Name): $($_.Count)" }

$outDir = Join-Path $Root 'build\tmp'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$json = [pscustomobject]@{ kept = $keptBase; moved = $movedBase } | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText((Join-Path $outDir "closure-$Version.json"), $json,
    (New-Object System.Text.UTF8Encoding($false)))
"`nsaved to build/tmp/closure-$Version.json"
