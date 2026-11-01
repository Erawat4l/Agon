'use strict';

/**
 * Runs before electron-builder packages the app.
 *
 * Two jobs, both learned from a real failed build:
 *
 * 1. **The icon.** The first Windows run shipped with "default Electron icon is used",
 *    because the icon files were excluded by a gitignore pattern and nothing complained.
 *    A build that cannot find its icon must fail, not install Electron's logo on someone's
 *    desktop.
 *
 * 2. **The signing bundle.** electron-builder also downloads a 5.6 MB `winCodeSign` archive
 *    to *rename and sign* the exe (app-builder-lib: winPackager.signAndEditResources ->
 *    getSignVendorPath -> "download-artifact --name winCodeSign"). That archive contains two
 *    macOS symlinks, and Windows refuses to create a symlink without Administrator rights or
 *    Developer Mode, so 7-Zip fails, electron-builder retries, and the whole build dies. The
 *    rename behind it is purely cosmetic ("Agon.exe" instead of "electron.exe"), so it is not
 *    worth a broken build. With win.signAndEditExecutable = false that code path is skipped
 *    entirely (winPackager.signApp returns early) - the check below makes sure nobody turns
 *    it back on by accident, and explains what to do if they really want the branded exe.
 */

const fs = require('fs');
const path = require('path');

exports.default = async function beforePack(context) {
  const root = path.join(__dirname, '..');
  // Strip a UTF-8 BOM first. PowerShell's `Set-Content -Encoding UTF8` (what the release
  // script used to write this file with) prepends one, and JSON.parse throws on it:
  //   Unexpected token '\uFEFF', "\uFEFF{ ... is not valid JSON
  // which failed the entire Windows build, in this hook, before anything was packed.
  const pkg = JSON.parse(
    String(fs.readFileSync(path.join(root, 'package.json'), 'utf8')).replace(/^\uFEFF/, '')
  );
  const win = (pkg.build && pkg.build.win) || {};

  const icon = path.join(root, 'build', 'icon.ico');
  const png = path.join(root, 'build', 'icon.png');

  if (!fs.existsSync(icon)) {
    throw new Error(
      'desktop/build/icon.ico is missing - the installer would get Electron\'s icon.\n'
      + 'Regenerate it with:  python ../artwork/make-icons.py   (or: npm run icon)'
    );
  }
  if (!fs.existsSync(png)) {
    throw new Error(
      'desktop/build/icon.png is missing (the window/taskbar icon).\n'
      + 'Regenerate it with:  python ../artwork/make-icons.py   (or: npm run icon)'
    );
  }

  if (win.signAndEditExecutable !== false) {
    // Allowed, but say what it needs: this is the one setting that breaks a build on a
    // normal (non-elevated, Developer Mode off) Windows account.
    // eslint-disable-next-line no-console
    console.log('  • WARNING: win.signAndEditExecutable is on. Packaging will need Windows\n'
      + '    Developer Mode (or an Administrator terminal) to unpack the signing tools,\n'
      + '    otherwise it fails on darwin symlinks. Use "npm run dist" for the safe build.');
  }

  // eslint-disable-next-line no-console
  console.log('  • icon check      build/icon.ico + build/icon.png present');
};
