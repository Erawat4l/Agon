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
    [string]$Token = "",
    [string]$Notes = "",
    [string]$Channel = "stable",
    [string]$Owner = "Erawat4l",
    [string]$Repo = "Agon",
    # Build the APK and the installers and STOP. No release is created, nothing is uploaded,
    # and GitHub is not contacted at all - so nothing can be left behind as a draft.
    [switch]$LocalOnly,
    # Publish even when this version already has every file attached. Without it a repeat
    # run of a finished version stops early instead of rebuilding for several minutes and
    # uploading byte-identical files (see the check before the upload).
    [switch]$Rebuild,
    # Build ONLY the APK and stop. The Windows installers are 140 MB and several minutes of
    # electron-builder for a file that is not being asked for; this is the APK iteration loop.
    [switch]$ApkOnly,
    # Publish even though this checkout does not belong to the repository being
    # published to. Off by default: a fork or a fresh clone must not put a release
    # under someone else's name (see the check further down).
    [switch]$PublishAnyway
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

# ---------------------------------------------------------------- token hygiene
# A token read from a file or an environment variable can carry a trailing CR, an LF, a
# BOM or stray spaces. To cmd.exe that still looks like a token, but to GitHub it is an
# invalid credential, and it answers 401 without saying why. Strip all of it first.
$Token = ($Token -replace "[\r\n\t ]", "").Trim()
if ($Token -match "^\uFEFF") { $Token = $Token.Substring(1) }
if ($Token.StartsWith("Bearer ")) { $Token = $Token.Substring(7) }

# Only complain about the length of a token that was actually supplied.
if ($Token.Length -gt 0 -and $Token.Length -lt 20) {
    Fail "The token is only $($Token.Length) characters long, so it was truncated when it was copied. Copy the whole thing from GitHub."
}

# With no token, borrow the one the GitHub CLI is already signed in with. That is the
# same credential used to rename the repo, so a machine that can run gh can publish,
# and there is no second place for a token to go stale.
if (-not $Token) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($null -ne $gh) {
        try {
            $fromGh = (& $gh.Source auth token 2>$null | Out-String).Trim()
            if ($fromGh) {
                $Token = $fromGh
                Write-Host "Using the credential the GitHub CLI is signed in with." -ForegroundColor Cyan
            }
        } catch { }
    }
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

# ---------------------------------------------------------------- Android SDK
# Gradle needs to be TOLD where the SDK is; it does not inherit build.bat's search. On a
# fresh machine (no local.properties, no ANDROID_HOME) it fails with "SDK location not
# found" after the APK build has already started, which reads like a broken project rather
# than a missing environment variable. Find it here, before the build, and write the same
# local.properties that build.bat would - it is git-ignored, so it is a local file only.
#
# Candidate folders, in the same order build.bat tries them, so the two commands agree.
$sdkCandidates = @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
    (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"),
    (Join-Path $env:ProgramFiles "Android\Android Studio\Sdk")
)
$androidSdk = $null
foreach ($candidate in $sdkCandidates) {
    if ($candidate -and (Test-Path (Join-Path $candidate "platforms"))) {
        $androidSdk = (Resolve-Path $candidate).Path
        break
    }
}
if (-not $androidSdk) {
    Write-Host ""
    Write-Host "[X] The Android SDK was not found, so nothing was built or published." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Install it once (~5 min), all inside cmd:" -ForegroundColor Yellow
    Write-Host "     mkdir %LOCALAPPDATA%\Android\Sdk\cmdline-tools" -ForegroundColor Yellow
    Write-Host "     curl -L -o %TEMP%\cmdtools.zip https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -ForegroundColor Yellow
    Write-Host "     tar -xf %TEMP%\cmdtools.zip -C %LOCALAPPDATA%\Android\Sdk\cmdline-tools" -ForegroundColor Yellow
    Write-Host "     ren %LOCALAPPDATA%\Android\Sdk\cmdline-tools\cmdline-tools latest" -ForegroundColor Yellow
    Write-Host "     (for /l %i in (1,1,30) do @echo y) | %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat --licenses" -ForegroundColor Yellow
    Write-Host "     %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat platform-tools platforms;android-35 build-tools;35.0.0" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  SDK already somewhere else? Tell Windows, then reopen cmd:" -ForegroundColor Yellow
    Write-Host "     setx ANDROID_HOME \"the\folder\holding\platforms\"" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  SETUP.md, Step 3 has the same commands with the full explanation." -ForegroundColor Red
    exit 1
}
# Java properties treats a backslash as an escape sequence, so the SDK path is written with
# forward slashes instead ("sdk.dir=C:/Android/Sdk"). Gradle accepts that form on Windows,
# and it is the one spelling the escaping rules cannot mangle.
$sdkDir = $androidSdk -replace '\\', '/'
Set-Content -Path (Join-Path $root "local.properties") -Value "sdk.dir=$sdkDir" -Encoding ascii
Write-Host "      Android SDK: $androidSdk"

# ---- one signing key shared by the PC and the phone
#
# Android installs an update only over an app signed with the SAME key. Until now a PC
# build was signed with %USERPROFILE%\.android\debug.keystore and a phone build with the
# phone's own - so an APK made here was refused by a phone that had been updated from
# Termux ("App not installed as package conflicts with an existing package"), with no
# way out except uninstalling, which wipes the arena session.
#
# So both build paths now use ONE key file, agon-build.jks, described by the
# git-ignored agon-build.properties:
#
#   - made here if it does not exist yet, and KEPT (unlike the debug key, it is not
#     disposable - losing it means the installed app has to be uninstalled once);
#   - build.py (on the phone or a PC) uses the same file and adopts this one;
#   - its SHA-256 is printed by both builds, so a mismatch is visible before an install
#     is attempted rather than after it is refused.
$sharedJks = Join-Path $root "agon-build.jks"
$sharedProps = Join-Path $root "agon-build.properties"
if (-not (Test-Path $sharedJks) -or -not (Test-Path $sharedProps)) {
    $keytool = Join-Path (Split-Path -Parent $env:JAVA_HOME) "bin\keytool.exe"
    if (-not (Test-Path $keytool)) { $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source }
    if (-not $keytool) {
        Write-Host "      [i] keytool not found - using Gradle's default debug key (phone builds will not install over this APK)." -ForegroundColor Yellow
    } else {
        Write-Host "      creating the shared signing key (agon-build.jks) - one time only"
        & $keytool -genkeypair -keystore $sharedJks -alias agon `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass agonbuild -keypass agonbuild `
            -dname "CN=Agon Build, OU=Mobile, O=Agon, C=IN" | Out-Null
        Set-Content -Path $sharedProps -Encoding ascii -Value @(
            "storeFile=agon-build.jks",
            "storePassword=agonbuild",
            "keyAlias=agon",
            "keyPassword=agonbuild"
        )
    }
}
if ((Test-Path $sharedJks) -and (Get-Command keytool -ErrorAction SilentlyContinue)) {
    $sha = & keytool -list -v -keystore $sharedJks -alias agon -storepass agonbuild 2>$null |
        Select-String -Pattern 'SHA256:' | Select-Object -First 1
    if ($sha) { Write-Host ("      signing key: " + (($sha.Line -split 'SHA256:')[1]).Trim() -replace '\s', '') }

    # A copy of the key the phone can pick up, so the two machines cannot drift apart.
    # It goes to the user's Downloads folder, NOT the repository: a signing key sitting
    # next to the source is one `git add -A` away from being published, and the folder is
    # meant to hold the project, not binary secrets. The phone looks for AGON-KEY.md in
    # its shared Download folder and signs with it.
    $portableDir = Join-Path $env:USERPROFILE "Downloads"
    if (-not (Test-Path $portableDir)) { $portableDir = $env:USERPROFILE }
    $portable = Join-Path $portableDir "AGON-KEY.md"
    try {
        if (-not [string]::Equals(
                (Get-FileHash $sharedJks).Hash, (Get-FileHash $portable -ErrorAction SilentlyContinue).Hash)) {
            Copy-Item $sharedJks $portable -Force
        }
        Write-Host ""
        Write-Host "      Key for the phone: $portable"
        Write-Host "      Copy that file into Download/ on the phone, then run build.bat there."
        Write-Host "      Until it does, an APK from this PC will not install over a phone build."
    } catch {
        Write-Host "      [i] Could not copy the key to $portable - copy agon-build.jks by hand."
    }
}

Write-Host "[1/3] Building..."
# No key flag: app/build.gradle signs with the project's own agon-build.jks for both build
# types, so this APK and one built by hand (or by Android Studio) are interchangeable on the
# phone instead of refusing each other with "package conflicts".
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
Write-Host "      $((Split-Path $asset -Leaf))  ($sizeMb MB)" -ForegroundColor Green
# Show exactly where the bytes are. "Why is the APK 2.1 MB and not 2.4?" is not a
# question anyone should have to answer by guesswork, and the answer is almost always
# the same: the APK holds bytecode, not binaries.
$files = (Get-ChildItem -Path (Join-Path $root "app\src\main\java") -Recurse -Filter *.java |
    Measure-Object).Count
$resFiles = (Get-ChildItem -Path (Join-Path $root "app\src\main\res") -Recurse -File |
    Measure-Object).Count
Write-Host "        $files java files, $resFiles resource files, no native .so (one APK for every phone)"
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($asset)
    $groups = @{}
    foreach ($entry in $zip.Entries) {
        $leaf = $entry.FullName.Split('/')[0]
        if (-not $groups.ContainsKey($leaf)) { $groups[$leaf] = 0 }
        $groups[$leaf] += $entry.Length
    }
    $zip.Dispose()
    $parts = $groups.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 4 |
        ForEach-Object { "{0} {1:N0} KB" -f $_.Key, ($_.Value / 1KB) }
    Write-Host ("        inside: " + ($parts -join ", "))
} catch {
    # A missing compression assembly must never fail a build that already succeeded.
}

if ($ApkOnly) {
    Write-Host ""
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "  [OK] APK built - nothing else was built" -ForegroundColor Green
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  APK: $asset"
    Write-Host ""
    Write-Host "  Installers: not built (that is what 'apk' means)."
    Write-Host "  To build them too:  publish.bat $Version local"
    Write-Host "  To publish this APK to GitHub:  publish.bat $Version"
    exit 0
}

if (-not $Token -and -not $LocalOnly) {
    Fail "No GitHub token. Either run 'gh auth login' first (publish.bat then reuses it), or store one with: publish.bat --token github_pat_xxx"
}
if ($LocalOnly) {
    # Same build a publish would do, then stop before any GitHub call. No release exists, so
    # there is nothing for an interrupted run to leave behind as a draft.
    Write-Host "Local build only: no release is created and nothing is uploaded." -ForegroundColor Cyan
    $desktopDir = Join-Path $root "desktop"
    $npm = Get-Command npm -ErrorAction SilentlyContinue
    if ((Test-Path (Join-Path $desktopDir "package.json")) -and $null -ne $npm) {
        Write-Host "[2/2] Building the Windows client..."
        Push-Location $desktopDir
        try {
            & $npm.Source install --no-audit --no-fund | Out-Host
            & $npm.Source run dist -- "-c.extraMetadata.version=$Version" | Out-Host
            $distOk = ($LASTEXITCODE -eq 0)
        } finally {
            Pop-Location
        }
        if (-not $distOk) { Fail "The Windows build failed. The APK above is still good." }
    } else {
        Write-Host "[2/2] Windows client: skipped (Node.js/npm not found, or desktop/ missing)."
    }
    Write-Host ""
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "  [OK] Built locally - nothing published" -ForegroundColor Green
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  APK      : $asset"
    Write-Host "  Installers: desktop\release\Agon-Setup-64.exe"
    Write-Host "              desktop\release\Agon-Setup-32.exe"
    Write-Host ""
    Write-Host "  Only this PC has them. Nothing was uploaded and no release exists."
    Write-Host "  To publish:  publish.bat $Version"
    exit 0
}

# ---------------------------------------------------------------- whose repository
# The build is anybody's business; the upload is not. A newcomer who clones or forks
# this and runs publish.bat would create a release under their own account that every
# installed copy of Agon treats as the next official version. So: publish only when the
# checkout points at the repository the script uploads to. -LocalOnly and -ApkOnly are
# untouched - they build and stop, and contacting GitHub is not part of them.
$originUrl = ""
try { $originUrl = (& git config --get remote.origin.url 2>$null | Out-String).Trim() } catch { }
if (-not $LocalOnly -and -not $ApkOnly -and -not $PublishAnyway) {
    if ($originUrl -and $originUrl -notmatch 'github\.com[:/]Erawat4l/Agon') {
        Write-Host ""
        Write-Host "[X] This folder is not Erawat4l/Agon - it points at $originUrl" -ForegroundColor Red
        Write-Host "    Publishing would upload a release to THAT repository, which every" -ForegroundColor Red
        Write-Host "    installed copy of Agon would treat as the next official version." -ForegroundColor Red
        Write-Host "    Nothing was uploaded. The APK above is still built and usable." -ForegroundColor Red
        Write-Host ""
        Write-Host "  To build only:  publish.bat $Version local" -ForegroundColor Yellow
        Write-Host "  To publish to your own repository anyway:  publish.bat $Version stable anyway" -ForegroundColor Yellow
        exit 1
    }
}

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
    $detail = ""
    try { $detail = $_.ErrorDetails.Message } catch { }
    Write-Host ""
    Write-Host "GitHub answered 401 Unauthorized for the token itself, so nothing was uploaded." -ForegroundColor Red
    Write-Host ""
    Write-Host "That means the credential is not valid any more (or never was). Usual causes, in order:"
    Write-Host "  1. the token expired or was revoked on GitHub"
    Write-Host "  2. it is a fine-grained token whose 'Expiration' has passed"
    Write-Host "  3. an old copy is sitting in gh-token.txt"
    Write-Host ""
    Write-Host "Fastest fix, using the GitHub CLI you already have signed in:"
    Write-Host "     gh auth login" -ForegroundColor Yellow
    Write-Host "     publish.bat" -ForegroundColor Yellow
    Write-Host "  (with no gh-token.txt, publish.bat now uses gh's own token)"
    Write-Host ""
    Write-Host "Or make a fresh token and store it:"
    Write-Host "     publish.bat --token github_pat_xxx" -ForegroundColor Yellow
    if ($detail) { Write-Host ""; Write-Host "GitHub said: $detail" }
    exit 1
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

# ---------------------------------------------------------------- never go backwards
# releases/latest only ever offers a build whose versionCode is higher than the one already
# installed, and the in-app updater compares exactly that number. Publishing a LOWER version
# than one that already exists (a stale version.txt once produced 1.0.2 on a project that was
# at 1.4.29) does not downgrade anybody - it is simply invisible for ever, and the phone keeps
# telling the user there is nothing new. Refuse it here instead.
try {
    $allReleases = Invoke-RestMethod -Uri "$api/releases?per_page=100" -Headers $headers -Method Get
} catch {
    $allReleases = @()
}
$highestCode = 0
$highestTag = ""
foreach ($rel in @($allReleases)) {
    $t = ($rel.tag_name -replace "^[vV]", "")
    if ($t -match '^(\d+)\.(\d+)\.(\d+)$') {
        $c = [int]$Matches[1] * 1000000 + [int]$Matches[2] * 1000 + [int]$Matches[3]
        if ($c -gt $highestCode) { $highestCode = $c; $highestTag = $rel.tag_name }
    }
}
if ($code -lt $highestCode) {
    Write-Host ""
    Write-Host "This would publish $tag (versionCode $code), but $highestTag (versionCode $highestCode)" -ForegroundColor Red
    Write-Host "is already published. A lower version is never offered to an installed app, so" -ForegroundColor Red
    Write-Host "this release would be invisible and the update check would keep saying" -ForegroundColor Red
    Write-Host "'you are up to date'." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Publish the next version instead:" -ForegroundColor Yellow
    Write-Host "     publish.bat            (auto-bumps to the next number)" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  A stale version.txt is the usual cause - delete it, or let the auto-bump overwrite it." -ForegroundColor Yellow
    exit 1
}

$sha = (& git rev-parse HEAD).Trim()
if (-not $Notes) {
    $Notes = "Agon $Version`n`nAndroid: Agon-$Version.apk - download it, tap it and approve Android's update prompt. One file for 32- and 64-bit phones.`n`nWindows: Agon-Setup-64.exe for 64-bit Windows, Agon-Setup-32.exe for 32-bit. They are two files because Electron compiles the app per architecture; one .exe cannot hold both.`n`nInstalled copies detect this release automatically."
}

# The size line only claims what was actually built: on an APK-only run there is no
# installer to mention, and saying otherwise is how a release title once promised Windows
# files that were never uploaded.
$Notes = "$Notes`n`nAPK: Agon-$Version.apk ($sizeMb MB)"
if ($ApkOnly) {
    $Notes = "$Notes`nWindows installers: built with the next full publish (publish.bat without the apk mode)."
}

Write-Host "[2/3] Creating release $tag on $($sha.Substring(0,7))..."

$existing = $null
try {
    $existing = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $headers -Method Get
} catch {
    # 404 simply means "no release for this tag yet"
}

# ONE release, two platforms, named for what it actually is.
#
# This used to be called "Agon <version>" even when the Windows installers failed to build
# and only the APK was attached - so releases/latest (which is what phones read) offered a
# title with no Windows files under it. The installers are uploaded after this line, so the
# title is decided by whether this run is going to build them: -ApkOnly never does, and a
# machine without Node.js cannot.
$willBuildDesktop = (-not $ApkOnly) -and
    (Test-Path (Join-Path $root "desktop\package.json")) -and
    ($null -ne (Get-Command npm -ErrorAction SilentlyContinue))

# A brand-new release starts as a draft; one that already exists keeps whatever state it
# has (see the comment on the field below).
$wasDraft = $(if ($existing) { [bool]$existing.draft } else { $true })

$body = @{
    tag_name         = $tag
    target_commitish = $sha
    name             = $(if ($willBuildDesktop) { "Agon " + $Version + " - Android APK + Windows installer" }
                         else { "Agon " + $Version + " - Android APK" })
    body             = $Notes
    # An existing release keeps its state; a new one starts as a draft. Two reasons.
    #
    # `releases/latest` ignores drafts, and the in-app updater reads that endpoint - so a
    # release that fails halfway through can no longer become the "newest" build with no
    # file in it. That is why a new one is created as a draft and published at the very end.
    #
    # But re-running for a version that is ALREADY published (the usual case: v1.4.57 was
    # published APK-only and now needs the installers) must not un-publish it on the way
    # in. Forcing draft=true here took the live release away from every installed app for
    # the several minutes the Windows build takes, and left it a draft whenever that build
    # failed. Only the release at the very end of this script changes draft state now.
    draft            = $wasDraft
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

# The upload host is not api.github.com, and on Windows PowerShell 5.1 a POST of a few
# megabytes to it fails intermittently with "the underlying connection was closed: an
# unexpected error occurred on a send". That is a dropped TLS connection mid-upload, not a
# rejected token - the release and the build are fine. Retry before giving up.
$upload = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=Agon-$Version.apk"
$attempt = 0
$uploaded = $false
while (-not $uploaded -and $attempt -lt 4) {
    $attempt++
    try {
        Invoke-RestMethod -Uri $upload -Headers $headers -Method Post `
            -ContentType "application/vnd.android.package-archive" -InFile $asset `
            -TimeoutSec 600 | Out-Null
        $uploaded = $true
    } catch {
        $reason = $_.Exception.Message
        if ($attempt -ge 4) {
            Write-Host ""
            Write-Host "[X] Uploading the APK failed 4 times. The release $tag was left as a DRAFT," -ForegroundColor Red
            Write-Host "    so installed apps still see the previous version and nothing broke." -ForegroundColor Red
            Write-Host "    Last error: $reason" -ForegroundColor Red
            Write-Host ""
            Write-Host "  Retry, reusing the same draft instead of building again:" -ForegroundColor Yellow
            Write-Host "     powershell -NoProfile -ExecutionPolicy Bypass -File tools\upload-apk.ps1 $Version" -ForegroundColor Yellow
            exit 1
        }
        Write-Host "      attempt $attempt failed ($reason) - retrying in 3s..." -ForegroundColor Yellow
        Start-Sleep -Seconds 3
    }
}

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
        # The version is passed to electron-builder instead of being written into
        # package.json. Writing it there modified a tracked file on every run, which made
        # `git pull` refuse to merge ("Your local changes to desktop/package.json would be
        # overwritten"), and it was what put the UTF-8 BOM into the file in the first place.
        # A clean working tree means a pull always works.
        & $npm.Source install --no-audit --no-fund | Out-Host
        # Force "never publish" on the command line as well as in the npm script: with a
        # publish config in package.json but no GH_TOKEN in the environment, electron-builder
        # finishes building both installers and then fails with
        #   "⨯ GitHub Personal Access Token is not set ... using env \"GH_TOKEN\""
        # The upload is this script's job, not electron-builder's.
        # ONE --publish flag only. `npm run dist` already expands to
        # `electron-builder --publish never`, so adding another one produced
        #   --publish never --publish never
        # and yargs turned that into the ARRAY ["never","never"], which is not the string
        # "never" - so electron-builder considered publishing enabled, built everything and
        # then died with "GitHub Personal Access Token is not set". The npm script carries
        # the flag; this adds only the version.
        & $npm.Source run dist -- "-c.extraMetadata.version=$Version" | Out-Host
        $distOk = ($LASTEXITCODE -eq 0)
    } finally {
        Pop-Location
    }
    if (-not $distOk) {
        # A release with the APK but no installer is worse than no release: phones are told
        # about a version Windows users cannot download, and the desktop updater finds a
        # latest.yml that is not there. This used to publish anyway, which is exactly how
        # 1.4.34 - 1.4.40 all shipped as APK-only.
        Write-Host ""
        Write-Host "[X] The Windows build failed, so nothing was published." -ForegroundColor Red
        Write-Host "    The release $tag was left as a DRAFT (invisible to installed apps)." -ForegroundColor Red
        Write-Host "    The APK is built and sitting at: $asset" -ForegroundColor Red
        Write-Host ""
        Write-Host "    Fix the error above, then run publish.bat again - it reuses the same" -ForegroundColor Yellow
        Write-Host "    draft instead of creating another version." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "    To publish the APK on its own anyway:" -ForegroundColor Yellow
        Write-Host "      powershell -NoProfile -ExecutionPolicy Bypass -File tools\upload-apk.ps1 $Version" -ForegroundColor Yellow
        exit 1
    } else {
        $releaseDir = Join-Path $desktopDir "release"
        # Exactly two executables, the way they are published: Agon-Setup-64.exe and
        # Agon-Setup-32.exe, plus the latest.yml that desktop/scripts/make-release-assets.js
        # rewrote to describe the 64-bit one. The arch-suffixed originals from
        # electron-builder are deliberately NOT uploaded - the same installer under two names
        # doubles the download for everybody.
        $wanted = @("Agon-Setup-64.exe", "Agon-Setup-32.exe", "latest.yml")
        $desktopAssets = @()
        if (Test-Path $releaseDir) {
            $desktopAssets = Get-ChildItem $releaseDir -File |
                Where-Object { $wanted -contains $_.Name }
        }
        $yml = $desktopAssets | Where-Object { $_.Name -eq "latest.yml" }
        if ($null -eq $yml) {
            Write-Host "      [i] No latest.yml in desktop/release; skipping the Windows upload." -ForegroundColor Yellow
        } elseif ($desktopAssets.Count -lt 3) {
            Write-Host "      [i] Windows build incomplete (need Agon-Setup-64.exe and -32.exe); skipping the Windows upload." -ForegroundColor Yellow
        } else {
            foreach ($file in $desktopAssets) {
                # Same rule as the APK above: re-running for the same version replaces that
                # version's file instead of failing on GitHub's 422 "already_exists". Without
                # this, the second run of a version that already had an installer stopped
                # dead at the upload.
                $clash = @($release.assets) | Where-Object { $_.name -eq $file.Name }
                foreach ($old in $clash) {
                    Write-Host "      replacing the existing $($file.Name)"
                    Invoke-RestMethod -Uri "$api/releases/assets/$($old.id)" -Headers $headers `
                        -Method Delete | Out-Null
                }
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

# ---------------------------------------------------------------- already done?
# Re-running a version that is already published is a normal thing to do: after an APK-only
# publish you come back with `publish.bat 1.4.57` to add the installers, and after that
# there is nothing left to add. Before spending several minutes on a full rebuild, check
# what the release actually holds.
#
#   the APK      - a byte-for-byte match on GitHub's own sha256 (or, on an older release
#                  without one, the same file size)
#   the installers - latest.yml on the release names this exact version
#
# If both match, this run would upload exactly what is there already, so it says so and
# stops. `publish.bat <ver> --rebuild` (or -Rebuild) forces the full run.
$alreadyComplete = $false
if (-not $ApkOnly -and -not $Rebuild -and $release) {
    $remoteApk = @($release.assets) | Where-Object { $_.name -eq "Agon-$Version.apk" }
    $remoteYml = @($release.assets) | Where-Object { $_.name -eq "latest.yml" }
    if ($remoteApk -and $remoteYml) {
        $apkSame = $false
        if ($remoteApk.digest) {
            $apkSame = ("$($remoteApk.digest)" -eq "sha256:" + (Get-FileHash $asset -Algorithm SHA256).Hash.ToLower())
        } else {
            $apkSame = ([int64]$remoteApk.size -eq [int64](Get-Item $asset).Length)
        }
        $ymlVersion = ""
        try {
            $ymlText = Invoke-RestMethod -Uri $remoteYml.url -Headers @{
                Authorization = "Bearer $Token"
                Accept        = "application/octet-stream"
                "User-Agent"  = "ArenaPublisher"
            } -Method Get
            if ($ymlText -match "(?m)^version:\s*'?([0-9][^'\s]*)") { $ymlVersion = $Matches[1] }
        } catch {
            $ymlVersion = ""
        }
        if ($apkSame -and $ymlVersion -eq $Version) {
            $alreadyComplete = $true
        }
    }
}

if ($alreadyComplete) {
    Write-Host ""
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "  [OK] $tag is already published and complete" -ForegroundColor Green
    Write-Host "  $($release.html_url)" -ForegroundColor Green
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  The APK on the release is this exact build, and latest.yml already"
    Write-Host "  describes $Version, so the Windows installers are there and current."
    Write-Host "  Nothing was rebuilt and nothing was uploaded."
    Write-Host ""
    Write-Host "  To build and upload again anyway:  publish.bat $Version --rebuild" -ForegroundColor Yellow
    if ($wasDraft) {
        Write-Host ""
        Write-Host "  The release is still a DRAFT - publishing it now." -ForegroundColor Yellow
        try {
            $release = Invoke-RestMethod -Uri "$api/releases/$($release.id)" -Headers $headers `
                -Method Patch -ContentType "application/json" `
                -Body (@{ draft = $false; prerelease = ($Channel -eq "beta") } | ConvertTo-Json)
            Write-Host "  Published: $($release.html_url)" -ForegroundColor Green
        } catch {
            Fail "The release could not be published: $($_.Exception.Message)"
        }
    }
    exit 0
}

# Nothing above this line is visible to an installed app: the release is still a draft and
# releases/latest ignores drafts. Only now that the APK (and the Windows files, when they
# built) are attached does it go live.
Write-Host "[3c] Publishing $tag..."
try {
    $release = Invoke-RestMethod -Uri "$api/releases/$($release.id)" -Headers $headers `
        -Method Patch -ContentType "application/json" `
        -Body (@{ draft = $false; prerelease = ($Channel -eq "beta") } | ConvertTo-Json)
} catch {
    Fail "The APK uploaded but the release could not be published: $($_.Exception.Message). It is still a draft on GitHub - open the Releases page and press Edit -> Publish."
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
