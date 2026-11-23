// Baut listen-editor.html: template.html + core.js + Artenindex (aus species_master.json und Label-CSV) + Aliase.
// Aufruf (Repo-Root oder hier): node tools/listen-editor/build.js
const fs = require('fs');
const path = require('path');
const here = __dirname;
const assets = path.join(here, '..', '..', 'app', 'src', 'main', 'assets');
const master = JSON.parse(fs.readFileSync(path.join(assets, 'species', 'species_master.json'), 'utf8'));
const csv = fs.readFileSync(path.join(assets, 'models', 'birdnet_v3_labels.csv'), 'utf8').split(/\r?\n/);
const aliases = JSON.parse(fs.readFileSync(path.join(assets, 'species_aliases.json'), 'utf8')).aliases;

function mapGroup(g, order) {
  g = (g || '').toLowerCase(); order = (order || '').toLowerCase();
  if (order === 'orthoptera' || g === 'orthoptera') return 'orthoptera';
  if (order === 'chiroptera' || g === 'chiroptera') return 'chiroptera';
  if (g === 'amphibia' || g === 'anura' || order === 'anura') return 'anura';
  if (g === 'aves') return 'aves';
  if (g === 'mammalia') return 'mammalia';
  if (g === 'insecta') return 'insecta';
  return 'other';
}
const head = csv[0].split(';');
const iSci = head.indexOf('sci_name'), iCom = head.indexOf('com_name'), iCls = head.indexOf('class'), iOrd = head.indexOf('order');
const map = new Map(); // key -> [sci, group, de, en, model]
for (const l of csv.slice(1)) {
  if (!l) continue;
  const f = l.split(';');
  const sci = f[iSci].trim().replace(/\s+/g, '_');
  map.set(sci.toLowerCase(), [sci, mapGroup(f[iCls], f[iOrd]), '', f[iCom] || '', 1, (f[iOrd] || '').trim(), '']);
}
for (const t of master.taxa) {
  const sci = t.scientific_name, k = sci.toLowerCase();
  const cn = t.common_names || {};
  const cur = map.get(k);
  if (cur) {
    cur[2] = cn.de || '';
    if (cn.en) cur[3] = cn.en;
    if (cur[1] === 'other' || cur[1] === 'insecta') cur[1] = mapGroup(t.taxon_group, t.order);
    if (t.order) cur[5] = t.order;
    if (t.family) cur[6] = t.family;
  } else {
    map.set(k, [sci, mapGroup(t.taxon_group, t.order), cn.de || '', cn.en || '', t.classifier_support && t.classifier_support.length ? 1 : 0, t.order || '', t.family || '']);
  }
}
const index = [...map.values()].sort((a, b) => (a[0] < b[0] ? -1 : 1));
let html = fs.readFileSync(path.join(here, 'template.html'), 'utf8');
const core = fs.readFileSync(path.join(here, 'core.js'), 'utf8');
// Funktionsersetzung statt String, damit "$" im Inhalt nicht als Muster gilt
html = html.replace('/*CORE*/', () => core)
  .replace('/*INDEX*/', () => JSON.stringify(index).replace(/<\//g, '<\/'))
  .replace('/*ALIASES*/', () => JSON.stringify(aliases));
const out = path.join(here, 'listen-editor.html');
fs.writeFileSync(out, html);
const dst = path.join(assets, 'editor', 'listen-editor.html');
fs.mkdirSync(path.dirname(dst), { recursive: true });
fs.copyFileSync(out, dst);
console.log('Arten im Index:', index.length, '| Groesse:', (Buffer.byteLength(html) / 1024).toFixed(0), 'KB');
