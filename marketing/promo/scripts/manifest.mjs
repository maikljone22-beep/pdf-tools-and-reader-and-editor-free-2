// Lists the recordings and screenshots the capture job produced (public/footage) into src/footage.json, with each
// video's length, so the video uses what exists and never plays a recording past its end.
import {execFileSync} from 'node:child_process';
import {existsSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const dir = 'public/footage';
const files = {};
const durations = {};
if (existsSync(dir)) {
  for (const name of readdirSync(dir).sort()) {
    const size = statSync(join(dir, name)).size;
    files[name] = size;
    if (name.endsWith('.mp4') && size > 50_000) {
      try {
        const out = execFileSync('npx', ['remotion', 'ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', join(dir, name)], {encoding: 'utf8'});
        const d = parseFloat(out.trim().split('\n').pop());
        if (Number.isFinite(d) && d > 0.5) durations[name] = d;
      } catch (e) {
        console.log(`could not read the length of ${name}: ${e.message.split('\n')[0]}`);
      }
    }
  }
}
writeFileSync('src/footage.json', JSON.stringify({files, durations}, null, 2) + '\n');
console.log(JSON.stringify({files, durations}, null, 2));
