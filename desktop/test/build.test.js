/**
 * Tests for the one build script: build.py + tools/needs.py.
 *
 * The point of these is the thing that kept breaking on a real phone - the toolchain is
 * detected, installed and then re-detected correctly, and the container is handed a
 * script that actually runs. Everything here is offline: the network paths are checked by
 * their rendered text, never by calling them.
 *
 *   cd desktop && node test/build.test.js
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..', '..');
const PY = process.env.PYTHON || 'python3';

let bad = 0;
function check(name, ok, detail) {
  if (!ok) bad++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}` + (ok || !detail ? '' : `\n      ${detail}`));
}

function py(args, env = {}) {
  return execFileSync(PY, args, {
    cwd: ROOT,
    env: { ...process.env, ...env },
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

/**
 * Same call, but a non-zero exit is an ANSWER here, not a crash: `needs.py check` exits 1
 * when something is missing, which is exactly what these tests want to read.
 */
function pyOut(args, env = {}) {
  try {
    return py(args, env);
  } catch (err) {
    return String(err.stdout || '') + String(err.stderr || '');
  }
}

// --- the two files exist and compile ----------------------------------------------------
for (const file of ['build.py', 'tools/needs.py']) {
  let ok = true;
  let detail = '';
  try {
    py(['-c', `import ast,sys; ast.parse(open(${JSON.stringify(file)}).read())`]);
  } catch (err) {
    ok = false;
    detail = String(err.stderr || err.message).trim();
  }
  check(`${file} parses`, ok, detail);
}

// --- host detection --------------------------------------------------------------------
{
  const win = pyOut(['tools/needs.py', 'check'], { AGON_HOST: 'windows' });
  const term = pyOut(['tools/needs.py', 'check'], { AGON_HOST: 'termux' });
  check('a Windows host is asked for the JDK and the SDK',
    /JDK 17/.test(win) && /Android SDK/.test(win) && /winget/.test(win), win);
  check('a Termux host is asked for the container and arm64 tools, not winget',
    /proot-distro/.test(term) && /arm64 build-tools/.test(term) && !/winget/.test(term),
    term);
  check('the SDK package list is the one the project compiles against',
    /platforms;android-35/.test(win) && /build-tools;35\.0\.0/.test(win));
}

// --- nothing inside the container script is Termux-escaped any more ----------------------
{
  const script = py(['-c',
    'import sys; sys.path.insert(0,"."); import build; ' +
    'print(build.termux_inner())']);
  // The bug that made the phone build die at "gradlew: command not found": the body was
  // wrapped in bash -lc " ... ", so the container's first line was `set -e` and nothing
  // else ran. The rendered script must therefore be plain shell with no escaped quotes.
  check('the container script has no backslash-escaped quotes',
    !/\\"/.test(script), script.slice(0, 200));
  check('it starts with set -e and installs the toolchain',
    /^set -e/.test(script.trim()) && /apt-get install/.test(script));
  check('the version reaches it as a variable, not as injected text',
    /-PVERSION_NAME="\$AGON_VERSION"/.test(script));
  check('it ends with the Gradle build and the APK path',
    /\.\/gradlew .*assembleDebug/.test(script) && /app-debug\.apk/.test(script));
  check('the arm64 aapt2 override is written where Gradle reads it',
    /aapt2FromMavenOverride=\$TOOLS\/aapt2/.test(script));

  // ...and it has to be valid shell. bash -n is the cheapest proof of that.
  const tmp = path.join(os.tmpdir(), `agon-inner-${process.pid}.sh`);
  fs.writeFileSync(tmp, script);
  let shellOk = true;
  let detail = '';
  try {
    execFileSync('bash', ['-n', tmp], { stdio: 'pipe' });
  } catch (err) {
    shellOk = false;
    detail = String(err.stderr || err.message);
  }
  fs.unlinkSync(tmp);
  check('the container script is valid shell (bash -n)', shellOk, detail);
}

// --- the handover to the container ---------------------------------------------------------
{
  // Termux's own /tmp is read-only on Android 10+ ("Permission denied" on a real phone),
  // so the handover file has to be written inside the repository, not in /tmp.
  const source = fs.readFileSync(path.join(ROOT, 'build.py'), 'utf8');
  check('build.py does not write the container script into /tmp',
    !/open\(\s*["']\/tmp/.test(source), 'a /tmp write is still there');
  check('it writes it next to build.py instead',
    /\.agon-inner\.sh/.test(source));

  const script = py(['-c',
    'import sys; sys.path.insert(0,"."); import build; print(build.termux_inner())']);
  const keyAt = script.indexOf("echo '  ... signing key'");
  const stopAt = script.indexOf('AGON_TOOLS_ONLY');
  check('the tools-only stop comes after the source and the signing key',
    keyAt > 0 && stopAt > keyAt,
    `key@${keyAt} stop@${stopAt}`);
  check('the signing key is made with a keytool path, not a bare name',
    /KEYTOOL=\S+/.test(script) && /"\$KEYTOOL" -genkeypair/.test(script));
}

// --- the signing key ------------------------------------------------------------------------
{
  const script = py(['-c',
    'import sys; sys.path.insert(0,"."); import build; print(build.termux_inner())']);
  // A phone can only be handed a file in its shared storage, and the Windows build leaves
  // its copy there as AGON-KEY.md - so the phone has to look for that name too.
  check('the phone accepts the key under all three names',
    /agon-build\.jks arena-release\.jks AGON-KEY\.md/.test(script), script.slice(0, 120));

  const source = fs.readFileSync(path.join(ROOT, 'build.py'), 'utf8');
  check('a Windows build copies the key to Downloads, not into the repository',
    /Downloads/.test(source) && /AGON-KEY\.md/.test(source));
  check('a Windows build prints the key fingerprint',
    /def fingerprint/.test(source) && /SHA256/.test(source));

  const trust = fs.readFileSync(path.join(ROOT, '.gitignore'), 'utf8');
  for (const name of ['agon-build.jks', 'agon-build.properties', 'AGON-KEY.md',
    'keystore.properties', '*.jks']) {
    if (/\*\*\.jks/.test(trust)) continue;
    check(`${name} can never be committed`, trust.includes(name), trust);
  }
}

// --- the one signing command ----------------------------------------------------------------
{
  const source = fs.readFileSync(path.join(ROOT, 'build.py'), 'utf8');
  check('there is a "sign" mode', /mode == "sign"/.test(source) && /sign_repair/.test(source));
  check('it adopts a key the user already has instead of replacing it',
    /def find_existing_keys/.test(source) && /Adopted/.test(source));
  check('it probes every password this project has ever used',
    /KNOWN_PASSWORDS/.test(source) && /agonphone/.test(source) && /agonbuild/.test(source));
  check('it proves the alias before writing properties',
    /-alias", found/.test(source));
  check('it prints a fingerprint in both the repair and the build',
    (source.match(/key_sha\(/g) || []).length >= 3 && /def fingerprint/.test(source));
  check('a missing keytool offers to install the JDK on Windows',
    /Temurin\.17\.JDK/.test(source) && /No keytool yet/.test(source));

  // The command has to be usable by a person who is on the PC for one day: one line,
  // and it must not need arguments or a working directory.
  const bat = fs.readFileSync(path.join(ROOT, 'build.bat'), 'utf8');
  check('build.bat sign is documented in the launcher',
    /build\.bat sign/.test(bat), bat.slice(0, 260));
}

// --- the two ways a person loses ten minutes on Windows ------------------------------
{
  const source = fs.readFileSync(path.join(ROOT, 'build.py'), 'utf8');
  // A version on its own is a valid argument set: `build.bat 1.4.58` has to build that
  // version, not silently do nothing while an unknown argument is ignored.
  const out = pyOut(['build.py', '--dry-run', '1.4.99']);
  check('a bare version on its own is a version, not an unknown word',
    !/Not sure what to do/.test(out), out.slice(0, 300));

  const junk = pyOut(['build.py', '--dry-run', 'frobnicate']);
  check('an unknown word is refused with the list of modes',
    /Not sure what to do/.test(junk) && /doctor/.test(junk), junk.slice(0, 300));

  // ...and the launcher names itself, so "which command do I type" has one answer.
  check('the banner says how to invoke it on each machine',
    /build\.bat <mode>/.test(source) && /build\.sh <mode>/.test(source));

  // The trap the user hit: a shell that cannot run .bat, from the wrong directory.
  const setup = fs.readFileSync(path.join(ROOT, 'SETUP.md'), 'utf8');
  check('SETUP.md warns about the fish shell', /fish shell/.test(setup) && /Unknown command/.test(setup));
  check('SETUP.md says the command must be run inside the project folder',
    /cd \/d "%USERPROFILE%\\Desktop\\Erawat\\Agon"/.test(setup));
}

// --- the version policy ------------------------------------------------------------------
{
  // A build numbered below what is published is invisible for ever, so it is refused
  // instead of being built for ten minutes and then ignored by every phone.
  const version = pyOut(['tools/needs.py', 'version']).trim();
  check('the next version matches a.b.c', /^\d+\.\d+\.\d+$/.test(version), version);

  let refused = false;
  let output = '';
  try {
    output = py(['build.py', 'apk', '1.0.0', '--dry-run'], { AGON_HOST: 'termux' });
  } catch (err) {
    refused = true;
    output = String(err.stdout || '') + String(err.stderr || '');
  }
  // a version with nothing above it must NOT be refused
  const allowed = pyOut(['build.py', 'apk', '9.9.9', '--dry-run'], { AGON_HOST: 'termux' });
  check('a sane version is accepted', /version: 9\.9\.9/.test(allowed), allowed.slice(-200));
  // A LOW number is a warning, not a refusal: the APK still installs over the app by hand,
  // and refusing the build turned a stale version.txt into a dead end. What must not happen
  // is publishing it silently.
  check('a version below the published one still builds, and says what it means',
    !refused && /lower than/.test(output) && /install this APK/.test(output),
    output.slice(-400));
  // `build.bat` with nothing on the command line takes the next free version, so a stale
  // version.txt can never make it build (or refuse) an old number.
  const plain = pyOut(['build.py', 'apk', '--dry-run'], { AGON_HOST: 'termux' });
  check('with no version typed, the next free one is taken',
    /version: \d+\.\d+\.\d+/.test(plain) && !/lower than/.test(plain),
    plain.slice(-300));
}

// --- the launchers ----------------------------------------------------------------------
{
  const bat = fs.readFileSync(path.join(ROOT, 'build.bat'), 'utf8');
  const sh = fs.readFileSync(path.join(ROOT, 'build.sh'), 'utf8');
  check('build.bat hands over to build.py', /build\.py/.test(bat) && /%PY%/.test(bat));
  check('build.bat does not trust a bare "where python"',
    /py -3 -c "import sys"/.test(bat) && /python -c "import sys"/.test(bat));
  check('build.bat is a CRLF file (cmd.exe needs it)',
    !/[^\r]\n/.test(bat), 'a bare LF was found');
  check('build.sh hands over to build.py and keeps the exit code',
    /exec python3 build\.py/.test(sh));
}

// --- the cleanup: one entry point instead of a pile of scripts ---------------------------
{
  const gone = ['build-all.bat', 'clean-all.bat', 'commit.bat', 'make-keystore.bat',
    'termux-build.sh', 'tools/collect-artifacts.py', 'tools/make-keystore.sh'];
  const still = gone.filter(name => fs.existsSync(path.join(ROOT, name)));
  check('the superseded build scripts are gone', still.length === 0, still.join(', '));
  const kept = ['publish.bat', 'tools/publish.ps1', 'tools/upload-apk.ps1',
    'tools/check-resources.py', 'tools/check-secrets.py', 'tools/check-apk-abis.py',
    'tools/needs.py', 'build.py', 'build.bat', 'build.sh'];
  const missing = kept.filter(name => !fs.existsSync(path.join(ROOT, name)));
  check('everything still needed is still there', missing.length === 0, missing.join(', '));
  // and nothing points at a file that no longer exists
  const docs = ['README.md', 'SETUP.md', 'desktop/README.md', 'NOTICE.md'];
  const refs = [];
  for (const doc of docs) {
    const text = fs.readFileSync(path.join(ROOT, doc), 'utf8');
    for (const name of gone) {
      if (text.includes(name)) refs.push(`${doc} -> ${name}`);
    }
  }
  check('the docs no longer mention a script that was removed',
    refs.length === 0, refs.join(', '));
}

// --- package identity: a build from anywhere must install over the app on the phone ------
//
// "If i build with android then would there be a package problem?" The two things that make
// an APK refuse another are the *package name* and the *signing key*. The name is fixed, so
// only the key can drift - and it did: the shared key was gated behind -PAGON_DEBUG_KEY=1,
// which build.py passes but a hand-typed `gradlew assembleDebug`, Android Studio and a
// plain `./gradlew installDebug` do not. Those builds were signed with that machine's
// ~/.android/debug.keystore and could never be updated by a release.
{
  const gradle = fs.readFileSync(path.join(ROOT, 'app', 'build.gradle'), 'utf8');
  check('the debug build is signed with the project key, with no opt-in flag',
    !/hasProperty\('AGON_DEBUG_KEY'\)/.test(gradle) &&
    /signingConfig signingConfigs\.shared/.test(gradle),
    (gradle.match(/AGON_DEBUG_KEY[^\n]*/g) || []).join(' | '));
  check('the release build prefers the same key (never a different one)',
    /release \{[\s\S]*agon-build\.properties[\s\S]*signingConfig signingConfigs\.shared/
      .test(gradle));
  check('a properties file naming a missing key fails the build instead of falling back',
    /is not in /.test(gradle) && /throw new GradleException/.test(gradle));
  check('the package name has no debug suffix, so both build types are one app',
    /applicationId 'app\.erawat\.agon'/.test(gradle) && !/applicationIdSuffix/.test(gradle));
  // The only mention left may be prose: a comment is not a flag being passed.
  const passes = (src) => src.split('\n')
    .filter((line) => !/^\s*(\/\/|#)/.test(line))
    .filter((line) => /AGON_DEBUG_KEY=1/.test(line));
  check('no build path still passes the removed flag',
    passes(gradle).length === 0 &&
    passes(fs.readFileSync(path.join(ROOT, 'build.py'), 'utf8')).length === 0 &&
    passes(fs.readFileSync(path.join(ROOT, 'tools', 'publish.ps1'), 'utf8')).length === 0,
    [...passes(gradle), ...passes(fs.readFileSync(path.join(ROOT, 'build.py'), 'utf8'))]
      .join(' | '));
  check('a local phone build does not take the name of the published asset',
    /PUBLISHING/.test(fs.readFileSync(path.join(ROOT, 'build.py'), 'utf8')));
}

// --- the command in the docs has to work when it is pasted -----------------------------
//
// "build.bat -> 'build.bat' is not recognized" happened because the documented command
// started with build.bat while the shell was still one folder up: build.bat changes into
// the project folder itself, which cannot help cmd find the file it was asked to run. The
// docs now show the cd on its own line, and a test that pastes the block must find the cd
// first and the build second.
{
  const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');
  // The block that carries the cd is the one with "publish" in it; an older snippet higher
  // up the file still shows a bare `build.bat` as shorthand.
  const blocks = [...readme.matchAll(/```bat\n([\s\S]*?)```/g)].map((m) => m[1]);
  const real = blocks.find((b) => /publish/.test(b));
  check('README has a paste-able Windows block', !!real);
  if (real) {
    const lines = real.split('\n').filter((l) => !/^\s*(::|#)/.test(l) && l.trim());
    check('it starts by entering the project folder', /^cd \/d /.test(lines[0].trim()),
      lines[0]);
    check('and only then runs the build', /^build\.bat\b/.test(lines[1].trim()), lines[1]);
  }
  const setup = fs.readFileSync(path.join(ROOT, 'SETUP.md'), 'utf8');
  check('SETUP explains why "not recognized" happens',
    /not recognized/.test(setup) && /fish/.test(setup) && /project folder/.test(setup));
  check('build.bat changes into its own folder after it has started',
    /cd \/d "%~dp0"/.test(fs.readFileSync(path.join(ROOT, 'build.bat'), 'utf8')));
  check('build.sh does the same, so it works from any directory',
    /cd "\$\(dirname "\$0"\)"/.test(fs.readFileSync(path.join(ROOT, 'build.sh'), 'utf8')));
}

console.log(`\n${bad === 0 ? 'all build-script tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
