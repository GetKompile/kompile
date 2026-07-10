# In-build entry point for Windows hosts (WINDOWS_EC2 fleets or Windows
# containers). Mirrors run-build.sh: clone DL4J at DL4J_REF, build the
# backend, export Maven artifacts, optionally build the kompile dist via
# git-bash, then publish.
$ErrorActionPreference = "Stop"
$required = @("BUILD_TARGET","DL4J_REPOSITORY","DL4J_REF","DL4J_BUILD_THREADS","JAVA11_HOME")
foreach ($name in $required) {
  if (-not (Get-Item "Env:$name" -ErrorAction SilentlyContinue).Value) { throw "Missing $name" }
}
if ($env:BUILD_TARGET -notin @("windows-x86_64","windows-cuda-12.6","windows-cuda-12.9","cross-windows-x86_64","cpu-sanity-windows")) {
  throw "Unsupported Windows target: $env:BUILD_TARGET"
}
$root = $env:CODEBUILD_SRC_DIR
$dl4j = Join-Path $root ".codebuild/deeplearning4j"
if (Test-Path $dl4j) { Remove-Item -Recurse -Force $dl4j }
New-Item -ItemType Directory -Force (Join-Path $root "dist") | Out-Null

if ($env:DL4J_GITHUB_TOKEN) {
  $askpass = Join-Path $root ".codebuild/git-askpass.cmd"
  New-Item -ItemType Directory -Force (Split-Path $askpass) | Out-Null
  Set-Content $askpass '@echo off'
  Add-Content $askpass 'echo %1 | findstr /i Username >nul && (echo x-access-token) || (echo %DL4J_GITHUB_TOKEN%)'
  $env:GIT_ASKPASS = $askpass; $env:GIT_TERMINAL_PROMPT = "0"
}
git clone --filter=blob:none --no-checkout $env:DL4J_REPOSITORY $dl4j
git -C $dl4j fetch --depth 1 origin $env:DL4J_REF
git -C $dl4j checkout --detach FETCH_HEAD

$env:JAVA_HOME = $env:JAVA11_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$mvnArgs = @()
$modules = ":nd4j-native-preset,:nd4j-native,:libnd4j,:nd4j-native-platform"
$extra = @("-Dlibnd4j.generate.flatc=ON","-Dlibnd4j.sdx.standalone=ON","-Dlibnd4j.triton=ON")
if ($env:BUILD_TARGET -ne "cross-windows-x86_64") { $mvnArgs += "-Pcpu" }
if ($env:BUILD_TARGET -eq "cross-windows-x86_64") {
  $modules = ":libtokenizers,:tokenizers-native-preset,:tokenizers-native"
  $extra += @("-Djavacpp.platform.build=windows-x86_64-mingw","-Djavacpp.platform.compiler=g++")
}
if ($env:BUILD_TARGET -like "windows-cuda-*") {
  $cuda = $env:BUILD_TARGET.Replace("windows-cuda-","")
  & bash "$dl4j/change-cuda-versions.sh" $cuda
  if ($LASTEXITCODE -ne 0) { throw "change-cuda-versions.sh failed" }
  $mvnArgs = @("-Pcuda")
  $modules = ":nd4j-cuda-$cuda,:nd4j-cuda-$cuda-preset,:libnd4j"
  $extra += @("-Dlibnd4j.chip=cuda","-Dlibnd4j.cuda.compile.skip=false","-Dlibnd4j.cpu.compile.skip=true","-Dlibnd4j.compute=$env:CUDA_COMPUTE_CAPABILITIES")
}

Push-Location $dl4j
if ($env:BUILD_TARGET -eq "cpu-sanity-windows") {
  # Build the checked-out backend first so tests exercise this ref, not stale
  # published snapshots.
  & mvn -Pcpu -pl ":nd4j-native,:nd4j-native-preset,:libnd4j" --also-make install -DskipTests `
    --batch-mode --no-transfer-progress "-Dlibnd4j.buildthreads=$env:DL4J_BUILD_THREADS" `
    "-Djavacpp.platform=windows-x86_64" @extra 2>&1 |
    Tee-Object (Join-Path $root "dist/$env:BUILD_TARGET-build.log")
  if ($LASTEXITCODE -ne 0) { throw "DL4J build failed" }
  & mvn -Pcpu -pl platform-tests test -Djavacpp.platform=windows-x86_64 --batch-mode --no-transfer-progress 2>&1 |
    Tee-Object (Join-Path $root "dist/$env:BUILD_TARGET.log")
  if ($LASTEXITCODE -ne 0) { throw "Windows validation failed" }
} else {
  & mvn @mvnArgs -pl $modules --also-make install -DskipTests --batch-mode --no-transfer-progress `
    "-Dlibnd4j.buildthreads=$env:DL4J_BUILD_THREADS" "-Djavacpp.platform=windows-x86_64" @extra 2>&1 |
    Tee-Object (Join-Path $root "dist/$env:BUILD_TARGET-dl4j.log")
  if ($LASTEXITCODE -ne 0) { throw "DL4J build failed" }

  $m2 = Join-Path $env:USERPROFILE ".m2/repository"
  $subtrees = @("org/nd4j","org/deeplearning4j","org/bytedeco","org/eclipse/deeplearning4j") |
    ForEach-Object { Join-Path $m2 $_ } | Where-Object { Test-Path $_ }
  if ($subtrees.Count -gt 0) {
    Compress-Archive -Path $subtrees -DestinationPath (Join-Path $root "dist/$env:BUILD_TARGET-maven-artifacts.zip") -Force
  }
}
Pop-Location

if ($env:BUILD_KOMPILE -eq "true") {
  $env:JAVA_HOME = $env:GRAALVM_HOME
  $env:Path = "$env:JAVA_HOME\bin;$env:Path"
  Push-Location $root
  & bash ./build-dist.sh $env:KOMPILE_VARIANT --parallel $env:NATIVE_PARALLELISM --output-dir "$root/dist"
  if ($LASTEXITCODE -ne 0) { throw "Kompile build failed" }
  Pop-Location
}

& (Join-Path $root "aws/codebuild/scripts/publish-dist.ps1") (Join-Path $root "dist")
