$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$ConfigB64 = '__KOMPILE_AZURE_WORKER_CONFIG_B64__'
$BuildDriverB64 = '__KOMPILE_BUILD_DRIVER_B64__'
$WorkRoot = 'C:\kompile-release'
$SourceDir = Join-Path $WorkRoot 'source'
$OutputDir = Join-Path $WorkRoot 'output'
$MavenRepo = Join-Path $WorkRoot 'm2'
$ConfigFile = Join-Path $WorkRoot 'worker.json'
$BuildDriver = Join-Path $WorkRoot 'build-platform.py'
$BootstrapLog = Join-Path $OutputDir 'bootstrap.log'
$BuildLog = Join-Path $OutputDir 'build.log'
New-Item -ItemType Directory -Force -Path $WorkRoot,$OutputDir,$MavenRepo | Out-Null
[IO.File]::WriteAllBytes($ConfigFile, [Convert]::FromBase64String($ConfigB64))
[IO.File]::WriteAllBytes($BuildDriver, [Convert]::FromBase64String($BuildDriverB64))
$Config = Get-Content -Raw $ConfigFile | ConvertFrom-Json
$Shard = $Config.shard
$BlobRoot = "https://$($Config.storageAccount).blob.core.windows.net/$($Config.artifactContainer)/$($Config.artifactPrefix)/$($Config.runId)/$($Shard.id)"
$ExitCode = 1
$UploadFailed = $false
$TranscriptStarted = $false
$AzCopy = Join-Path $WorkRoot 'azcopy.exe'

function Invoke-AzCopyRetry([string[]]$Arguments) {
  foreach ($Attempt in 1..10) {
    & $AzCopy @Arguments
    if ($LASTEXITCODE -eq 0) { return }
    Start-Sleep -Seconds ($Attempt * 6)
  }
  throw "AzCopy failed after retries: $($Arguments[0])"
}

function Upload-IfPresent([string]$Path, [string]$Name) {
  if (Test-Path $Path) {
    try {
      Invoke-AzCopyRetry @('copy', $Path, "$BlobRoot/$Name", '--overwrite=true')
    }
    catch {
      $script:UploadFailed = $true
      Write-Warning "Upload failed: $Name"
    }
  }
}

function Test-KillSwitch {
  $Target = Join-Path $WorkRoot 'kill-switch.json'
  Remove-Item $Target -Force -ErrorAction SilentlyContinue
  try {
    & $AzCopy copy $Config.killSwitchUrl $Target '--overwrite=true' | Out-Null
    if ($LASTEXITCODE -ne 0) { return $true }
    $Value = Get-Content -Raw $Target | ConvertFrom-Json
    return [bool]$Value.enabled
  }
  catch {
    return $true
  }
}

try {
  Start-Transcript -Path $BootstrapLog -Append -Force | Out-Null
  $TranscriptStarted = $true
  Set-ExecutionPolicy Bypass -Scope Process -Force
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  if (-not (Get-Command choco -ErrorAction SilentlyContinue)) {
    Invoke-Expression ((New-Object Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))
  }
  choco install -y --no-progress ccache cmake git maven ninja temurin11 temurin17 python312 7zip msys2 rustup.install visualstudio2022buildtools visualstudio2022-workload-vctools
  $MachinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
  $UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
  $env:PATH = "C:\Program Files\Git\cmd;C:\Program Files\Git\bin;C:\ProgramData\chocolatey\bin;$MachinePath;$UserPath;C:\tools\msys64\mingw64\bin;C:\tools\msys64\usr\bin;$env:PATH"
  git config --system core.longpaths true
  if ($LASTEXITCODE -ne 0) { throw 'Failed to enable Git long-path support' }
  $PythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
  if (-not $PythonCommand -or $PythonCommand.Source -like 'C:\tools\msys64\*') {
    throw "Windows Python executable unavailable after Chocolatey install"
  }
  $PythonExe = $PythonCommand.Source
  $MavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if (-not $MavenCommand) {
    $MavenScript = Get-ChildItem "$env:ChocolateyInstall\lib\maven" -Filter mvn.cmd -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($MavenScript) {
      $env:PATH = "$($MavenScript.DirectoryName);$env:PATH"
      $MavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    }
  }
  if (-not $MavenCommand) { throw 'mvn.cmd unavailable after Chocolatey install' }

  $AzCopyZip = Join-Path $WorkRoot 'azcopy.zip'
  $AzCopyDir = Join-Path $WorkRoot 'azcopy'
  Invoke-WebRequest 'https://aka.ms/downloadazcopy-v10-windows' -OutFile $AzCopyZip -UseBasicParsing
  Expand-Archive $AzCopyZip $AzCopyDir -Force
  $DownloadedAzCopy = Get-ChildItem $AzCopyDir -Filter azcopy.exe -Recurse | Select-Object -First 1
  Copy-Item $DownloadedAzCopy.FullName $AzCopy -Force
  & $AzCopy login --identity --identity-client-id $Config.managedIdentityClientId
  if ($LASTEXITCODE -ne 0) { throw 'AzCopy managed-identity login failed' }

  $JavaHome = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
  if (-not $JavaHome) {
    $JavaHome = Get-ChildItem 'C:\Program Files\Java' -Directory | Sort-Object Name -Descending | Select-Object -First 1
  }
  $env:JAVA_HOME = $JavaHome.FullName
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
  $env:CCACHE_DIR = Join-Path $WorkRoot 'ccache'
  $env:CMAKE_C_COMPILER_LAUNCHER = 'ccache'
  $env:CMAKE_CXX_COMPILER_LAUNCHER = 'ccache'
  New-Item -ItemType Directory -Force -Path $env:CCACHE_DIR | Out-Null
  ccache --max-size=100G

  & C:\tools\msys64\usr\bin\bash.exe -lc "pacman -S --needed --noconfirm base-devel git tar pkg-config unzip p7zip zip autoconf autoconf-archive automake patch make diffutils grep gzip mingw-w64-x86_64-make mingw-w64-x86_64-gnupg mingw-w64-x86_64-cmake mingw-w64-x86_64-nasm mingw-w64-x86_64-toolchain mingw-w64-x86_64-libtool mingw-w64-x86_64-gcc mingw-w64-x86_64-gcc-fortran mingw-w64-x86_64-libwinpthread-git mingw-w64-x86_64-SDL2 mingw-w64-x86_64-ragel mingw-w64-x86_64-sed mingw-w64-x86_64-ninja"
  $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
  $RustToolchain = 'stable-x86_64-pc-windows-gnu'
  $Rustup = Join-Path $env:USERPROFILE '.cargo\bin\rustup.exe'
  if (-not (Test-Path $Rustup)) { throw "rustup executable not found: $Rustup" }
  & $Rustup set auto-self-update disable
  & $Rustup toolchain install $RustToolchain --profile minimal
  if ($LASTEXITCODE -ne 0) { throw 'Rust GNU toolchain installation failed' }
  $env:RUSTUP_TOOLCHAIN = $RustToolchain
  $env:CARGO_BUILD_TARGET = 'x86_64-pc-windows-gnu'
  & $Rustup run $RustToolchain cargo install --locked cbindgen
  if ($LASTEXITCODE -ne 0) { throw 'cbindgen installation failed' }

  if ($Shard.build.backend -eq 'vulkan') {
    choco install -y --no-progress vulkan-sdk
  }

  if ($Shard.build.backend -eq 'cuda') {
    $env:CUDA_VERSION = $Shard.build.cudaVersion
    $Installer = Join-Path $WorkRoot 'install_cuda_windows.ps1'
    Invoke-WebRequest 'https://raw.githubusercontent.com/KonduitAI/cuda-install/master/.github/actions/install-cuda-windows/install_cuda_windows.ps1' -OutFile $Installer -UseBasicParsing
    & $Installer
    $CudaPath = "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v$($Shard.build.cudaVersion)"
    $SparseVersion = if ($Shard.build.cudaVersion -eq '12.9') { '12.5.10.65' } else { '12.5.4.2' }
    $SparseZip = Join-Path $WorkRoot 'cusparse.zip'
    $SparseDir = Join-Path $WorkRoot 'cusparse'
    Invoke-WebRequest "https://developer.download.nvidia.com/compute/cuda/redist/libcusparse/windows-x86_64/libcusparse-windows-x86_64-$SparseVersion-archive.zip" -OutFile $SparseZip -UseBasicParsing
    Expand-Archive $SparseZip $SparseDir -Force
    $SparseRoot = Get-ChildItem $SparseDir -Directory | Select-Object -First 1
    Copy-Item "$($SparseRoot.FullName)\include\*" "$CudaPath\include\" -Recurse -Force
    Copy-Item "$($SparseRoot.FullName)\lib\x64\*" "$CudaPath\lib\x64\" -Recurse -Force
    if (Test-Path "$($SparseRoot.FullName)\bin") {
      Copy-Item "$($SparseRoot.FullName)\bin\*" "$CudaPath\bin\" -Recurse -Force
    }
    $env:CUDA_PATH = $CudaPath
    $env:CUDNN_ROOT_DIR = $CudaPath
    $env:PATH = "$CudaPath\bin;$CudaPath\libnvvp;$env:PATH"
  }

  if (Test-KillSwitch) { throw 'Azure release kill switch is enabled or unreadable' }
  git init $SourceDir
  git -C $SourceDir remote add origin $Config.repository
  # The controller resolves a requested branch once; the worker always fetches
  # that immutable commit so a moving branch cannot invalidate an active run.
  git -C $SourceDir fetch --depth=1 origin $Config.commit
  if ($LASTEXITCODE -ne 0) { throw "Failed to fetch commit $($Config.commit)" }
  git -C $SourceDir checkout --detach $Config.commit
  $Actual = git -C $SourceDir rev-parse HEAD
  if ($Actual.Trim() -ne $Config.commit) { throw "Commit mismatch: $Actual" }

  $MavenOutput = Join-Path $OutputDir 'maven-repository'
  $SdkOutput = Join-Path $OutputDir 'sdk-assets'
  New-Item -ItemType Directory -Force -Path $MavenOutput,$SdkOutput | Out-Null
  Stop-Transcript | Out-Null
  $TranscriptStarted = $false
  $BuildStdout = Join-Path $OutputDir 'build.stdout.log'
  $BuildStderr = Join-Path $OutputDir 'build.stderr.log'
  $Arguments = @($BuildDriver, '--config', $ConfigFile, '--source', $SourceDir, '--repository', $MavenRepo, '--maven-output', $MavenOutput, '--sdk-output', $SdkOutput)
  $Process = Start-Process $PythonExe -ArgumentList $Arguments -RedirectStandardOutput $BuildStdout -RedirectStandardError $BuildStderr -PassThru -NoNewWindow
  while (-not $Process.HasExited) {
    Start-Sleep -Seconds 20
    if (Test-KillSwitch) {
      taskkill /PID $Process.Id /T /F | Out-Null
      throw 'Azure release kill switch stopped the build'
    }
    $Process.Refresh()
  }
  $Process.WaitForExit()
  $BuildExitCode = $Process.ExitCode
  Get-Content $BootstrapLog | Set-Content $BuildLog -Encoding UTF8
  if (Test-Path $BuildStdout) {
    Get-Content $BuildStdout | Add-Content $BuildLog -Encoding UTF8
  }
  if (Test-Path $BuildStderr) {
    Get-Content $BuildStderr | Add-Content $BuildLog -Encoding UTF8
  }
  if ($BuildExitCode -ne 0) { throw "Build failed with exit code $BuildExitCode" }

  & $PythonExe -c "import hashlib,json,pathlib,sys; root=pathlib.Path(sys.argv[1]); c=json.load(open(sys.argv[2])); files=[]; [(lambda p: files.append({'path':p.relative_to(root).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'size':p.stat().st_size}))(p) for p in sorted(root.rglob('*')) if p.is_file()]; json.dump({'schemaVersion':2,'provider':'azure','runId':c['runId'],'shard':c['shard']['id'],'commit':c['commit'],'dl4jCommit':c.get('dl4jCommit',''),'dl4jInputMode':c['dl4jInputMode'],'releaseVersion':c['releaseVersion'],'classifiers':[v.get('classifier',v.get('distributionClassifier',v['name'])) for v in c['shard']['build']['variants']],'files':files},open(root/'shard-manifest.json','w'),indent=2,sort_keys=True)" $OutputDir $ConfigFile
  & $PythonExe -c "import pathlib,tarfile,sys; root=pathlib.Path(sys.argv[1]); out=pathlib.Path(sys.argv[2]); t=tarfile.open(out,'w:gz'); t.add(root,arcname='.'); t.close()" $MavenOutput (Join-Path $OutputDir 'maven-repository.tar.gz')
  & $PythonExe -c "import pathlib,tarfile,sys; root=pathlib.Path(sys.argv[1]); out=pathlib.Path(sys.argv[2]); t=tarfile.open(out,'w:gz'); t.add(root,arcname='.'); t.close()" $SdkOutput (Join-Path $OutputDir 'sdk-assets.tar.gz')
  $ExitCode = 0
}
catch {
  $_ | Out-String | Add-Content $BuildLog -Encoding UTF8
  $ExitCode = 1
}
finally {
  if ($TranscriptStarted) {
    Stop-Transcript | Out-Null
    Get-Content $BootstrapLog | Add-Content $BuildLog -Encoding UTF8
  }
  Upload-IfPresent $BuildLog 'build.log'
  Upload-IfPresent (Join-Path $OutputDir 'maven-repository.tar.gz') 'maven-repository.tar.gz'
  Upload-IfPresent (Join-Path $OutputDir 'sdk-assets.tar.gz') 'sdk-assets.tar.gz'
  Upload-IfPresent (Join-Path $OutputDir 'shard-manifest.json') 'shard-manifest.json'
  if ($UploadFailed) { $ExitCode = 1 }
  @{shard=$Shard.id; exitCode=$ExitCode; completedAt=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()} | ConvertTo-Json | Set-Content (Join-Path $OutputDir 'status.json')
  # status.json is uploaded last and is the controller's durable completion marker.
  Upload-IfPresent (Join-Path $OutputDir 'status.json') 'status.json'
  if ($UploadFailed) { $ExitCode = 1 }
  shutdown.exe /s /t 0 /f
}
exit $ExitCode
