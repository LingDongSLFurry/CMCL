$ErrorActionPreference = 'Stop'
& java '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' -jar (Join-Path $PSScriptRoot 'cairn.jar') @args
exit $LASTEXITCODE
