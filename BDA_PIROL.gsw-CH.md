# PIROL — Bedienigsaleitig

*Entwurf in Bärndütsch, noch nicht überprüft. Fehlendi Texte erschiine uf Dütsch.*

Akustischi Echtziit-Artekennig für Android, gmacht für d Fäldarbet.

*Gilt ab Version v0.1.4 (Stand 2026-11-25). Was sich zwüsche de Versione gänderet het, steit i `CHANGELOG.md`.*

Di App het fönf Tab: **Live**, **Analyse**, **Referänze**, **Charte** und **Istellige**. De Knopf **?** (Hilfe) isch kei Tab: Ei Tipp schaltet de Hilfemodus ii, en Doppeltipp öffnet di Aleitig (Kapitel 1, Abschnitt "Hilfe-Knopf").

---

## 1. Erschte Start

1. App öffne. De Iirichtigs-Assistänt füehrt dür Sprach, Niveau (Ischteiger/Experte), Iistatzzweck, Artelischte, Modell (inkl. Geomodell), Berechtigige (Mikrofon, GPS, Benachrichtigige) und Zämefassig. D Wärt wärde erscht bi "Fertig" übernoo.
2. **Artelischte** wähle (Kapitel 11): D Artgruppe (Vögel, Heuschräcke, Amphibie, Säugetier, Anderi) filtere d Erkennig uf plausibli Arte. Spöter ändrbar under Istellige > Arte & Alarm.
3. **Modell** abelade: Me cha mehreri Modelle uf eimal wähle. Standard isch BirdNET 3.0 FP16 (empfohle, ca. 170 MB). Dr Download louft im Hintergrund witer, cha abbroche und spöter wiiter gah. Alternativ e Modell-Datei vo Hand wähle.
4. **Speicherort** prüefe (Istellige > Ufnahm). Standard: `Downloads/PIROL/`, per USB sichtbar.

**Installation (APK):** D Release-APK git's pro Prozessor-Architektur. Für aktuelli Gerät (Telefon und Tablet) d Datei `Pirol-<Version>-arm64-v8a-release.apk` installiere, nume für alti 32-Bit-Gerät d Datei `...-armeabi-v7a-release.apk`. D APKs si dadurch dütlich chliiner als früener di gmeinsami APK. D App het es neus Symbol (gäle Vogel), au als eifarbigs Symbol für Themed Icons. Details zur Installation und zum Update (au Samsung-Gerät) stönd im Kapitel 20.

**Was isch neu:** Nach emene Update zeigt PIROL eimal e Dialog "Was isch neu" mit de wichtigste Änderige vo de Version. Er cha jederzit under de Istellige unter "Info" wider ufgrüeft werde.

**Update vo ältere Versione:** Ab v0.0.8 isch PIROL signiert. Wer v0.0.7 oder elter installiert het, mues eimal deinstalliere und vorher d Sessione per USB sichere (`Android/data/ch.etasystems.pirol/files/PIROL/`). Vo v0.0.8 bis v0.0.10 uf v0.0.11 goht s Update ohni Deinstallation, Sessione und Modelle bliibe erhalte. Verwärt bi v0.0.11: D Konfidänz-Schwälle isch neu e gmeinsami Schwälle für alli Arte (Kapitel 2 und 13), de Rägler danach prüefe. Vo v0.0.11 uf v0.1.1 goht s Update ohni Deinstallation. Druf d Artgruppe (Kapitel 11) prüefe: alti Filter stönd uf Vögel Auto, Rescht Aus. Sessione us v0.0.6 und elter wärde nümm speziell unterstützt.

### Hilfe-Knopf
De Knopf **?** (Tablet und schmali Navigation: under Istellige; Telefon: ganz rächts i de Layschte) bietet zwei Wäg:
- **Aatippe** schaltet de **Hilfemodus** ii oder uus. Obe erschiint d Layschte "Hilfemodus: Knopf berüere für Erklärig". Ei Tipp uf en Knopf oder Rägler löst dänn nüt us, sondern zeigt e churzi Erklärig, bi Bedarf mit "Meh i de Aleitig" (springt zum passende Kapitel). Beände: X i de Layschte, Zrügg oder nomal **?**.
- **Doppeltippe** öffnet di Aleitig offline, i de App-Sprach (fählt d Übersetzig, uf Dütsch). Es Inhaltsverzeichnis füehrt zu de Abschnitt.

---

## 2. Live-Erkennig

**Start:** Ufnahm-Knopf im Live-Tab drücke. **Stopp:** Knopf lang drücke. Session und Raven-Export wärde automatisch gspicheret.

Ufnahm und Analyse louf i emene Dienst. Sie gönd witer, wenn d App i Hintergrund goht oder de Bildschirm uusgaht.

Bim Stoppe erschiint obe d Hinwiis "Lang drücke zum Stoppe" (obe, damit de Fingerr am Knopf en nid verdeckt).

Erkannti Arte erschiine als Charte under em Sonogramm:
- Artname i de gwählte Sprach, wüsseschaftlichi Name als Zuesatz
- Konfidänz i Prozänt (dünne Balke), Ziitstämpel, GPS-Position
- Alternativä (uufklappbar, antippbar)
- Hiiwise "Sälte", "Saison-untypisch", Watchlist-Markierig
- **Abspiele:** De Play-Knopf uf de Charte spielt de Uusschnitt vo de Detäktion (3 s dervor, 5 s dernoo) ab, au während de Ufnahm. En zwiite Tipp stoppt d Wiedergab.
- **Blinke:** Wird e Art erneut erkennt, blinkt ihri Charte churz blau uf. D Lischte springt derbii nid (lueg "Ruehigi Lischte").
- **Alarm-Glocke:** Im Menü vo de volle Artcharte legt me d Art dauerhaft i d Alarmlischte oder für 24 h aa. 24-h-Alarm zeige e Glocke mit em Abzeiche "24h"; nach Ablauf verschwindet s automatisch.
- **Kompakt-Knopf:** Schaltet alli Artcharte zwüsche kompakt und usfüehrlich um. Uf em Telefon sitzt er i de Statuszile, uf em Tablet i de Chopfzile. D Alternativä lön sich über de Chopf vo de Charte uf- und zuelklappe.

**Kompakti Aasicht uf em Telefon:** Im Hoch- und im Querformat bruucht de Live-Tab de Platz sparsam: kei Titelzile, d Navigation bestoht nume us Icons, d Knopfzile vo de Charte isch eizeilig. Uf schmale Charte stönd Teile, Referänz, Vergliiche und "Alternativä" im Menü "Witeri Aktione" (Symbol mit drü Pünkt); uf em Tablet bliibe alli Chnöpf sichtbar. Im Querformat lige links Sonogramm und Chnöpf, rächts d Artcharte. S Panel "Ähnlichi Arte" isch uf em Telefon igklappet und lat sich uufklappe.

**Mikrofon stumm bi Wiedergab:** Spielt PIROL während de Ufnahm en Detäktion über de Lautsprächer ab, wird s Mikrofon stumm gschaltet, damit d Wiedergab nid nomal erkennt oder ufgnoo wird. Im Live-Tab erschiint de Hiiwis "Mikrofon stumm während Wiedergab". Mit aagschlosse Chopfhörer (Chabel, USB, Bluetooth) bliibt s Mikrofon aktiv.

| Knopf | Zustand |
|-------|---------|
| grün, pulsierend | Preroll-Puffer louft (Ufnahm no nid gstartet) |
| rot, pulsierend | Ufnahm louft |

**Statuszile:** Laufziit i mm:ss. **GPS-Liischte:** aktuelli Genauigkeit i ±m, rot bi länger als 30 s ohne gültige Fix.

**Bemerkig:** Pro Ufnahm cha en Titel igä werde. Er erschiint i de Session-Lischte, im ZIP-Name und im Raven-Export.

**Vo Hand zuefüege:** Ei Art vo Hand iitrage (mit Stückzahl, Atlascode, Bemerkig). Pro Detäktion lö sich zunäch Stückzahl, Atlascode, Bemerkig und es Sprach-Memo erfasse. Ei Art cha gsperret werde (nume ei Meldig pro Session).

**Zämefasse:** Detäktione vo de gliiche Art wärde räumlich und zeitlich zu eim Iitrag zämegfasst (Radius und Ziitfenster iistellbar, Istellige > Modell & Erkennig).

### Ruehigi Lischte
D Riihefolg vo de Artcharte ändert sich höchschtens alle 5 s. Neui Arte wärde gsammlet und gmeinsam obe iigfügt (di allererscht Charte erschiint sofort); si chönne aso bis zu 5 s spöter sichtbar werde, Alarm chöme aber witerhin sofort. Wird e Art i de oberschte 5 Charte erneut erkennt, bliibt si schtoh und blinkt blau. E Art witer unde rückt bim nöchschte Wechsel nach obe und blinkt. Lösche, Verifiziere und gsperrti Arte wirke sofort. Detäktione, Alarm, Zämefassig und Analyse si dervo nid betroffe.

### Session-Memo
De Memo-Knopf im Live-Tab (nume während ere Ufnahm sichtbar) funktioniert wie es Walkie-Talkie: **gdrückt halte und rede**, bim Loslo ändet s Memo. Während de Ufnahm isch de Knopf rot und zeigt d Laufziit; druf erschiint "Memo gspicheret (n s)". Memos unter 0.5 s wärde verworfe ("Z churz"), nach 3 Minute ändet es Memo automatisch. S Memo wird mit siiner Lag i de Ufnahm gspicheret (`memos.jsonl`, Dateie unter `memos/`), erschiint i de Analyse im Block "Memos" (Kapitel 6) und als Zile "Memo" im Raven-Export (Kapitel 7). Memos wärde nie als Stimm usgblendet (Kapitel 7). Sprach-Memos zu einzelne Arte blibe dervo trennt.

Bim Drücke merkt sich PIROL de letscht GPS-Fix (nid elter als 2 min) und speichert en mit em Memo; uf de Charte erschiint s Memo de als Mikrofon-Marker (Kapitel 8). Ohni aktuelle Fix het s Memo kei Ort. Lösche, Teile und Speichere unter: Kapitel 6.

### Mehrfacherkennig und Konfidänz-Schwälle
Im gliiche 3-s-Fänster wärde alli Arte erkennt, wo d Schwälle erreiche, nid nume di oberscht. De Rägler heisst "Konfidänz-Schwälle (alli Arte)" (Istellige > Modell & Erkennig, Standard 50 %) und gilt für alli Arte gliich. Arte drunger, aber über de Kandidate-Grenze, bliibe als Kandidate bi de Detäktione vom Fänster. Früener het de Rägler e anderi Schwälle gsteuert (TopK, Standard 0.3); drum nach em Update prüefe. Watchlist-Alarm lö erscht ab de Schwälle uus.

### Zweitmodell (Experte-Modus)
E Zweitmodell (z.B. Perch v2 oder BirdNET 2.4, Kapitel 13) rächnet di gliiche Fänster no einisch. D Charte zeigt de neben de Konfidänz vom Hauptmodell d Zahl vom Zweitmodell: i Prozänt, bi Perch als Rang ("Perch #2"). Arte, wo s Zweitmodell nid kennt, si als nid prüefbar markiert. S Zweitmodell bitroffet weder Alarm no Filter oder Zämefassig. D Schwälle "Alternativä ab" (5/10/20/30 %, Istellige > Modell & Erkennig) bestimmt, ab welcher Konfidänz Alternativä gleischtet wärde.

### Preroll
Wenn aktiviert, wird Audio **vor** em Ufnahmstart mitgspicheret (Länge iistellbar) und au analysiert. S Preroll stoht lückelos am Afang vo `recording.wav`. S Sonogramm zeigt devo höchschtens 5 s.

### Überlappig
Analysefänster chönne sich überlappe: 0 % (Standard, Fänster alli 3 s), 50 % (alli 1.5 s) oder 67 % (jedi Sekunde). Mehr Überlappig erkennt churzi Ruef a Fänstergränze besser, bruucht aber meh Rächezit und Akku. Isch s Gerät z langsam, reduziert PIROL d Überlappig automatisch und meldet das eimal pro Session i de Snackbar. Bi Modälle mit längerem Fänster (Perch, 5 s) begränzt de Rägler "Max. Schritwiiti" (3, 4 oder 5 s) de Abstand zwüsche zwei Fänster.

### Lengi Ufnahme (Segmänt)
Nach de iigschtellte Maximaldauer (Standard 60 min, iistellbar 10-80 min) fangt automatisch e nüi Datei aa (`recording-002.wav` usw.). D Ufnahm louft ohni Unterbruch witer. Ziitaagabe i `detections.jsonl` und Raven gälte ab Session-Start. De WAV-Chopf wird alli 5 s nachgfüehrt: Wird d App hart beände, gönd höchschtens ca. 3 s Audio verlore.

### Hochpassfilter und Ufbereitig
De Hochpassfilter (200 Hz) filtered Wind und Trittschall, standardmässig aa. Er wirkt nume uf d Kopie für d Erkennig, di gspicheret Ufnahm bliibt unverändert. Änderige gälte ab de nöchschte Ufnahm.

---

## 3. Aasicht: Sonogramm, Spektrum, Oszillogramm

Im Live- und im Analyse-Tab. D Frequänzachse zeigt 1/2/4/8/12 kHz. Dargstellt wird standardmässig bis **12 kHz** (Voriistellig für Vögel), iistellbar bis **24 kHz**. D Pegel vom Sonogramm si in dBFS.

**Aasicht-Modi (Live-Tab):** Im Aasicht-Panel (Langdruck) schaltet me zwüsche **Sonogramm**, **Spektrum** und **Oszillogramm** um. S Spektrum zeigt d Lautstärke je Frequänz mit echter FFT (dBFS, Mittelig, Spitze-Halte mit verblassende Spitze, Cursor). Im Spektrum-Panel chasch d Frequänzachse zwüsche **Linear** (Standard, glychi Hz-Abstand) und **Mel** (tiefi Frequänze gstreckt, wie im Sonogramm) umschalte; Raster und Cursor-Aazeig folge dr Wahl. De Rägler **Intensität (Gamma)** wirkt au im Spektrum (Kurvehöchi und dB-Raster) und isch dr gliich Wert wie im Sonogramm. S Oszillogramm zeigt de Ziitverlauf mit Pegelaazeig (Peak/RMS), Bandpass und rot Markierig bi Übersteuerig; sis **Ziitfenster** (5 bis 30 s) stellt me im Oszillogramm-Panel ii. E Gamma-Rägler gheert nid zum Oszillogramm. Solang no kei Date da sind, het jedi Aasicht e eigene Hiiwis ("Spektrum wird glade", "Oszillogramm wird glade"). Frequänz, Dynamik und Farbmuster gälte für alli drü Aasichte zäme. In de Analyse stönd Spektrum und Oszillogramm au zur Verfüegig.

**Darstellig aapasse:** Langdruck uf s Sonogramm (Live oder Analyse) öffnet s Iistellig-Panel under em Sonogramm; di gliiche Rägler stönd under Istellige > Aazeig. S Panel gilt für Live, Analyse und Istellige zäme. Obe im Panel stoht "Fertig" i eigener Zile; Voriistellige und Farbpalette si Chachle, wo bi Platzmangel umbreche.

| Rägler | Wirkig |
|--------|---------|
| Voriistellig | Standard / Leisi Ruef / Lüüti Umgäbig |
| Auto-Kontrast | passt d dB-Schwälle loufend em Signal aa; sus vo Hand (Bereich i dB) |
| Intensität (Gamma) | chliiner = leisi Aateil heller |
| Helligkeits-Deckel | begränzt starchi Signal |
| Farbpalette | Magma, Viridis, Graustufe, **Papier (hell)** |
| Frequänz | aazeigte Bereich i kHz |
| Ziitfenster | Sekunde, wo sichtbar sind (5 bis 30 s) |

**Papier (hell):** hälli Palette wie es klassischs Papier-Sonagramm, guet bi Tageslicht. D Palette wird im Panel (Langdruck uf s Sonogramm) gwählt; uf em Telefon gits dafür kei eigene Knopf im Live-Tab meh.

**Tab-Wechsel:** Verlaset me de Live-Tab oder schaltet de Bildschirm uus, rächnet PIROL s Sonogramm bi dr Rückkehr nach (bis zum iigschtellte Ziitfenster), so dass es lückelos witerlauft. War me länger weg als s Ziitfenster, fangt es nöi aa.

**Modus** (Istellige > Aazeig): Vögel, Fläderemuus oder Breitband. De Fläderemuus-Modus nimmt mit 96 kHz uf (Ultraschall); debii gits kei Preroll.

---

## 4. Verifikation

Pro Detäktion stönd die Aktione zur Verfüegig (Live-Tab, Analyse-Tab und Charte):

| Aktion | Status | Darstellig |
|--------|--------|-------------|
| Bstätige | Bstätigt | grüne Rand |
| Unsicher (?) | Unsicher | orange Rand |
| Ablehne | Abgelehnt | usgegraue |
| Korrigiere | Korrigiert | Name dur eigeni Iigab ersetzt |
| Alternativ wähle | Bstätigt | D Art vo de Detäktion wird dur d Alternativ ersetzt (gliiche Zile, Ziit und Position); di alt Art bliibt als Kandidat. Rückgängig i de Snackbar |

Im **Live-Tab** gits bim Verifiziere kei Snackbar meh. Nach "Bstätige" zeigt d Charte stattdessen e grüene Rückgängig-Knopf; er setzt d Detäktion uf "Unsicher" zrügg. D Chnöpf Bstätige, Unsicher, Ablehne und Bearbeite bliibe sichtbar, de Status lat sich also jederzit umschalte. Bim Wähle vo ere **Alternativ**, bim Verschiebe uf de Charte und bim Bstätige über de Watchlist-Alarm erschiint witerhin e Snackbar mit **Rückgängig** (mindeschtens 5 s). Alli Aktione wärde i `verifications.jsonl` festghalte; au abgläinti und ersetzti Detäktione bliibe im Export (Audit-Trail).

**Detäktion teile:** Uusschnitt (3 s dervor, 5 s dernoo, Maximallänge iistellbar) als WAV, M4A (AAC), FLAC (falls s Gerät das cha) oder Sonogramm-PNG mit Beschriftig.

---

## 5. Sessione

Jedi Ufnahm erzüügt en Session-Ordner, standardmässig `Downloads/PIROL/YYYY-MM-DD/{sessionId}/`. De Ordnername treit d Ortszit.

```
session.json              Metadate (Dauer, Ort, GPS-Statistik, verwendet Modell)
detections.jsonl          alli Detäktione
verifications.jsonl       alli Verifikations-Aktione
detections.updates.jsonl  Zusatzdatei während de Ufnahm (wird bim Stopp i detections.jsonl zämegfüert)
recording.wav             Ufnahm (16-bit, Mono, 48 kHz); bi lange Sessione
recording-002.wav ...     witeri Segmänt
recording.selections.txt  Raven Selection Table (automatisch bim Stopp)
memos.jsonl               Session-Memos mit Lag i de Ufnahm (Dateie unter memos/)
human_windows.jsonl       Merkliste für s Usblände menschlichi Stimme (nie exportiert)
```

- **Speicherbedarf:** ca. 5.5 MB pro Minute Ufnahm.
- **Ziitstämpel:** immer Ortszit mit Zone-Offset (z.B. `+02:00`).
- **Auto-Lösche:** Churzi Sessione ohni Detäktion wärde gläscht (Minimaldauer iistellbar, 0 = uus).
- **Lösche** vo ere Session i de Analyse: mit Bstätigung.
- **Aagaabe vo importierte Sessione bearbeite:** Bi importierte Sessione gits im Überlaufmenü vo de Chopfzile (Symbol mit drü Pünkt) de Iitrag "Aagaabe bearbeite": Startziit, Ort und Bemerkig lö sich nachträglich ändere. Ei nüi Startziit verschiebt alli Ziite vo de Session (Detäktione, Verifikatione, Variante, Route) um di gliich Differänz; Positione i de Ufnahm bliibe. Ändert sich s **Datum**, benennt PIROL o de Session-Ordner um (nöii Kennig mit de nöie Ortszit) und verschiebt en i de passende Tagesordner (sofern Tages-Unterordner aa sind). Startziit und Datum wählt me mit de Datums- und Ziitauswahl. Raven und GPX wärde nöi gschriebe. De Ort gilt für d Session und alli Detäktione ohni eigene GPS-Fix; uf de Charte verschobeni Pünkt bliibe. Nid megli während emene Rescan; e Datumsändrig isch o nid megli, solang es Upload vo dere Session igreiht oder aktiv isch.

### Speicherort wächsle
Istellige > Ufnahm > Speicherort: "Anderen Ordner wähle" (SAF: interne Speicher, SD-Charte, Cloud-Ordner). Optional mit Tages-Unterordner. Isch de Ordner nid erreichbar, speichert PIROL im Fallback-Ordner vo de App und meldet das. Einzeli Ufnahme lö sich au im Analyse-Tab verschiebe (Kapitel 6).

**Sessione verschiebe:** Bim Wächsle fragt PIROL, ob bestehendi Sessione an dn nöie Ort **kopiert** wärde solle ("Kopiere") oder nume de Ort gwächslet wird ("Nume wächsle").

---

## 6. Analyse-Tab

- **Session-Lischte:** nach Datum, mit Dauer, Grööss und Bemerkig.
- **Session öffne:** Sonogramm und Detäktionslischte mit Ziitagab. D grad abgspielti Detäktion isch markiert, en Zeiger zeigt d Position.
- **Abspiele:** pro Detäktion (Uusschnitt mit Zoom) oder d ganzi Ufnahm; Segmänt wärde nahtlos aneinangerreiht. Lengi Ufnahme wärde gstreamt.
- **Mehreri Vorchoo:** Wird e Art im Lauf vo de Ufnahm mehrmals erkennt und zu einer Charte zämegfasst, zeigt d Charte d Zile "N Vorchoo", wo sich uufklappe lat. Ufeinanderfolgendi Fänster si zu **Episode** zämegfasst ("N Vorchoo i M Episode"). Jedi Episode stoht mit Start- und Ändziit, Position i de Ufnahm ("@ m:ss") und Dauer ("N Fänster, M s") i eir Zile; ihre Play-Knopf spielt di ganzi Episode am Stück ab (au bi langem Dauersang). Zu einzelne Fänster isch nume de Ziitpunkt bekannt, nid d Konfidänz.
- **Wiedergab:** Es spielt immer nume ein Iitrag. En zwiite Tipp uf de gliiche Knopf stoppt, ein Tipp uf en andere Iitrag wächslet, und en Tab-Wechsel bändet d Wiedergab. Das gilt i de ganze App (Live, Analyse, Charte, Referänze, Sprach-Memos).
- **Verifiziere, korrigiere, Art zuefüege:** wie im Live-Tab, au nachträglich.
- **Vergliich:** Detäktion und Referänz neebeenand, mit synchroner Wiedergab.
- **Suech:** S Such-Symbol i de Session-Lischte filtered noch Artname (Teilwort, dütsche oder wüsseschaftleche Name). I ere ufgmachte Ufnahm filtered d Suech d Detäktionslischte.
- **Chopfzile und Wiedergabelischte:** Zrügg, Datum und Chennung, Play mit Positionsaazeig, Suech, Raven und Upload. Ds Ergäbnis vom Upload zeigt en Snackbar ("Hochgeladen", bi Fehler mit Grund, oder dass PIROL uf en Entscheid wartet, Kapitel 7). Mit vorige/nöchschti Detäktion springt me zwüsche de Iiträge.
- **Navigation:** Tippe im Sonogramm oder Oszillogramm springt a di Stell; louft kei Wiedergab, merkt sich PIROL d Startposition (cyanfarbene Zeiger) für s nöchscht Play.
- **Spektrum und Oszillogramm:** wie im Live-Tab (incl. Linear/Mel und Gamma, Kapitel 3); im Oszillogramm zeigt en Zeiger d Abspielposition.
- **Zoom mit zwei Finger:** In de Ibersichtsliischte und im Sonogramm zoomt me mit Pinch (zwei Finger). Dr Uusschnitt isch mindeschtens 3 s (bi churze Sessione di ganzi Session) und höchstens di ganzi Ufnahm; wenn me uf di ganzi Ufnahm uuszoomt, isch de Zoom ufghobe. Tippe und Zieh mit eim Finger funktioniere wie bisher.
- **+ Art:** übernimmt d Ziit us de Abspielposition (+/-10 s). Bi Sessione us mehrere Dateie mit Lücke zwüsche de Segmänt rächnet PIROL über d Wanduhr vo dem Segment; s Fänster bliibt i somene Segment. De Ort lat sich über "Uf Charte wähle" bestimme.
- **Ibersicht:** Ne Ibersichtsliischte zeigt d Detäktione über di ganzi Ufnahm; d Raven-Tabälle listet sie als Tabälle.
- **Raven-Datei nöi erzüge**, z.B. nach nachträglicher Verifikation.
- **Episode teile:** Jedi Episode hät en eigene Teile-Knopf; teilt wird de Uusschnitt vo dere Episode.
- **Memos:** Gits Memos, zeigt d Analyse under Sonogramm und Ibersicht de uufklappbare Block "Memos (n)" (höchschtens 200 dp hoch, scrollbar) mit Abspiele. Er listet Session-Memos und Memos zu Arte; bi Art-Memos ohni bekannti Ufnahmziit stoht "ca. ... (Ziit vo de Detäktion)". Ds Menü vo jedem Memo biet **Teile** und **Speichere unter** (d WAV-Datei unverändert, Name `YYYYMMDDHHMMSS_Memo_Bemerkig.wav`, ohni Usblände vo Stimme) und bi Session-Memos **Lösche**: Ds Memo verschwindet sofort, en Snackbar biet **Rückgängig** (10 s); erst dernoo wird d Datei endgültig gläscht. In de grad laufende Ufnahm isch Lösche gsperrt ("Lösche (Ufnahm louft)").
- **Rescan:** De Rescan-Knopf näbe de Lupe (nume wenn e Ufnahm da isch) öffnet d Wahl vo Modell, Lischte bzw. Artgruppe, Konfidänz-Schwälle, Überlappig und Zweitmodell. De Rescan louft im Hintergrund als Vordergrund-Dienscht mit Fortschritt und Abbreche; s Ergebnis erschiint als Chip näbe em Original ("Datum Zit · Modell"). D Ergäbnis erschiine **loufend**: D Variante öffnet sich sofort, d Detäktionslischte wachst alli paar Sekunde und lat sich scho während em Scan verifiziere (Chopfzile "· louft"). Bim Abbreche bliibt s Teilergebnis erhalte ("abgbroche bi x min"); dasälb gilt, wenn d App während em Scan beändet wird. Dr Rescan isch schnäller worde: Er rächnet mit meh Rächekärne, und s Zweitmodell louft parallel zum Hauptmodell; d Ergäbnis si gliich wie vorher. Bereits vorgnoo Verifikatione wärde nie überschriebe. Während eme Rescan isch kei Ufnahm megli und d loufendi Variante nid löschbar. Nach emene Import vo Audiodateie louft de Scan dän gliiche Wäg (sihe "Audio importiere").
- **Audio importiere:** De Import-Knopf im Chopf vo de Session-Lischte übernimmt e PIROL-Session (ZIP oder Ordner) oder Audiodateie (WAV, FLAC, MP3, M4A, OGG) als nöii Session. D Startziit stoht vorbelegt (z.B. us emene AudioMoth-Kommentar) und isch ändrbar. Bi mehrere Dateie wählt me: je Datei e Session oder alli zu eir Session (Segmänt, nach Ziit sortiert).
  - **Startziit:** Datum und Ziit wählt me mit de Datums- und Ziitauswahl. Bi Recordern isch d Dateiziit oft s Ende vo de Ufnahm; de Schalter **"Dateiziit = Ände"** (Standard aa) rechnet dänn Start = Dateiziit minus Laufziit, usgschaltet gilt d Dateiziit als Start. Under em Fäld steit, woher d Ziit chunnt.
  - **Scan nach em Import:** Anschliessend biet PIROL dr Scan a (Rescan-Dialog). Bi mehrere Dateie, wo je als eigeni Session importiert wärde, gilt d Wahl für **alli** nöie Sessione, wo nacheinander gscannt wärde. Isch s Original vo ere Session no läär (ohni Detäktione), wird ne vollständige Scan zum **Original**: `detections.jsonl`, `verifications.jsonl` und d Raven-Datei lige de direkt im Session-Ordner, ohni manuelle Schritt. Das gilt o, wenn scho ältere Scans da sind; bim Öffne vo somene Session übernimmt PIROL de neuscht vollständige Scan. Spöteri Scans blibe Varianten, abgbroche Scans werde nie übernoo.
  - **Abbruch:** Bricht me de Import ab (oder schiitet er), wird d grad halb importierti Session gläscht; scho fertigi Sessione vo me Mehrfach-Import blibe erhalte. Das isch andersch als bim Rescan, wo s Teilergebnis bhaltet. Bricht me de Scan-Dialog ab, werde d wartende Scans verworfe.
- **Speichere unter:** Im Überlaufmenü vo de Chopfzile. Me wählt **ZIP** oder **Ordner** und druf s Ziel (au SD-Charte, USB oder Cloud-Ordner). De Inhalt entspricht em Upload-ZIP; bim Ordner entsteht en Unterordner mit em Exportname (Kapitel 7). E Ziel i de PIROL-Ablag oder i de Session sälber wird abgläh. Für di laufendi Ufnahm isch de Iitrag usgegraut. Menschlichi Stimme wärde je nach Iistellig usgblendet (Kapitel 7).
- **Chopfzile:** Datum · Gmäind · Bemerkig i ere Zile; ei Tipp uf d Bemerkig öffnet de Dialog. De Kompakt/Gross-Umschalter entspricht dem vo de Artcharte.

**Mehreri Ufnahme wähle:** Langdruck uf ei Ufnahm i de Session-Lischte startet de Auswahlmodus; witeri Ufnahme wärde per Tipp gwählt. D Chopfleischte zeigt "N usgwählt" mit de Aktione "Alli", "Verschiebe" und "Lösche"; s X oder d Zrügg-Taste beändet de Modus. D grad laufendi Ufnahm isch nid uswählbar.
- **Verschiebe:** De Zielordner isch frei wählbar (Ordnerwahl, au SD-Charte oder USB-Speicher). Jedi Session wird mit allne Dateie (Ufnahm-Segmänt, Memos) kopiert, d Dateigrössen wärde prüefet, druf wird s Original gläscht. Bi emene Fehler oder Abbruch bliibt s Original erhalte und d Teilkopie wird entfernt. Existiert am Ziel scho ei Session mit gliichem Name, wird `-2`, `-3` usw. aagchänkt. De Fortschritt isch sichtbar und abbrechbar. En Zielordner innerhalb vo `Downloads/PIROL` (oder d Ufnahm sälber) wird abgläh; es wird dänn nüt verschobe.
- **Lösche:** Nach Rückfrag ("N Ufnahme lösche?"); das cha nid rückgängig gmacht werde. Ds Lösche einzelner Ufnahme per Papierchorb-Symbol bliibt wie bisher.

---

## 7. Export

**Raven Selection Table** (`recording.selections.txt`): wird bim Stopp automatisch neben de WAV gschriebe, Tab-trennt, UTF-8. Öffnet sich i Cornell Raven, Audacity (als Label-Spur) und Sonic Visualiser.

Enthalte si u.a. Selection, Begin File (bi Segmänt d jeweilig WAV), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (mit Offset), Species, Confidence, Status (bi Zweitmodell Second Rank). Welchi witeren Spalte gschriebe wärde, stoht under Istellige > Export & Upload > Raven-Export Felder. KML-Export gits nümm.

### Raven: eini Zile pro Episode
Ufeinanderfolgendi Erkennige vo ere Art bilde ei **Episode**; d Tabälle het pro Episode ei Zile (nümm pro zämegfasste Iitrag). Ei nöii Zile fangt aa, wenn d Lücke grösser isch als es Analysefänster. Confidence isch s Maximum vo de Episode, Begin File s Segmänt vo ihrem Afang. Zuesätzlichi Spalte: `Cluster` (Chennung vom erschte Iitrag) und `Windows` (Aazahl Fänster). D Raven-Tabälle i de App zeigt di gliiche Zile.

### Raven: Memos
Jedes Session-Memo (Kapitel 2) erschiint als eigeni Zile mit Species `Memo` a siner Position i de Ufnahm. D Spalte `Memo` enthaltet de Pfad vo de Memo-Datei (au bi Art-Memos); sie lat sich under Raven-Export Felder abschalte. Memo-Zile stönd au i de Tabälle vo Rescan-Variante.

**ZIP-Paket:** d Session als ZIP, für manuells Teile oder de automatisch Upload (Kapitel 12).

### Menschlichi Stimme usblände (Datenschutz)
PIROL ersetzt i **Export-Kopie** (Speichere unter als ZIP oder Ordner, Upload-ZIP, Teile vo Uusschnitt) menschlichi Stimme dur Rausche: Wo s Modell Mensche erkennt, wird s Sprachband (ca. 300-3900 Hz) ersetzt, mit 0.5 s Rand; dr Rescht bliibt unverändert. D Originalufnahm, Detäktione und Raven wärde nie verändert. D Merkliste `human_windows.jsonl` entsteht live und bim Rescan; no nid bewertete Bereich (importierti oder alti Sessione) scannt PIROL bim Export nach (mit Fortschritt, abbrechbar).
- **Iistellig:** Istellige > Export & Upload > Datenschutz > "Menschlichi Stimme im Export usblände" (Standard aa). Bim Uusschalte warnt PIROL ("Stimme nümm usblände?"), well Exporte dänn Gspröch vo unbeteiligte Lüüte chönne enthalte. Im Experte-Modus stellt "Mensch-Schwälle" ii, ab weli Score usgblendet wird (tiefer = vorsichtiger).
- **Memos** bliibe immer unbearbeitet; d Ziitruum vo me Live-Memo i de Ufnahm wird vom Usblände usgnoo.
- **Modell ohni Mensch-Klass:** Kennt s Modell vo ere Session kei menschlichi Stimme, fragt PIROL bim Speichere, Ufelade und Teile: mit BirdNET 3.0 nach Mensche sueche (nid während ere Ufnahm, cha Minute dauere), ohni Usblände exportiere (mit Warnig) oder abbreche. D automatisch Upload lad i däm Fall nüt unmaskiert ufe, sondern wartet uf d Entscheidig.

**Dateinamen:** Teilti Detäktione heisse `YYYYMMDDHHMMSS_Art_Bemerkig.ext` (z.B. `20260420143005_Amsel_Garten_hinten.wav`), ZIP-Pakete `YYYYMMDDHHMMSS_Bemerkig.zip` (Ziit = Session-Start). Fehlt d Bemerkig, entfallt de Teil; Leer- und Sonderzeiche wärde zu `_`.

---

## 8. Charte

Georeferenzierti Detäktione uf ere Charte. S **Chartemenü** öffnet sich per Wisch vom linke Rand oder über s Menü-Symbol obe links ("Chartemenü öffne"). Es enthaltet Aazeig, Chartequälle, Offline-Charte und de Artfilter.

- **Chartequälle:** Automatisch (swisstopo i de Schwiz, OpenStreetMap usserhalb), OpenStreetMap, swisstopo Charte, swisstopo Luftbild.
- **Offline:** Für swisstopo-Charte lat sich de sichtbari Uusschnitt abelade ("Uusschnitt abelade", mit Aagab vo de Chachle-Aazahl und Grössi). E Abbruch bhaltet bereits gladeni Chachle; en erneuti Start lad nume s Fählende. "Offline-Charte lösche" entfernt alli gspicherete Chachle.
- **Aazeig:** alli Sessione, "Nume Live" oder "Letschti 3". Abgläinti Beobachtige si usgbländet. Überlappendi Marker wärde je nach Zoom gruppiert. D Charte startet uf em eigene Standort.
- **Pünkt aatippe:** En Tipp uf en Pünkt öffnet es Menü mit Verifiziere (Bstätige, Unsicher, Ablehne), "Bearbeite", "Position verschiebe" und Abspiele (Play bzw. "Wiedergab stoppe"). E Langdruck öffnet dasälb Menü und erlaubt zuesätzlich, Beobachtige als Simultanbeobachtig z verbinde. Abgspielt wird de Uusschnitt vo de Detäktion us de Session-Ufnahm; fehlt sie, erschiint en Meldig.
- **Memo-Marker:** Session-Memos mit Position (Kapitel 2) erschiine als Mikrofon-Marker. En Tipp öffnet en Dialog mit Ziit und Abspiele. Si sind bi "Alli Sessione" und "Letschti 3" sichtbar, nid bi "Nume Live".
- **Mehreri Pünkt übere enang (Cluster):** En Tipp uf es Cluster zeigt d Lischte vo de Detäktione. Pro Iitrag gits Verifiziere, Abspiele und "Position verschiebe".
- **Position verschiebe:** D Charte zeigt obe de Hiiwis "Charte verschiebe: Fadechrüz uf di nöii Position vo ..., dänn Ibernäh." D Charte so verschiebe, dass s Fadechrüz i de Mitti uf de nöie Position lit, dänn unde "Ibernäh" (oder "Abbreche"). Druf erschiint e Snackbar mit **Rückgängig**. S Verschiebe geht au für Live-Pünkt; louft kei Session, gilt di nöi Position nume bis zum Zue vo de App.
- **Artfilter:** Im Chartemenü stoht de Artfilter: "Nume Alarmarte" oder einzelni Arte usblände (Alli/Kei). De Filter gilt bis zum Zue vo de App; Route bliibe sichtbar.
- **Route:** En während de Ufnahm ufzeichnete Route erschiint als Linie (bi Modus "Nume bi Detäktion" gstrichlet, nume a Detäktionspünkt). Lueg Kapitel 14.
- **Standort-Knopf:** Zentriert d Charte uf d eigeni Position und zoomt so, dass d sichtbari Chartehöchi ungfähr 200 m beträgt.
- **Zoom-Knöpf:** D +/- Chnöpf si 50 % grösser und lige über em Standort-Knopf.

---

## 9. Referänze und ähnlichi Arte

Verifizierti Detäktione lö sich als Referänz spicheren ("Als Referänz spichere") und wider entferne. D Referänze-Tab zeigt d gspicherete Ufnahme, au en Audio-Import isch megli. Nöii Detäktione wärde per Embedding mit de Referänze verglicheh.

Istellige > Ähnlichi Arte & Referänze: Ähnlichkeitssuech aa/us, Aazahl aazeigter Zile (0 = us, 0.5 = Laufschrift vo de letschte 5, 1-10 = Lischte), Obergränze vo gspicherete Vergliichsufnahme.

---

## 10. Watchlist und Alarm

**Watchlist:** Zielarte, wo e Alarm uslöse. Verwaltig under Istellige > Watchlist & Alarm: Art sueche und zuefüege, entferne, oder us `watchlist.json` lade. Gross-/Chliinschriibig und Leerschlag spiele bim Abgliich kei Rolle.

**Freigäbene Ordner:** Under "PIROL-Ordner freigäh" eimal `Downloads/PIROL` wähle. PIROL liest dänn `watchlist.json` und d Ordner `lists/` automatisch (Details Kapitel 11). "Biispiel-Lischte aalege" erzügt e Vorlag.

**Alarm-Usgab** (einzeln ii-/usschaltbar): Vibration, Benachrichtigung, Vollbild-Hiiwis. Uf em **Sperrbildschirm** erschiint de Vollbild-Alarm nume, wenn "Vollbild-Benachrichtigige" für PIROL i de Android-Istellige erlaubt si (Knopf "Berechtigung öffne"). Sus bliibt s e Benachrichtigung. Mit "Alarm teste" (nach 5 s, Gerät sperre) lat sich das prüefe.

Im Alarm cha d Art direkt **bstätigt** wärde; "Rückgängig" bliibt dernoo verfüegbar.

**Alarm-Pause:** Mindeschtabstand zwüsche Alarme vo dr gliiche Art (0 = jede Träffer alarmiert).

---

## 11. Artgruppe und Artelischte

De Artfilter wirkt pro Artgruppe: **Vögel, Heuschrecke, Amphibie, Säugetier** je **Aus / Alli / Auto / Lischte**; **Anderi** nume **Aus / Alli** (Standard Aus). "Auto" bruucht s Geomodell (Standort und BirdNET-Woche). Arte, wo s Geomodell nid kennt, sowie Gruppe ohni Geomodell-Maske, nehme d Begleitlischte vo de Gruppe (Vögel: CH-Lischte, Heuschrecke und Amphibie: CH-Lischte unde, Säugetier: alli). "Lischte" wählt eini vo de mitgliferete oder eigene Lischte. Mitgliferet isch d Lischte "CH Heuschrecke & Amphibie" (Date GBIF, CC BY 4.0).

**Auto ohni GPS:** Fählt en aktuelle Standort, gilt di zletscht gspicheret Position höchstens 30 Tag; danach nimmt "Auto" d Begleitlischte vo de Gruppe. E Hiiwis bim Start vo de Ufnahm weist druf hii, und unter Istellige > Arte & Alarm (Geomodell) steit d Grundlag ("Ohni GPS gilt di letscht Position vom ..." bzw. "Kei aktuelli Position: Es gilt d Begleitlischte vo de Gruppe"). D gspicheret Position wird nume bi emene Berechnig mit GPS-Fix aktualisiert. Optional ignoriert d **Jahresliischte** d Jahreszit (Schalter im Geomodell-Abschnitt).

Im Lischte-Editor lö sich Arte nach Ordnig, Familie und Gattig uswähle; "Artname wüsseschaftlich azeige" schaltet d Namesform um.

### Eigeni Artelischte

Eigeni Artelischte chönne als JSON-Dateie im Ordner `lists/` vom freigäbene PIROL-Ordner abgleit werde (`Downloads/PIROL/lists/*.json`). Sie erschiine als wählbari Lischte bi de Artgruppe (Option "Lischte"). D Lischte wärde bi **jedem Ufnahmstart nöi igläse**; Änderige gälte drum ab de nöchschte Ufnahm. Fählerhafti Lischte wärde üsgslah und mit Grund gmäldet. Als Vorlag dient "Biispiel-Lischte aalege" (`lists/beispiel_liste.json`).

Alti und nöii wüsseschaftlichi Name (z.B. nach Taxonomie-Änderige) wärde zämegfüert, so dass Lischte modälübergriifend funktioniere.

---

## 12. Upload

Istellige > Upload: Ufgnoo Sessione chönne nach de Ufnahm automatisch ufegladen werde.

| Ziel | Aagabe |
|------|---------|
| Uus | nume lokali Chopie, mänueller Export |
| Ordner | beliebige Ordner (Gerät, Drive, OneDrive ...) per Ordnerwahl |
| NAS (SMB / Windows-Freigab) | Host, Freigab, Pfad, Benutzer, Passwort |

S NAS-Passwort wird verschlüsslet uf em Gerät gspicheret. "Verbindig teste" prüeft d Aagabe. Optione: nume bi WLAN ufelade, lokali ZIP-Chopie nach em Upload nid bhalte. De Status vom letschte Upload stoht drunter.

---

## 13. Modelle

Istellige > Modell & Erkennig > Modell:

| Modell | Eigeschafte |
|--------|---------------|
| BirdNET 3.0 FP16 (empfohle) | Standard, 11'725 Klasse, 32 kHz, 3-s-Fänster |
| BirdNET 3.0 FP16 kompakt (pruned) | chliiner (ca. 70 MB), Erkennig praktisch gliich |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | elteri Vorabversion, 11'560 Klasse |

S aktivi Modell wird per Uswahl umgschaltet, ab de nöchschte Analyse. D Artezahl näbe em Modell stimmt nach em Wächsel sofort. Modelle chönne abegladen (abbreche und witerfahre megli), importiert und gläscht wärde. Jedi Session merkt sich s brucht Modell.

**Modelltabälle (ab v0.1.1):** D Spalte Primär (Radio) bestimmt s Hauptmodell. Sekundär (Häckli, nume im Experte-Modus) wählt s Zweitmodell, wo jedi Detäktion mitprüeft; abwählbar, nie glich wie s Primär. De Papierchorb lösche es installiertes Modell nach Bstätigung; s Primär lat sich nid lösche und isch während eir Ufnahm gsperret. Nid gladeni Modell zeige es Lade-Symbol mit Fortschritt und Abbreche i de Zile.

**Konfidänz-Schwälle:** De Rägler "Konfidänz-Schwälle (alli Arte)" gilt für alli Arte gliich (Standard 50 %). Watchlist-Alarm lö erscht ab dere Schwälle uus.

**Mindest-Konfidänz:** D Erkennigs-Schwälle isch global iistellbar. E Modell cha zuesätzlich en eigeni Mindest-Konfidänz mitbringe (z.B. 80 % für Perch). Es gilt de höcher Wert; s Modell zeigt en a.

### Eigeni und witeri Modelle importiere
PIROL isch nid uf BirdNET festgleit. Über "Anders Modell importiere..." wird es Paket us drü Dateie zäme gwählt: `.onnx` (Modell), `.pirol.json` (Profil) und Label-CSV. D App prüeft Prüfsummen, Iigäng und Usgäng und Labelzahl und meldet Fähler im Klartext. Abtastrate und Fänsterlänge si frei.

Gprüeft si **BirdNET 2.4** (6'522 Arte, 48 kHz, ungfähr halbi Rächezit vo 3.0), **Perch v2** (Google, 14'795 Klasse, 5-s-Fänster) und **Nighthawk 0.3.1**. D Dateie si nid Teil vo de App. Für d Umwandlig vo TensorFlow-, Keras- und TFLite-Modelle (au eigeni Trainings) lit s Skript `tools/convert_model.py` bi, lueg `tools/README_convert_model.md` und `tools/MODEL_PROFILE_FORMAT.md`.

**Lizänze:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 und Nighthawk nume nid-kommerziell (CC BY-NC bzw. BY-NC-SA). D Lizänz wird bim Import aazeigt.

**Hiiwis:** Perch arbeitet mit 32 kHz; Laubheuschrecke im Ultraschall si dermit nid hörbar.

**Zweitmodell-Statistik (Experte-Modus):** Under Istellige > Modell & Erkennig öffnet de Knopf "Zweitmodell-Statistik" es Dialog. PIROL liest dafür alli Sessione (nume d Originale, nume lesend; Rescan-Varianten und mänuelli Iiträg zähle nid) und zeigt e Chrüztabälle: Verifikation (bstätigt, abgläh, unsicher, offe, korrigiert/ersetzt) gäge s Ergäbnis vom Zweitmodell (Gprüeft, Nid prüefbar, Widersprüchlich, Kei Ergäbnis), gsamt und pro Zweitmodell. Dezue chömed Fortschrittsbalke zum Ziel (200 bstätigti und 50 abgläinti Detäktione mit Zweitmodell-Ergäbnis) und dr Aateil vo de Widersprüch bi bstätigte und bi abgläinte Detäktione. Das hilft Iischätze, ob s Zweitmodell als Prüefhilf taugt. Zuemache vom Dialog bricht s Iilese ab.

**Perch als Zweitmodell:** bruucht ca. 650 MB zuesätzlichen Speicher. D App warnt bim Iischalte, wenn s Gerät weniger als 6 GB RAM het. S Zweitmodell arbeitet mit em Rohaudio vo de Ufnahm, damit d 5-s-Fänster echti Umgäbig enthalte.

---

## 14. GPS

Istellige > Karte & GPS (bis v0.0.10: Ufnahm & Speicher):

| Istellig | Bedütig |
|-------------|-----------|
| GPS-Modus "Dauernd" | loufend Fixes im iigschtellte Intervall (2/5/10/20/60 s, Standard 10 s) |
| GPS-Modus "Nume bi Detäktion" | GPS nume bi ere Detäktion, mindeschtens 30 s zwüsche zwei Fixes; s Intervall gilt dänn nid. Schpart rund e Drittel Akku |
| Max. GPS-Ungenauigkeit | ungenauere Fixes wärde verworfe (Standard 50 m) |
| GPS-Smoothing | Median über 5 Fixes, glättet Usrüsser |

Jedi Detäktion erhaltet en frische Standort, au bi uusgschaltetem Bildschirm. Änderige wirke ab de nöchschte Ufnahm.

**Start-Fix:** Bim Programmstart und bim Zoome vo de Charte holt PIROL ohni laufendi Ufnahm eimal en Standort (kei Dauertracking).

**Route:** D Route ("Route ufzeichne", Standard aa) wird während de Ufnahm mitgschriebe (`track.jsonl`) und bim Stopp als `track.gpx` neben d Ufnahm gleit (Ziite i UTC).

**Gmäind:** De Knopf "Gmäind iifüege" treit d Gmäind us de Position i d Bemerkig ii (im Detail-Dialog, i de Session-Bemerkig und i de Analyse). D Gmäinddate (Schwiz) lige offline i de App. Usserhalb devo wird nüt ermittlet, usser de Experte-Schalter Istellige > Karte & GPS > Erwiitert > "Gmäind online ermittle (Google)" isch aa (Standard us; debii wärde d Koordinate a Google gschickt).

---

## 15. Energie und Akku

Istellige > Modell & Erkennig > Energieprofil:

| Profil | Analyse |
|--------|---------|
| Genau | jedes 3-s-Fänster |
| Balanciert | alli 6 s |
| Sparsam | alli 15 s, für lengi Fäldtäg |
| Ultra-Sparsam | alli 30 s, maximali Laufziit |

**Praxiswärt** (Tablet Galaxy Tab A11+, 6'840 mAh, Dauerufnahm, WLAN aa, BirdNET 3.0 FP16; Richtwärt, ca. ±15 %):

| Istellig | Verbruuch | Laufziit voll Akku |
|-------------|-----------|----------------------|
| Genau, Bildschirm us, GPS dauernd (10 s) | ca. 2.2 %/h | ca. 45 h |
| wie obe, 67 % Überlappig | ca. 3.5 %/h | ca. 28 h |
| wie obe, **Bildschirm aa** | ca. 9 %/h | ca. 12 h |
| Genau, GPS nume bi Detäktion | ca. 1.5 %/h | ca. 60 h |
| Sparsam (15 s), GPS 60 s | ca. 0.9 %/h | ca. 4-5 Tag |

**Tipps:**
1. **Bildschirm usschalte** isch de grösscht Hebel (meh als doppelt so viel wie Ufnahm und Analyse zäme). D Ufnahm louft im Dienscht witer.
2. GPS-Modus "Nume bi Detäktion" wähle.
3. Überlappig bi 0 % lah, bi lange Iistätze Profil "Sparsam".
4. Modellwahl spilt chuum e Rolle (pruned etwa wie final, Preview 3.1 ca. 10 % meh, FP32 nid meh als FP16).

---

## 16. Sprache

Istellige > Sprach: Sprach vo de App (Systemsprach oder Uswahl): Dütsch, Änglisch, Französisch, Italienisch, Katalanisch, Portugiesisch, Niederländisch, Bärndütsch. **Bärndütsch und Portugiesisch si Entwürf**, fählendi Texte erschiine uf Dütsch. D **Artename**-Sprach folgt de App-Sprach, cha aber separat gsetzt werde (23 Sprache). Artename erschiine durchgängig i de gwählte Sprach, o uf de Charte, im Raven und bi ältere Detäktione (si wärde bim Aazeige neu ufglöst, gspicheret Dateie blibe unverändert); umbenannti Arte findet PIROL o unger irem alte Name. Fählt en Name i de gwählte Sprach, nimmt PIROL bi Dütsch de dütsche Name us de mitgliferete Artelischte, bi andere Sprache zersch de änglisch Name.

---

## 17. Istellige im Überblick

D Istellige si i ufklappbari Sektione gglideret; de Zuestand wird gmerkt. Standardmässig offe isch nume "Sprach".

| Sektion | Inhalt |
|---------|--------|
| Sprach | App-Sprach, Artename-Sprach |
| Ufnahm | Audio-Info, Preroll, Überlappig, Energieprofil, Speicherort (Ordner, Tages-Unterordner, Auto-Lösche, Sessione verschiebe), Max. Ufnahmelänge und Uusschnittlänge, Resampler |
| Modell & Erkennig | Modelle (Download, Import, Lösche), Erkennigs-Schwälle (alli Arte), Alternativä ab, Max. Arte pro Fänster, Voriistellige, Artevorschläge, Zämefasse vo Detäktione, Ähnlichi Arte und Embedding-Limit |
| Arte & Alarm | Regionale Filter und eigeni Lischte, Watchlist, Alarm-Pause und -Usgab, Alarm teste, PIROL-Ordner, Saisonalität und Sälteheit |
| Aazeig | Artename-Sprach, Vollbild, Sonogramm (Modus, Voriistellig, Kontrast, Gamma, Deckel, Palette, Frequänz, Ziitfenster), kompakti Artcharte |
| Karte & GPS | GPS-Modus, Route ufzeichne, GPS-Intervall, Genauigkeit und Glättig; Experte (Erwiitert): "Gmäind online ermittle (Google)" |
| Export & Upload | Raven-Export-Felder (inkl. Memo), Datenschutz (menschlichi Stimme usblände, Mensch-Schwälle), Upload (Ziel Ordner/NAS, Passwort, Verbindigstest, nume WLAN) |
| Ähnlichi Arte & Referänze | Ähnlichkeitssuech, Zilezahl, Vergliichsufnahme (Kapitel 9) |
| Info | Version, Quelle, Was isch neu, Diagnose (Benchmark), "PIROL unterstütze" |

De Schalter **Experteistellige** (under "Iirichtig starte") blendet im Ii-fach-Modus alles Sälte us; im Experte-Modus isch alles sichtbar. Kei Istellig geit debii verlore. Sälteni Optione stönd im ufklappbare Unterblock "Erwiitert" vo de jeweilige Sektion (z.B. Hochpassfilter, Detäktions-Cluster, Phänologie, Max. Ufnahmelänge).

**PIROL unterstütze:** Under Istellige > Info öffnet dr Knopf "PIROL unterstütze" d Spändeseite im Browser. D Spände isch fräiwillig; PIROL blibt gratis und vollständig.

**Resampler:** Polyphase (empfohle) filtered Störgräusch über 16 kHz, wo sust als Gschpängschter erschiine chönne. "Linear" isch d alt Method. D Umschaltig zeigt en Hiiwis und wirkt ohni Neischtart vo de Ufnahm.

**Diagnose:** De Benchmark misst d Rächezit vom aktive Modell mit 1/2/4 Threads (1-3 Minute, nid während ere Ufnahm). Mit "ONNX-Datei wähle und messe" lat sich au es bliebigs Modell vor em Import teste.

---

## 18. Technischi Hiiwis

- **Ufnahm:** 48 kHz, 16-bit PCM, Mono (96 kHz im Fläderemuus-Modus). Erkennig uf 32 kHz umgrächnet (BirdNET 3.0).
- **Mindestgerät:** Android 8.0. Kompatibel mit 16-KB-Speicherseite (Android 15+).
- **Fairphone und andere Gerät:** D Abtastrate wird mit em Gerät ushandlet, bi Problem wiicht d App uf en anderen Audio-Träiber uus.
- **Datenformat:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven-TSV; Ziitstämpel i Ortszit mit Offset.

---

## 19. Änderige

D vollständig Lischte stoht i `CHANGELOG.md` im Projekt.

### Version 0.1.4
- De Chnopf Artkarte kompakt/usfüehrlich im Live-Tab blybt au während de Ufnahm sichtbar
- Import vo mehrere Dateie: de Fortschritt zeigt "Session i vo n"; e Meldig, wenn e Scan als Original übernoh wird
- Memo lösche zverlässiger: s Lösche wird au bim Verlah vo de Analyse ganz abgschlosse

### Version 0.1.3
- Session-Memos: lösche mit Rückgängig, Standort bim Drücke gspicheret und als Marker uf de Charte, teile und speichere unter (Kapitel 2, 6, 8)
- Import: Datums- und Ziitauswahl, Schalter "Dateiziit = Ände", Scan für alli importierte Sessione, e Scan wird Original, wenn s Original läär isch, mit Raven im Ordner (Kapitel 6)
- Datumsändrig vo ere importierte Session benennt de Ordner um und verschiebt en i de richtige Tagesordner (Kapitel 5)
- "+ Art" berücksichtigt Lücke zwüsche Segmänt (Kapitel 6)
- Spektrum: Linear (Standard) oder Mel, Gamma o im Spektrum, Ziitfenster im Oszillogramm (Kapitel 3)
- Analyse: Zoom mit zwei Finger, Upload meldet sis Ergäbnis mit Snackbar (Kapitel 6)
- Rescan schneller, Zweitmodell parallel (Kapitel 6)
- Auto-Filter ohni GPS: letschti Position bis 30 Tag, Hiiwis bim Start, Grundlag i de Istellige, Jahresliischte (Kapitel 11)
- Zweitmodell-Statistik im Experte-Modus (Kapitel 13)
- Artename durchgängig i de gwählte Sprach, dütschi Name us de Artelischte (Kapitel 16)
- Knopf "PIROL unterstütze" (fräiwillig) under Istellige > Info (Kapitel 17)
- Hilfemodus deckt meh Bedienelemänt ab, o Kartemarker und Dialog (Kapitel 1)
- Neus Kapitel 20: Installation und Update

### Version 0.1.2
- Aagaabe vo importierte Sessione (Startziit, Ort, Bemerkig) nachträglich bearbeite (Kapitel 5)
- "Speichere unter" als ZIP oder Ordner (Kapitel 6)
- Raven: eini Zile pro Episode, nöii Spalte `Cluster`, `Windows` und `Memo` (Kapitel 7)
- Rescan und Import: Ergäbnis loufend sichtbar und verifizierbar, bim Rescan bhaltet dr Abbruch s Teilergebnis, bim Import lösched er d halb importierti Session (Kapitel 6)
- Menschlichi Stimme wärde im Export usgblände, Standard aa; Rückfrag bi Modell ohni Mensch-Klass (Kapitel 7)
- Gmäind online über Google nume no als Option, Standard us (Kapitel 17)
- Session-Memo per gdrückt halte, Block "Memos" i de Analyse, Memo-Zile im Raven (Kapitel 2, 6, 7)
- Live-Artcharte ruehiger: Wächsel höchschtens alli 5 s, Träffer i de Top 5 blinke blau (Kapitel 2)
- Hilfe-Knopf "?": Hilfemodus und Aleitig offline (Kapitel 1)
- Aleitig korrigiert: Artfilter im Chartemenü (Kapitel 8), Istellige-Sektionsnamen (Kapitel 1, 5, 7, 17)

---

## 20. Installation und Update

**Weli APK?** Jedi Version gits als Release uf GitHub (Nikiphedia/Pirol, Bereich "Releases") mit je einer APK pro Prozessor-Architektur:
- `Pirol-<Version>-arm64-v8a-release.apk`: für fast alli aktuelle Telefone und Tablets. Im Zwiifel di Datei nää.
- `Pirol-<Version>-armeabi-v7a-release.apk`: nume für elteri 32-Bit-Gerät.

**Installation:**
1. Di passendi APK us em GitHub-Release uf s Gerät lade und öffne. Android fragt bim erschte Mal, ob dr brucht Browser oder Dateimanager Apps installiere dörf; das eimal erlaube.
2. **Samsung-Gerät:** D Funktion **"Automatische Sperre"** (Auto Blocker; Istellige > Sicherheit und Datenschutz) verhindert d Installation vo Apps usserhalb vom Store. Si für d Installation churz uusschalte, PIROL installiere und nachher wider aaschalte. Das gilt **bi jedem Update** wider.
3. Installiere antippe und d App öffne. Bim erschte Start füehrt dr Iirichtigs-Assistänt dür di Grundistellige (Kapitel 1).

**Update:** Di neu APK eifach über di bestehendi Installation installiere (gliichi Architektur; bi Samsung wider mit churz uusgschaltetere Automatischer Sperre). Sessione, Modelle, Lischte und Istellige bliibe erhalte; nüt muess deinstalliert werde. Hiiwis zu ältere Versione stönd im Kapitel 1 (Update vo ältere Versione). Nach em Update zeigt PIROL eimal "Was isch neu".

---

*PIROL v0.1.4 — ETA Systems GmbH, 5004 Aarau — Stand 2026-11-25*
