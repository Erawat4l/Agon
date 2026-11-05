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
  check('a version below the published one is refused, and says why',
    refused && /lower than/.test(output) && /never offered/.test(output), output);
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

console.log(`\n${bad === 0 ? 'all build-script tests passed' : bad + ' FAILED'}`);
process.exit(bad === 0 ? 0 : 1);
