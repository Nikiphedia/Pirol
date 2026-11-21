# Mitmachen bei PIROL

Danke, dass du PIROL besser machen willst! Am meisten helfen Rückmeldungen aus dem Feld.

## Fehler melden und Wünsche äussern

Bitte als [Issue](https://github.com/Nikiphedia/Pirol/issues/new/choose) mit der passenden Vorlage:

- **Fehler:** Was hast du gemacht, was ist passiert, was hättest du erwartet? Dazu Gerät, Android-Version und
  PIROL-Version (Einstellungen → Info). Screenshots helfen sehr.
- **Fehlerkennung einer Art:** Art, erkannte Art, Konfidenz, Ort/Datum. Wenn möglich die Session oder einen kurzen
  Audioausschnitt anhängen.
- **Wunsch:** Was möchtest du im Feld erreichen? Der Zweck ist wichtiger als der Lösungsvorschlag.

Sicherheitslücken bitte **nicht** als Issue, sondern wie in [SECURITY.md](SECURITY.md) beschrieben melden.

## Code beitragen

Code-Beiträge sind willkommen, aber **nur nach Absprache**: Eröffne zuerst ein Issue und beschreibe, was du ändern
möchtest. Pull Requests ohne vorherige Absprache werden in der Regel geschlossen, weil die App einem festen Plan folgt.

Wenn ein Pull Request abgesprochen ist:

- Ein Thema pro Pull Request, kleine und nachvollziehbare Änderungen.
- `./gradlew testDebugUnitTest assembleDebug` muss grün sein.
- Code-Bezeichner auf Englisch, Kommentare auf Deutsch. Neue UI-Texte in `res/values/strings.xml` (Deutsch) und
  `res/values-en/strings.xml` (Englisch).
- Datenformate (`detections.jsonl`, `verifications.jsonl`, Raven-Export, `session.json`) nur additiv ändern.

Mit einem Beitrag erklärst du dich einverstanden, dass er unter der [GPL-3.0](LICENSE) veröffentlicht wird.

## Verhalten

Für alle Beiträge gilt der [Verhaltenskodex](CODE_OF_CONDUCT.md).
