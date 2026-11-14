<#
  Builds the Microsoft Store package (MSIX) from the jpackage app image.

    ./gradlew :composeApp:createDistributable -PappVersion=1.1.2
    powershell -File composeApp/packaging/msix/make-msix.ps1            # -> composeApp/build/msix/PhoneInput-1.1.2.msix
    powershell -File composeApp/packaging/msix/make-msix.ps1 -Register  # also install the unpacked layout (needs Developer Mode)

  The version is the app image's own (jpackage app-version), so the package always matches the app.

  The package is unsigned: Partner Center signs it for the Store.
#>
param(
    [switch]$Register
)
$ErrorActionPreference = 'Stop'

$here = $PSScriptRoot
$module = Resolve-Path (Join-Path $here '..\..')
$appImage = Join-Path $module 'build\compose\binaries\main\app\PhoneInput'
$outDir = Join-Path $module 'build\msix'
$layout = Join-Path $outDir 'layout'

if (-not (Test-Path (Join-Path $appImage 'PhoneInput.exe'))) { throw "App image not found at $appImage. Run :composeApp:createDistributable first." }
$Version = ([xml](Get-Content (Join-Path $appImage 'app\.jpackage.xml') -Raw)).'jpackage-state'.'app-version'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "App version must look like 1.2.3 (got '$Version')." }

# Unpacked layout: app image + second launcher + manifest + tile images.
if (Test-Path $layout) {
    # Unregister a previous -Register install so its files aren't locked.
    Get-AppxPackage -Name 'DrAtomic.PhoneInput' -ErrorAction SilentlyContinue | Remove-AppxPackage -ErrorAction SilentlyContinue
    Remove-Item $layout -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $layout | Out-Null
Copy-Item -Path (Join-Path $appImage '*') -Destination $layout -Recurse

# PhoneInputTray.exe: same jpackage launcher; it reads app\PhoneInputTray.cfg, whose [ArgOptions]
# default argument starts the app hidden in the tray (used by the StartupTask).
Copy-Item (Join-Path $layout 'PhoneInput.exe') (Join-Path $layout 'PhoneInputTray.exe')
$cfg = Get-Content (Join-Path $layout 'app\PhoneInput.cfg') -Raw
Set-Content -Path (Join-Path $layout 'app\PhoneInputTray.cfg') -Value ($cfg.TrimEnd() + "`r`n`r`n[ArgOptions]`r`n--tray`r`n") -Encoding ascii -NoNewline

Copy-Item (Join-Path $here 'Assets') (Join-Path $layout 'Assets') -Recurse
# Store versions need 0 as the fourth part.
(Get-Content (Join-Path $here 'AppxManifest.xml') -Raw).Replace('@VERSION@', "$Version.0") |
    Set-Content -Path (Join-Path $layout 'AppxManifest.xml') -Encoding utf8

if ($Register) {
    Add-AppxPackage -Register (Join-Path $layout 'AppxManifest.xml')
    Write-Host "Registered the unpacked package from $layout"
}

# makeappx.exe from the newest Windows 10/11 SDK.
$kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
$makeappx = Get-ChildItem $kits -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^10\.' } | Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'x64\makeappx.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $makeappx) { throw 'makeappx.exe not found: install the Windows 10/11 SDK.' }

$msix = Join-Path $outDir "PhoneInput-$Version.msix"
& $makeappx pack /d $layout /p $msix /o /h SHA256 | Out-Host
if ($LASTEXITCODE -ne 0) { throw "makeappx failed ($LASTEXITCODE)" }
Write-Host "Built $msix"
