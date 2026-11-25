# PIROL — Mode d'emploi

Reconnaissance acoustique des espèces en temps réel pour Android, conçue pour le travail sur le terrain.

*Valable à partir de la version v0.1.3 (état 2026-11-25). Les changements entre les versions figurent dans `CHANGELOG.md`.*

L'application comporte cinq onglets : **Live**, **Analyse**, **Références**, **Carte** et **Réglages**. Le bouton **?** (Aide) n'est pas un onglet : un appui active le mode aide, un double appui ouvre ce mode d'emploi (chapitre 1, section « Bouton d'aide »).

---

## 1. Premier démarrage

1. Ouvrir l'application. L'assistant de configuration guide à travers la langue, le niveau (débutant/expert), l'usage, la liste d'espèces, le modèle (y compris le géomodèle), les autorisations (microphone, GPS, notifications) et le récapitulatif. Les valeurs ne sont appliquées qu'à « Terminer ».
2. Choisir la **liste d'espèces** (chapitre 11) : les groupes d'espèces (Oiseaux, Orthoptères, Amphibiens, Mammifères, Autres) filtrent la détection sur des espèces plausibles. Modifiable plus tard dans Réglages > Espèces et alarme.
3. Télécharger le **modèle** : plusieurs modèles peuvent être sélectionnés en même temps. Le standard est BirdNET 3.0 FP16 (recommandé, env. 170 Mo). Le téléchargement se poursuit en arrière-plan, peut être interrompu et repris plus tard. Il est aussi possible de choisir manuellement un fichier de modèle.
4. Vérifier l'**emplacement de stockage** (Réglages > Enregistrement). Par défaut : `Downloads/PIROL/`, visible par USB.

**Installation (APK) :** La version APK de publication existe pour chaque architecture de processeur. Pour les appareils récents (téléphones et tablettes), installer le fichier `Pirol-<Version>-arm64-v8a-release.apk` ; seulement pour les anciens appareils 32 bits, le fichier `...-armeabi-v7a-release.apk`. Les APK sont ainsi nettement plus petites qu'autrefois avec l'APK commune. L'application a une nouvelle icône (oiseau jaune), également disponible en icône monochrome pour les icônes thématiques. Les détails sur l'installation et la mise à jour (y compris les appareils Samsung) figurent au chapitre 20.

**Nouveautés :** Après une mise à jour, PIROL affiche une seule fois une fenêtre « Nouveautés » avec les principaux changements de la version. Elle peut être rappelée à tout moment dans les réglages, sous « Infos ».

### Bouton d'aide
Le bouton **?** (tablette et navigation étroite : sous Réglages ; téléphone : tout à droite de la barre) offre deux modes :
- **Appui simple** active ou désactive le **mode aide**. En haut apparaît la barre « Mode aide : touchez un bouton pour une explication ». Un appui sur un bouton ou un réglage n'exécute alors rien, mais affiche une brève explication, si nécessaire avec « Plus dans le mode d'emploi » (ouvre le chapitre correspondant). Pour quitter : X dans la barre, Retour ou à nouveau **?**.
- **Double appui** ouvre ce mode d'emploi hors ligne, dans la langue de l'application (en l'absence de traduction, en allemand). Une table des matières mène aux sections.

**Mise à jour depuis les anciennes versions :** Depuis la v0.0.8, PIROL est signée. Qui a installé la v0.0.7 ou une version antérieure doit une fois désinstaller et auparavant sauvegarder les sessions par USB (`Android/data/ch.etasystems.pirol/files/PIROL/`). De la v0.0.8 à la v0.0.10 vers la v0.0.11, la mise à jour se fait sans désinstallation ; sessions et modèles sont conservés. Attention avec la v0.0.11 : le seuil de confiance est désormais un seuil commun à toutes les espèces (chapitres 2 et 13), vérifier ensuite le réglage. De la v0.0.11 à la v0.1.1, la mise à jour se fait sans désinstallation. Ensuite, vérifier les groupes d'espèces (chapitre 11) : les anciens filtres sont réglés sur Oiseaux Auto, le reste sur Arrêt. Les sessions de la v0.0.6 et antérieures ne sont plus prises en charge spécifiquement.

---

## 2. Détection en direct

**Démarrage :** appuyer sur le bouton d'enregistrement dans l'onglet Live. **Arrêt :** appui long sur le bouton. La session et l'export Raven sont enregistrés automatiquement.

L'enregistrement et l'analyse fonctionnent dans un même service. Ils se poursuivent lorsque l'application passe en arrière-plan ou que l'écran s'éteint.

Lors de l'arrêt, l'indication « Appui long pour arrêter » apparaît en haut (en haut pour que le doigt posé sur le bouton ne la masque pas).

Les espèces détectées apparaissent sous forme de fiches sous le sonogramme :
- Nom de l'espèce dans la langue choisie, nom scientifique en complément
- Confiance en pourcentage (barre fine), horodatage, position GPS
- Alternatives (dépliables, sélectionnables)
- Mentions « Rare », « Hors saison », marquage de la watchlist
- **Lecture :** le bouton Lecture sur la fiche joue l'extrait de la détection (3 s avant, 5 s après), y compris pendant l'enregistrement. Un second appui arrête la lecture.
- **Clignotement :** si une espèce est détectée à nouveau, sa fiche clignote brièvement en bleu. La liste ne saute pas à cette occasion (voir « Liste calme »).
- **Cloche d'alarme :** dans le menu de la fiche complète, on inscrit l'espèce durablement dans la liste d'alarme ou pour 24 h. Les alarmes de 24 h affichent une cloche avec le badge « 24h » ; à l'expiration, elle disparaît automatiquement.
- **Bouton compact :** bascule toutes les fiches entre compact et détaillé. Sur le téléphone, il se trouve dans la barre d'état, sur la tablette dans l'en-tête. Les alternatives se déplient et se replient via l'en-tête de la fiche.

**Vue compacte sur téléphone :** en format portrait comme en paysage, l'onglet Live utilise l'espace avec parcimonie : pas de barre de titre, la navigation n'affiche que des icônes, la rangée de boutons des fiches tient sur une ligne. Sur les fiches étroites, Partager, Référence, Comparer et « Alternatives » se trouvent dans le menu « Autres actions » (icône à trois points) ; sur la tablette, tous les boutons restent visibles. En paysage, le sonogramme et les boutons sont à gauche, les fiches d'espèces à droite. Le panneau « Espèces similaires » est replié sur le téléphone et se déplie.

**Microphone coupé pendant la lecture :** si PIROL joue une détection par le haut-parleur pendant l'enregistrement, le microphone est coupé afin que la lecture ne soit pas détectée ni enregistrée à nouveau. L'onglet Live affiche « Microphone coupé pendant la lecture ». Avec un casque branché (câble, USB, Bluetooth), le microphone reste actif.

| Bouton | État |
|--------|------|
| vert, pulsant | tampon de préroll actif (enregistrement pas encore démarré) |
| rouge, pulsant | enregistrement en cours |

**Ligne d'état :** durée en mm:ss. **Barre GPS :** précision actuelle en ±m, rouge si plus de 30 s sans fix valide.

**Remarque :** pour chaque enregistrement, un titre peut être saisi. Il apparaît dans la liste des sessions, dans le nom du ZIP et dans l'export Raven.

**Ajout manuel :** saisir une espèce à la main (avec nombre d'individus, code atlas, remarque). Pour chaque détection, on peut en outre saisir le nombre, le code atlas, une remarque et un mémo vocal. Une espèce peut être verrouillée (une seule annonce par session).

**Regroupement :** les détections d'une même espèce sont regroupées dans l'espace et dans le temps en une seule entrée (rayon et fenêtre temporelle réglables, Réglages > Modèle et détection).

### Liste calme
L'ordre des fiches d'espèces ne change au plus que toutes les 5 s. Les nouvelles espèces sont collectées puis insérées ensemble en haut (la toute première fiche apparaît immédiatement) ; elles peuvent donc devenir visibles jusqu'à 5 s plus tard, les alarmes arrivent toujours immédiatement. Si une espèce est redétectée dans les 5 premières fiches, elle reste en place et clignote en bleu. Une espèce plus bas remonte au prochain changement et clignote. Les suppressions, les vérifications et les espèces verrouillées agissent immédiatement. Les détections, l'alarme, le regroupement et l'analyse ne sont pas concernés.

### Mémo de session
Le bouton Mémo de l'onglet Live (visible uniquement pendant un enregistrement) fonctionne comme un talkie-walkie : **maintenir appuyé et parler**, le mémo se termine au relâchement. Pendant l'enregistrement, le bouton est rouge et affiche la durée ; ensuite apparaît « Mémo enregistré (n s) ». Les mémos de moins de 0,5 s sont rejetés (« Trop court »), après 3 minutes un mémo se termine automatiquement. Le mémo est enregistré avec sa position dans l'enregistrement (`memos.jsonl`, fichiers sous `memos/`), apparaît dans l'analyse dans le bloc « Mémos » (chapitre 6) et comme ligne « Memo » dans l'export Raven (chapitre 7). Les mémos ne sont jamais masqués comme voix (chapitre 7). Les mémos vocaux relatifs à une espèce restent séparés.

Au moment de l'appui, PIROL mémorise la dernière position GPS (pas plus de 2 min) et l'enregistre avec le mémo ; sur la carte, le mémo apparaît alors comme marqueur de microphone (chapitre 8). Sans position GPS récente, le mémo n'a pas de lieu. Supprimer, partager et enregistrer sous : chapitre 6.

### Détections multiples et seuil de confiance
Dans la même fenêtre de 3 s, toutes les espèces qui atteignent le seuil sont détectées, pas seulement la première. Le réglage s'appelle « Seuil de confiance (toutes les espèces) » (Réglages > Modèle et détection, par défaut 50 %) et s'applique de la même façon à toutes les espèces. Les espèces situées en dessous, mais au-dessus de la limite des candidats, restent comme candidates dans les détections de la fenêtre. Auparavant, le réglage pilotait un autre seuil (TopK, par défaut 0,3) ; il faut donc le vérifier après la mise à jour. Les alarmes de la watchlist ne se déclenchent qu'à partir du seuil.

### Deuxième modèle (mode expert)
Un deuxième modèle (p. ex. Perch v2 ou BirdNET 2.4, chapitre 13) recalcule les mêmes fenêtres. La fiche affiche alors, à côté de la confiance du modèle principal, la valeur du deuxième modèle : en pourcentage, pour Perch sous forme de rang (« Perch #2 »). Les espèces inconnues du deuxième modèle sont marquées comme non vérifiables. Le deuxième modèle n'influence ni l'alarme, ni les filtres, ni le regroupement. Le seuil « Alternatives à partir de » (5/10/20/30 %, Réglages > Modèle et détection) détermine à partir de quelle confiance les alternatives sont listées.

### Préroll
Si elle est activée, le son est enregistré **avant** le démarrage de l'enregistrement (durée réglable) et également analysé. Le préroll se trouve sans interruption au début de `recording.wav`. Le sonogramme n'en montre au maximum que 5 s.

### Chevauchement
Les fenêtres d'analyse peuvent se chevaucher : 0 % (par défaut, une fenêtre toutes les 3 s), 50 % (toutes les 1,5 s) ou 67 % (chaque seconde). Plus de chevauchement permet de mieux détecter les cris courts aux limites de fenêtres, mais demande plus de temps de calcul et d'autonomie. Si l'appareil est trop lent, PIROL réduit automatiquement le chevauchement et l'indique une fois par session dans une barre. Pour les modèles à fenêtre plus longue (Perch, 5 s), le réglage « Pas max. » (3, 4 ou 5 s) limite l'écart entre deux fenêtres.

### Longs enregistrements (segments)
Après la durée maximale réglée (par défaut 60 min, réglable de 10 à 80 min), un nouveau fichier commence automatiquement (`recording-002.wav`, etc.). L'enregistrement se poursuit sans interruption. Les indications de temps dans `detections.jsonl` et Raven sont comptées depuis le début de la session. L'en-tête WAV est mis à jour toutes les 5 s : en cas d'arrêt brutal de l'application, au plus env. 3 s d'audio sont perdus.

### Filtre passe-haut et préparation
Le filtre passe-haut (200 Hz) élimine le vent et les bruits de pas ; il est activé par défaut. Il n'agit que sur la copie utilisée pour la détection ; l'enregistrement sauvegardé reste inchangé. Les modifications s'appliquent à partir du prochain enregistrement.

---

## 3. Affichage : sonogramme, spectre, oscillogramme

Dans l'onglet Live et l'onglet Analyse. L'axe des fréquences affiche 1/2/4/8/12 kHz. L'affichage va par défaut jusqu'à **12 kHz** (préréglage pour les oiseaux), réglable jusqu'à **24 kHz**. Les niveaux du sonogramme sont exprimés en dBFS.

**Modes d'affichage (onglet Live) :** dans le panneau d'affichage (appui long), on bascule entre **Sonogramme**, **Spectre** et **Oscillogramme**. Le spectre montre le volume par fréquence avec une vraie FFT (dBFS, moyennage, maintien des crêtes avec des pics qui s'estompent, curseur). L'axe des fréquences du spectre se règle entre **Linéaire** (par défaut, intervalles de Hz égaux) et **Mel** (basses fréquences dilatées, comme dans le sonogramme) ; le quadrillage et l'affichage du curseur suivent ce choix. Le réglage **Intensité (gamma)** agit aussi sur le spectre (hauteur des courbes et grille en dB) et correspond à la même valeur que dans le sonogramme. L'oscillogramme montre l'évolution dans le temps avec l'indication de niveau (crête/RMS), le passe-bande et un marquage rouge en cas de saturation ; sa **fenêtre temporelle** (5 à 30 s) se règle dans le panneau de l'oscillogramme. Un gamma ne fait pas partie de l'oscillogramme. Tant qu'il n'y a pas encore de données, chaque vue affiche son propre message (« Spectre en cours de chargement », « Oscillogramme en cours de chargement »). La fréquence, la dynamique et les palettes de couleurs valent pour les trois vues. Dans l'analyse, le spectre et l'oscillogramme sont également disponibles.

**Adapter l'affichage :** un appui long sur le sonogramme (Live ou Analyse) ouvre le panneau de réglages sous le sonogramme ; les mêmes réglages se trouvent dans Réglages > Affichage. Le panneau vaut pour Live, Analyse et Réglages. En haut du panneau, « Terminé » figure sur une ligne séparée ; les préréglages et les palettes de couleurs sont des tuiles qui passent à la ligne si la place manque.

| Réglage | Effet |
|---------|-------|
| Préréglage | Standard / Appels faibles / Environnement bruyant |
| Contraste auto | adapte en continu le seuil en dB au signal ; sinon à la main (plage en dB) |
| Intensité (gamma) | plus petit = parties faibles plus claires |
| Plafond de luminosité | limite les signaux forts |
| Palette de couleurs | Magma, Viridis, Niveaux de gris, **Papier (clair)** |
| Fréquence | plage affichée en kHz |
| Fenêtre temporelle | secondes visibles (5 à 30 s) |

**Papier (clair) :** palette claire, comme un sonagramme papier classique, adaptée à la lumière du jour. La palette se choisit dans le panneau (appui long sur le sonogramme) ; sur le téléphone, il n'existe plus de bouton dédié dans l'onglet Live.

**Changement d'onglet :** si l'on quitte l'onglet Live ou éteint l'écran, PIROL recalcule le sonogramme au retour (jusqu'à la fenêtre temporelle réglée), afin qu'il se poursuive sans interruption. Si l'absence a duré plus longtemps que la fenêtre temporelle, il recommence.

**Mode** (Réglages > Affichage) : Oiseaux, Chauve-souris ou Large bande. Le mode chauve-souris enregistre à 96 kHz (ultrasons) ; il n'y a alors pas de préroll.

---

## 4. Vérification

Pour chaque détection, les actions suivantes sont disponibles (onglet Live, onglet Analyse et Carte) :

| Action | Statut | Présentation |
|--------|--------|--------------|
| Confirmer | Confirmé | bord vert |
| Incertain (?) | Incertain | bord orange |
| Rejeter | Rejeté | grisé |
| Corriger | Corrigé | nom remplacé par votre saisie |
| Choisir une alternative | Confirmé | L'espèce de la détection est remplacée par l'alternative (même ligne, même heure, même position) ; l'ancienne espèce reste comme candidate. Annulation dans la barre |

Dans l'**onglet Live**, la vérification n'affiche plus de barre. Après « Confirmer », la fiche montre à la place un bouton vert « Annuler » ; il rétablit la détection sur « Incertain ». Les boutons Confirmer, Incertain, Rejeter et Modifier restent visibles, le statut peut donc être changé à tout moment. Lors du choix d'une **alternative**, du déplacement sur la carte et de la confirmation via l'alarme de la watchlist, une barre avec **Annuler** (au moins 5 s) apparaît toujours. Toutes les actions sont consignées dans `verifications.jsonl` ; les détections rejetées et remplacées restent également dans l'export (piste d'audit).

**Partager une détection :** extrait (3 s avant, 5 s après, longueur maximale réglable) en WAV, M4A (AAC), FLAC (si l'appareil le permet) ou image PNG du sonogramme avec légende.

---

## 5. Sessions

Chaque enregistrement crée un dossier de session, par défaut `Downloads/PIROL/AAAA-MM-JJ/{sessionId}/`. Le nom du dossier contient l'heure locale.

```
session.json              métadonnées (durée, lieu, statistiques GPS, modèle utilisé)
detections.jsonl          toutes les détections
verifications.jsonl       toutes les actions de vérification
detections.updates.jsonl  fichier complémentaire pendant l'enregistrement (fusionné dans detections.jsonl à l'arrêt)
recording.wav             enregistrement (16 bits, mono, 48 kHz) ; pour les longues sessions
recording-002.wav ...     segments suivants
recording.selections.txt  Raven Selection Table (automatique à l'arrêt)
memos.jsonl               mémos de session avec leur position dans l'enregistrement (fichiers sous memos/)
human_windows.jsonl       liste de travail pour masquer les voix humaines (jamais exportée)
```

- **Place requise :** env. 5,5 Mo par minute d'enregistrement.
- **Horodatages :** toujours heure locale avec décalage horaire (p. ex. `+02:00`).
- **Suppression automatique :** les sessions courtes sans détection sont supprimées (durée minimale réglable, 0 = désactivé).
- **Supprimer** une session dans l'analyse : avec confirmation.
- **Modifier les données d'une session importée :** pour les sessions importées, le menu de débordement de l'en-tête (icône à trois points) contient l'entrée « Modifier les données » : heure de début, lieu et remarque peuvent être changés après coup. Une nouvelle heure de début décale toutes les heures de la session (détections, vérifications, variantes, parcours) de la même différence ; les positions dans l'enregistrement restent inchangées. Si la **date** change, PIROL renomme aussi le dossier de session (nouvel identifiant avec la nouvelle heure locale) et le déplace dans le dossier journalier correspondant (si les sous-dossiers journaliers sont activés). L'heure et la date de début se choisissent avec les sélecteurs de date et d'heure. Raven et GPX sont réécrits. Le lieu vaut pour la session et toutes les détections sans propre fix GPS ; les points déplacés sur la carte restent. Impossible pendant un rescan ; un changement de date n'est pas possible non plus tant qu'un envoi de cette session est en file d'attente ou en cours.

### Changer l'emplacement de stockage
Réglages > Enregistrement > Emplacement de stockage : « Choisir un autre dossier » (SAF : stockage interne, carte SD, dossier cloud). En option avec des sous-dossiers par jour. Si le dossier est inaccessible, PIROL enregistre dans le dossier de secours de l'application et le signale. Des enregistrements individuels peuvent aussi être déplacés dans l'onglet Analyse (chapitre 6).

**Déplacer des sessions :** lors du changement, PIROL demande si les sessions existantes doivent être **copiées** vers le nouvel emplacement (« Copier ») ou si seul l'emplacement doit changer (« Changer seulement »).

---

## 6. Onglet Analyse

- **Liste des sessions :** par date, avec durée, taille et remarque.
- **Ouvrir une session :** sonogramme et liste des détections avec indication de l'heure. La détection en cours de lecture est marquée, un curseur indique la position.
- **Lecture :** par détection (extrait avec zoom) ou l'enregistrement entier ; les segments s'enchaînent sans coupure. Les longs enregistrements sont diffusés en flux.
- **Plusieurs occurrences :** si une espèce a été détectée plusieurs fois au cours de l'enregistrement et regroupée en une seule fiche, la fiche affiche la ligne « N occurrences », dépliable. Les fenêtres successives sont regroupées en **épisodes** (« N occurrences en M épisodes »). Chaque épisode figure sur une ligne avec heure de début et de fin, position dans l'enregistrement (« @ m:ss ») et durée (« N fenêtres, M s ») ; son bouton Lecture joue l'épisode entier d'un seul tenant (y compris pour un chant continu long). Pour les fenêtres isolées, seul le moment est connu, pas la confiance.
- **Lecture :** une seule entrée est lue à la fois. Un second appui sur le même bouton arrête, un appui sur une autre entrée change, et un changement d'onglet met fin à la lecture. Cela vaut pour toute l'application (Live, Analyse, Carte, Références, mémos vocaux).
- **Vérifier, corriger, ajouter une espèce :** comme dans l'onglet Live, aussi après coup.
- **Comparaison :** détection et référence côte à côte, avec lecture synchronisée.
- **Recherche :** l'icône de loupe dans la liste des sessions filtre par nom d'espèce (partie de mot, nom local ou scientifique). Dans une session ouverte, la recherche filtre la liste des détections.
- **En-tête et barre de lecture :** retour, date et identifiant, lecture avec indication de position, recherche, Raven et envoi. L'envoi signale son résultat dans une barre (« Envoyé », en cas d'erreur avec la cause, ou lorsque PIROL attend une décision, chapitre 7). Avec « détection précédente/suivante », on passe d'une entrée à l'autre.
- **Navigation :** un appui sur le sonogramme ou l'oscillogramme saute à cet endroit ; si aucune lecture n'est en cours, PIROL mémorise la position de départ (curseur cyan) pour la prochaine lecture.
- **Spectre et oscillogramme :** comme dans l'onglet Live (y compris Linéaire/Mel et gamma, chapitre 3) ; dans l'oscillogramme, un curseur indique la position de lecture.
- **Zoom à deux doigts :** dans la vue d'ensemble et le sonogramme, on zoome par pincement (deux doigts). L'extrait fait au moins 3 s (pour les sessions plus courtes, toute la session) et au plus l'enregistrement entier ; en dézoomant jusqu'à l'enregistrement entier, le zoom est annulé. Taper et glisser avec un doigt fonctionnent comme avant.
- **+ Espèce :** reprend l'heure de la position de lecture (+/-10 s). Pour les sessions composées de plusieurs fichiers avec des lacunes entre les segments, PIROL calcule à partir de l'horloge murale du segment concerné ; la fenêtre reste à l'intérieur du segment. Le lieu peut être déterminé via « Choisir sur la carte ».
- **Vue d'ensemble :** une barre de vue d'ensemble montre les détections sur tout l'enregistrement ; le tableau Raven les liste sous forme de tableau.
- **Recréer le fichier Raven**, par exemple après une vérification ultérieure.
- **Partager les épisodes :** chaque épisode a son propre bouton de partage ; c'est l'extrait de cet épisode qui est partagé.
- **Mémos :** s'il existe des mémos, l'analyse affiche sous le sonogramme et la vue d'ensemble le bloc dépliable « Mémos (n) » (200 dp de hauteur maximale, défilable) avec lecture. Il liste les mémos de session et les mémos d'espèces ; pour les mémos d'espèces sans heure d'enregistrement connue, il indique « env. ... (heure de la détection) ». Le menu de chaque mémo propose **Partager** et **Enregistrer sous** (fichier WAV inchangé, nom `AAAAMMJJHHMMSS_Memo_Remarque.wav`, sans masquage des voix) et, pour les mémos de session, **Supprimer** : le mémo disparaît aussitôt, une barre propose **Annuler** (10 s) ; ce n'est qu'ensuite que le fichier est supprimé définitivement. Pendant l'enregistrement en cours, la suppression est bloquée (« Supprimer (enregistrement en cours) »).
- **Rescan :** le bouton Rescan à côté de la loupe (uniquement si un enregistrement existe) ouvre le choix du modèle, de la liste ou des groupes d'espèces, du seuil de confiance, du chevauchement et du deuxième modèle. Le rescan s'exécute en arrière-plan comme service de premier plan, avec progression et possibilité d'annulation ; le résultat apparaît comme puce à côté de l'original (« Date Heure · Modèle »). Les résultats apparaissent **en continu** : la variante s'ouvre immédiatement, la liste des détections s'allonge toutes les quelques secondes et peut être vérifiée pendant l'analyse (en-tête « · en cours »). En cas d'annulation, le résultat partiel est conservé (« interrompu à x min ») ; il en va de même si l'application est fermée pendant l'analyse. Le rescan est devenu plus rapide : il utilise davantage de cœurs de calcul, et le deuxième modèle tourne en parallèle du modèle principal ; les résultats sont les mêmes qu'avant. Les vérifications déjà faites ne sont jamais écrasées. Pendant un rescan, aucun enregistrement n'est possible et la variante en cours ne peut pas être supprimée. Après l'import de fichiers audio, le scan emprunte la même voie (voir « Importer de l'audio »).
- **Importer de l'audio :** le bouton Importer dans l'en-tête de la liste des sessions reprend une session PIROL (ZIP ou dossier) ou des fichiers audio (WAV, FLAC, MP3, M4A, OGG) comme nouvelle session. L'heure de début est préremplie (p. ex. d'après un commentaire AudioMoth) et reste modifiable. Pour plusieurs fichiers, on choisit : une session par fichier ou toutes dans une seule session (segments, triés par heure).
  - **Heure de début :** on choisit la date et l'heure avec les sélecteurs de date et d'heure. L'heure du fichier correspond souvent à la fin de l'enregistrement ; l'interrupteur **« Heure du fichier = fin de l'enregistrement »** (par défaut activé) calcule alors début = heure du fichier moins la durée ; désactivé, l'heure du fichier vaut comme début. Sous le champ figure la source de l'heure.
  - **Scan après l'import :** ensuite PIROL propose le scan (boîte de dialogue Rescan). Pour plusieurs fichiers importés chacun comme session propre, le choix vaut pour **toutes** les nouvelles sessions, scannées les unes après les autres. Le premier scan complet d'une session importée sans détections devient l'**original** : `detections.jsonl`, `verifications.jsonl` et le fichier Raven se trouvent alors directement dans le dossier de session, sans étape manuelle. Les scans suivants restent des variantes.
  - **Annulation :** si l'import est annulé (ou échoue), la session à moitié importée est supprimée ; les sessions déjà terminées d'un import multiple restent. Contrairement au rescan, qui conserve son résultat partiel. Si l'on annule la boîte de dialogue du scan, les scans en attente sont abandonnés.
- **Enregistrer sous :** dans le menu de débordement de l'en-tête. On choisit **ZIP** ou **Dossier**, puis la destination (aussi carte SD, USB ou dossier cloud). Le contenu correspond au ZIP d'envoi ; pour un dossier, un sous-dossier portant le nom d'export est créé (chapitre 7). Une destination dans l'espace PIROL ou dans la session elle-même est refusée. Pour l'enregistrement en cours, l'entrée est grisée. Les voix humaines sont masquées selon le réglage (chapitre 7).
- **En-tête :** date · commune · remarque sur une ligne ; un appui sur la remarque ouvre la boîte de dialogue. Le sélecteur Compact/Grand correspond à celui de la fiche d'espèce.

**Sélectionner plusieurs enregistrements :** un appui long sur un enregistrement de la liste lance le mode sélection ; les autres enregistrements se choisissent par appui. L'en-tête affiche « N sélectionnés » avec les actions « Tout », « Déplacer » et « Supprimer » ; la croix ou la touche Retour quitte le mode. L'enregistrement en cours ne peut pas être sélectionné.
- **Déplacer :** le dossier de destination se choisit librement (sélection de dossier, y compris carte SD ou stockage USB). Chaque session est copiée avec tous ses fichiers (segments d'enregistrement, mémos) ; les tailles de fichiers sont vérifiées, puis l'original est supprimé. En cas d'erreur ou d'annulation, l'original est conservé et la copie partielle supprimée. Si une session du même nom existe déjà à la destination, `-2`, `-3` etc. sont ajoutés. La progression est visible et peut être annulée. Une destination à l'intérieur de `Downloads/PIROL` (ou l'enregistrement lui-même) est refusée ; dans ce cas, rien n'est déplacé.
- **Supprimer :** après demande de confirmation (« Supprimer N enregistrements ? ») ; l'opération est irréversible. La suppression d'enregistrements individuels via l'icône de corbeille reste inchangée.

---

## 7. Export

**Raven Selection Table** (`recording.selections.txt`) : écrit automatiquement à côté du WAV à l'arrêt, séparateur tabulation, UTF-8. S'ouvre dans Cornell Raven, Audacity (comme piste d'étiquettes) et Sonic Visualiser.

Contiennent notamment : Selection, Begin File (pour les segments, le WAV correspondant), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (avec décalage horaire), Species, Confidence, Status (avec deuxième modèle : Second Rank). Les colonnes supplémentaires se règlent sous Réglages > Export et envoi > Champs d'export Raven. L'export KML n'existe plus.

### Raven : une ligne par épisode
Les détections successives d'une même espèce forment un **épisode** ; le tableau compte une ligne par épisode (et non plus par entrée regroupée). Une nouvelle ligne commence lorsque l'écart est plus grand qu'une fenêtre d'analyse. La Confidence est le maximum de l'épisode, le Begin File le segment de son début. Colonnes supplémentaires : `Cluster` (identifiant de la première entrée) et `Windows` (nombre de fenêtres). Le tableau Raven de l'application montre les mêmes lignes.

### Raven : mémos
Chaque mémo de session (chapitre 2) apparaît comme ligne distincte avec Species `Memo` à sa position dans l'enregistrement. La colonne `Memo` contient le chemin du fichier mémo (également pour les mémos d'espèces) ; elle peut être désactivée sous Champs d'export Raven. Les lignes de mémo figurent aussi dans les tableaux des variantes de rescan.

**Paquet ZIP :** la session en ZIP, pour un partage manuel ou l'envoi automatique (chapitre 12).

### Masquer les voix humaines (protection des données)
PIROL remplace dans les **copies d'export** (Enregistrer sous en ZIP ou en dossier, ZIP d'envoi, partage d'extraits) les voix humaines par du bruit : là où le modèle reconnaît des personnes, la bande vocale (env. 300-3900 Hz) est remplacée, avec une marge de 0,5 s ; le reste reste inchangé. L'enregistrement original, les détections et Raven ne sont jamais modifiés. La liste `human_windows.jsonl` est créée en direct et lors du rescan ; les zones non encore évaluées (sessions importées ou anciennes) sont analysées par PIROL lors de l'export (avec progression, annulable).
- **Réglage :** Réglages > Export et envoi > Protection des données > « Masquer les voix humaines dans l'export » (par défaut activé). Lors de la désactivation, PIROL avertit (« Ne plus masquer les voix ? »), car les exports peuvent alors contenir des conversations de personnes non concernées. En mode expert, « Seuil humain » définit à partir de quel score le masquage a lieu (plus bas = plus prudent).
- **Mémos :** restent toujours non modifiés ; la période d'un mémo en direct dans l'enregistrement est exclue du masquage.
- **Modèle sans classe humaine :** si le modèle d'une session ne connaît pas les voix humaines, PIROL demande lors de l'enregistrement, de l'envoi et du partage : avec BirdNET 3.0, rechercher des personnes (pas pendant un enregistrement, cela peut prendre des minutes), exporter sans masquage (avec avertissement) ou annuler. Dans ce cas, l'envoi automatique ne télécharge rien non masqué et attend la décision.

**Noms de fichiers :** les détections partagées s'appellent `AAAAMMJJHHMMSS_Espèce_Remarque.ext` (p. ex. `20260420143005_Merle_noir_jardin_arriere.wav`), les paquets ZIP `AAAAMMJJHHMMSS_Remarque.zip` (heure = début de session). Sans remarque, cette partie est omise ; les espaces et caractères spéciaux deviennent `_`.

---

## 8. Carte

Détections géoréférencées sur une carte. Le **menu de la carte** s'ouvre par glissement depuis le bord gauche ou via l'icône de menu en haut à gauche (« Ouvrir le menu de la carte »). Il contient l'affichage, la source de carte, les cartes hors ligne et le filtre d'espèces.

- **Source de carte :** Automatique (swisstopo en Suisse, OpenStreetMap à l'étranger), OpenStreetMap, Carte swisstopo, Image aérienne swisstopo.
- **Hors ligne :** pour les cartes swisstopo, la portion visible peut être téléchargée (« Télécharger le secteur », avec indication du nombre de tuiles et de la taille). Une interruption conserve les tuiles déjà chargées ; un nouveau lancement ne charge que les manquantes. « Supprimer les cartes hors ligne » efface toutes les tuiles enregistrées.
- **Affichage :** toutes les sessions, « Live seulement » ou « 3 dernières ». Les observations rejetées sont masquées. Les marqueurs qui se chevauchent sont regroupés selon le zoom. La carte démarre sur votre position.
- **Toucher un point :** un appui sur un point ouvre un menu avec Vérifier (Confirmer, Incertain, Rejeter), « Modifier », « Déplacer la position » et Lecture (Lire ou « Arrêter la lecture »). Un appui long ouvre le même menu et permet en plus de relier des observations en observation simultanée. Est lu l'extrait de la détection issu de l'enregistrement de la session ; s'il manque, un message apparaît.
- **Marqueur de mémo :** les mémos de session avec position (chapitre 2) apparaissent comme marqueurs de microphone. Un appui ouvre une boîte de dialogue avec l'heure et la lecture. Ils sont visibles avec « toutes les sessions » et « 3 dernières », pas avec « Live seulement ».
- **Plusieurs points superposés (groupe) :** un appui sur un groupe affiche la liste des détections. Pour chaque entrée : Vérifier, Lire et « Déplacer la position ».
- **Déplacer la position :** la carte affiche en haut l'indication « Déplacer la carte : mettre la croix sur la nouvelle position de ..., puis Valider. » Déplacer la carte pour que la croix au centre se trouve sur la nouvelle position, puis en bas « Valider » (ou « Annuler »). Ensuite apparaît une barre avec **Annuler**. Le déplacement est possible aussi pour les points en direct ; si aucune session n'est en cours, la nouvelle position n'est valable que jusqu'à la fermeture de l'application.
- **Filtre d'espèces :** dans le menu de la carte, le filtre d'espèces propose « Seulement les espèces d'alarme » ou masquer des espèces individuelles (Toutes/Aucune). Le filtre vaut jusqu'à la fermeture de l'application ; les parcours restent visibles.
- **Parcours :** un parcours enregistré pendant l'enregistrement apparaît comme ligne (en mode « Seulement lors d'une détection », en pointillés, uniquement aux points de détection). Voir chapitre 14.
- **Bouton de position :** centre la carte sur votre position et zoome jusqu'à une hauteur visible d'environ 200 m.
- **Boutons de zoom :** les boutons +/- sont 50 % plus grands et se trouvent au-dessus du bouton de position.

---

## 9. Références et espèces similaires

Les détections vérifiées peuvent être enregistrées comme référence (« Enregistrer comme référence ») et retirées ensuite. L'onglet Références affiche les enregistrements sauvegardés ; un import audio est également possible. Les nouvelles détections sont comparées aux références par embedding.

Réglages > Espèces similaires et références : recherche de similarité activée/désactivée, nombre de lignes affichées (0 = désactivé, 0,5 = défilement des 5 dernières, 1-10 = liste), limite du nombre d'enregistrements de comparaison sauvegardés.

---

## 10. Watchlist et alarme

**Watchlist :** espèces cibles qui déclenchent une alarme. Gestion sous Réglages > Watchlist et alarme : rechercher une espèce et l'ajouter, la retirer, ou la charger depuis `watchlist.json`. La casse et les espaces ne jouent aucun rôle dans la comparaison.

**Dossier autorisé :** sous « Autoriser le dossier PIROL », choisir une seule fois `Downloads/PIROL`. PIROL lit alors automatiquement `watchlist.json` et le dossier `lists/` (détails au chapitre 11). « Créer une liste d'exemple » crée un modèle.

**Sortie d'alarme** (activable individuellement) : vibration, notification, avertissement plein écran. Sur l'**écran de verrouillage**, l'alarme plein écran n'apparaît que si les « notifications plein écran » sont autorisées pour PIROL dans les réglages Android (bouton « Ouvrir l'autorisation »). Sinon, cela reste une notification. Avec « Tester l'alarme » (après 5 s, verrouiller l'appareil), on peut le vérifier.

Dans l'alarme, l'espèce peut être **confirmée** directement ; « Annuler » reste ensuite disponible.

**Pause d'alarme :** intervalle minimal entre deux alarmes de la même espèce (0 = chaque détection déclenche une alarme).

---

## 11. Groupes d'espèces et listes d'espèces

Le filtre d'espèces agit par groupe : **Oiseaux, Orthoptères, Amphibiens, Mammifères**, chacun sur **Arrêt / Tout / Auto / Liste** ; **Autres** seulement sur **Arrêt / Tout** (par défaut Arrêt). « Auto » utilise le géomodèle (position et semaine BirdNET). Les espèces inconnues du géomodèle, ainsi que les groupes sans masque de géomodèle, prennent la liste d'accompagnement du groupe (Oiseaux : liste CH ; Orthoptères et Amphibiens : liste CH ci-dessous ; Mammifères : toutes). « Liste » choisit une des listes fournies ou une liste personnelle. La liste « CH Orthoptères & Amphibiens » est fournie (données GBIF, CC BY 4.0).

**Auto sans GPS :** s'il n'y a pas de position actuelle, la dernière position enregistrée vaut au maximum 30 jours ; ensuite, « Auto » prend la liste de remplacement du groupe. Au démarrage de l'enregistrement, une indication le signale, et Réglages > Espèces et alarme (géomodèle) indique la base (« Sans GPS, la dernière position du [date] s'applique (30 jours au maximum), sinon la liste de remplacement »). La position enregistrée n'est mise à jour que lors d'un calcul avec fix GPS. Facultatif : la **liste annuelle** ignore la saison (interrupteur dans la section géomodèle).

Dans l'éditeur de listes, les espèces se choisissent par ordre, famille et genre ; « Afficher les noms scientifiques » permet de basculer la forme des noms.

### Listes personnelles
Des listes personnelles peuvent être déposées comme fichiers JSON dans le dossier `lists/` du dossier PIROL autorisé (`Downloads/PIROL/lists/*.json`). Elles apparaissent comme listes sélectionnables dans les groupes d'espèces (option « Liste »). Les listes sont **relues à chaque démarrage d'enregistrement** ; les modifications valent donc à partir du prochain enregistrement. Les listes défectueuses sont ignorées et signalées avec le motif. Le modèle est « Créer une liste d'exemple » (`lists/beispiel_liste.json`).

Les noms scientifiques anciens et nouveaux (p. ex. après des changements de taxonomie) sont fusionnés, de sorte que les listes fonctionnent avec tous les modèles.

---

## 12. Envoi

Réglages > Envoi : les sessions enregistrées peuvent être envoyées automatiquement après l'enregistrement.

| Destination | Indications |
|-------------|-------------|
| Désactivé | copie locale uniquement, export manuel |
| Dossier | dossier au choix (appareil, Drive, OneDrive ...) via la sélection de dossier |
| NAS (SMB / partage Windows) | hôte, partage, chemin, utilisateur, mot de passe |

Le mot de passe NAS est stocké chiffré sur l'appareil. « Tester la connexion » vérifie les indications. Options : envoi uniquement via Wi-Fi, ne pas conserver la copie ZIP locale après l'envoi. Le statut du dernier envoi s'affiche en dessous.

---

## 13. Modèles

Réglages > Modèle et détection > Modèle :

| Modèle | Caractéristiques |
|--------|------------------|
| BirdNET 3.0 FP16 (recommandé) | Standard, 11 725 classes, 32 kHz, fenêtre de 3 s |
| BirdNET 3.0 FP16 compact (pruned) | plus petit (env. 70 Mo), détection pratiquement identique |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | version préliminaire plus ancienne, 11 560 classes |

Le modèle actif se change par sélection, à partir de la prochaine analyse. Le nombre d'espèces à côté du modèle est immédiatement à jour après le changement. Les modèles peuvent être téléchargés (interruption et reprise possibles), importés et supprimés. Chaque session mémorise le modèle utilisé.

**Tableau des modèles (à partir de la v0.1.1) :** la colonne Principal (radio) détermine le modèle principal. Secondaire (case à cocher, uniquement en mode expert) choisit le deuxième modèle qui vérifie chaque détection ; désélectionnable, jamais identique au principal. La corbeille supprime un modèle installé après confirmation ; le principal ne peut pas être supprimé et est verrouillé pendant un enregistrement. Les modèles non chargés affichent sur leur ligne une icône de chargement avec progression et annulation.

**Seuil de confiance :** le réglage « Seuil de confiance (toutes les espèces) » vaut de la même façon pour toutes les espèces (par défaut 50 %). Les alarmes de la watchlist ne se déclenchent qu'à partir de ce seuil.

**Confiance minimale :** le seuil de détection est réglable globalement. Un modèle peut en plus apporter sa propre confiance minimale (p. ex. 80 % pour Perch) ; la valeur la plus élevée s'applique, et le modèle l'indique.

### Importer des modèles personnalisés ou autres
PIROL n'est pas limité à BirdNET. Via « Importer un autre modèle... », un paquet de trois fichiers est sélectionné ensemble : `.onnx` (modèle), `.pirol.json` (profil) et CSV d'étiquettes. L'application vérifie les sommes de contrôle, les entrées/sorties et le nombre d'étiquettes, et signale les erreurs en clair. La fréquence d'échantillonnage et la longueur de fenêtre sont libres.

Ont été testés **BirdNET 2.4** (6 522 espèces, 48 kHz, environ la moitié du temps de calcul de 3.0), **Perch v2** (Google, 14 795 classes, fenêtre de 5 s) et **Nighthawk 0.3.1**. Les fichiers ne font pas partie de l'application. Pour la conversion de modèles TensorFlow, Keras et TFLite (y compris vos propres entraînements), le script `tools/convert_model.py` est fourni, voir `tools/README_convert_model.md` et `tools/MODEL_PROFILE_FORMAT.md`.

**Licences :** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 et Nighthawk uniquement non commercial (CC BY-NC ou BY-NC-SA). La licence est affichée lors de l'import.

**Remarque :** Perch fonctionne à 32 kHz ; les sauterelles chanteuses en ultrasons ne sont donc pas audibles.

**Statistique du deuxième modèle (mode expert) :** dans Réglages > Modèle et détection, le bouton « Statistique du deuxième modèle » ouvre une boîte de dialogue. PIROL lit pour cela toutes les sessions (uniquement les originaux, en lecture seule ; les variantes de rescan et les entrées manuelles ne comptent pas) et affiche un tableau croisé : vérification (confirmé, rejeté, incertain, ouvert, corrigé/remplacé) face au résultat du deuxième modèle (Vérifié, Non vérifiable, Contradictoire, Aucun résultat), au total et pour chaque deuxième modèle. S'y ajoutent des barres de progression vers l'objectif (200 détections confirmées et 50 rejetées avec résultat du deuxième modèle) ainsi que la part des contradictions pour les détections confirmées et pour les rejetées. Cela aide à juger si le deuxième modèle est utile comme aide à la vérification. Fermer la boîte de dialogue interrompt la lecture.

**Perch comme deuxième modèle :** nécessite env. 650 Mo de mémoire supplémentaire. L'application avertit à l'activation si l'appareil dispose de moins de 6 Go de RAM. Le deuxième modèle travaille avec l'audio brut de l'enregistrement, afin que les fenêtres de 5 s contiennent l'environnement réel.

---

## 14. GPS

Réglages > Carte et GPS (jusqu'à v0.0.10 : Enregistrement et stockage) :

| Réglage | Signification |
|---------|---------------|
| Mode GPS « En continu » | fixes réguliers à l'intervalle réglé (2/5/10/20/60 s, par défaut 10 s) |
| Mode GPS « Seulement lors d'une détection » | GPS uniquement lors d'une détection, au moins 30 s entre deux fixes ; l'intervalle ne s'applique alors pas. Économise environ un tiers de l'autonomie |
| Imprécision GPS max. | les fixes moins précis sont rejetés (par défaut 50 m) |
| Lissage GPS | médiane sur 5 fixes, atténue les valeurs aberrantes |

Chaque détection reçoit une position fraîche, même écran éteint. Les modifications valent à partir du prochain enregistrement.

**Premier fixe :** au démarrage du programme et au zoom de la carte, PIROL obtient une fois une position sans enregistrement en cours (pas de suivi permanent).

**Parcours :** le parcours (« Enregistrer le parcours », par défaut activé) est écrit pendant l'enregistrement (`track.jsonl`) et déposé à l'arrêt comme `track.gpx` à côté de l'enregistrement (heures en UTC).

**Commune :** le bouton « Insérer la commune » inscrit la commune de la position dans la remarque (dans la boîte de dialogue de détail, dans la remarque de session et dans l'analyse). Les données communales (Suisse) sont disponibles hors ligne dans l'application. En dehors, rien n'est déterminé, sauf si l'option experte Réglages > Carte et GPS > Avancé > « Déterminer la commune en ligne (Google) » est activée (par défaut désactivée ; les coordonnées sont alors envoyées à Google).

---

## 15. Énergie et batterie

Réglages > Modèle et détection > Profil énergétique :

| Profil | Analyse |
|--------|---------|
| Précis | chaque fenêtre de 3 s |
| Équilibré | toutes les 6 s |
| Économe | toutes les 15 s, pour les longues journées de terrain |
| Ultra-économe | toutes les 30 s, autonomie maximale |

**Valeurs pratiques** (tablette Galaxy Tab A11+, 6 840 mAh, enregistrement continu, Wi-Fi activé, BirdNET 3.0 FP16 ; valeurs indicatives, env. ±15 %) :

| Réglage | Consommation | Autonomie batterie pleine |
|---------|--------------|---------------------------|
| Précis, écran éteint, GPS en continu (10 s) | env. 2,2 %/h | env. 45 h |
| idem, chevauchement 67 % | env. 3,5 %/h | env. 28 h |
| idem, **écran allumé** | env. 9 %/h | env. 12 h |
| Précis, GPS seulement lors d'une détection | env. 1,5 %/h | env. 60 h |
| Économe (15 s), GPS 60 s | env. 0,9 %/h | env. 4-5 jours |

**Conseils :**
1. **Éteindre l'écran** est le plus grand levier (plus de deux fois la consommation de l'enregistrement et de l'analyse réunis). L'enregistrement se poursuit dans le service.
2. Choisir le mode GPS « Seulement lors d'une détection ».
3. Garder le chevauchement à 0 % ; pour les longues missions, le profil « Économe ».
4. Le choix du modèle joue à peine un rôle (la version pruned est à peu près comme la version finale, Preview 3.1 env. 10 % de plus, FP32 pas plus que FP16).

---

## 16. Langues

Réglages > Langue : langue de l'application (langue du système ou choix) : allemand, anglais, français, italien, catalan, portugais, néerlandais, suisse allemand. **Le suisse allemand et le portugais sont des brouillons** ; les textes manquants apparaissent en allemand. La langue des **noms d'espèces** suit la langue de l'application, mais peut être réglée séparément (23 langues). Les noms d'espèces apparaissent toujours dans la langue choisie, y compris sur la carte, dans Raven et pour les détections plus anciennes (ils sont recalculés à l'affichage ; les fichiers enregistrés restent inchangés) ; les espèces renommées se retrouvent aussi sous leur ancien nom. Si un nom manque dans la langue choisie, PIROL prend pour l'allemand le nom allemand des listes d'espèces fournies, pour les autres langues d'abord le nom anglais.

---

## 17. Réglages en bref

Les réglages sont répartis en sections repliables ; l'état est mémorisé. Par défaut, seule la section « Langue » est ouverte.

| Section | Contenu |
|---------|---------|
| Langue | langue de l'application, langue des noms d'espèces |
| Enregistrement | info audio, préroll, chevauchement, profil énergétique, emplacement de stockage (dossier, sous-dossiers par jour, suppression automatique, déplacer les sessions), durée max. d'enregistrement et longueur d'extrait, rééchantillonneur |
| Modèle et détection | modèles (téléchargement, import, suppression), seuil de détection (toutes les espèces), alternatives à partir de, nombre max. d'espèces par fenêtre, préréglages, suggestions d'espèces, regroupement des détections, espèces similaires et limite d'embeddings |
| Espèces et alarme | filtre régional et listes personnelles, watchlist, pause et sortie d'alarme, tester l'alarme, dossier PIROL, saisonnalité et rareté |
| Affichage | langue des noms d'espèces, plein écran, sonogramme (mode, préréglage, contraste, gamma, plafond, palette, fréquence, fenêtre temporelle), fiche d'espèce compacte |
| Carte et GPS | mode GPS, enregistrer le parcours, intervalle GPS, précision et lissage ; experts (Avancé) : « Déterminer la commune en ligne (Google) » |
| Export et envoi | champs d'export Raven (dont mémo), protection des données (masquer les voix humaines, seuil humain), envoi (destination dossier/NAS, mot de passe, test de connexion, Wi-Fi seulement) |
| Espèces similaires et références | recherche de similarité, nombre de lignes, enregistrements de comparaison (chapitre 9) |
| Infos | version, sources, nouveautés, diagnostic (benchmark), « Soutenir PIROL » |

L'interrupteur **Réglages experts** (sous « Lancer la configuration ») masque en mode simple tout ce qui est rare ; en mode expert, tout est visible. Aucun réglage n'est perdu. Les options rares se trouvent dans le sous-bloc repliable « Avancé » de la section concernée (p. ex. filtre passe-haut, regroupement des détections, phénologie, durée max. d'enregistrement).

**Soutenir PIROL :** dans Réglages > Infos, le bouton « Soutenir PIROL » ouvre la page de don dans le navigateur. Le don est facultatif ; PIROL reste gratuit et complet.

**Rééchantillonneur :** Polyphase (recommandé) filtre les bruits parasites au-dessus de 16 kHz, qui pourraient sinon apparaître comme signaux fantômes. « Linéaire » est l'ancienne méthode. Le changement affiche une indication et prend effet sans redémarrer l'enregistrement.

**Diagnostic :** le benchmark mesure le temps de calcul du modèle actif avec 1/2/4 threads (1 à 3 minutes, pas pendant un enregistrement). Avec « Choisir le fichier ONNX et mesurer », un modèle quelconque peut aussi être testé avant l'import.

---

## 18. Remarques techniques

- **Enregistrement :** 48 kHz, PCM 16 bits, mono (96 kHz en mode chauve-souris). Détection convertie en 32 kHz (BirdNET 3.0).
- **Appareil minimal :** Android 8.0. Compatible avec les pages mémoire de 16 Ko (Android 15+).
- **Fairphone et autres appareils :** le taux d'échantillonnage est négocié avec l'appareil ; en cas de problème, l'application bascule sur un autre pilote audio.
- **Formats de données :** `session.json`, `detections.jsonl`, `verifications.jsonl`, TSV Raven ; horodatages en heure locale avec décalage.

---

## 19. Modifications

La liste complète figure dans `CHANGELOG.md` du projet.

### Version 0.1.3
- Mémos de session : suppression avec Annuler, position enregistrée à l'appui et affichée comme marqueur sur la carte, partage et enregistrer sous (chapitres 2, 6, 8)
- Import : sélecteurs de date et d'heure, interrupteur « Heure du fichier = fin de l'enregistrement », scan pour toutes les sessions importées, le premier scan devient l'original avec Raven dans le dossier (chapitre 6)
- Changer la date d'une session importée renomme le dossier de session et le déplace dans le bon dossier journalier (chapitre 5)
- « + Espèce » tient compte des lacunes entre les segments (chapitre 6)
- Spectre : fréquence linéaire (par défaut) ou Mel, gamma aussi dans le spectre, fenêtre temporelle dans l'oscillogramme (chapitre 3)
- Analyse : zoom à deux doigts, l'envoi signale son résultat par une barre (chapitre 6)
- Rescan plus rapide, deuxième modèle en parallèle (chapitre 6)
- Auto sans GPS : dernière position jusqu'à 30 jours, indication au démarrage, base dans les réglages, liste annuelle (chapitre 11)
- Statistique du deuxième modèle en mode expert (chapitre 13)
- Noms d'espèces toujours dans la langue choisie, noms allemands des listes comme repli (chapitre 16)
- Bouton « Soutenir PIROL » (facultatif) dans Réglages > Infos (chapitre 17)
- Aide : le mode aide couvre plus d'éléments, y compris les marqueurs de carte et les boîtes de dialogue (chapitre 1)
- Nouveau chapitre 20 : installation et mise à jour

### Version 0.1.2
- Modifier après coup les données de sessions importées (heure de début, lieu, remarque) (chapitre 5)
- « Enregistrer sous » en ZIP ou en dossier (chapitre 6)
- Raven : une ligne par épisode, nouvelles colonnes `Cluster`, `Windows` et `Memo` (chapitre 7)
- Rescan et import : résultats visibles et vérifiables en continu ; avec le rescan, l'annulation conserve le résultat partiel, avec l'import, elle supprime la session à moitié importée (chapitre 6)
- Voix humaines masquées dans l'export, activé par défaut ; demande de confirmation si le modèle n'a pas de classe humaine (chapitre 7)
- Commune en ligne via Google seulement comme option, désactivée par défaut (chapitre 17)
- Mémo de session par appui maintenu, bloc « Mémos » dans l'analyse, ligne Mémo dans Raven (chapitres 2, 6, 7)
- Fiches d'espèces en direct plus calmes : changement au plus toutes les 5 s, correspondances dans le top 5 en bleu (chapitre 2)
- Bouton d'aide « ? » : mode aide et mode d'emploi hors ligne (chapitre 1)
- Mode d'emploi corrigé : filtre d'espèces dans le menu de la carte (chapitre 8), noms des sections de Réglages (chapitres 1, 5, 7, 17)

---

## 20. Installation et mise à jour

**Quelle APK ?** Chaque version est publiée sur GitHub (Nikiphedia/Pirol, rubrique « Releases ») avec une APK par architecture de processeur :
- `Pirol-<Version>-arm64-v8a-release.apk` : pour presque tous les téléphones et tablettes récents. En cas de doute, prendre ce fichier.
- `Pirol-<Version>-armeabi-v7a-release.apk` : seulement pour les anciens appareils 32 bits.

**Installation :**
1. Télécharger l'APK correspondante depuis la publication GitHub sur l'appareil, puis l'ouvrir. Android demande la première fois si le navigateur ou le gestionnaire de fichiers utilisé peut installer des applications ; l'autoriser une fois.
2. **Appareils Samsung :** la fonction **« Blocage automatique »** (Auto Blocker ; Réglages > Sécurité et confidentialité) empêche l'installation d'applications hors du store. La désactiver brièvement pour l'installation, installer PIROL, puis la réactiver. Cela doit être refait **à chaque mise à jour**.
3. Appuyer sur Installer, puis ouvrir l'application. Au premier démarrage, l'assistant de configuration guide à travers les réglages de base (chapitre 1).

**Mise à jour :** installer la nouvelle APK simplement par-dessus l'installation existante (même architecture ; sur Samsung, de nouveau avec le blocage automatique brièvement désactivé). Sessions, modèles, listes et réglages sont conservés ; rien n'a besoin d'être désinstallé. Les remarques sur les anciennes versions figurent au chapitre 1 (mise à jour depuis les anciennes versions). Après la mise à jour, PIROL affiche une fois « Nouveautés ».

---

*PIROL v0.1.3 — ETA Systems — état 2026-11-25*
