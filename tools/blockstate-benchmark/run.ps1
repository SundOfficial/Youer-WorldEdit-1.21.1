param(
    [Parameter(Mandatory = $true)][string]$BaselineJar,
    [Parameter(Mandatory = $true)][string]$CandidateJar,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [ValidateRange(1, 10)][int]$Rounds = 3
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$baselinePath = (Resolve-Path -LiteralPath $BaselineJar).Path
$candidatePath = (Resolve-Path -LiteralPath $CandidateJar).Path
$dependencies = Get-Content -LiteralPath (Join-Path $repoRoot 'build/backport/runtime-classpath.txt') -Raw
$outputDirectory = Join-Path $repoRoot ('build/backport/probe-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $outputDirectory | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
& $javac -proc:none -cp "$baselinePath;$dependencies" -d $outputDirectory (Join-Path $PSScriptRoot 'BlockStateMemoryProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
Get-FileHash -LiteralPath $baselinePath, $candidatePath | Format-List | Out-File (Join-Path $outputDirectory 'hashes.txt')
& cmd /c "`"$java`" -version 2>&1" | Out-File (Join-Path $outputDirectory 'java-version.txt')
for ($round = 1; $round -le $Rounds; $round++) {
    foreach ($jar in @($baselinePath, $candidatePath)) {
        & $java -Xms512m -Xmx2g -XX:+UseG1GC -cp "$outputDirectory;$jar;$dependencies" BlockStateMemoryProbe |
            Tee-Object -FilePath (Join-Path $outputDirectory 'results.txt') -Append
        if ($LASTEXITCODE -ne 0) { throw "Probe failed: $jar" }
    }
}
Write-Output "Results: $outputDirectory"
