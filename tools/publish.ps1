<#
  Creates (or updates) a GitHub release for this repository and uploads the APK,
  so every installed copy of the app sees it and offers the update.

  Called by publish.bat - you normally do not run this directly.

  Parameters:
    -Version  1.2.0 (no leading "v")
    -Token    GitHub token with "Contents: read and write"
    -Notes    release notes text (plain)
    -Channel  "stable" (published) or "beta" (pre-release)
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Notes = "",
    [string]$Channel = "stable",
    [string]$Owner = "Erawat4l",
    [string]$Repo = "Arena-Ai-App"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# Repo root = parent of this script's folder.
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
Write-Host "Working in: $root"

function Fail($message) {
    Write-Host ""
    Write-Host "[X] $message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- version
$Version = $Version.TrimStart("v", "V")
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    Fail "Version must look like 1.2.3 (got '$Version')."
}
$major = [int]$Matches[1]; $minor = [int]$Matches[2]; $patch = [int]$Matches[3]
$code = $major * 1000000 + $minor * 1000 + $patch
$tag = "v$Version"

Write-Host "Publishing Arena $Version (versionCode $code)" -ForegroundColor Cyan

# ---------------------------------------------------------------- build
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$asset = Join-Path $root "ArenaAI-$Version.apk"

# Pre-flight: catch broken strings, arity, shadowed fields, unbalanced braces and
# out-of-bounds tab lookups before the build, so a crashing APK is never published.
$preflight = Join-Path $root "tools\check-resources.py"
$python = Get-Command python -ErrorAction SilentlyContinue
if ($null -ne $python -and (Test-Path $preflight)) {
    Write-Host "[0/3] Pre-flight checks..."
    & $python.Source $preflight | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail "Pre-flight checks failed (see above). Nothing was published." }
}

Write-Host "[1/3] Building..."
& (Join-Path $root "gradlew.bat") --no-daemon assembleDebug `
    "-PVERSION_NAME=$Version" "-PVERSION_CODE=$code" | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed." }
if (-not (Test-Path $apk)) { Fail "APK not found at $apk" }

Copy-Item $apk $asset -Force
$sizeMb = [math]::Round((Get-Item $asset).Length / 1MB, 1)
Write-Host "      $((Split-Path $asset -Leaf))  ($sizeMb MB)"

# ---------------------------------------------------------------- github api
$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "ArenaPublisher"
}
$api = "https://api.github.com/repos/$Owner/$Repo"

# Verify the token before doing anything else.
try {
    $me = Invoke-RestMethod -Uri "https://api.github.com/user" -Headers $headers -Method Get
} catch {
    Fail "GitHub rejected the token (401). Create one with 'Contents: read and write' on $Owner/$Repo."
}

# Fail early with a clear message when the token can read the repo but not write to it.
try {
    $repoInfo = Invoke-RestMethod -Uri $api -Headers $headers -Method Get
} catch {
    Fail "This token cannot see $Owner/$Repo. Give it access to that repository."
}
if ($repoInfo.permissions -and -not $repoInfo.permissions.push) {
    Fail "The token can read $Owner/$Repo but not write. Set Contents = Read and write."
}

$sha = (& git rev-parse HEAD).Trim()
if (-not $Notes) {
    $Notes = "Arena $Version`n`nInstall: download the APK below, tap it and approve Android's update prompt. Apps already running this build detect the release automatically."
}

$Notes = "$Notes`n`nAPK: ArenaAI-$Version.apk ($sizeMb MB)"

Write-Host "[2/3] Creating release $tag on $($sha.Substring(0,7))..."

$existing = $null
try {
    $existing = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $headers -Method Get
} catch {
    # 404 simply means "no release for this tag yet"
}

$body = @{
    tag_name         = $tag
    target_commitish = $sha
    name             = "Arena $Version"
    body             = $Notes
    draft            = $false
    prerelease       = ($Channel -eq "beta")
} | ConvertTo-Json

if ($existing) {
    Write-Host "      release already exists, updating it"
    $release = Invoke-RestMethod -Uri "$api/releases/$($existing.id)" -Headers $headers `
        -Method Patch -ContentType "application/json" -Body $body
} else {
    $release = Invoke-RestMethod -Uri "$api/releases" -Headers $headers `
        -Method Post -ContentType "application/json" -Body $body
}

# Replace an asset with the same name, otherwise GitHub answers 422.
foreach ($a in @($release.assets)) {
    if ($a.name -eq "ArenaAI-$Version.apk") {
        Invoke-RestMethod -Uri "$api/releases/assets/$($a.id)" -Headers $headers -Method Delete | Out-Null
    }
}

Write-Host "[3/3] Uploading $((Split-Path $asset -Leaf))..."
$upload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=ArenaAI-$Version.apk"
Invoke-RestMethod -Uri $upload -Headers $headers -Method Post `
    -ContentType "application/vnd.android.package-archive" -InFile $asset | Out-Null

Write-Host ""
Write-Host "=============================================" -ForegroundColor Green
Write-Host "  [OK] Release live: $tag" -ForegroundColor Green
Write-Host "  $($release.html_url)" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
Write-Host ""
Write-Host "Installed apps will show the update within a few hours, or immediately via"
Write-Host "Update -> Check for updates. To see it right now, pull down to refresh too."
Write-Host ""
Write-Host "Signed in as $($me.login). APK kept at: $asset"
