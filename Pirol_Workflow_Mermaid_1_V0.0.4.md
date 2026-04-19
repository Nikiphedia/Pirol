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