param(
    [Parameter(Mandatory=$true)][string]$Repository,
    [Parameter(Mandatory=$true)][string]$Commit,
    [Parameter(Mandatory=$true)][string]$Tag,
    [Parameter(Mandatory=$true)][string]$BodyFile,
    [Parameter(Mandatory=$true)][string[]]$AssetPaths,
    [Parameter(Mandatory=$true)][string]$Receipt,
    [Parameter(Mandatory=$true)][string]$VerificationDirectory
)
$ErrorActionPreference='Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$' -or $Commit -notmatch '^[0-9a-f]{40}$' -or $Tag -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid repository, commit or tag.' }
$owner=$Repository.Split('/')[0]
$env:GIT_TERMINAL_PROMPT='0'
$env:GCM_INTERACTIVE='Never'
$credentialLines=@("protocol=https`nhost=github.com`nusername=$owner`n`n" | git -c credential.interactive=never credential fill 2>$null)
if ($LASTEXITCODE -ne 0) { throw 'A cached GitHub credential with repository write access is required.' }
$passwordLine=$credentialLines | Where-Object { $_.StartsWith('password=') } | Select-Object -First 1
if (-not $passwordLine) { throw 'The cached credential does not contain a token.' }
$releaseToken=$passwordLine.Substring(9)
$credentialLines=$null
$passwordLine=$null
$headers=@{Authorization="Bearer $releaseToken";Accept='application/vnd.github+json';'X-GitHub-Api-Version'='2022-11-28';'User-Agent'='CairnReleaseTool/0.1.0'}
$api="https://api.github.com/repos/$Repository"
try {
    $repo=Invoke-RestMethod "$api" -Headers $headers -TimeoutSec 30
    if (-not $repo.permissions.push) { throw 'The authenticated account cannot push to this repository.' }
    if ($repo.private) { throw 'This publishing helper verifies public downloads and expects a public repository.' }
    $assets=@()
    $names=@{}
    foreach($path in $AssetPaths) {
        $file=Get-Item -LiteralPath $path
        if ($file.PSIsContainer -or $file.Length -le 0) { throw "Asset is not a nonempty file: $path" }
        if ($names.ContainsKey($file.Name)) { throw "Duplicate asset name: $($file.Name)" }
        $names[$file.Name]=$true
        $assets += [ordered]@{name=$file.Name;path=$file.FullName;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $release=$null
    try { $release=Invoke-RestMethod "$api/releases/tags/$Tag" -Headers $headers -TimeoutSec 30 }
    catch { if ([int]$_.Exception.Response.StatusCode -ne 404) { throw } }
    if ($release -and $release.target_commitish -ne $Commit) { throw 'An existing release uses this tag for another source revision.' }
    if (-not $release) {
        $body=@{tag_name=$Tag;target_commitish=$Commit;name='Cairn / 石栈 0.1.0 — Java 终端启动器';body=(Get-Content -LiteralPath $BodyFile -Raw -Encoding UTF8);draft=$true;prerelease=$true;make_latest='false'} | ConvertTo-Json -Depth 6
        $release=Invoke-RestMethod "$api/releases" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
    }
    $uploaded=@()
    foreach($asset in $assets) {
        $existing=@($release.assets | Where-Object name -EQ $asset.name)
        if ($existing.Count -gt 0) {
            $remote=$existing[0]
            if ($remote.size -ne $asset.bytes -or $remote.digest -ne ('sha256:'+$asset.sha256)) { throw "Existing remote asset has different content: $($asset.name)" }
        } else {
            if (-not $release.draft) { throw 'Refusing to append files to an already published release.' }
            $contentType=switch ([IO.Path]::GetExtension($asset.name)) { '.jar' {'application/java-archive'} '.zip' {'application/zip'} '.json' {'application/json'} '.md' {'text/markdown'} default {'application/octet-stream'} }
            $uri="https://uploads.github.com/repos/$Repository/releases/$($release.id)/assets?name=$([uri]::EscapeDataString($asset.name))"
            $remote=Invoke-RestMethod $uri -Method Post -Headers $headers -InFile $asset.path -ContentType $contentType -TimeoutSec 180
        }
        if ($remote.size -ne $asset.bytes) { throw "Remote size mismatch: $($asset.name)" }
        if ($remote.digest -and $remote.digest -ne ('sha256:'+$asset.sha256)) { throw "Remote SHA-256 mismatch: $($asset.name)" }
        $uploaded += [ordered]@{name=$asset.name;bytes=$asset.bytes;sha256=$asset.sha256;url=$remote.browser_download_url;apiDigest=$remote.digest}
        Write-Output "Uploaded and checked: $($asset.name) ($($asset.bytes) bytes)"
    }
    if ($release.draft) {
        $release=Invoke-RestMethod "$api/releases/$($release.id)" -Method Patch -Headers $headers -ContentType 'application/json' -Body '{"draft":false,"prerelease":true,"make_latest":"false"}' -TimeoutSec 30
    }
    # Draft upload URLs use a temporary untagged name. Refresh them after publishing.
    $published=Invoke-RestMethod "$api/releases/tags/$Tag" -Headers $headers -TimeoutSec 30
    foreach($asset in $uploaded) {
        $fresh=@($published.assets | Where-Object name -EQ $asset.name)
        if ($fresh.Count -ne 1 -or $fresh[0].size -ne $asset.bytes) { throw "Published asset metadata mismatch: $($asset.name)" }
        $asset['url']=$fresh[0].browser_download_url
    }
    New-Item -ItemType Directory -Force -Path $VerificationDirectory | Out-Null
    foreach($asset in $uploaded) {
        $download=Join-Path $VerificationDirectory $asset.name
        $downloaded=$false
        for($attempt=0;$attempt -lt 3;$attempt++) {
            try {
                Invoke-WebRequest -Uri $asset.url -OutFile $download -TimeoutSec 120 | Out-Null
                $downloaded=$true
                break
            } catch { if ($attempt -ge 2) { throw }; Start-Sleep -Seconds (2*($attempt+1)) }
        }
        if (-not $downloaded -or (Get-Item -LiteralPath $download).Length -ne $asset.bytes -or (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $asset.sha256) { throw "Public download verification failed: $($asset.name)" }
        $asset['verified']=$true
        Write-Output "Public download SHA-256 verified: $($asset.name)"
    }
    $published=Invoke-RestMethod "$api/releases/tags/$Tag" -Headers $headers -TimeoutSec 30
    if ($published.draft) { throw 'Release is still a draft.' }
    $receiptObject=[ordered]@{repository=$Repository;sourceCommit=$Commit;tag=$Tag;url=$published.html_url;prerelease=$published.prerelease;draft=$published.draft;assets=$uploaded}
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Receipt))) | Out-Null
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($Receipt),($receiptObject | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
    Write-Output "Published: $($published.html_url)"
} finally {
    $releaseToken=$null
    $headers.Clear()
}
