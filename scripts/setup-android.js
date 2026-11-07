// Run after "npx cap add android": adds the background-audio service to the Android project.
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const cfg = JSON.parse(fs.readFileSync(path.join(root, 'capacitor.config.json'), 'utf8'));
const pkg = cfg.appId;
const app = path.join(root, 'android', 'app', 'src', 'main');
if (!fs.existsSync(app)) { console.error('android/ not found - run "npx cap add android" first.'); process.exit(1); }

// 1. Java sources (replaces the default MainActivity)
const javaDir = path.join(app, 'java', ...pkg.split('.'));
fs.mkdirSync(javaDir, { recursive: true });
for (const f of ['MainActivity.java', 'MediaService.java', 'NativeAudioPlugin.java', 'MusicLibraryPlugin.java']) {
  const text = fs.readFileSync(path.join(root, 'native', f), 'utf8').replace(/__PACKAGE__/g, pkg);
  fs.writeFileSync(path.join(javaDir, f), text);
}

// 2. Notification icon
const drawDir = path.join(app, 'res', 'drawable');
fs.mkdirSync(drawDir, { recursive: true });
fs.copyFileSync(path.join(root, 'native', 'ic_stat_music.xml'), path.join(drawDir, 'ic_stat_music.xml'));

// 3. Manifest: permissions + service
const mf = path.join(app, 'AndroidManifest.xml');
let xml = fs.readFileSync(mf, 'utf8');
if (xml.includes('MediaService')) { console.log('Manifest already patched.'); process.exit(0); }
const perms = [
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  'android.permission.POST_NOTIFICATIONS',
  'android.permission.WAKE_LOCK',
].filter(p => !xml.includes(p)).map(p => `    <uses-permission android:name="${p}" />\n`).join('');
if (!xml.includes('<application') || !xml.includes('</application>')) { console.error('Unexpected manifest layout.'); process.exit(1); }
xml = xml.replace('<application', perms + '    <application');
xml = xml.replace('</application>',
  `    <service android:name="${pkg}.MediaService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>`);
fs.writeFileSync(mf, xml);
console.log('Android project patched for background audio.');
