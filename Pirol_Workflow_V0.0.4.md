Live‑Analyse & Interaktionslogik – Dokumentation
Überblick
Diese Datei beschreibt den technischen Ablauf der Live‑Audioanalyse, die UI‑Logik für Artenvorschläge sowie die Interaktionsmöglichkeiten für erfahrene Feldornithologen.
Die App nutzt BirdNET‑Analysen in 3‑Sekunden‑Chunks und kombiniert diese mit einer deduplizierten, sortierten Artenliste, die live aktualisiert wird.

1. Live‑Analyse‑Flow
Ablaufbeschreibung
- Die Audioaufnahme läuft.
- Jeder neue 3‑Sekunden‑Chunk wird:
- als Spektrogramm gerendert,
- an BirdNET gesendet,
- analysiert.
- BirdNET liefert eine Liste von Arten + Wahrscheinlichkeiten.
- Die App dedupliziert die Artenliste (pro Art nur ein Eintrag).
- Neue oder erneut erkannte Arten rücken an die erste Stelle.
- Die oberste Art erhält einen 5‑Sekunden‑Highlight‑Timer.
- Die UI aktualisiert die Liste in Echtzeit.
Flowchart
flowchart TD

A[Audioaufnahme läuft] --> B[Neuer 3s‑Chunk verfügbar]
B --> C[Spektrogramm rendern]
C --> D[Chunk an BirdNET senden]
D --> E{BirdNET liefert Arten?}

E -->|Nein| F[Keine Änderung an Liste]
E -->|Ja| G[Arten + Wahrscheinlichkeiten sortieren]

G --> H[Deduplizieren: pro Art nur 1 Eintrag]
H --> I{Art bereits in Liste?}

I -->|Nein| J[Art als neuer Eintrag hinzufügen]
I -->|Ja| K[Art nach oben verschieben]

J --> L[Highlight‑Timer 5s starten]
K --> L[Highlight‑Timer 5s neu starten]

L --> M[Art an erste Stelle setzen]
M --> N[UI aktualisieren]

N --> B



2. Interaktions‑Flow (Verifizieren, Fragezeichen, Abwählen, Alternativen)
Interaktionsmöglichkeiten pro Art
Jeder Eintrag in der Artenliste kann:
- verifiziert werden
- als unsicher (?) markiert werden
- abgewählt werden
- über ein Pfeilsymbol Alternativarten anzeigen
Alternativarten
Beim Klick auf das Pfeilsymbol:
- BirdNET‑Alternativen über einem Schwellwert (z. B. > 10 %) werden angezeigt.
- Nutzer kann eine Alternative auswählen.
- Die Alternative ersetzt die Hauptart und rückt an die erste Stelle.
- Highlight‑Timer startet neu.
Flowchart
flowchart TD

A[Art‑Eintrag ausgewählt] --> B{Aktion des Nutzers}

B -->|Verifizieren| C[Status = verifiziert]
C --> D[UI markiert Art grün]
D --> Z[Ende]

B -->|Fragezeichen| E[Status = unsicher]
E --> F[UI markiert Art mit ?]
F --> Z

B -->|Abwählen| G[Status = abgelehnt]
G --> H[Art aus Liste entfernen oder grau markieren]
H --> Z

B -->|Alternativen öffnen| I[BirdNET‑Alternativen anzeigen]

I --> J{Nutzer wählt Alternative?}

J -->|Nein| Z
J -->|Ja| K[Alternative auswählen]

K --> L[Alternative ersetzt Hauptart]
L --> M[Highlight‑Timer 5s starten]
M --> N[Vorherige Art → unsicher ODER entfernen]
N --> O[Alternative an erste Stelle setzen]
O --> Z



3. Zustandsmaschine für Arteneinträge
Jeder Art‑Eintrag kann folgende Zustände haben:
|  |  | 
|  |  | 
|  |  | 
|  |  | 
|  |  | 
|  |  | 
|  |  | 



4. Regeln für Sortierung & Highlighting
Sortierung
- Neu erkannte Art → immer ganz oben
- Bereits erkannte Art erneut erkannt → nach oben verschieben
- Keine Duplikate
Highlight‑Timer
- Jede neu oder erneut erkannte Art erhält 5 Sekunden Highlight
- Wird währenddessen eine andere Art erkannt:
- alter Timer stoppt
- neue Art rückt nach oben
- neuer Timer startet

5. Datenstruktur (Empfehlung)
interface SpeciesEntry {
  id: string;                // eindeutiger Art-Identifier
  name: string;              // deutscher/englischer Name
  probability: number;       // BirdNET-Wahrscheinlichkeit
  state: "neu" | "aktiv" | "verifiziert" | "unsicher" | "abgelehnt" | "ersetzt";
  lastDetected: number;      // Timestamp
  highlightUntil?: number;   // Timestamp für Highlight-Ende
  alternatives?: AlternativeEntry[];
}

interface AlternativeEntry {
  id: string;
  name: string;
  probability: number;
}



6. Zusammenfassung
Dieses Dokument definiert:
- den Live‑Analyse‑Ablauf
- die UI‑Logik für Artenvorschläge
- die Interaktionsmöglichkeiten
- die State‑Machine
- die Sortier‑ und Highlight‑Regeln
- eine empfohlene Datenstruktur
