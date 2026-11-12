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