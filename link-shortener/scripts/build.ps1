$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    New-Item -ItemType Directory -Force out/main, out/test | Out-Null
    $main = @(Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $test = @(Get-ChildItem src/test/java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    & javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d out/main @main
    if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed' }
    & javac --release 17 -encoding UTF-8 -Xlint:all -Werror -cp out/main -d out/test @test
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & java '-Dfile.encoding=UTF-8' -cp 'out/main;out/test' shortener.TestRunner
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
    & jar --create --file out/link-shortener.jar --main-class shortener.Main -C out/main .
    if ($LASTEXITCODE -ne 0) { throw 'Jar creation failed' }
} finally {
    Pop-Location
}
