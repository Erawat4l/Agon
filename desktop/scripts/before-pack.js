'use strict';

/**
 * Runs before electron-builder packages the app.
 *
 * Its one job is to make a missing icon a loud error instead of a silent fallback: the
 * first real Windows build shipped with "default Electron icon is used", because the icon
 * files were excluded by a gitignore pattern and nothing complained. A build that cannot
 * find its icon must fail, not install Electron's logo on someone's desktop.
 */

const fs = require('fs');
const path = require('path');

exports.default = async function beforePack(context) {
  const root = path.join(__dirname, '..');
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
  // eslint-disable-next-line no-console
  console.log('  • icon check      build/icon.ico + build/icon.png present');
};
