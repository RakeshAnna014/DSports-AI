param(
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$BackendDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $NoBuild) {
    Write-Host "=== Building project ===" -ForegroundColor Cyan
    mvn clean package -DskipTests -pl bootstrap -am
    if (-not $?) { exit 1 }
}

$JarFile = Get-ChildItem "$BackendDir\bootstrap\target\dsports-bootstrap-*.jar" | Select-Object -First 1
if (-not $JarFile) {
    Write-Error "No JAR found. Run without -NoBuild first."
    exit 1
}

Write-Host "=== Starting server ===" -ForegroundColor Green
java -jar $JarFile.FullName
