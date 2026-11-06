const fs = require('fs'), path = require('path');
const src = path.join(__dirname, '..', 'node_modules', 'jsmediatags', 'dist', 'jsmediatags.min.js');
const dst = path.join(__dirname, '..', 'www', 'jsmediatags.min.js');
if (!fs.existsSync(src)) { console.error('Missing ' + src + ' - run "npm install" first.'); process.exit(1); }
fs.copyFileSync(src, dst);
console.log('Copied jsmediatags.min.js into www/');
