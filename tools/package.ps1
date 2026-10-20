param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Outputs,
    [string]$Version='0.1.1'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$sourcePath=(Resolve-Path -LiteralPath $Source).Path
$outputsPath=(Resolve-Path -LiteralPath $Outputs).Path
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid package version' }
$sourceName="CMCL-Java-$Version-source.zip"
$releaseName="CMCL-Java-$Version.zip"
function Write-Zip([string]$Target,[object[]]$Entries) {
    if (Test-Path -LiteralPath $Target) { throw "Package already exists: $Target" }
    $stream=[IO.File]::Open($Target,[IO.FileMode]::CreateNew)
    try {
        $zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$true,[Text.Encoding]::UTF8)
        try {
            foreach ($item in $Entries) {
                $entry=$zip.CreateEntry($item.Name,[IO.Compression.CompressionLevel]::Optimal)
                $inputStream=[IO.File]::OpenRead($item.Path)
                $outputStream=$entry.Open()
                try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose();$inputStream.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $stream.Dispose() }
}
$sourceEntries=@()
foreach($file in Get-ChildItem -LiteralPath (Join-Path $sourcePath 'src') -Recurse -File) {
    $sourceEntries += @{Path=$file.FullName;Name=$file.FullName.Substring($sourcePath.Length+1).Replace('\','/')}
}
foreach($name in @('build.ps1','build.sh','.gitignore','.gitattributes')) {
    $sourceEntries += @{Path=(Join-Path $sourcePath $name);Name=$name}
}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $sourcePath 'tools') -File) {
    $sourceEntries += @{Path=$file.FullName;Name=('tools/'+$file.Name)}
}
$sourceEntries += @{Path=(Join-Path $outputsPath 'README.md');Name='README.md'}
$sourceEntries += @{Path=(Join-Path $outputsPath 'LICENSE.txt');Name='LICENSE.txt'}
foreach($name in @('Start.cmd','Start.ps1','start.sh')) {
    $sourceEntries += @{Path=(Join-Path $outputsPath $name);Name=$name}
}
foreach($file in Get-ChildItem -LiteralPath $outputsPath -File | Where-Object { $_.Extension -in @('.md','.txt','.json') -and $_.Name -notin @('README.md','LICENSE.txt','checksums.json','publish-receipt.json') }) {
    $sourceEntries += @{Path=$file.FullName;Name=('docs/'+$file.Name)}
}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $outputsPath 'examples') -File) {
    $sourceEntries += @{Path=$file.FullName;Name=('examples/'+$file.Name)}
}
Write-Zip (Join-Path $outputsPath $sourceName) $sourceEntries
$releaseEntries=@()
foreach($file in Get-ChildItem -LiteralPath $outputsPath -File | Where-Object { $_.Name -notin @($releaseName,'publish-receipt.json','checksums.json') }) {
    $entryName=$file.Name
    if ($file.Extension -in @('.md','.txt','.json') -and $file.Name -notin @('README.md','LICENSE.txt')) { $entryName='docs/'+$file.Name }
    $releaseEntries += @{Path=$file.FullName;Name=$entryName}
}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $outputsPath 'examples') -File) {
    $releaseEntries += @{Path=$file.FullName;Name=('examples/'+$file.Name)}
}
Write-Zip (Join-Path $outputsPath $releaseName) $releaseEntries
$checksumEntries=@()
foreach($file in Get-ChildItem -LiteralPath $outputsPath -File | Where-Object { $_.Name -notin @('checksums.json','publish-receipt.json') }) {
    $packagePath=$file.Name
    if ($file.Extension -in @('.md','.txt','.json') -and $file.Name -notin @('README.md','LICENSE.txt')) { $packagePath='docs/'+$file.Name }
    if ($file.Name -eq $releaseName) { $packagePath=$null }
    $checksumEntries += [ordered]@{file=$file.Name;packagePath=$packagePath;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$manifest=[ordered]@{product='Cairn / CMCL Java';version=$Version;schema=1;files=$checksumEntries}
[IO.File]::WriteAllText((Join-Path $outputsPath 'checksums.json'),($manifest | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$checksumEntries | ForEach-Object { [pscustomobject]$_ } | Select-Object file,bytes,sha256
