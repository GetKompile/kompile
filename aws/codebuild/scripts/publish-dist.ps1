# Windows mirror of publish-dist.sh: stable S3 layout plus optional GitHub
# Release asset uploads (target-prefixed names, non-log files only).
param([Parameter(Mandatory = $true)][string]$Dist)
$ErrorActionPreference = "Stop"

$target = $env:BUILD_TARGET
if (-not $target) { throw "BUILD_TARGET is required" }
$version = $env:RELEASE_TAG
if (-not $version) {
  $sha = $env:CODEBUILD_RESOLVED_SOURCE_VERSION
  if ($sha) { $version = $sha.Substring(0, [Math]::Min(12, $sha.Length)) } else { $version = "adhoc" }
}
$files = @(Get-ChildItem -Path $Dist -Recurse -File -ErrorAction SilentlyContinue)
if ($files.Count -eq 0) { Write-Host "publish-dist: $Dist is empty; nothing to publish"; exit 0 }

if ($env:ARTIFACT_BUCKET -and (Get-Command aws -ErrorAction SilentlyContinue)) {
  $prefix = if ($env:RELEASE_PREFIX) { $env:RELEASE_PREFIX } else { "releases" }
  $dest = "s3://$($env:ARTIFACT_BUCKET)/$prefix/$version/$target/"
  Write-Host "publish-dist: uploading $Dist to $dest"
  & aws s3 cp --recursive --only-show-errors $Dist $dest
  if ($LASTEXITCODE -ne 0) { throw "S3 upload failed" }
} else {
  Write-Host "publish-dist: skipping S3 layout (no ARTIFACT_BUCKET or aws cli)"
}

if (-not ($env:GITHUB_RELEASE_REPO -and $env:GITHUB_RELEASE_TOKEN -and $env:RELEASE_TAG)) {
  Write-Host "publish-dist: GitHub Release upload disabled (needs GITHUB_RELEASE_REPO, GITHUB_RELEASE_TOKEN and a RELEASE_TAG)"
  exit 0
}
$repo = $env:GITHUB_RELEASE_REPO
$headers = @{
  Authorization = "Bearer $($env:GITHUB_RELEASE_TOKEN)"
  Accept = "application/vnd.github+json"
  "X-GitHub-Api-Version" = "2022-11-28"
}
$api = "https://api.github.com/repos/$repo"
$releaseId = $null
try {
  $releaseId = (Invoke-RestMethod -Headers $headers "$api/releases/tags/$($env:RELEASE_TAG)").id
} catch {
  Write-Host "publish-dist: creating GitHub release $($env:RELEASE_TAG) in $repo"
  $body = @{ tag_name = $env:RELEASE_TAG; name = $env:RELEASE_TAG; prerelease = $true
             body = "Automated kompile/DL4J build artifacts." } | ConvertTo-Json
  try { Invoke-RestMethod -Headers $headers -Method Post -Body $body "$api/releases" | Out-Null } catch {}
  $releaseId = (Invoke-RestMethod -Headers $headers "$api/releases/tags/$($env:RELEASE_TAG)").id
}
if (-not $releaseId) { throw "Could not create or find release $($env:RELEASE_TAG) in $repo" }

foreach ($f in ($files | Where-Object { $_.Extension -ne ".log" })) {
  $name = "$target-$($f.Name)"
  Write-Host "publish-dist: uploading asset $name"
  $uri = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets?name=$name"
  try {
    Invoke-RestMethod -Headers ($headers + @{"Content-Type" = "application/octet-stream"}) `
      -Method Post -InFile $f.FullName $uri | Out-Null
  } catch {
    Write-Warning "asset $name failed to upload (already exists?)"
  }
}
Write-Host "publish-dist: done (release $($env:RELEASE_TAG), $repo)"
