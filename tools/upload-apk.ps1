<#
  Uploads an already-built Agon-<version>.apk to its release, without rebuilding.

  Use this when tools/publish.ps1 built everything and then lost the connection while
  uploading. That failure leaves the release as a DRAFT, so nothing is live and no
  installed app has been told about a version it cannot download.

  It finds the draft for the tag (or the published release, if the upload half-succeeded),
  replaces any APK already attached, uploads the local file, and only then publishes the
  release if it is still a draft.

  Usage:
      powershell -NoProfile -ExecutionPolicy Bypass -File tools\upload-apk.ps1 1.4.29
      powershell -NoProfile -ExecutionPolicy Bypass -File tools\upload-apk.ps1 1.4.29 -Token github_pat_xxx

  No token argument needed when the GitHub CLI is signed in or gh-token.txt exists.
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Token = "",
    [string]$Owner = "Erawat4l",
    [string]$Repo = "Agon"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

function Fail($message) {
    Write-Host ""
    Write-Host "[X] $message" -ForegroundColor Red
    exit 1
}

$Version = $Version.TrimStart("v", "V")
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    Fail "Version must look like 1.4.29 (got '$Version')."
}
$tag = "v$Version"

# Same three sources, same order, as publish.bat - with the token cleaned up first, because
# a stray CR or BOM in the file makes GitHub answer 401 with no useful message.
if (-not $Token) {
    if (Test-Path (Join-Path $root "gh-token.txt")) {
        $Token = (Get-Content (Join-Path $root "gh-token.txt") -Raw)
    } elseif ($env:GH_TOKEN) { $Token = $env:GH_TOKEN }
    elseif ($env:GITHUB_TOKEN) { $Token = $env:GITHUB_TOKEN }
}
$Token = ($Token -replace "[\r\n\t ]", "").Trim()
if ($Token -match "^\uFEFF") { $Token = $Token.Substring(1) }
if ($Token.StartsWith("Bearer ")) { $Token = $Token.Substring(7) }
if (-not $Token) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($null -ne $gh) {
        $fromGh = (& $gh.Source auth token 2>$null | Out-String).Trim()
        if ($fromGh) {
            $Token = $fromGh
            Write-Host "Using the credential the GitHub CLI is signed in with."
        }
    }
}
if (-not $Token) {
    Fail "No GitHub token. Run 'gh auth login' first, or pass -Token github_pat_xxx."
}

$asset = Join-Path $root "Agon-$Version.apk"
if (-not (Test-Path $asset)) {
    Fail "No Agon-$Version.apk in $root. Build it first (publish.bat $Version), then retry."
}
$sizeMb = [math]::Round((Get-Item $asset).Length / 1MB, 1)

$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "ArenaPublisher"
}
$api = "https://api.github.com/repos/$Owner/$Repo"

try {
    $release = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $headers -Method Get
} catch {
    Fail "There is no release $tag on $Owner/$Repo to upload to. Publish it first: publish.bat $Version"
}

Write-Host "Release $tag is a draft: $($release.draft)"

foreach ($a in @($release.assets)) {
    if ($a.name -eq "Agon-$Version.apk") {
        Write-Host "Replacing the APK already attached..."
        Invoke-RestMethod -Uri "$api/releases/assets/$($a.id)" -Headers $headers -Method Delete | Out-Null
    }
}

$upload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=Agon-$Version.apk"
$attempt = 0
$uploaded = $false
while (-not $uploaded -and $attempt -lt 4) {
    $attempt++
    try {
        Write-Host "Uploading Agon-$Version.apk ($sizeMb MB), attempt $attempt..."
        Invoke-RestMethod -Uri $upload -Headers $headers -Method Post `
            -ContentType "application/vnd.android.package-archive" -InFile $asset `
            -TimeoutSec 600 | Out-Null
        $uploaded = $true
    } catch {
        if ($attempt -ge 4) { Fail "Upload failed 4 times: $($_.Exception.Message)" }
        Write-Host "  attempt $attempt failed ($($_.Exception.Message)) - retrying in 3s..." -ForegroundColor Yellow
        Start-Sleep -Seconds 3
    }
}

if ($release.draft) {
    Write-Host "Publishing the release..."
    $release = Invoke-RestMethod -Uri "$api/releases/$($release.id)" -Headers $headers `
        -Method Patch -ContentType "application/json" -Body (@{ draft = $false } | ConvertTo-Json)
}

Write-Host ""
Write-Host "  [OK] $($release.html_url)" -ForegroundColor Green
Write-Host "       Agon-$Version.apk ($sizeMb MB) is attached and live."
Write-Host ""
Write-Host "Installed apps see it on the next update check, or right now via"
Write-Host "Update -> Check for updates."
