# One-off migration helper: resolves Stonecutter `//?` conditional comments for a single
# target Minecraft version, so a forked file can be split into per-version pure implementations.
#
# Kept for reference only — the repository no longer contains any `//?` markers, so nothing
# in a normal build invokes this. It is how the 8 forked files under v26.2/src and v26.3/src
# were generated from the pre-migration sources.
#
# Usage: .\tools\resolve-stonecutter.ps1 -SourcePath <file> -Version 26.2 -OutFile <file>
param(
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$OutFile
)

$ErrorActionPreference = 'Stop'

$target = [version]$Version
$absPath = (Resolve-Path -LiteralPath $SourcePath).Path
$text = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($absPath))
$text = $text.TrimStart([char]0xFEFF)          # drop a leading BOM
$lines = $text -split "\r?\n"

# Branch state:
#   'outside' -> not inside any `//? if` block; keep the line verbatim
#   'take'    -> inside the branch selected for $target; unwrap and keep
#   'skip'    -> inside a branch not selected; blank the line
$state = 'outside'
# Whether the most recent `if` matched, so the following `else` can flip it.
$lastIfMatched = $null

$out = New-Object System.Collections.Generic.List[string]
$debug = New-Object System.Collections.Generic.List[string]
$lineNo = 0

function Test-Condition([string]$expr) {
    $e = $expr.Trim()
    if ($e -match '^>=\s*([0-9][0-9.]*)$') { return $target -ge [version]$Matches[1] }
    if ($e -match '^>\s*([0-9][0-9.]*)$') { return $target -gt [version]$Matches[1] }
    if ($e -match '^<=\s*([0-9][0-9.]*)$') { return $target -le [version]$Matches[1] }
    if ($e -match '^<\s*([0-9][0-9.]*)$') { return $target -lt [version]$Matches[1] }
    if ($e -match '^==\s*([0-9][0-9.]*)$') { return $target -eq [version]$Matches[1] }
    throw "unsupported stonecutter condition: '$expr'"
}

foreach ($raw in $lines) {
    $lineNo++
    $line = $raw
    $verdict = ''

    # Directive? Two spellings appear in this codebase:
    #     //? if >=26.3 {
    #     //?} else {          (in the selected branch)
    #     *///?} else {       (in the block-commented branch)
    #     //?}  /  *///?}
    # The leading `*/` is the residual block-comment terminator. Anything else is an error.
    if ($line -match '^\s*(?:\*/)?\s*//\?') {
        $cond = $null
        $isElse = $false

        if ($line -match '^\s*(?:\*/)?\s*//\?\s*if\s+(.+?)\s*\{\s*$') {
            $cond = $Matches[1]
        }
        elseif ($line -match '^\s*(?:\*/)?\s*//\?\s*\}?\s*else\s*\{\s*$') {
            $isElse = $true
        }
        elseif ($line -match '^\s*(?:\*/)?\s*//\?\s*\}?\s*$') {
            # plain block end
        }
        else {
            throw "${absPath}:${lineNo} unsupported directive: $line"
        }

        if ($isElse) {
            if ($null -eq $lastIfMatched) {
                throw "${absPath}:${lineNo} 'else' without a preceding 'if'"
            }
            $state = if ($lastIfMatched) { 'skip' } else { 'take' }
            $verdict = 'DIRECTIVE-else'
        }
        elseif ($null -ne $cond) {
            $lastIfMatched = Test-Condition $cond
            $state = if ($lastIfMatched) { 'take' } else { 'skip' }
            $verdict = "DIRECTIVE-if($cond)=$lastIfMatched"
        }
        else {
            $state = 'outside'
            $lastIfMatched = $null
            $verdict = 'DIRECTIVE-end'
        }

        $out.Add('')
    }
    elseif ($state -eq 'skip') {
        # branch not selected: keep the line count stable with a blank line
        $out.Add('')
        $verdict = 'skip'
    }
    else {
        $s = $line
        if ($state -eq 'take') {
            # unwrap the `/* ... */` block-comment form
            if ($s -match '^\s*/\*') { $s = $s -replace '^(\s*)/\*', '$1' }
            if ($s -match '\*/\s*$') { $s = $s -replace '\*/(\s*)$', '$1' }
            $s = $s -replace '\*///\?\s*\}.*$', ''
            $verdict = 'keep'
        }
        else {
            $verdict = 'keep(plain)'
        }
        $out.Add($s)
    }

    if ($env:SC_TRACE) { $debug.Add(("{0,4} {1,-28} state={2} | {3}" -f $lineNo, $verdict, $state, $line)) }
}

$resolved = ($out -join "`n")

if ($env:SC_TRACE) {
    $debug.Add("TOTAL out=$($out.Count) nonEmpty=$(($out | Where-Object { $_ -ne '' }).Count) chars=$($resolved.Length)")
    [System.IO.File]::WriteAllLines($env:SC_TRACE, $debug)
}

# Sanity check: the resolved source must have balanced delimiters.
if (-not $env:SC_NO_CHECK) {
    foreach ($pair in @(@('{', '}'), @('(', ')'), @('[', ']'))) {
        $open = ([regex]::Matches($resolved, [regex]::Escape($pair[0]))).Count
        $close = ([regex]::Matches($resolved, [regex]::Escape($pair[1]))).Count
        if ($open -ne $close) {
            throw "$SourcePath -> $Version : unbalanced '$($pair[0])$($pair[1])' ($open vs $close)"
        }
    }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($OutFile, $resolved, $utf8)
Write-Output "$Version -> $OutFile"
