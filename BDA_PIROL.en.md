# PIROL — User Manual

Real-time acoustic species recognition for Android, designed for field work.

*Applies from version v0.1.3 (status 2026-11-25). What has changed between versions is listed in `CHANGELOG.md`.*

The app has five tabs: **Live**, **Analysis**, **References**, **Map** and **Settings**. The **?** (Help) button is not a tab: one tap turns help mode on, a double-tap opens this manual (chapter 1, section "Help button").

---

## 1. First start

1. Open the app. The setup assistant guides you through language, level (beginner/expert), purpose of use, species list, model (incl. geomodel), permissions (microphone, GPS, notifications) and summary. The values are only applied when you tap "Done".
2. Choose a **species list** (chapter 11): the species groups (birds, grasshoppers, amphibians, mammals, other) filter the recognition to plausible species. You can change this later under Settings > Species & Alarm.
3. **Download a model**: several models can be selected at once. The default is BirdNET 3.0 FP16 (recommended, approx. 170 MB). The download continues in the background, can be cancelled and resumed later. Alternatively, select a model file manually.
4. **Check the storage location** (Settings > Recording). Default: `Downloads/PIROL/`, visible over USB.

**Installation (APK):** There is one release APK per processor architecture. For current devices (phones and tablets) install the file `Pirol-<Version>-arm64-v8a-release.apk`; only for older 32-bit devices use `...-armeabi-v7a-release.apk`. The APKs are therefore much smaller than the former shared APK. The app has a new icon (yellow bird), also available as a monochrome icon for themed icons. Details on installation and update (also Samsung devices) are in chapter 20.

**What's new:** After an update, PIROL shows a one-time "What's new" dialog with the most important changes of the version. It can be called up again at any time in Settings under "Info".

### Help button
The **?** button (tablet and narrow navigation: under Settings; phone: far right of the bar) offers two ways of use:
- **Tapping** switches **help mode** on or off. A bar "Help mode: touch a control for an explanation" appears at the top. A tap on a button or control then triggers nothing, but shows a short explanation, if needed with "More in the manual" (jumps to the matching chapter). To exit: X in the bar, Back, or **?** again.
- **Double-tapping** opens this manual offline, in the app language (if no translation exists, in German). A table of contents leads to the sections.

**Update from older versions:** From v0.0.8 PIROL is signed. Anyone who has installed v0.0.7 or older must uninstall once and secure the sessions beforehand via USB (`Android/data/ch.etasystems.pirol/files/PIROL/`). From v0.0.8 to v0.0.10 the update to v0.0.11 works without uninstalling; sessions and models are kept. Note for v0.0.11: the confidence threshold is now a common threshold for all species (chapters 2 and 13), check the slider afterwards. From v0.0.11 to v0.1.1 the update works without uninstalling. Afterwards check the species groups (chapter 11): old filters are set to birds Auto, the rest Off. Sessions from v0.0.6 and older are no longer specifically supported.

---

## 2. Live detection

**Start:** Press the record button in the Live tab. **Stop:** press and hold the button. The session and the Raven export are saved automatically.

Recording and analysis run in one service. They continue when the app goes to the background or the screen is switched off.

When stopping, the note "Long-press to stop" appears at the top (at the top so that your finger on the button does not cover it).

Recognised species appear as cards below the sonogram:
- Species name in the chosen language, scientific name as an addition
- Confidence in percent (thin bar), time stamp, GPS position
- Alternatives (expandable, tappable)
- Notes "Rare", "Atypical for the season", watchlist marking
- **Play:** the play button on the card plays the excerpt of the detection (3 s before, 5 s after), also during recording. A second tap stops playback.
- **Flash:** if a species is detected again, its card briefly flashes blue. The list does not jump in this case (see "Calm list").
- **Alarm bell:** in the menu of the full species card you can add the species permanently to the alarm list or for 24 h. 24-h alarms show a bell with the badge "24h"; after expiry it disappears automatically.
- **Compact button:** switches all species cards between compact and detailed. On the phone it sits in the status line, on the tablet in the header. The alternatives can be expanded and collapsed via the card header.

**Compact view on the phone:** In portrait and landscape the Live tab uses space sparingly: no title bar, the navigation consists of icons only, the button row of the cards is single-line. On narrow cards, Share, Reference, Compare and "Alternatives" are in the menu "More actions" (three-dot icon); on the tablet all buttons remain visible. In landscape, the sonogram and buttons are on the left, the species cards on the right. The panel "Similar species" is collapsed on the phone and can be expanded.

**Microphone muted during playback:** If PIROL plays a detection through the loudspeaker during recording, the microphone is muted so that the playback is not detected or recorded again. The note "Microphone muted during playback" appears in the Live tab. With headphones connected (cable, USB, Bluetooth) the microphone stays active.

| Button | State |
|--------|-------|
| green, pulsing | pre-roll buffer running (recording not yet started) |
| red, pulsing | recording running |

**Status line:** running time in mm:ss. **GPS bar:** current accuracy in ±m, red if there has been no valid fix for more than 30 s.

**Note:** For each recording a title can be entered. It appears in the session list, in the ZIP name and in the Raven export.

**Add manually:** Enter a species by hand (with count, atlas code, note). For each detection you can also record count, atlas code, note and a voice memo. A species can be locked (only one report per session).

**Merge:** Detections of the same species are merged into one entry spatially and temporally (radius and time window adjustable, Settings > Model & Detection).

### Calm list
The order of the species cards changes at most every 5 s. New species are collected and inserted together at the top (the very first card appears immediately); they can therefore become visible up to 5 s later, alarms still come immediately. If a species in the top 5 cards is detected again, it stays in place and flashes blue. A species further down moves up at the next change and flashes. Deleting, verifying and locked species take effect immediately. Detections, alarm, merging and analysis are not affected by this.

### Session memo
The memo button in the Live tab (visible only during a recording) works like a walkie-talkie: **press and hold while speaking**, release to end the memo. During recording the button is red and shows the running time; afterwards "Memo saved (n s)" appears. Memos shorter than 0.5 s are discarded ("Too short"); after 3 minutes a memo ends automatically. The memo is saved with its position in the recording (`memos.jsonl`, files under `memos/`), appears in the analysis in the block "Memos" (chapter 6) and as a "Memo" row in the Raven export (chapter 7). Memos are never hidden as voice (chapter 7). Voice memos for individual species remain separate from these.

When pressed, PIROL notes the last GPS fix (not older than 2 min) and stores it with the memo; on the map the memo then appears as a microphone marker (chapter 8). Without a current fix the memo has no location. Deleting, sharing and Save as: chapter 6.

### Multiple detection and confidence threshold
Within the same 3-s window all species that reach the threshold are detected, not only the top one. The slider is called "Confidence threshold (all species)" (Settings > Model & Detection, default 50 %) and applies equally to all species. Species below it, but above the candidate limit, remain as candidates in the detections of the window. Previously the slider controlled a different threshold (TopK, default 0.3); therefore check it after the update. Watchlist alarms only trigger from the threshold upwards.

### Secondary model (expert mode)
A secondary model (e.g. Perch v2 or BirdNET 2.4, chapter 13) analyses the same windows again. The card then shows, next to the confidence of the main model, the figure of the secondary model: in percent, or for Perch as a rank ("Perch #2"). Species that the secondary model does not know are marked as not checkable. The secondary model influences neither alarm nor filter nor merging. The threshold "Alternatives from" (5/10/20/30 %, Settings > Model & Detection) determines from which confidence alternatives are listed.

### Pre-roll
If activated, audio is saved and analysed **before** the recording start (length adjustable). The pre-roll sits seamlessly at the beginning of `recording.wav`. The sonogram shows at most 5 s of it.

### Overlap
Analysis windows can overlap: 0 % (default, a window every 3 s), 50 % (every 1.5 s) or 67 % (every second). More overlap detects short calls at window boundaries better, but needs more computing time and battery. If the device is too slow, PIROL reduces the overlap automatically and reports this once per session in a snackbar. For models with a longer window (Perch, 5 s) the slider "Max. step" (3, 4 or 5 s) limits the distance between two windows.

### Long recordings (segments)
After the set maximum duration (default 60 min, adjustable 10-80 min) a new file starts automatically (`recording-002.wav` etc.). The recording continues without interruption. Times in `detections.jsonl` and Raven refer to the session start. The WAV header is updated every 5 s: if the app is killed hard, at most approx. 3 s of audio is lost.

### High-pass filter and processing
The high-pass filter (200 Hz) filters out wind and footsteps, on by default. It only acts on the copy used for detection; the saved recording remains unchanged. Changes apply from the next recording.

---

## 3. Display: sonogram, spectrum, oscillogram

In the Live and Analysis tabs. The frequency axis shows 1/2/4/8/12 kHz. By default the display goes up to **12 kHz** (preset for birds), adjustable up to **24 kHz**. The levels of the sonogram are in dBFS.

**Display modes (Live tab):** In the display panel (long-press) you switch between **sonogram**, **spectrum** and **oscillogram**. The spectrum shows the loudness per frequency with a real FFT (dBFS, averaging, peak hold with fading peaks, cursor). In the spectrum panel the frequency axis can be switched between **Linear** (default, equal Hz spacing) and **Mel** (low frequencies stretched, as in the sonogram); grid and cursor readout follow the choice. The **Intensity (gamma)** slider also acts in the spectrum (curve height and dB grid) and is the same value as in the sonogram. The oscillogram shows the time course with a level display (peak/RMS), band-pass and a red marking for overload; its **time window** (5 to 30 s) is set in the oscillogram panel. A gamma slider does not belong to the oscillogram. While no data is available yet, each view shows its own notice ("Spectrum loading", "Oscillogram loading"). Frequency, dynamics and colour pattern apply jointly to all three views. In the analysis, spectrum and oscillogram are also available.

**Adjusting the display:** A long-press on the sonogram (Live or Analysis) opens the settings panel below the sonogram; the same controls are available under Settings > Display. The panel applies jointly to Live, Analysis and Settings. At the top of the panel "Done" stands on its own line; presets and colour palettes are tiles that wrap when space is short.

| Control | Effect |
|--------|---------|
| Preset | Standard / Quiet calls / Loud environment |
| Auto contrast | continuously adjusts the dB threshold to the signal; otherwise manual (range in dB) |
| Intensity (gamma) | smaller = quiet parts appear brighter |
| Brightness ceiling | limits strong signals |
| Colour palette | Magma, Viridis, greyscale, **Paper (light)** |
| Frequency | displayed range in kHz |
| Time window | seconds visible (5 to 30 s) |

**Paper (light):** a light palette like a classic paper sonogram, good in daylight. The palette is chosen in the panel (long-press on the sonogram); on the phone there is no separate button for it in the Live tab any more.

**Tab change:** If you leave the Live tab or switch the screen off, PIROL recalculates the sonogram on your return (up to the set time window), so that it continues without gaps. If you were away longer than the time window, it starts anew.

**Mode** (Settings > Display): birds, bat or broadband. The bat mode records at 96 kHz (ultrasound); there is no pre-roll in this case.

---

## 4. Verification

For each detection the following actions are available (Live tab, Analysis tab and Map):

| Action | Status | Display |
|--------|--------|---------|
| Confirm | Confirmed | green border |
| Uncertain (?) | Uncertain | orange border |
| Reject | Rejected | greyed out |
| Correct | Corrected | name replaced by your own entry |
| Choose alternative | Confirmed | The species of the detection is replaced by the alternative (same line, time and position); the old species remains as a candidate. Undo in the snackbar |

In the **Live tab** there is no longer a snackbar when verifying. After "Confirm" the card shows a green undo button instead; it resets the detection to "Uncertain". The buttons Confirm, Uncertain, Reject and Edit remain visible, so the status can be switched at any time. When choosing an **alternative**, moving on the map and confirming via the watchlist alarm, a snackbar with **Undo** still appears (at least 5 s). All actions are recorded in `verifications.jsonl`; rejected and replaced detections also remain in the export (audit trail).

**Share detection:** excerpt (3 s before, 5 s after, maximum length adjustable) as WAV, M4A (AAC), FLAC (if the device supports it) or sonogram PNG with labelling.

---

## 5. Sessions

Each recording creates a session folder, by default `Downloads/PIROL/YYYY-MM-DD/{sessionId}/`. The folder name carries the local time.

```
session.json              metadata (duration, location, GPS statistics, model used)
detections.jsonl          all detections
verifications.jsonl       all verification actions
detections.updates.jsonl  additional file during recording (merged into detections.jsonl when stopped)
recording.wav             recording (16-bit, mono, 48 kHz); for long sessions
recording-002.wav ...     further segments
recording.selections.txt  Raven Selection Table (automatic on stop)
memos.jsonl               session memos with their position in the recording (files under memos/)
human_windows.jsonl       list for hiding human voices (never exported)
```

- **Storage requirement:** approx. 5.5 MB per minute of recording.
- **Time stamps:** always local time with time-zone offset (e.g. `+02:00`).
- **Auto-delete:** short sessions without detection are deleted (minimum duration adjustable, 0 = off).
- **Delete** a session in the analysis: with confirmation.
- **Edit details of imported sessions:** For imported sessions the overflow menu in the header (three-dot icon) has the entry "Edit details": start time, location and note can be changed afterwards. A new start time shifts all times of the session (detections, verifications, variants, route) by the same difference; positions in the recording remain. If the **date** changes, PIROL also renames the session folder (new identifier with the new local time) and moves it into the matching day folder (if day subfolders are on). Start time and date are chosen with a date and time picker. Raven and GPX are rewritten. The location applies to the session and to all detections without their own GPS fix; points moved on the map remain. Not possible during a rescan; a date change is also not possible while an upload of this session is queued or running.

### Change storage location
Settings > Recording > Storage location: "Choose another folder" (SAF: internal storage, SD card, cloud folder). Optionally with daily subfolders. If the folder cannot be reached, PIROL saves in the app's fallback folder and reports this. Individual recordings can also be moved in the Analysis tab (chapter 6).

**Move sessions:** When changing the location PIROL asks whether existing sessions should be **copied** to the new location ("Copy") or whether only the location is changed ("Only change").

---

## 6. Analysis tab

- **Session list:** by date, with duration, size and note.
- **Open a session:** sonogram and detection list with time information. The detection currently being played is marked, a pointer shows the position.
- **Play:** per detection (excerpt with zoom) or the whole recording; segments are strung together seamlessly. Long recordings are streamed.
- **Multiple occurrences:** If a species was detected several times during the recording and merged into one card, the card shows the line "N occurrences", which can be expanded. Consecutive windows are combined into **episodes** ("N occurrences in M episodes"). Each episode is shown with start and end time, position in the recording ("@ m:ss") and duration ("N windows, M s") in one line; its play button plays the whole episode in one go (also for long continuous song). For individual windows only the time point is known, not the confidence.
- **Playback:** Only one entry ever plays. A second tap on the same button stops, a tap on another entry switches, and a tab change ends playback. This applies across the whole app (Live, Analysis, Map, References, voice memos).
- **Verify, correct, add species:** as in the Live tab, also afterwards.
- **Comparison:** detection and reference side by side, with synchronised playback.
- **Search:** the search icon in the session list filters by species name (partial word, German or scientific name). In an open recording the search filters the detection list.
- **Header and playback bar:** Back, date and identifier, Play with position display, Search, Raven and Upload. The upload reports its result in a snackbar ("Uploaded", on error with the reason, or that PIROL waits for a decision, chapter 7). With previous/next detection you jump between the entries.
- **Navigation:** tapping in the sonogram or oscillogram jumps to that position; if no playback is running, PIROL remembers the start position (cyan pointer) for the next Play.
- **Spectrum and oscillogram:** as in the Live tab (incl. Linear/Mel and gamma, chapter 3); in the oscillogram a pointer shows the playback position.
- **Two-finger zoom:** in the overview bar and the sonogram you zoom by pinching (two fingers). The section is at least 3 s (for shorter sessions the whole session) and at most the whole recording; zooming out to the whole recording clears the zoom. Tapping and dragging with one finger work as before.
- **+ Species:** takes the time from the playback position (+/-10 s). For sessions made of several files with gaps between the segments, PIROL calculates via the wall clock of the respective segment; the window stays inside the segment. The location can be determined via "Choose on map".
- **Overview:** an overview bar shows the detections over the whole recording; the Raven table lists them as a table.
- **Regenerate Raven file**, e.g. after later verification.
- **Share episodes:** each episode has its own share button; the excerpt of this episode is shared.
- **Memos:** if there are memos, the analysis shows below the sonogram and overview the expandable block "Memos (n)" (at most 200 dp high, scrollable) with play. It lists session memos and memos for species; for species memos without a known recording time it says "approx. ... (time of detection)". The menu of each memo offers **Share** and **Save as** (the WAV file unchanged, name `YYYYMMDDHHMMSS_Memo_Note.wav`, without hiding voices) and, for session memos, **Delete**: the memo disappears immediately, a snackbar offers **Undo** (10 s); only afterwards the file is deleted for good. In the recording currently running, deleting is locked ("Delete (recording running)").
- **Rescan:** the rescan button next to the magnifier (only if a recording exists) opens the choice of model, list or species groups, confidence threshold, overlap and secondary model. The rescan runs in the background as a foreground service with progress and cancel; the result appears as a chip next to the original ("date time · model"). The results appear **continuously**: the variant opens immediately, the detection list grows every few seconds and can already be verified during the scan (header "· running"). When cancelled, the partial result is kept ("cancelled at x min"); the same applies if the app is closed during the scan. The rescan has become faster: it uses more processor cores and the secondary model runs in parallel with the main model; the results are the same as before. Verifications already made are never overwritten. During a rescan no recording is possible and the running variant cannot be deleted. After importing audio files the scan uses the same path (see "Import audio").
- **Import audio:** The import button in the header of the session list takes over a PIROL session (ZIP or folder) or audio files (WAV, FLAC, MP3, M4A, OGG) as a new session. The start time is pre-filled (e.g. from an AudioMoth comment) and can be changed. For several files you choose: one session per file or all into one session (segments, sorted by time).
  - **Start time:** date and time are chosen with a date and time picker. With recorders the file time is often the end of the recording; the switch **"File time = end"** (default on) then calculates start = file time minus duration; switched off, the file time counts as the start. Below the field it says where the time comes from.
  - **Scan after the import:** PIROL then offers the scan (rescan dialog). If several files were imported as one session each, the choice applies to **all** new sessions, which are scanned one after the other. If the original of a session is still empty, a complete scan becomes the **original**: `detections.jsonl`, `verifications.jsonl` and the Raven file then lie directly in the session folder, without a manual step. This also applies if older scans already exist; when such a session is opened, PIROL adopts the newest complete scan. Further scans remain variants, cancelled ones are never adopted.
  - **Cancel:** If you cancel the import (or it fails), the session currently half imported is deleted; sessions already finished in a multiple import are kept. This differs from the rescan, which keeps its partial result. If you cancel the scan dialog, the waiting scans are discarded.
- **Save as:** in the overflow menu of the header. You choose **ZIP** or **Folder** and then the target (also SD card, USB or cloud folder). The content corresponds to the upload ZIP; for a folder a subfolder with the export name is created (chapter 7). A target in the PIROL storage or in the session itself is refused. For the running recording the entry is greyed out. Human voices are hidden depending on the setting (chapter 7).
- **Header:** date · municipality · note in one line; a tap on the note opens the dialog. The compact/large switch corresponds to that of the species card.

**Select several recordings:** A long-press on a recording in the session list starts selection mode; further recordings are selected by tapping. The header shows "N selected" with the actions "All", "Move" and "Delete"; the X or the Back key ends the mode. The recording currently running cannot be selected.
- **Move:** the target folder can be chosen freely (folder selection, also SD card or USB storage). Each session is copied with all its files (recording segments, memos), the file sizes are checked, afterwards the original is deleted. In case of an error or cancellation the original remains and the partial copy is removed. If a session with the same name already exists at the target, `-2`, `-3` etc. is appended. Progress is visible and can be cancelled. A target folder inside `Downloads/PIROL` (or the recording itself) is refused; in that case nothing is moved.
- **Delete:** after a query ("Delete N recordings?"); this cannot be undone. Deleting individual recordings via the bin icon remains as before.

---

## 7. Export

**Raven Selection Table** (`recording.selections.txt`): written automatically next to the WAV on stop, tab-separated, UTF-8. Opens in Cornell Raven, Audacity (as a label track) and Sonic Visualiser.

It contains, among others, Selection, Begin File (for segments the respective WAV), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (with offset), Species, Confidence, Status (with secondary model Second Rank). Which further columns are written is set under Settings > Export & Upload > Raven export fields. There is no KML export any more.

### Raven: one row per episode
Consecutive detections of a species form an **episode**; the table has one row per episode (no longer one per merged entry). A new row starts when the gap is larger than one analysis window. Confidence is the maximum of the episode, Begin File the segment of its beginning. Additional columns: `Cluster` (identifier of the first entry) and `Windows` (number of windows). The Raven table in the app shows the same rows.

### Raven: memos
Each session memo (chapter 2) appears as its own row with Species `Memo` at its position in the recording. The column `Memo` contains the path of the memo file (also for species memos); it can be switched off under Raven export fields. Memo rows also appear in the tables of rescan variants.

**ZIP package:** the session as a ZIP, for manual sharing or the automatic upload (chapter 12).

### Hiding human voices (privacy)
In **export copies** (Save as ZIP or folder, upload ZIP, sharing excerpts) PIROL replaces human voices with noise: where the model detects people, the speech band (approx. 300-3900 Hz) is replaced, with a 0.5 s margin; the rest remains unchanged. The original recording, detections and Raven are never altered. The list `human_windows.jsonl` is created live and during rescan; not yet evaluated sections (imported or old sessions) are scanned by PIROL during export (with progress, cancellable).
- **Setting:** Settings > Export & Upload > Privacy > "Hide human voices in export" (default on). When switching it off PIROL warns ("Stop hiding voices?"), because exports can then contain conversations of uninvolved persons. In expert mode "Human threshold" sets from which score hiding takes place (lower = more cautious).
- **Memos** always remain unedited; the time span of a live memo in the recording is exempt from hiding.
- **Model without human class:** If the model of a session does not know human voices, PIROL asks when saving, uploading and sharing: with BirdNET 3.0 search for humans (not during a recording, can take minutes), export without hiding (with a warning) or cancel. The automatic upload in this case uploads nothing unmasked, but waits for the decision.

**File names:** Shared detections are called `YYYYMMDDHHMMSS_Species_Note.ext` (e.g. `20260420143005_Blackbird_Garden_back.wav`), ZIP packages `YYYYMMDDHHMMSS_Note.zip` (time = session start). If the note is missing, that part is omitted; spaces and special characters become `_`.

---

## 8. Map

Georeferenced detections on a map. The **map menu** opens by swiping from the left edge or via the menu icon at the top left ("Open map menu"). It contains display, map source, offline maps and the species filter.

- **Map source:** Automatic (swisstopo in Switzerland, OpenStreetMap elsewhere), OpenStreetMap, swisstopo map, swisstopo aerial image.
- **Offline:** For swisstopo maps the visible section can be downloaded ("Download area", with the number of tiles and size). A cancellation keeps tiles already loaded; a new start only loads what is missing. "Delete offline maps" removes all stored tiles.
- **Display:** all sessions, "Live only" or "Last 3". Rejected observations are hidden. Overlapping markers are grouped depending on the zoom. The map starts at your own location.
- **Tap a point:** A tap on a point opens a menu with Verify (Confirm, Uncertain, Reject), "Edit", "Move position" and Play (or "Stop playback"). A long-press opens the same menu and additionally allows linking observations as a simultaneous observation. The excerpt of the detection is played from the session recording; if it is missing, a message appears.
- **Memo markers:** session memos with a position (chapter 2) appear as microphone markers. A tap opens a dialog with the time and play. They are visible for "All sessions" and "Last 3", not for "Live only".
- **Several points on top of each other (cluster):** A tap on a cluster shows the list of detections. Each entry has Verify, Play and "Move position".
- **Move position:** The map shows at the top the note "Move map: crosshair onto the new position of ..., then Apply." Move the map so that the crosshair in the centre lies on the new position, then tap "Apply" (or "Cancel") at the bottom. Afterwards a snackbar with **Undo** appears. Moving also works for live points; if no session is running, the new position applies only until the app is closed.
- **Species filter:** In the map menu is the species filter: "Alert species only" or hide individual species (All/None). The filter applies until the app is closed; routes remain visible.
- **Route:** A route recorded during the recording appears as a line (in mode "Only on detection" dashed, only at detection points). See chapter 14.
- **Location button:** centres the map on your own position and zooms so that the visible map height is about 200 m.
- **Zoom buttons:** The +/- buttons are 50 % larger and sit above the location button.

---

## 9. References and similar species

Verified detections can be saved as a reference ("Save as reference") and removed again. The References tab shows the saved recordings; an audio import is also possible. New detections are compared with the references via embedding.

Settings > Similar species & References: similarity search on/off, number of displayed rows (0 = off, 0.5 = ticker of the last 5, 1-10 = list), upper limit of saved comparison recordings.

---

## 10. Watchlist and alarm

**Watchlist:** target species that trigger an alarm. Management under Settings > Watchlist & Alarm: search for a species and add it, remove it, or load it from `watchlist.json`. Upper/lower case and spaces play no role in the matching.

**Shared folder:** Under "Share PIROL folder" choose `Downloads/PIROL` once. PIROL then reads `watchlist.json` and the folder `lists/` automatically (details in chapter 11). "Create example list" creates a template.

**Alarm output** (each can be switched on/off individually): vibration, notification, full-screen notice. On the **lock screen** the full-screen alarm appears only if "Full-screen notifications" are allowed for PIROL in the Android settings (button "Open permission"). Otherwise it remains a notification. With "Test alarm" (after 5 s, lock the device) this can be checked.

In the alarm the species can be **confirmed** directly; "Undo" remains available afterwards.

**Alarm pause:** minimum interval between alarms of the same species (0 = every hit raises an alarm).

---

## 11. Species groups and species lists

The species filter acts per species group: **Birds, grasshoppers, amphibians, mammals** each **Off / All / Auto / List**; **Other** only **Off / All** (default Off). "Auto" uses the geomodel (location and BirdNET week). Species that the geomodel does not know, as well as groups without a geomodel mask, use the group's companion list (birds: CH list, grasshoppers and amphibians: CH list below, mammals: all). "List" selects one of the supplied or own lists. Supplied is the list "CH grasshoppers & amphibians" (data GBIF, CC BY 4.0).

**Auto without GPS:** If there is no current location, the last stored position applies for at most 30 days; after that "Auto" uses the group's companion list. A notice at the start of the recording points this out, and Settings > Species & alarm (geomodel) shows the basis ("Without GPS the last position from ... applies" or "No current position: the group's companion list applies"). The stored position is only updated by a calculation with a GPS fix. Optionally the **year list** ignores the season (switch in the geomodel section).

In the list editor species can be selected by order, family and genus; "Show scientific species names" switches the name form.

### Own species lists

Own species lists can be stored as JSON files in the folder `lists/` of the shared PIROL folder (`Downloads/PIROL/lists/*.json`). They appear as selectable lists under the species groups (option "List"). The lists are **read again at every recording start**; changes therefore apply from the next recording. Faulty lists are skipped and reported with the reason. "Create example list" (`lists/beispiel_liste.json`) serves as a template.

Old and new scientific names (e.g. after taxonomy changes) are merged, so that lists work across models.

---

## 12. Upload

Settings > Upload: recorded sessions can be uploaded automatically after the recording.

| Target | Details |
|------|---------|
| Off | local copy only, manual export |
| Folder | any folder (device, Drive, OneDrive ...) via folder selection |
| NAS (SMB / Windows share) | host, share, path, user, password |

The NAS password is stored encrypted on the device. "Test connection" checks the details. Options: upload only over Wi-Fi, do not keep the local ZIP copy after the upload. The status of the last upload is shown below.

---

## 13. Models

Settings > Model & Detection > Model:

| Model | Properties |
|--------|---------------|
| BirdNET 3.0 FP16 (recommended) | default, 11'725 classes, 32 kHz, 3-s window |
| BirdNET 3.0 FP16 compact (pruned) | smaller (approx. 70 MB), practically the same detection |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | older preview version, 11'560 classes |

The active model is switched by selection, from the next analysis. The species count next to the model updates immediately after the switch. Models can be downloaded (cancel and resume possible), imported and deleted. Each session remembers the model used.

**Model table (from v0.1.1):** The column Primary (radio) determines the main model. Secondary (tick, only in expert mode) selects the secondary model that checks each detection; can be deselected, never equal to the primary. The bin deletes an installed model after confirmation; the primary cannot be deleted and is locked during a recording. Models not yet loaded show a loading symbol with progress and cancel in the row.

**Confidence threshold:** The slider "Confidence threshold (all species)" applies equally to all species (default 50 %). Watchlist alarms only trigger from this threshold.

**Minimum confidence:** The detection threshold is globally adjustable. A model can additionally bring its own minimum confidence (e.g. 80 % for Perch). The higher value applies; the model displays it.

### Import own and further models
PIROL is not tied to BirdNET. Via "Import other model..." a package of three files is selected together: `.onnx` (model), `.pirol.json` (profile) and label CSV. The app checks checksums, inputs/outputs and label count and reports errors in plain text. Sample rate and window length are free.

Tested are **BirdNET 2.4** (6'522 species, 48 kHz, roughly half the computing time of 3.0), **Perch v2** (Google, 14'795 classes, 5-s window) and **Nighthawk 0.3.1**. The files are not part of the app. For converting TensorFlow, Keras and TFLite models (also own trainings) the script `tools/convert_model.py` is included, see `tools/README_convert_model.md` and `tools/MODEL_PROFILE_FORMAT.md`.

**Licences:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 and Nighthawk only non-commercial (CC BY-NC or BY-NC-SA). The licence is displayed on import.

**Note:** Perch works at 32 kHz; bush crickets (katydids) calling in the ultrasound range are therefore not audible.

**Secondary model statistics (expert mode):** In Settings > Model & Detection the button "Secondary model statistics" opens a dialog. PIROL reads all sessions for it (originals only, read only; rescan variants and manual entries do not count) and shows a cross table: verification (confirmed, rejected, uncertain, open, corrected/replaced) against the result of the secondary model (Checked, Not checkable, Disagrees, No result), in total and per secondary model. In addition there are progress bars towards the target (200 confirmed and 50 rejected detections with a secondary model result) and the share of disagreements for confirmed and for rejected detections. This helps to judge whether the secondary model is useful as a checking aid. Closing the dialog cancels the reading.

**Perch as secondary model:** needs approx. 650 MB of additional storage. The app warns when switching it on if the device has less than 6 GB RAM. The secondary model works with the raw audio of the recording, so that the 5-s windows contain real surroundings.

---

## 14. GPS

Settings > Map & GPS (up to v0.0.10: Recording & Storage):

| Setting | Meaning |
|-------------|-----------|
| GPS mode "Continuous" | ongoing fixes at the set interval (2/5/10/20/60 s, default 10 s) |
| GPS mode "Only on detection" | GPS only on a detection, at least 30 s between two fixes; the interval then does not apply. Saves roughly a third of the battery |
| Max. GPS inaccuracy | less accurate fixes are discarded (default 50 m) |
| GPS smoothing | median over 5 fixes, smooths outliers |

Each detection receives a fresh location, also with the screen switched off. Changes take effect from the next recording.

**Start fix:** At app start and when zooming the map, PIROL fetches a location once without a running recording (no continuous tracking).

**Route:** The route ("Record route", default on) is written during the recording (`track.jsonl`) and placed as `track.gpx` next to the recording on stop (times in UTC).

**Municipality:** The button "Insert municipality" enters the municipality from the position into the note (in the detail dialog, in the session note and in the analysis). The municipality data (Switzerland) lie offline in the app. Outside this nothing is determined, unless the expert switch Settings > Map & GPS > Advanced > "Determine municipality online (Google)" is on (default off; in that case the coordinates are sent to Google).

---

## 15. Energy and battery

Settings > Model & Detection > Energy profile:

| Profile | Analysis |
|--------|--------|
| Accurate | every 3-s window |
| Balanced | every 6 s |
| Economical | every 15 s, for long field days |
| Ultra-economical | every 30 s, maximum running time |

**Practical values** (tablet Galaxy Tab A11+, 6'840 mAh, continuous recording, Wi-Fi on, BirdNET 3.0 FP16; guide values, approx. ±15 %):

| Setting | Consumption | Running time on full battery |
|-------------|-----------|----------------------|
| Accurate, screen off, GPS continuous (10 s) | approx. 2.2 %/h | approx. 45 h |
| as above, 67 % overlap | approx. 3.5 %/h | approx. 28 h |
| as above, **screen on** | approx. 9 %/h | approx. 12 h |
| Accurate, GPS only on detection | approx. 1.5 %/h | approx. 60 h |
| Economical (15 s), GPS 60 s | approx. 0.9 %/h | approx. 4-5 days |

**Tips:**
1. **Switching the screen off** is the biggest lever (more than twice as much as recording and analysis together). The recording continues in the service.
2. Choose GPS mode "Only on detection".
3. Leave overlap at 0 %; for long deployments use profile "Economical".
4. The choice of model hardly matters (pruned roughly like final, Preview 3.1 approx. 10 % more, FP32 no more than FP16).

---

## 16. Languages

Settings > Language: language of the app (system language or selection): German, English, French, Italian, Catalan, Portuguese, Dutch, Bernese German. **Bernese German and Portuguese are drafts**; missing texts appear in German. The **species name** language follows the app language but can be set separately (23 languages). Species names appear consistently in the chosen language, also on the map, in Raven and for older detections (they are resolved again when displayed, stored files remain unchanged); renamed species are also found under their old name. If a name is missing in the chosen language, PIROL uses for German the German name from the supplied species lists, for other languages the English name first.

---

## 17. Settings at a glance

The settings are divided into collapsible sections; the state is remembered. By default only "Language" is open.

| Section | Content |
|---------|---------|
| Language | app language, species name language |
| Recording | audio info, pre-roll, overlap, energy profile, storage location (folder, daily subfolder, auto-delete, move sessions), max. recording length and excerpt length, resampler |
| Model & Detection | models (download, import, delete), detection threshold (all species), alternatives from, max. species per window, presets, species suggestions, merging of detections, similar species and embedding limit |
| Species & Alarm | regional filter and own lists, watchlist, alarm pause and output, test alarm, PIROL folder, seasonality and rarity |
| Display | species name language, full screen, sonogram (mode, preset, contrast, gamma, ceiling, palette, frequency, time window), compact species card |
| Map & GPS | GPS mode, record route, GPS interval, accuracy and smoothing; expert (Advanced): "Determine municipality online (Google)" |
| Export & Upload | Raven export fields (incl. memo), privacy (hide human voices, human threshold), upload (target folder/NAS, password, connection test, Wi-Fi only) |
| Similar species & References | similarity search, number of rows, comparison recordings (chapter 9) |
| Info | version, sources, What's new, diagnostics (benchmark), "Support PIROL" |

The switch **Expert settings** (under "Start setup") hides everything rare in the simple mode; in expert mode everything is visible. No setting is lost in the process. Rare options are in the collapsible sub-block "Advanced" of the respective section (e.g. high-pass filter, detection clusters, phenology, max. recording length).

**Support PIROL:** In Settings > Info the button "Support PIROL" opens the donation page in the browser. The donation is voluntary; PIROL stays free and complete.

**Resampler:** Polyphase (recommended) filters out interference above 16 kHz, which could otherwise appear as ghost signals. "Linear" is the old method. The switch shows a note and takes effect without restarting the recording.

**Diagnostics:** The benchmark measures the computing time of the active model with 1/2/4 threads (1-3 minutes, not during a recording). With "Choose ONNX file and measure" any model can also be tested before import.

---

## 18. Technical notes

- **Recording:** 48 kHz, 16-bit PCM, mono (96 kHz in bat mode). Detection converted to 32 kHz (BirdNET 3.0).
- **Minimum device:** Android 8.0. Compatible with 16 KB memory pages (Android 15+).
- **Fairphone and other devices:** The sample rate is negotiated with the device; on problems the app falls back to another audio driver.
- **Data formats:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven TSV; time stamps in local time with offset.

---

## 19. Changes

The complete list is in `CHANGELOG.md` in the project.

### Version 0.1.3
- Session memos: delete with undo, location stored when pressed and shown as a marker on the map, share and Save as (chapters 2, 6, 8)
- Import: date and time picker, "File time = end" switch, scan for all imported sessions, a scan becomes the original if the original is empty, with Raven in the folder (chapter 6)
- Changing the date of an imported session renames the folder and moves it into the right day folder (chapter 5)
- "+ Species" takes gaps between segments into account (chapter 6)
- Spectrum: Linear (default) or Mel, gamma also in the spectrum, time window in the oscillogram (chapter 3)
- Analysis: two-finger zoom, the upload reports its result in a snackbar (chapter 6)
- Rescan faster, secondary model in parallel (chapter 6)
- Auto filter without GPS: last position up to 30 days, notice at start, basis in the settings, year list (chapter 11)
- Secondary model statistics in expert mode (chapter 13)
- Species names consistently in the chosen language, German names from the species lists (chapter 16)
- Button "Support PIROL" (voluntary) in Settings > Info (chapter 17)
- Help mode covers more controls, also map markers and dialogs (chapter 1)
- New chapter 20: Installation and update

### Version 0.1.2
- Edit details of imported sessions (start time, location, note) afterwards (chapter 5)
- "Save as" as ZIP or folder (chapter 6)
- Raven: one row per episode, new columns `Cluster`, `Windows` and `Memo` (chapters 6, 7)
- Rescan and import: results visible and verifiable continuously; for the rescan cancelling keeps the partial result, for the import it deletes the half-imported session (chapter 6)
- Human voices are hidden in the export, default on; query for a model without human class (chapter 7)
- Municipality online via Google only as an option, default off (chapter 17)
- Session memo by press-and-hold, "Memos" block in the analysis, memo row in Raven (chapters 2, 6, 7)
- Calmer live species cards: changes at most every 5 s, hits in the top 5 flash blue (chapter 2)
- Help button "?": help mode and manual offline (chapter 1)
- Manual corrected: species filter in the map menu (chapter 8), settings section names (chapters 1, 5, 7, 17)

---

## 20. Installation and update

**Which APK?** Every version is available as a release on GitHub (Nikiphedia/Pirol, section "Releases") with one APK per processor architecture:
- `Pirol-<Version>-arm64-v8a-release.apk`: for almost all current phones and tablets. If in doubt, take this file.
- `Pirol-<Version>-armeabi-v7a-release.apk`: only for older 32-bit devices.

**Installation:**
1. Download the matching APK from the GitHub release to the device and open it. The first time, Android asks whether the browser or file manager used may install apps; allow this once.
2. **Samsung devices:** The function **"Auto Blocker"** (Settings > Security and privacy) prevents installing apps from outside the store. Switch it off briefly for the installation, install PIROL and switch it on again afterwards. This applies **again with every update**.
3. Tap Install and open the app. On first start the setup assistant leads through the basic settings (chapter 1).

**Update:** Simply install the new APK over the existing installation (same architecture; on Samsung again with Auto Blocker briefly off). Sessions, models, lists and settings are kept; nothing has to be uninstalled. Notes on older versions are in chapter 1 (Update from older versions). After the update PIROL shows "What's new" once.

---

*PIROL v0.1.3 — ETA Systems GmbH, 5004 Aarau — status 2026-11-25*
