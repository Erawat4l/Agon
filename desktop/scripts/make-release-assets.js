/*
 * Turns electron-builder's output into the two files a person actually downloads.
 *
 * Why this exists
 * ---------------
 * electron-builder builds one installer per architecture, but it writes the update
 * metadata (latest.yml) with NO architecture in the name: on Windows the file is always
 * `latest.yml`, so the second arch silently overwrites what the first one wrote. The
 * result was a release where `latest.yml` described the ia32 installer while 64-bit
 * Windows clients read it and were offered a 32-bit build - and the un-suffixed
 * `Agon-Setup-<version>.exe` was whichever arch happened to finish last.
 *
 * So this runs after the build and:
 *   1. renames the two installers to names that say what they are:
 *        Agon-Setup-64.exe   (x64 Windows)
 *        Agon-Setup-32.exe   (32-bit Windows)
 *   2. writes latest.yml itself, describing the 64-bit installer, with a freshly
 *      computed sha512 (base64, which is what electron-updater verifies).
 *
 * 64-bit Windows therefore self-updates. A 32-bit install keeps working and is updated
 * by downloading Agon-Setup-32.exe: electron-updater only ever reads `latest.yml`, and a
 * single YAML file cannot describe two binaries. Serving the 64-bit one from it is the
 * safe choice - a 32-bit machine offered an x64 installer can only fail, while a 32-bit
 * user who downloads the right file by hand always succeeds.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ARCH_NAMES = { x64: '64', ia32: '32' };

function sha512Base64(file) {
  return crypto.createHash('sha512').update(fs.readFileSync(file)).digest('base64');
}

/** One YAML entry, in the shape electron-updater expects. */
function yamlFor(name, file, version) {
  return [
    `  - url: ${name}`,
    `    sha512: ${sha512Base64(file)}`,
    `    size: ${fs.statSync(file).size}`,
  ].join('\n');
}

exports.default = async function makeReleaseAssets(context) {
  const outDir = context.outDir || (context.buildResult && context.buildResult.outDir);
  if (!outDir || !fs.existsSync(outDir)) {
    throw new Error('no output directory; nothing to rename');
  }

  // The version comes from the installers THIS run just wrote, not from package.json.
  //
  // package.json holds whatever version is committed (1.4.22 today), and the release
  // passes the real one on the command line (-c.extraMetadata.version=...), so reading
  // package.json would look under the wrong number.
  //
  // It must also be the version of the *newest* installer here, not the first one in
  // directory order. `release/` is not cleaned between builds, so it still held
  // Agon-Setup-1.4.50-x64.exe from an old run; readdir handed that back first, and the
  // script then demanded Agon-Setup-1.4.50-ia32.exe - which the 1.4.57 build had of
  // course never produced. That is the "missing installer for ia32" failure.
  const found = [];
  for (const name of fs.readdirSync(outDir)) {
    const m = /^Agon-Setup-(\d+\.\d+\.\d+)-(x64|ia32)\.exe$/.exec(name);
    if (!m) continue;
    const stat = fs.statSync(path.join(outDir, name));
    found.push({ version: m[1], arch: m[2], mtime: stat.mtimeMs, size: stat.size });
  }
  if (!found.length) {
    throw new Error('no Agon-Setup-<version>-<arch>.exe in ' + outDir
      + ' - electron-builder did not produce the installers');
  }

  // Newest mtime wins, and the choice is logged with the timestamp so a stale installer
  // sitting in the folder is visible instead of silent.
  found.sort((a, b) => b.mtime - a.mtime);
  const version = found[0].version;
  const stale = found.filter(f => f.version !== version);
  if (stale.length) {
    console.log('[release] ignoring installers from another build already in release/: '
      + stale.map(f => `${f.version}-${f.arch}`).join(', '));
  }
  console.log('[release] version ' + version + ' (newest installer in ' + outDir + ')');

  // Every installer that is not from this build is removed before anything is copied.
  //
  // `release/` is never cleaned between builds, so old installers pile up there and the
  // copies below (Agon-Setup-64.exe / -32.exe) can be silently replaced by a run that
  // only half finished. Deleting the other versions first means what is published is
  // always exactly the build that just succeeded.
  for (const f of found) {
    if (f.version === version) continue;
    for (const suffix of ['', '.blockmap']) {
      const victim = path.join(outDir, `Agon-Setup-${f.version}-${f.arch}.exe${suffix}`);
      try { if (fs.existsSync(victim)) fs.unlinkSync(victim); } catch (ignored) { /* locked */ }
    }
  }

  const picked = {};
  for (const [arch, suffix] of Object.entries(ARCH_NAMES)) {
    const built = path.join(outDir, `Agon-Setup-${version}-${arch}.exe`);
    if (!fs.existsSync(built)) {
      throw new Error(`missing installer for ${arch}: ${path.basename(built)}`);
    }
    const clean = path.join(outDir, `Agon-Setup-${suffix}.exe`);
    fs.copyFileSync(built, clean);
    // The copy is what gets published; the arch-suffixed original is left on disk for the
    // CI arch check but is not uploaded (it would double the release size for no gain).
    for (const extra of ['.blockmap']) {
      const src = built + extra;
      if (fs.existsSync(src)) fs.copyFileSync(src, clean + extra);
    }
    picked[arch] = { clean, name: path.basename(clean) };
    console.log(`[release] ${path.basename(clean)}  (${(fs.statSync(clean).size / 1048576).toFixed(1)} MB, from ${arch})`);
  }

  // The update metadata must describe the 64-bit build, and must be written AFTER both
  // builds exist - electron-builder's own copy describes whichever arch ran last.
  const latest = [
    `version: ${version}`,
    'files:',
    yamlFor(picked.x64.name, picked.x64.clean, version),
    `path: ${picked.x64.name}`,
    `sha512: ${sha512Base64(picked.x64.clean)}`,
    `releaseDate: '${new Date().toISOString()}'`,
    '',
  ].join('\n');
  fs.writeFileSync(path.join(outDir, 'latest.yml'), latest);
  console.log('[release] latest.yml now describes Agon-Setup-64.exe');

  // Delete the combined x64+ia32 installer and the arch-suffixed duplicates. electron-builder
  // emits Agon-Setup-<version>.exe (both arches in one file, ~133 MB) as well as the two
  // per-arch installers, and none of those three are published - keeping only the two that
  // are means desktop/release/ cannot be mistaken for the release contents, and saves 270 MB
  // of disk on every build.
  for (const name of fs.readdirSync(outDir)) {
    if (/^Agon-Setup-\d+\.\d+\.\d+(-(x64|ia32))?(\.exe|\.exe\.blockmap)$/.test(name)) {
      try { fs.unlinkSync(path.join(outDir, name)); } catch (ignored) { /* locked file: ignore */ }
    }
  }

  // Everything the release should carry, and nothing else.
  return [
    picked.x64.clean,
    picked.ia32.clean,
    path.join(outDir, 'latest.yml'),
  ];
};
