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
$asset = Join-Path $root "Agon-$Version.apk"

# Pre-flight: catch broken strings, arity, shadowed fields, unbalanced braces and
# out-of-bounds tab lookups before the build, so a crashing APK is never published.
$preflight = Join-Path $root "tools\check-resources.py"
$python = Get-Command python -ErrorAction SilentlyContinue
if ($null -ne $python -and (Test-Path $preflight)) {
    Write-Host "[0/3] Pre-flight checks..."
    & $python.Source $preflight | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail "Pre-flight checks failed (see above). Nothing was published." }
}

# A release is public the moment it is attached to a published repo, so refuse to publish
# anything that carries a token, a keystore password or a personal path.
$secretCheck = Join-Path $root "tools\check-secrets.py"
if ($null -ne $python -and (Test-Path $secretCheck)) {
    Write-Host "[0/3] Checking tracked files for secrets..."
    & $python.Source $secretCheck | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail "A secret or personal path is in the repository. Nothing was published." }
}

Write-Host "[1/3] Building..."
& (Join-Path $root "gradlew.bat") --no-daemon assembleDebug `
    "-PVERSION_NAME=$Version" "-PVERSION_CODE=$code" | Out-Host
if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed." }
if (-not (Test-Path $apk)) { Fail "APK not found at $apk" }

Copy-Item $apk $asset -Force
$sizeMb = [math]::Round((Get-Item $asset).Length / 1MB, 1)

# One asset for every phone: check the APK's architectures before it is published, so a
# 32-bit-only or 64-bit-only build can never become the download the in-app updater picks.
$abiCheck = Join-Path $root "tools\check-apk-abis.py"
if ($null -ne $python -and (Test-Path $abiCheck)) {
    Write-Host "      checking architectures..."
    & $python.Source $abiCheck $asset | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail "The APK does not cover both 32-bit and 64-bit (see above). Nothing was published." }
}
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
    $Notes = "Agon $Version`n`nInstall: download the APK below, tap it and approve Android's update prompt. Apps already running this build detect the release automatically."
}

$Notes = "$Notes`n`nAPK: Agon-$Version.apk ($sizeMb MB)"

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
    name             = "Agon $Version"
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
# Re-running for the same version is deliberate and safe: the old APK for that exact tag is
# replaced, and no other asset is touched. A *different* version is never overwritten - that
# would move people backwards.
foreach ($a in @($release.assets)) {
    if ($a.name -eq "Agon-$Version.apk") {
        Write-Host "      replacing the existing APK for this version"
        Invoke-RestMethod -Uri "$api/releases/assets/$($a.id)" -Headers $headers -Method Delete | Out-Null
    }
}

Write-Host "[3/3] Uploading $((Split-Path $asset -Leaf))..."
$upload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=Agon-$Version.apk"
Invoke-RestMethod -Uri $upload -Headers $headers -Method Post `
    -ContentType "application/vnd.android.package-archive" -InFile $asset | Out-Null

# ---------------------------------------------------------------- windows build
# The desktop client is published from the same tag, so both platforms have exactly one
# version number. It is optional on purpose: a PC without Node.js still publishes the APK
# (and says why the Windows files were skipped) instead of failing the whole release.
$desktopDir = Join-Path $root "desktop"
$npm = Get-Command npm -ErrorAction SilentlyContinue
if ((Test-Path (Join-Path $desktopDir "package.json")) -and $null -ne $npm) {
    Write-Host "[3b] Building the Windows client..."
    Push-Location $desktopDir
    try {
        # Keep package.json in step with the tag that was just published, so the Windows
        # build carries the same version number as the APK in the same release.
        $desktopPkg = Join-Path $desktopDir "package.json"
        $pkg = Get-Content $desktopPkg -Raw | ConvertFrom-Json
        if ($pkg.version -ne $Version) {
            $pkg.version = $Version
            ($pkg | ConvertTo-Json -Depth 20) | Set-Content $desktopPkg -Encoding UTF8
        }
        & $npm.Source install --no-audit --no-fund | Out-Host
        & $npm.Source run dist | Out-Host
        $distOk = ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
    }
    if (-not $distOk) {
        Write-Host "      [i] Windows build failed - the APK release is still published." -ForegroundColor Yellow
    } else {
        $releaseDir = Join-Path $desktopDir "release"
        $desktopAssets = @()
        if (Test-Path $releaseDir) {
            # latest.yml is what the desktop updater reads; builder-debug.yml is internal.
            $desktopAssets = Get-ChildItem $releaseDir -File |
                Where-Object { $_.Extension -in ".exe", ".blockmap" -or $_.Name -eq "latest.yml" }
        }
        $yml = $desktopAssets | Where-Object { $_.Name -eq "latest.yml" }
        if ($null -eq $yml) {
            Write-Host "      [i] No latest.yml from electron-builder; skipping desktop upload." -ForegroundColor Yellow
        } else {
            foreach ($file in $desktopAssets) {
                Write-Host "      uploading $($file.Name)..."
                $name = [uri]::EscapeDataString($file.Name)
                $assetUpload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=$name"
                Invoke-RestMethod -Uri $assetUpload -Headers $headers -Method Post `
                    -ContentType "application/octet-stream" -InFile $file.FullName | Out-Null
            }
        }
    }
} else {
    Write-Host "[3b] Windows client: skipped (Node.js/npm not found, or desktop/ missing)."
}

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
