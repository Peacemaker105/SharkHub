# Builds tools\installer\dist\SharkHubInstaller.exe with the C# compiler that ships with the
# .NET Framework (no SDK needed), then renders a check picture of the window.
# Usage:  powershell -ExecutionPolicy Bypass -File tools\installer\build.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$fw = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
$csc = Join-Path $fw 'csc.exe'
$wpf = Join-Path $fw 'WPF'
$build = Join-Path $here 'build'
$dist = Join-Path $here 'dist'
New-Item -ItemType Directory -Force $build, $dist | Out-Null

python (Join-Path $here 'make_assets.py') $build
if ($LASTEXITCODE -ne 0) { throw 'make_assets.py failed' }

$exe = Join-Path $dist 'SharkHubInstaller.exe'
$cscArgs = @(
    '/nologo', '/target:winexe', '/optimize+', '/langversion:5',
    "/out:$exe",
    "/win32icon:$(Join-Path $build 'app.ico')",
    "/r:$(Join-Path $wpf 'PresentationFramework.dll')",
    "/r:$(Join-Path $wpf 'PresentationCore.dll')",
    "/r:$(Join-Path $wpf 'WindowsBase.dll')",
    '/r:System.Xaml.dll', '/r:System.Web.Extensions.dll',
    '/r:System.IO.Compression.dll', '/r:System.IO.Compression.FileSystem.dll',
    "/resource:$(Join-Path $here 'Installer.xaml'),Installer.xaml",
    "/resource:$(Join-Path $build 'logo.png'),logo.png",
    "/resource:$(Join-Path $build 'icon.png'),icon.png",
    (Join-Path $here 'SharkHubInstaller.cs')
)
& $csc @cscArgs
if ($LASTEXITCODE -ne 0) { throw 'csc failed' }

# the window, rendered off-screen: proves the embedded XAML loads
$check = Join-Path $build 'check.png'
$p = Start-Process -FilePath $exe -ArgumentList @('--snapshot', $check, 'idle') -Wait -PassThru
if ($p.ExitCode -ne 0 -or -not (Test-Path $check)) { throw 'the built exe could not render its window' }
Get-Item $exe | Select-Object FullName, Length
