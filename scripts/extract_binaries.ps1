# extract_binaries.ps1
# Extract OpenVPN binaries from OpenVPN Connect APK
# Run this script from the scripts directory

param(
    [string]$ApkPath = "openvpn-connect.apk"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Join-Path $ScriptDir ".."
$OutputDir = Join-Path $ProjectRoot "android\app\src\main"

function Extract-Binaries {
    param([string]$ApkFile, [string]$Output)
    
    Write-Host "=== Extracting OpenVPN Binaries ===" -ForegroundColor Cyan
    Write-Host "APK: $ApkFile" -ForegroundColor Gray
    Write-Host "Output: $Output" -ForegroundColor Gray
    Write-Host ""
    
    if (-not (Test-Path $ApkFile)) {
        Write-Host "ERROR: APK not found at $ApkFile" -ForegroundColor Red
        Write-Host ""
        Write-Host "Please download OpenVPN Connect APK manually:" -ForegroundColor Yellow
        Write-Host "1. Go to: https://www.apkmirror.com/apk/openvpn-connect/openvpn-connect/" -ForegroundColor White
        Write-Host "2. Download the latest version (arm64-v8a or armeabi-v7a)" -ForegroundColor White
        Write-Host "3. Save the APK as 'openvpn-connect.apk' in the scripts folder" -ForegroundColor White
        Write-Host "4. Run this script again" -ForegroundColor White
        return
    }
    
    Write-Host "Checking APK contents..." -ForegroundColor Cyan
    
    # Get temp directory for extraction
    $TempDir = Join-Path $env:TEMP "openvpn_extract_$(Get-Random)"
    New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
    Write-Host "Using temp directory: $TempDir" -ForegroundColor Gray
    
    try {
        # Expand APK (it's a zip file)
        Write-Host "Expanding APK..." -ForegroundColor Cyan
        Expand-Archive -Path $ApkFile -DestinationPath $TempDir -Force
        
        # Find lib directories
        $LibDir = Join-Path $TempDir "lib"
        if (Test-Path $LibDir) {
            $Architectures = Get-ChildItem $LibDir -Directory
            
            foreach ($Arch in $Architectures) {
                $ArchName = $Arch.Name
                Write-Host ""
                Write-Host "Processing $ArchName..." -ForegroundColor Yellow
                
                # Create output directories
                $JniOutput = Join-Path $Output "jniLibs" $ArchName
                $AssetsOutput = Join-Path $Output "assets"
                New-Item -ItemType Directory -Path $JniOutput -Force | Out-Null
                New-Item -ItemType Directory -Path $AssetsOutput -Force | Out-Null
                
                # Find relevant files
                $Files = Get-ChildItem $Arch.FullName -Filter "*.so" -File
                
                foreach ($File in $Files) {
                    if ($File.Name -like "*openvpn*" -or $File.Name -like "*ovpn*") {
                        $TargetPath = Join-Path $JniOutput $File.Name
                        Copy-Item $File.FullName $TargetPath -Force
                        Write-Host "  Extracted jniLibs: $($File.Name)" -ForegroundColor Green
                    }
                }
                
                # Check for pie_openvpn binary in assets
                $AssetsDir = Join-Path $TempDir "assets"
                if (Test-Path $AssetsDir) {
                    $PieFiles = Get-ChildItem $AssetsDir -Filter "pie_openvpn.*" -File
                    foreach ($File in $PieFiles) {
                        $TargetPath = Join-Path $AssetsOutput $File.Name
                        Copy-Item $File.FullName $TargetPath -Force
                        Write-Host "  Extracted assets: $($File.Name)" -ForegroundColor Green
                    }
                }
            }
        }
        
        Write-Host ""
        Write-Host "=== Extraction Complete ===" -ForegroundColor Green
        Write-Host ""
        Write-Host "Binaries have been extracted to:" -ForegroundColor Cyan
        Write-Host "  - $Output\jniLibs\" -ForegroundColor White
        Write-Host "  - $Output\assets\" -ForegroundColor White
        Write-Host ""
        
    }
    finally {
        # Cleanup
        if (Test-Path $TempDir) {
            Remove-Item $TempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# Check for dependencies
if (-not (Get-Command Expand-Archive -ErrorAction SilentlyContinue)) {
    Write-Host "PowerShell 5.0+ required for Expand-Archive" -ForegroundColor Red
    exit 1
}

# Run extraction
Extract-Binaries -ApkFile $ApkPath -Output $OutputDir

Write-Host ""
Write-Host "Press any key to exit..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
