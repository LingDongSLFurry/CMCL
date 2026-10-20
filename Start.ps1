$ErrorActionPreference = 'Stop'
$cairnUtf8 = [Text.UTF8Encoding]::new($false)
$cairnPreviousOutput = [Console]::OutputEncoding
$cairnPreviousInput = [Console]::InputEncoding
$cairnPreviousPipeline = $OutputEncoding
$cairnExit = 1
try {
    & "$env:SystemRoot\System32\chcp.com" 65001 | Out-Null
    [Console]::OutputEncoding = $cairnUtf8
    [Console]::InputEncoding = $cairnUtf8
    $OutputEncoding = $cairnUtf8
    & java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' '-Dcairn.console.encoding=UTF-8' -jar (Join-Path $PSScriptRoot 'cairn.jar') --plain @args
    $cairnExit = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $cairnPreviousOutput
    [Console]::InputEncoding = $cairnPreviousInput
    $OutputEncoding = $cairnPreviousPipeline
}
exit $cairnExit
