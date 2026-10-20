param(
    [string]$Output = (Join-Path $PSScriptRoot 'dist'),
    [switch]$SkipTest
)
$ErrorActionPreference = 'Stop'
$javaCmd = (Get-Command java.exe -ErrorAction Stop).Source
$javaSettings = & $javaCmd -XshowSettings:properties -version 2>&1 | Out-String
$javaHomeMatch = [regex]::Match($javaSettings, 'java.home\s*=\s*([^\r\n]+)')
if (-not $javaHomeMatch.Success) { throw 'Cannot locate JDK. Set JAVA_HOME to a JDK 17 or later.' }
$jdkPath = $javaHomeMatch.Groups[1].Value.Trim()
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $jdkPath = $env:JAVA_HOME }
$compiler = Join-Path $jdkPath 'bin\javac.exe'
$jarTool = Join-Path $jdkPath 'bin\jar.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw "JDK required, javac not found: $compiler" }
$buildPath = Join-Path $PSScriptRoot 'build\classes'
New-Item -ItemType Directory -Force -Path $buildPath,$Output | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java\dev\cairn') -Filter '*.java' | ForEach-Object { $_.FullName })
& $compiler '-J-Duser.language=en' '-J-Duser.country=US' --release 17 -encoding UTF-8 -Xlint:all -d $buildPath @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$targetJar = Join-Path $Output 'cairn.jar'
& $jarTool --create --file $targetJar --main-class dev.cairn.Main -C $buildPath .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
if (-not $SkipTest) {
    & (Join-Path $jdkPath 'bin\java.exe') '-Djava.awt.headless=true' "-Dcairn.testRoot=$PSScriptRoot\build\test-work" -jar $targetJar --self-test
    if ($LASTEXITCODE -ne 0) { throw 'Self tests failed' }
}
Get-Item -LiteralPath $targetJar | Select-Object FullName,Length
Get-FileHash -LiteralPath $targetJar -Algorithm SHA256
