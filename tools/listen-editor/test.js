// Selbsttest der Kernlogik: node tools/listen-editor/test.js [--example] (oder test.html im Browser)
(function (root) {
  function run(C) {
    var res = [];
    function t(name, ok, extra) { res.push({ name: name, ok: !!ok, extra: ok ? '' : String(extra || '') }); }
    function eq(a, b) { return JSON.stringify(a) === JSON.stringify(b); }
    function clone(o) { return JSON.parse(JSON.stringify(o)); }

    // Saison
    var wint = [{ from: '11', to: '02' }];
    t('Jahreswechsel Dez', C.inSeason(wint, 12, 15));
    t('Jahreswechsel Feb 29', C.inSeason(wint, 2, 29));
    t('Jahreswechsel Maerz aus', !C.inSeason(wint, 3, 1));
    t('Jahreswechsel Okt aus', !C.inSeason(wint, 10, 31));
    var sm = [{ from: '04-20', to: '08-31' }];
    t('MM-DD Grenzen inklusiv', C.inSeason(sm, 4, 20) && C.inSeason(sm, 8, 31) && !C.inSeason(sm, 4, 19));
    t('ohne Saison ganzjaehrig', C.inSeason([], 6, 1) && C.inSeason(undefined, 6, 1));
    t('ungueltige Saison = ganzjaehrig', C.inSeason([{ from: '13', to: '02' }], 6, 1));
    t('parseMD 04-31 ungueltig', C.parseMD('04-31') === null);
    t('parseMD 02-29 gueltig', C.parseMD('02-29') !== null);
    t('parseMD 13 / 4 ungueltig', C.parseMD('13') === null && C.parseMD('4') === null);

    // Beispiel: Vogel + Heuschrecke, Saison Nov-Feb
    var st = C.emptyState();
    st.head.author = 'Test'; st.head.created = '2026-11-23';
    st.head.taxonomy.orthoptera = { reference: 'OSF', version: '5.0' };
    st.set.id = 'ch_test_mixed'; st.set.name_de = 'Testliste'; st.set.name_en = 'Test list';
    st.species = ['Turdus_merula', 'Bubo_bubo', 'Roeseliana_roeselii'];
    st.info.Turdus_merula = { common_name: '', taxon_group: '', season: [], note: '', extra: {} };
    st.info.Bubo_bubo = { common_name: 'Uhu', taxon_group: '', season: [{ from: '11', to: '02' }], note: '', extra: {} };
    st.info.Roeseliana_roeselii = { common_name: 'Roesels Beissschrecke', taxon_group: 'orthoptera', season: [{ from: '07-01', to: '10' }], note: '', extra: {} };
    var v = C.validate(st);
    t('Validierung ok', v.errors.length === 0, v.errors.join(';'));
    var out = C.buildExport(st, '2026-11-23T12:00:00+01:00');
    var keys = Object.keys(out);
    t('version 4 + generator', out.version === 4 && /^pirol-list-editor/.test(out.generator));
    t('species sortiert', eq(out.species, ['Bubo_bubo', 'Roeseliana_roeselii', 'Turdus_merula']));
    t('Bubo Saison', eq(out.species_info.Bubo_bubo.season, [{ from: '11', to: '02' }]));
    t('Heuschrecke taxon_group', out.species_info.Roeseliana_roeselii.taxon_group === 'orthoptera');
    t('Amsel ohne Eintrag (leer)', !out.species_info.Turdus_merula);
    t('Reihenfolge version, id, species', keys.indexOf('version') < keys.indexOf('id') && keys.indexOf('id') < keys.indexOf('species'));
    t('updated mit Offset', /[+-]\d{2}:\d{2}$/.test(out.updated));
    var text = C.toText(out);
    t('Text endet mit LF, 2er-Einrueckung', text.slice(-2) === '}\n' && text.indexOf('\n  "version"') > 0);

    // Round-Trip mit unbekannten Feldern
    var src = { version: 4, id: 'x_y', name_de: 'X', taxon_group: 'aves', future: { a: 1 }, taxonomy: { aves: { reference: 'IOC', version: '15.1' } },
      species: ['Parus major', 'Parus_major', 'Apus_apus'],
      species_info: { Apus_apus: { season: [{ from: '04', to: '08' }], extra_field: 7 }, Ghost_x: { note: 'n' } } };
    var rt = C.stateFromJson(src);
    t('Duplikat + Geisterinfo gemeldet', rt.warnings.length === 2, rt.warnings.join(';'));
    var o2 = C.buildExport(rt.state, '2026-11-23T12:00:00+01:00');
    t('Round-Trip erhaelt unbekannte Felder', eq(o2.future, { a: 1 }) && o2.species_info.Apus_apus.extra_field === 7);
    t('Leerzeichen -> Unterstrich', o2.species.indexOf('Parus_major') >= 0);
    t('v3-Datei ladbar', C.stateFromJson({ id: 'a', species: ['Apus_apus'] }).state.species.length === 1);
    t('Sammeldatei Set 2', C.stateFromJson({ version: 4, sets: [{ id: 'a', species: ['A_a'] }, { id: 'b', species: ['B_b'] }] }, 1).state.set.id === 'b');
    var threw = false; try { C.stateFromJson({ id: 'a' }); } catch (e) { threw = true; }
    t('species fehlt = Fehler', threw);

    // Validierungsfehler
    function bad(mod) { var s = clone(st); mod(s); return C.validate(s).errors; }
    t('ID fehlt', bad(function (s) { s.set.id = ''; }).length > 0);
    t('ID Grossbuchstaben', bad(function (s) { s.set.id = 'Ch_Test'; }).length > 0);
    t('leere Liste', bad(function (s) { s.species = []; }).length > 0);
    t('Duplikat', bad(function (s) { s.species.push('turdus_merula'); }).length > 0);
    t('Unterart', bad(function (s) { s.species.push('Motacilla_flava_flava'); }).length > 0);
    t('Monat 13', bad(function (s) { s.info.Bubo_bubo.season[0].from = '13'; }).length > 0);
    t('Monat 00', bad(function (s) { s.info.Bubo_bubo.season[0].to = '00'; }).length > 0);
    t('04-31', bad(function (s) { s.info.Bubo_bubo.season[0].to = '04-31'; }).length > 0);
    t('bis fehlt', bad(function (s) { s.info.Bubo_bubo.season[0].to = ''; }).length > 0);
    t('aves ohne Taxonomie', bad(function (s) { s.head.taxonomy = {}; }).length > 0);
    var w = C.validate(clone(st), { aliases: { Turdus_merula: 'Turdus_neu' }, modelKeys: { bubo_bubo: 1 } });
    t('Alias- und Modellhinweis als Warnung', w.warnings.length >= 2 && w.errors.length === 0, w.warnings.join(';'));
    var s3 = clone(st); s3.species.push('turdus_X');
    t('Namensformat Warnung', C.validate(s3).warnings.length > 0);
    // T220: Taxonomie-Auswahl und Namensumschaltung
    var tx = [{ s: 'Parus_major', o: 'Passeriformes', f: 'Paridae' }, { s: 'Parus_ater', o: 'Passeriformes', f: 'Paridae' },
      { s: 'Cyanistes_caeruleus', o: 'Passeriformes', f: 'Paridae' }, { s: 'Turdus_merula', o: 'Passeriformes', f: 'Turdidae' },
      { s: 'Bubo_bubo', o: 'Strigiformes', f: 'Strigidae' }, { s: 'Roeseliana_roeselii', o: 'Orthoptera', f: '' }];
    t('Gattung aus Name', C.genusOf('Parus_major') === 'Parus' && C.genusOf('Parus major') === 'Parus');
    t('Ordnungen sortiert', eq(C.taxonLevels(tx, 'order'), ['Orthoptera', 'Passeriformes', 'Strigiformes']));
    t('Familien ohne leere', eq(C.taxonLevels(tx, 'family'), ['Paridae', 'Strigidae', 'Turdidae']));
    t('Gattungen einer Familie', eq(C.taxonLevels(C.filterTaxa(tx, { family: 'Paridae' }), 'genus'), ['Cyanistes', 'Parus']));
    t('Filter Familie', C.filterTaxa(tx, { family: 'Paridae' }).length === 3);
    t('Filter Gattung', eq(C.filterTaxa(tx, { genus: 'Parus' }).map(function (e) { return e.s; }), ['Parus_major', 'Parus_ater']));
    t('Filter Ordnung+Familie', C.filterTaxa(tx, { order: 'Passeriformes', family: 'Turdidae' }).length === 1);
    t('Leerer Filter = alle', C.filterTaxa(tx, {}).length === 6);
    t('Name Landessprache', eq(C.displayNames('Parus_major', 'Kohlmeise', false), { title: 'Kohlmeise', sub: 'Parus major' }));
    t('Name wissenschaftlich', eq(C.displayNames('Parus_major', 'Kohlmeise', true), { title: 'Parus major', sub: 'Kohlmeise' }));
    t('Name ohne Landesname', eq(C.displayNames('X_y', '', false), { title: 'X y', sub: 'X y' }));
    return { results: res, example: out };
  }
  if (typeof module !== 'undefined' && module.exports) {
    var r = run(require('./core.js'));
    var fails = r.results.filter(function (x) { return !x.ok; });
    r.results.forEach(function (x) { console.log((x.ok ? 'OK   ' : 'FAIL ') + x.name + (x.extra ? ' -> ' + x.extra : '')); });
    console.log('\n' + (r.results.length - fails.length) + '/' + r.results.length + ' bestanden');
    if (process.argv[2] === '--example') console.log(JSON.stringify(r.example, null, 2));
    process.exit(fails.length ? 1 : 0);
  } else root.runListTests = run;
})(typeof window !== 'undefined' ? window : this);
