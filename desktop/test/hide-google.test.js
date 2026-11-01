/**
 * Tests for the in-page rule that hides arena's "Continue with Google" control and the "OR"
 * divider above the email field.
 *
 * They matter because this code rewrites a page it does not own: a wrong match can hide the
 * email field itself, and the failure would be invisible until someone cannot sign in.
 *
 * The script under test is EXTRACTED FROM src/main.js, not copied, so a change there cannot
 * silently leave these tests passing against stale code.
 *
 *   cd desktop
 *   npm install --no-save jsdom
 *   node test/hide-google.test.js
 */
const fs=require('fs');let JSDOM;
try {
  ({ JSDOM } = require('jsdom'));
} catch (missing) {
  console.log('jsdom is not installed - skipping the page-rewrite tests.');
  console.log('To run them:  cd desktop && npm install --no-save jsdom && node test/hide-google.test.js');
  process.exit(0);
}
const src=fs.readFileSync(require('path').join(__dirname,'..','src','main.js'),'utf8');
const js=src.match(/const HIDE_GOOGLE_JS = `([\s\S]*?)`;/)[1];
if(!js){console.error('extract failed');process.exit(1);}
function run(html){
  const d=new JSDOM(html,{runScripts:'outside-only'});d.window.eval(js);
  const doc=d.window.document;
  return {hidden:[...doc.querySelectorAll('[data-agon-hidden-google]')].map(e=>e.textContent.trim().slice(0,30)),doc};
}
let bad=0;
function check(name,html,wantHidden,mustStay=[]){
  const {hidden,doc}=run(html);
  let ok=JSON.stringify(hidden.slice().sort())===JSON.stringify(wantHidden.slice().sort());
  let extra='';
  for(const sel of mustStay){const el=doc.querySelector(sel);
    if(!el||el.closest('[data-agon-hidden-google]')){ok=false;extra+=` (${sel} hidden/absent)`;}}
  if(!ok)bad++;
  console.log(`${ok?'PASS':'FAIL'}  ${name}  hidden=${JSON.stringify(hidden)}${extra}`);
}
const panel=(inner)=>`<div class="panel"><h2>Log In or Create Account</h2>${inner}</div>`;

// --- core behaviour
check('button + OR + email form', panel(`<button id=g>Continue with Google</button>
  <div id=or>OR</div><input placeholder="Your email" id=ein><button id=sub>Continue with email</button>`),
  ['Continue with Google','OR'], ['#ein','#sub']);

// --- must NOT hide things
check('nav Google link outside the panel', `<nav><a href=# id=nav>Google</a></nav>`+panel(
  `<button id=g>Continue with Google</button><input placeholder="Your email" id=ein><button id=sub>Continue with email</button>`),
  ['Continue with Google'], ['#nav','#ein']);
check('no panel present', `<div><a href=#>Sign in with Google</a></div>`, [], ['a']);
check('panel without a Google control', panel(`<input placeholder="Your email" id=ein><button id=sub>Continue with email</button>`), [], ['#ein']);
check('newsletter email box is untouched', `<div class="news"><h2>Subscribe</h2>
  <input placeholder="Your email" id=ein><a href=# id=gl>Sign in with Google</a></div>`, [], ['#ein','#gl']);
check('a sentence containing "or" is left alone', panel(`<button id=g>Continue with Google</button>
  <small id=note>Use Google or email below</small><input placeholder="Your email" id=ein><button id=sub>Continue with email</button>`),
  ['Continue with Google'], ['#note','#ein']);
check('wrapper starting with OR that holds the email field', panel(`<button id=g>Continue with Google</button>
  <div id=wrap>OR <input placeholder="Your email" id=ein><button id=sub>Continue with email</button></div>`),
  ['Continue with Google'], ['#wrap','#ein','#sub']);

// --- structural variety
check('nested panel', `<main><div><div class=modal><div class=card><h2>Log In or Create Account</h2>
  <button id=g>Continue with Google</button><div id=or>OR</div><input placeholder="Your email" id=ein>
  <button id=sub>Continue with email</button></div></div></div></main>`,
  ['Continue with Google','OR'], ['#ein','#sub']);
check('role=dialog + role=button row', `<div role=dialog><h1>Create Account</h1>
  <div role=button id=g>Continue with Google</div><div id=or>OR</div>
  <input type=email placeholder="Your email" id=ein><button id=sub>Continue with email</button></div>`,
  ['Continue with Google','OR'], ['#ein','#sub']);
check('em-dash divider', panel(`<button id=g>Continue with Google</button><div id=or>—</div>
  <input placeholder="Your email" id=ein><button id=sub>Continue with email</button>`),
  ['Continue with Google','—'], ['#ein']);

// --- the panel itself must never be hidden
{
  const {doc}=run(panel(`<button id=g>Continue with Google</button><input placeholder="Your email" id=ein>`));
  const p=doc.querySelector('.panel').hasAttribute('data-agon-hidden-google');
  console.log(`${p?'FAIL':'PASS'}  the panel itself is never hidden`); if(p)bad++;
}
console.log(bad?`\n${bad} FAILED`:'\nall hiding cases pass');
process.exit(bad?1:0);
