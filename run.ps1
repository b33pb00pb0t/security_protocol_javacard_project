param(
    [ValidateSet("simulator", "frontend", "")]
    [string]$Target = ""
)

$ErrorActionPreference = "Stop"

$BuildDir = "build"
$Classpath = "$BuildDir;lib/*"

Write-Host "--- Starting Build Process ---"
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

$Sources = @(
    Get-ChildItem "src/applet/*.java"
    Get-ChildItem "src/backend/*.java"
    Get-ChildItem "src/frontend/*.java"
    Get-ChildItem "src/terminals/*.java"
    Get-ChildItem "simulator/*.java"
) | ForEach-Object { $_.FullName }

javac -source 8 -target 8 -cp $Classpath -d $BuildDir @Sources

Write-Host "--- Build Completed Successfully ---"

switch ($Target) {
    "simulator" {
        Write-Host "Launching Membership Simulator..."
        java -cp $Classpath RunMembershipSimulator
    }
    "frontend" {
        Write-Host "Launching Frontend..."
        java -cp $Classpath frontend.Main
    }
    default {
        Write-Host "Usage: .\run.ps1 [simulator|frontend]"
        Write-Host "  simulator -> Run the Membership Simulator"
        Write-Host "  frontend  -> Run the Swing Frontend application"
    }
}
