# Toolchain install for WINDOWS_EC2 reserved-fleet AMIs. bake-ami.sh runs this
# via SSM on a Windows Server 2022 base instance and then snapshots the AMI.
# Env inputs: GRAALVM_ARCHIVE_URL / JDK11_ARCHIVE_URL (windows-x86_64 zips),
# optional WINDOWS_CUDA_INSTALLER_URLS (space-separated silent installers).
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072

if (-not (Get-Command choco -ErrorAction SilentlyContinue)) {
  Invoke-Expression ((New-Object Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))
  $env:Path = "$env:Path;$env:ALLUSERSPROFILE\chocolatey\bin"
}
choco install -y git maven cmake protoc ccache strawberryperl python ruby 7zip msys2

# MSYS2 MINGW64 toolchain — mirrors the DL4J Windows workflows' pacman list
# (build-deploy-windows*.yml). Without this the libnd4j native Windows build
# has no gcc/gfortran/nasm/ragel/SDL2, and — critically — no Vulkan headers or
# loader, so windows-x86_64 and its Vulkan support cannot compile.
$msys = 'C:\tools\msys64'
if (-not (Test-Path "$msys\usr\bin\pacman.exe")) { throw "MSYS2 not found at $msys after choco install" }
$pacman = "$msys\usr\bin\pacman.exe"
$mingwPkgs = @(
  'mingw-w64-x86_64-toolchain', 'mingw-w64-x86_64-gcc', 'mingw-w64-x86_64-gcc-fortran',
  'mingw-w64-x86_64-cmake', 'mingw-w64-x86_64-make', 'mingw-w64-x86_64-libtool',
  'mingw-w64-x86_64-libwinpthread-git', 'mingw-w64-x86_64-nasm', 'mingw-w64-x86_64-ragel',
  'mingw-w64-x86_64-pkg-config', 'mingw-w64-x86_64-gnupg', 'mingw-w64-x86_64-SDL2',
  'mingw-w64-x86_64-vulkan-headers', 'mingw-w64-x86_64-vulkan-loader'
)
# Refresh + upgrade the base (each pacman call is a fresh subprocess, so the
# MSYS2 runtime self-update on the first pass is picked up by the second).
& $pacman -Syu  --noconfirm 2>&1 | Out-Null
& $pacman -Syuu --noconfirm 2>&1 | Out-Null
& $pacman -S --needed --noconfirm @mingwPkgs
if ($LASTEXITCODE -ne 0) { throw "pacman failed to install the MINGW64 toolchain" }
$env:MSYSTEM = 'MINGW64'
[Environment]::SetEnvironmentVariable('MSYSTEM', 'MINGW64', 'Machine')
# Put the MinGW64 bin on the machine PATH so run-build.ps1's bash/cmake find
# g++, gfortran, and the vulkan loader import lib during the native build.
$mingwBin = "$msys\mingw64\bin"
$machinePath0 = [Environment]::GetEnvironmentVariable('Path', 'Machine')
if ($machinePath0 -notlike "*$mingwBin*") {
  [Environment]::SetEnvironmentVariable('Path', "$machinePath0;$mingwBin", 'Machine')
}

function Install-ZipTo([string]$Url, [string]$Dest) {
  $zip = Join-Path $env:TEMP ([IO.Path]::GetRandomFileName() + ".zip")
  Invoke-WebRequest $Url -OutFile $zip
  $stage = Join-Path $env:TEMP ([IO.Path]::GetRandomFileName())
  & "$env:ProgramData\chocolatey\bin\7z.exe" x $zip "-o$stage" -y | Out-Null
  $entries = @(Get-ChildItem $stage)
  New-Item -ItemType Directory -Force (Split-Path $Dest) | Out-Null
  if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) {
    Move-Item $entries[0].FullName $Dest
  } else {
    Move-Item $stage $Dest
  }
  Remove-Item $zip -Force -ErrorAction SilentlyContinue
}

if (Test-Path 'C:\opt\graalvm') { Remove-Item -Recurse -Force 'C:\opt\graalvm' }
if (Test-Path 'C:\opt\jdk11') { Remove-Item -Recurse -Force 'C:\opt\jdk11' }
Install-ZipTo $env:GRAALVM_ARCHIVE_URL 'C:\opt\graalvm'
Install-ZipTo $env:JDK11_ARCHIVE_URL 'C:\opt\jdk11'

if ($env:WINDOWS_CUDA_INSTALLER_URLS) {
  foreach ($url in ($env:WINDOWS_CUDA_INSTALLER_URLS -split '\s+' | Where-Object { $_ })) {
    $exe = Join-Path $env:TEMP ([IO.Path]::GetRandomFileName() + ".exe")
    Invoke-WebRequest $url -OutFile $exe
    Write-Host "Installing CUDA (silent): $url"
    Start-Process -FilePath $exe -ArgumentList "-s" -Wait
    Remove-Item $exe -Force -ErrorAction SilentlyContinue
  }
}

[Environment]::SetEnvironmentVariable('JAVA11_HOME', 'C:\opt\jdk11', 'Machine')
[Environment]::SetEnvironmentVariable('GRAALVM_HOME', 'C:\opt\graalvm', 'Machine')
# run-build.ps1 shells out to `bash` (git-bash) for build-dist.sh and
# change-cuda-versions.sh; put it on the machine PATH.
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
if ($machinePath -notlike '*Git\bin*') {
  [Environment]::SetEnvironmentVariable('Path', "$machinePath;C:\Program Files\Git\bin", 'Machine')
}

& 'C:\opt\jdk11\bin\java.exe' -version
& 'C:\opt\graalvm\bin\native-image.cmd' --version
& "$mingwBin\gcc.exe" --version | Select-Object -First 1
if (-not (Test-Path "$msys\mingw64\include\vulkan\vulkan.h")) { throw "Vulkan headers missing from MINGW64 toolchain" }
Write-Host "Windows toolchain bootstrap complete (MINGW64 + Vulkan headers/loader present)."
