/**
 * Pull one page script out of TabWebViews.java, as the text the page receives.
 *
 * The scripts are Java string literals, so reading them back means three things and getting
 * any of them wrong has already cost two build cycles:
 *
 *  - take the WHOLE constant, up to its `;` - not up to the first blank line;
 *  - drop `//` comment lines, which are Java, not script;
 *  - join adjacent literals, then undo the Java escaping exactly once (\n, \" and \\).
 *
 * Every suite uses this function rather than its own copy.
 */
const fs = require('fs');
const path = require('path');

const NAV = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'java', 'ai', 'arena', 'webapp');

/** One `private static final String NAME = ...;` constant, as the script it produces. */
function constant(source, name) {
  const start = source.indexOf('private static final String ' + name + ' =');
  if (start < 0) throw new Error(name + ' not found');
  const end = source.indexOf(';\n', start);
  let js = '';
  for (const line of source.slice(start, end).split('\n')) {
    if (/^\s*\/\//.test(line)) continue;
    for (const m of line.matchAll(/"((?:[^"\\]|\\.)*)"/g)) js += m[1];
  }
  return unescapeJava(js);
}

/** Undoes the Java string escaping, once. */
function unescapeJava(literal) {
  let out = '';
  for (let i = 0; i < literal.length; i++) {
    const c = literal[i];
    if (c !== '\\') { out += c; continue; }
    const n = literal[++i];
    if (n === 'n') out += '\n';
    else if (n === 't') out += '\t';
    else if (n === 'r') out += '\r';
    else if (n === 'u') { out += String.fromCharCode(parseInt(literal.slice(i + 1, i + 5), 16)); i += 4; }
    else out += n;
  }
  return out;
}

function read(file) {
  return fs.readFileSync(path.join(NAV, file), 'utf8');
}

module.exports = { constant, unescapeJava, read, NAV };
