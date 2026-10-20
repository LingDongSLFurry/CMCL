param(
    [Parameter(Mandatory=$true)][string]$Reference,
    [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference = 'Stop'
$refPath = (Resolve-Path -LiteralPath $Reference).Path
$revision = (& git -C $refPath rev-parse HEAD).Trim()
$sourceFiles = @(Get-ChildItem -LiteralPath $refPath -Recurse -File |
    Where-Object { $_.Extension -in @('.vb','.cs','.xaml') -and $_.FullName -notlike '*\.git\*' })
$resultFiles = @()
$lineCount = 0
foreach ($file in $sourceFiles) {
    $rows = @(Get-Content -LiteralPath $file.FullName -Encoding UTF8)
    $lineCount += $rows.Count
    $declarations = @()
    $settings = @()
    $labels = @()
    for ($i = 0; $i -lt $rows.Count; $i++) {
        $line = $rows[$i]
        $vbMatch = [regex]::Match($line,'^\s*(?:(?:Public|Private|Friend|Protected|Shared|Async|Partial|Overrides|Overloads|Overridable|ReadOnly|WriteOnly|NotInheritable|MustInherit|Default|Static|Shadows)\s+)*(Class|Module|Enum|Structure|Interface|Sub|Function|Property)\s+([^\s(]+)')
        if ($vbMatch.Success) {
            $declarations += [ordered]@{kind=$vbMatch.Groups[1].Value;name=$vbMatch.Groups[2].Value;line=($i+1)}
        }
        if ($file.Extension -eq '.cs') {
            $csMatch = [regex]::Match($line,'^\s*(?:(?:public|private|internal|protected|static|abstract|sealed|partial|async|override|virtual|readonly)\s+)+(?:[\w<>,?\[\].]+\s+)?(\w+)\s*(?:\(|\{)')
            if ($csMatch.Success) { $declarations += [ordered]@{kind='C# declaration';name=$csMatch.Groups[1].Value;line=($i+1)} }
        }
        foreach ($m in [regex]::Matches($line,'New Setting\("([^"]+)"')) { $settings += [ordered]@{name=$m.Groups[1].Value;line=($i+1)} }
        if ($file.Extension -eq '.xaml') {
            foreach ($m in [regex]::Matches($line,'(?:Text|Title|Content)="([^"]{1,160})"')) {
                $text = $m.Groups[1].Value
                if ($text -notlike '{*' -and $text -notmatch '^pack://|^M\d|^&#') { $labels += [ordered]@{label=$text;line=($i+1)} }
            }
        }
    }
    $resultFiles += [ordered]@{
        path=$file.FullName.Substring($refPath.Length+1).Replace('\','/')
        lines=$rows.Count
        declarations=$declarations
        settings=$settings
        featureLabels=$labels
    }
}
$inventory = [ordered]@{
    repository='https://github.com/Meloong-Git/PCL'
    revision=$revision
    surveyDate='2026-10-20'
    scope='Public-source symbol/setting/feature-label inventory including the pinned MeloongCore submodule; no implementation bodies or private service code.'
    submodules=@([ordered]@{name='MeloongCore';repository='https://github.com/Meloong-Git/MeloongCore';revision=(& git -C (Join-Path $refPath 'MeloongCore') rev-parse HEAD).Trim()})
    sourceFiles=$sourceFiles.Count
    sourceLines=$lineCount
    files=$resultFiles
}
$json = $inventory | ConvertTo-Json -Depth 20
$outputPath = [IO.Path]::GetFullPath($Output)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath,$json,[Text.UTF8Encoding]::new($false))
[pscustomobject]@{Files=$sourceFiles.Count;Lines=$lineCount;Declarations=($resultFiles.declarations.Count);Settings=($resultFiles.settings.Count);Output=$outputPath}
