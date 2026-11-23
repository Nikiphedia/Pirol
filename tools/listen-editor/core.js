/* PIROL Listen-Editor - Kernlogik (Export, Validierung, Saison). Ohne DOM, damit Node und test.html sie pruefen koennen. */
(function (root) {
  'use strict';

  var GENERATOR = 'pirol-list-editor 0.1';
  var TAXON_GROUPS = ['aves', 'orthoptera', 'chiroptera', 'anura', 'mammalia', 'insecta', 'other'];
  var ROOT_KNOWN = ['version', 'generator', 'author', 'created', 'updated', 'description', 'display_language', 'taxonomy', 'license', 'sets'];
  var SET_KNOWN = ['id', 'description_de', 'description_en', 'taxon_group', 'source', 'species', 'species_info'];
  var INFO_KNOWN = ['common_name', 'taxon_group', 'season', 'note'];
  var MAX_DAYS = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

  function normalizeName(s) {
    return String(s == null ? '' : s).trim().replace(/\s+/g, '_');
  }
  function validFormat(name) { return /^[A-Z][a-z]+_[a-z]+(-[a-z]+)?$/.test(name); }
  function isSubspecies(name) { return name.split('_').length > 2; }
  function isNameKey(k) { return /^name_[A-Za-z-]+$/.test(k); }

  /* "MM" oder "MM-DD" -> {m,d} (d=null bei MM); ungueltig -> null */
  function parseMD(s) {
    if (typeof s !== 'string') return null;
    var r = /^(\d{2})(?:-(\d{2}))?$/.exec(s);
    if (!r) return null;
    var m = +r[1];
    if (m < 1 || m > 12) return null;
    if (r[2] === undefined) return { m: m, d: null };
    var d = +r[2];
    if (d < 1 || d > MAX_DAYS[m - 1]) return null;
    return { m: m, d: d };
  }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  /* Zeitraum inklusiv auswerten; month/day = Stichtag. Spiegel der Spec-Funktion inRange. */
  function inSeason(season, month, day) {
    if (!season || !season.length) return true;
    var any = false, valid = false;
    for (var i = 0; i < season.length; i++) {
      var f = parseMD(season[i].from), t = parseMD(season[i].to);
      if (!f || !t) continue;
      valid = true;
      var fk = f.m * 100 + (f.d == null ? 1 : f.d);
      var tk = t.m * 100 + (t.d == null ? MAX_DAYS[t.m - 1] : t.d);
      var dk = month * 100 + day;
      if (fk <= tk ? (fk <= dk && dk <= tk) : (dk >= fk || dk <= tk)) any = true;
    }
    return valid ? any : true;
  }

  function seasonLabel(season) {
    if (!season || !season.length) return '';
    return season.map(function (p) { return p.from + ' bis ' + p.to; }).join(', ');
  }

  /*
   * State:
   * { head:{author,created,description,display_language,license,taxonomy,extra},
   *   set:{id,name_de,name_en,names:{fr:..},description_de,description_en,taxon_group,source,extra},
   *   species:[ 'Genus_species', ... ],
   *   info:{ 'Genus_species': {common_name,taxon_group,season:[{from,to}],note,extra} } }
   */
  function emptyState() {
    return {
      head: { author: '', created: '', description: '', display_language: 'de', license: '',
              taxonomy: { aves: { reference: 'IOC', version: '15.1' } }, extra: {} },
      set: { id: '', name_de: '', name_en: '', names: {}, description_de: '', description_en: '',
             taxon_group: 'aves', source: '', extra: {} },
      species: [], info: {}
    };
  }

  /* Ein Set aus geparstem JSON (v3/v4, Einzel- oder Sammeldatei). Gibt {state, sets, warnings} */
  function stateFromJson(obj, setIndex) {
    var warnings = [];
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) throw new Error('Kein JSON-Objekt');
    var collection = Array.isArray(obj.sets);
    var sets = collection ? obj.sets : [obj];
    if (!sets.length) throw new Error('Datei enthaelt keine Sets');
    var raw = sets[setIndex || 0];
    if (!raw || !Array.isArray(raw.species)) throw new Error('Set ohne species-Array');
    var st = emptyState();
    var h = st.head;
    ['author', 'created', 'description', 'license'].forEach(function (k) { if (typeof obj[k] === 'string') h[k] = obj[k]; });
    if (typeof obj.display_language === 'string') h.display_language = obj.display_language;
    if (obj.taxonomy && typeof obj.taxonomy === 'object') h.taxonomy = JSON.parse(JSON.stringify(obj.taxonomy));
    if (collection) {
      Object.keys(obj).forEach(function (k) { if (ROOT_KNOWN.indexOf(k) < 0) h.extra[k] = obj[k]; });
    }
    if (obj.version > 4) warnings.push('Neuere Formatversion (' + obj.version + '): unbekannte Felder bleiben erhalten.');
    var s = st.set;
    s.id = typeof raw.id === 'string' ? raw.id : '';
    s.name_de = raw.name_de || ''; s.name_en = raw.name_en || '';
    s.description_de = raw.description_de || ''; s.description_en = raw.description_en || '';
    s.taxon_group = raw.taxon_group || 'aves'; s.source = raw.source || '';
    Object.keys(raw).forEach(function (k) {
      if (isNameKey(k)) {
        if (k !== 'name_de' && k !== 'name_en') s.names[k.slice(5)] = raw[k];
      } else if (SET_KNOWN.indexOf(k) < 0 && (collection || ROOT_KNOWN.indexOf(k) < 0)) {
        s.extra[k] = raw[k];
      }
    });
    var seen = {};
    raw.species.forEach(function (n) {
      var name = normalizeName(n);
      if (!name) return;
      var key = name.toLowerCase();
      if (seen[key]) { warnings.push('Duplikat ignoriert: ' + name); return; }
      seen[key] = 1; st.species.push(name);
    });
    var si = raw.species_info && typeof raw.species_info === 'object' ? raw.species_info : {};
    Object.keys(si).forEach(function (k) {
      var name = normalizeName(k);
      if (!seen[name.toLowerCase()]) { warnings.push('species_info ohne Art in species ignoriert: ' + name); return; }
      var e = si[k] || {};
      var o = { common_name: e.common_name || '', taxon_group: e.taxon_group || '',
                season: Array.isArray(e.season) ? e.season.map(function (p) { return { from: p && p.from, to: p && p.to }; }) : [],
                note: e.note || '', extra: {} };
      Object.keys(e).forEach(function (kk) { if (INFO_KNOWN.indexOf(kk) < 0) o.extra[kk] = e[kk]; });
      st.info[name] = o;
    });
    return { state: st, sets: sets.length, warnings: warnings };
  }

  function sortNames(a) { return a.slice().sort(function (x, y) { return x < y ? -1 : x > y ? 1 : 0; }); }
  function put(o, k, v) { if (v !== undefined && v !== null && v !== '') o[k] = v; }

  /* Kanonische v4-Einzel-Set-Ausgabe (Spec 7). now = ISO-String mit Offset (Parameter fuer Tests). */
  function buildExport(st, now) {
    var h = st.head, s = st.set, out = {};
    out.version = 4;
    out.generator = GENERATOR;
    put(out, 'author', h.author);
    put(out, 'created', h.created || now.slice(0, 10));
    out.updated = now;
    put(out, 'description', h.description);
    out.display_language = h.display_language || 'de';
    put(out, 'license', h.license);
    out.taxonomy = h.taxonomy || {};
    Object.keys(h.extra || {}).forEach(function (k) { out[k] = h.extra[k]; });
    out.id = s.id;
    put(out, 'name_de', s.name_de);
    put(out, 'name_en', s.name_en);
    Object.keys(s.names || {}).sort().forEach(function (l) { put(out, 'name_' + l, s.names[l]); });
    put(out, 'description_de', s.description_de);
    put(out, 'description_en', s.description_en);
    out.taxon_group = s.taxon_group || 'aves';
    put(out, 'source', s.source);
    Object.keys(s.extra || {}).forEach(function (k) { out[k] = s.extra[k]; });
    var names = sortNames(st.species);
    out.species = names;
    var si = {};
    names.forEach(function (n) {
      var e = st.info[n];
      if (!e) return;
      var o = {};
      put(o, 'common_name', e.common_name);
      if (e.taxon_group && e.taxon_group !== out.taxon_group) o.taxon_group = e.taxon_group;
      var seas = (e.season || []).filter(function (p) { return p && p.from && p.to; })
        .map(function (p) { return { from: p.from, to: p.to }; });
      if (seas.length) o.season = seas;
      put(o, 'note', e.note);
      Object.keys(e.extra || {}).forEach(function (k) { o[k] = e.extra[k]; });
      if (Object.keys(o).length) si[n] = o;
    });
    if (Object.keys(si).length) out.species_info = si;
    return out;
  }

  /* Rueckgabe {errors:[], warnings:[]}. opts.aliases / opts.modelKeys (Kleinschreibung) optional. */
  function validate(st, opts) {
    opts = opts || {};
    var errors = [], warnings = [];
    var s = st.set, h = st.head;
    if (!s.id) errors.push('ID fehlt.');
    else if (!/^[a-z0-9_]+$/.test(s.id)) errors.push('ID "' + s.id + '" ungueltig (erlaubt: a-z, 0-9, Unterstrich).');
    if (!st.species.length) errors.push('Die Liste ist leer.');
    var seen = {};
    st.species.forEach(function (n) {
      var key = n.toLowerCase();
      if (seen[key]) errors.push('Duplikat: ' + n);
      seen[key] = 1;
      if (!n) { errors.push('Leerer Artname.'); return; }
      if (isSubspecies(n)) errors.push('Unterart nicht erlaubt: ' + n);
      else if (!validFormat(n)) warnings.push('Namensformat ungewoehnlich: ' + n);
      var cur = opts.aliases && opts.aliases[n];
      if (cur) warnings.push(n + ' ist ein alter Name; aktuell: ' + cur);
      if (opts.modelKeys && !opts.modelKeys[(cur || n).toLowerCase()])
        warnings.push(n + ': vom Modell nicht erkennbar (keine Label).');
    });
    Object.keys(st.info).forEach(function (n) {
      if (!seen[n.toLowerCase()]) warnings.push('Zusatzangaben ohne Art in der Liste: ' + n);
    });
    var groups = {};
    groups[s.taxon_group || 'aves'] = 1;
    st.species.forEach(function (n) { var e = st.info[n]; if (e && e.taxon_group) groups[e.taxon_group] = 1; });
    Object.keys(groups).forEach(function (g) {
      if (TAXON_GROUPS.indexOf(g) < 0) warnings.push('Unbekannte Tiergruppe: ' + g);
      if (g === 'aves' && !(h.taxonomy && h.taxonomy.aves && h.taxonomy.aves.reference && h.taxonomy.aves.version))
        errors.push('Taxonomie fuer aves (Referenz und Version) fehlt.');
    });
    st.species.forEach(function (n) {
      var e = st.info[n];
      if (!e) return;
      (e.season || []).forEach(function (p, i) {
        var lbl = n + ' Saison ' + (i + 1);
        if (!p || !p.from || !p.to) { errors.push(lbl + ': von/bis fehlt.'); return; }
        if (!parseMD(p.from)) errors.push(lbl + ': "' + p.from + '" ungueltig (MM oder MM-DD, Monat 1-12).');
        if (!parseMD(p.to)) errors.push(lbl + ': "' + p.to + '" ungueltig (MM oder MM-DD, Monat 1-12).');
      });
    });
    if (h.created && !/^\d{4}-\d{2}-\d{2}(T.+(Z|[+-]\d{2}:\d{2}))?$/.test(h.created)) warnings.push('Erstellungsdatum hat kein ISO-Format.');
    return { errors: errors, warnings: warnings };
  }

  /* Lokale Zeit mit Offset, z.B. 2026-11-23T14:05:00+01:00 */
  function isoLocal(d) {
    var off = -d.getTimezoneOffset(), a = Math.abs(off);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + 'T' +
      pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds()) +
      (off < 0 ? '-' : '+') + pad2(Math.floor(a / 60)) + ':' + pad2(a % 60);
  }

  /* T220: Gattung = erster Teil des Namens (Unterstrich oder Leerzeichen) */
  function genusOf(name) { return String(name || '').split(/[_ ]/)[0]; }

  /* T220: Eintraege {s,o,f} nach Ordnung/Familie/Gattung filtern. Leerer Filter = alle. */
  function filterTaxa(entries, f) {
    f = f || {};
    return entries.filter(function (e) {
      return (!f.order || e.o === f.order) && (!f.family || e.f === f.family) && (!f.genus || genusOf(e.s) === f.genus);
    });
  }

  /* T220: sortierte, eindeutige Werte der Ebene 'order' | 'family' | 'genus' */
  function taxonLevels(entries, level) {
    var seen = {};
    entries.forEach(function (e) {
      var v = level === 'genus' ? genusOf(e.s) : level === 'family' ? e.f : e.o;
      if (v) seen[v] = 1;
    });
    return sortNames(Object.keys(seen));
  }

  /* T220: Anzeigename: wissenschaftlich als Titel (Landesname als Untertitel) oder umgekehrt */
  function displayNames(sci, common, scientificMode) {
    var sn = String(sci).replace(/_/g, ' ');
    if (scientificMode) return { title: sn, sub: common || '' };
    return { title: common || sn, sub: sn };
  }

  function toText(obj) { return JSON.stringify(obj, null, 2) + '\n'; }

  var api = { TAXON_GROUPS: TAXON_GROUPS, normalizeName: normalizeName, validFormat: validFormat, parseMD: parseMD,
    inSeason: inSeason, seasonLabel: seasonLabel, emptyState: emptyState, stateFromJson: stateFromJson,
    buildExport: buildExport, validate: validate, isoLocal: isoLocal, toText: toText, pad2: pad2,
    genusOf: genusOf, filterTaxa: filterTaxa, taxonLevels: taxonLevels, displayNames: displayNames };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ListCore = api;
})(typeof window !== 'undefined' ? window : this);
