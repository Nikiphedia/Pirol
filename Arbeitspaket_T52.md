# Arbeitspaket: Code-Scan T52

## Ziel

Vollstaendiger Scan des PIROL-Quellcodes gegen die Konventionen in `CLAUDE.md` sowie Pruefung aller Imports und Abhaengigkeiten auf Konsistenz. Jeder Verstoss wird dokumentiert mit Datei, Zeile, Regel und Schweregrad.

## Kontext

- Projekt-Root: `C:\eta_Data\80002\PIROL`
- Quellcode: `app/src/main/kotlin/ch/etasystems/pirol/`
- Build-Config: `build.gradle.kts` (Root + App), `gradle/libs.versions.toml`
- Konventionen: `CLAUDE.md` im Projekt-Root
- Letzter Scan: T42 (8 Funde, 6 gefixt, 2 akzeptiert) — Stand P19, seither P20-P22 dazugekommen
- Seit T42 geaenderte/neue Dateien: T44 (6 Dateien), T48 (SecurePreferences), T49 (XenoCantoClient, XenoCantoModels), T51 (Autocomplete), T45 (TileSourceConfig, MapScreen, SettingsScreen, AppPreferences)

## Teil 1: Code-Konventionen

| # | Regel (aus CLAUDE.md) | Pruefen auf |
|---|----------------------|------------|
| 1 | Namenskonventionen | Packages lowercase, Klassen PascalCase, Funktionen camelCase, Konstanten SCREAMING_SNAKE, Dateien PascalCase.kt |
| 2 | Verbotene Patterns | `Thread.sleep()`, `GlobalScope`, hardcodierte Pfade, `any` ohne Kommentar |
| 3 | Sicherheit | API-Keys in Plain-Text SharedPreferences, Secrets in Log-Statements, HTTP statt HTTPS |
| 4 | Sprache | Code-Bezeichner Englisch, Kommentare Deutsch, UI-Strings Deutsch |
| 5 | Toter Code | Unbenutzte Imports, unerreichbare Funktionen, auskommentierter Code. Bekannt offen: `findCommonName()` |
| 6 | Datenformate | Artennamen mit Leerzeichen statt Unterstrich, Zeitstempel nicht ISO 8601 |
| 7 | Koin DI | Fehlende oder doppelte Registrierungen in `AppModule.kt` |
| 8 | Coroutines | `runBlocking` auf Main-Thread, fehlende structured concurrency |

## Teil 2: Abhaengigkeiten und Imports

| # | Pruefung | Detail |
|---|---------|--------|
| 9 | **Import-Aufloesung** | Jeder `import` in `.kt`-Dateien muss auf eine existierende Klasse/Funktion zeigen — entweder im eigenen Quellcode (`ch.etasystems.pirol.*`) oder in einer Bibliothek die in `build.gradle.kts` / `libs.versions.toml` deklariert ist |
| 10 | **Fehlende Gradle-Dependencies** | Werden Klassen/Packages verwendet die in keiner deklarierten Dependency enthalten sind? (z.B. Ktor ContentNegotiation ohne `ktor-serialization` Dependency) |
| 11 | **Phantom-Imports** | Imports die auf Projekt-interne Klassen zeigen die nicht existieren (falsche Package-Pfade, umbenannte/geloeschte Klassen) |
| 12 | **Versions-Konsistenz** | `libs.versions.toml` Versionen vs. tatsaechlich verwendete API-Features — werden APIs genutzt die erst in neueren Versionen existieren? |
| 13 | **Doppelte/widerspruechliche Dependencies** | Gleiche Bibliothek in verschiedenen Versionen, oder funktional redundante Bibliotheken |

## Scope-Ausschluss

- **Nichts fixen** — nur dokumentieren
- Keine Dateien unter `app/.cxx/`, `.claude/`, `.gradle/`, `build/`
- Keine Bewertung der Architektur oder Feature-Vollstaendigkeit
- Keine `res/`-Ressourcen

## Ergebnis-Format

Datei `Scan_T52.md` im Projekt-Root:

```
# Code-Scan T52 — [Datum]

## Zusammenfassung
- X Dateien gescannt
- Y Funde Teil 1 (Konventionen)
- Z Funde Teil 2 (Abhaengigkeiten)

## Teil 1: Konventions-Funde

| # | Datei:Zeile | Regel | Schweregrad | Beschreibung |
|---|-------------|-------|-------------|--------------|
| 1 | ... | ... | kritisch/minor | ... |

## Teil 2: Abhaengigkeits-Funde

| # | Datei:Zeile | Pruefung | Schweregrad | Beschreibung |
|---|-------------|---------|-------------|--------------|
| 1 | ... | ... | kritisch/minor | ... |

## Kein Verstoss gefunden
[Liste der Regeln/Pruefungen ohne Funde]
```

## Acceptance Criteria

1. Jede `.kt`-Datei unter `app/src/main/kotlin/ch/etasystems/pirol/` wurde geprueft
2. Alle 8 Konventions-Kriterien wurden angewendet
3. Alle 5 Abhaengigkeits-Pruefungen wurden durchgefuehrt (gegen `build.gradle.kts` + `libs.versions.toml`)
4. `Scan_T52.md` existiert im Projekt-Root
5. Kein Quellcode wurde veraendert
