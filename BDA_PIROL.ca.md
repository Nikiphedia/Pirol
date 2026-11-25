# PIROL — Manual d'ús

Reconeixement acústic d'espècies en temps real per a Android, pensat per al treball de camp.

*Vàlid des de la versió v0.1.3 (estat 2026-11-25). Els canvis entre versions són a `CHANGELOG.md`.*

L'app té cinc pestanyes: **Live**, **Anàlisi**, **Referències**, **Mapa** i **Configuració**. El botó **?** (Ajuda) no és una pestanya: un toc activa el mode d'ajuda, i un doble toc obre aquest manual (capítol 1, apartat "Botó d'ajuda").

---

## 1. Primer inici

1. Obriu l'app. L'assistent de configuració us guia per l'idioma, el nivell (principiant/expert), l'ús previst, la llista d'espècies, el model (inclòs el geomodel), els permisos (micròfon, GPS, notificacions) i el resum. Els valors només s'apliquen en prémer "Fet".
2. **Triar la llista d'espècies** (capítol 11): els grups d'espècies (Ocells, Llagostes, Amfibis, Mamífers, Altres) filtren la detecció cap a espècies plausibles. Es pot canviar més tard a Configuració > Espècies i alarma.
3. **Descarregar el model**: es poden seleccionar diversos models alhora. El model per defecte és BirdNET 3.0 FP16 (recomanat, uns 170 MB). La descàrrega continua en segon pla, es pot cancel·lar i reprendre més tard. Com a alternativa, es pot triar manualment un fitxer de model.
4. **Comprovar la carpeta d'emmagatzematge** (Configuració > Gravació). Per defecte: `Downloads/PIROL/`, visible per USB.

**Instal·lació (APK):** L'APK de publicació hi ha per arquitectura de processador. Per als dispositius actuals (telèfons i tauletes) instal·leu el fitxer `Pirol-<Versió>-arm64-v8a-release.apk`; només per als dispositius antics de 32 bits, el fitxer `...-armeabi-v7a-release.apk`. Així, les APK són molt més petites que l'antiga APK comuna. L'app té una icona nova (ocell groc), també com a icona monocroma per a les icones temàtiques. Detalls sobre la instal·lació i l'actualització (també dispositius Samsung) són al capítol 20.

**Novetats:** Després d'una actualització, PIROL mostra una vegada un diàleg "Novetats" amb els canvis més importants de la versió. Es pot tornar a obrir en qualsevol moment a Configuració, sota "Info".

### Botó d'ajuda
El botó **?** (tauleta i navegació estreta: sota Configuració; telèfon: a la dreta de tot de la barra) ofereix dues vies:
- **Un toc** activa o desactiva el **mode d'ajuda**. A dalt apareix la barra "Mode d'ajuda: toqueu un botó per a una explicació". Un toc sobre un botó o un control aleshores no fa l'acció, sinó que mostra una explicació breu, si cal amb "Més al manual" (salta al capítol corresponent). Per sortir: la X de la barra, Enrere o tornar a prémer **?**.
- **Doble toc** obre aquest manual fora de línia, en l'idioma de l'app (si falta la traducció, en alemany). Un índex porta als apartats.

**Actualització de versions anteriors:** Des de la v0.0.8, PIROL està signat. Qui tingui instal·lada la v0.0.7 o anterior ha de desinstal·lar una vegada i abans copiar les sessions per USB (`Android/data/ch.etasystems.pirol/files/PIROL/`). De la v0.0.8 a la v0.0.10 es pot actualitzar a la v0.0.11 sense desinstal·lar; les sessions i els models es conserven. Atenció amb la v0.0.11: el llindar de confiança és ara un llindar comú per a totes les espècies (capítols 2 i 13); reviseu el regulador després. De la v0.0.11 a la v0.1.1 es pot actualitzar sense desinstal·lar. Després, reviseu els grups d'espècies (capítol 11): els filtres antics estaven en Ocells Automàtic, la resta Desactivat. Les sessions de la v0.0.6 i anteriors ja no tenen suport específic.

---

## 2. Detecció en directe

**Inici:** premeu el botó de gravació a la pestanya Live. **Aturar:** premeu el botó llargament. La sessió i l'exportació Raven es desen automàticament.

La gravació i l'anàlisi s'executen en un mateix servei. Continuen si l'app passa a segon pla o si la pantalla s'apaga.

En aturar apareix a dalt l'avís "Premeu llargament per aturar" (a dalt, perquè el dit sobre el botó no el tapi).

Les espècies detectades apareixen com a targetes sota el sonograma:
- Nom de l'espècie en l'idioma triat, amb el nom científic com a complement
- Confiança en per cent (barra fina), hora i posició GPS
- Alternatives (desplegables, tocables)
- Avisos "Rar", "Fora de temporada", marca de llista de seguiment
- **Reproduir:** el botó de reproducció de la targeta reprodueix el fragment de la detecció (3 s abans, 5 s després), també durant la gravació. Un segon toc atura la reproducció.
- **Parpelleig:** si una espècie es torna a detectar, la seva targeta parpelleja breument en blau. La llista no salta (vegeu "Llista tranquil·la").
- **Campana d'alarma:** al menú de la targeta completa, l'espècie es posa de manera permanent a la llista d'alarmes o durant 24 h. Les alarmes de 24 h mostren una campana amb la insígnia "24h"; en acabar el termini desapareix automàticament.
- **Botó compacte:** commuta totes les targetes entre compacta i detallada. Al telèfon és a la barra d'estat, a la tauleta a la capçalera. Les alternatives es despleguen i es plegen des del capçal de la targeta.

**Vista compacta al telèfon:** en vertical i en horitzontal, la pestanya Live aprofita l'espai amb mesura: sense barra de títol, la navegació només té icones, i la fila de botons de les targetes és d'una sola línia. A les targetes estretes, Compartir, Referència, Comparar i "Alternatives" són al menú "Més accions" (icona de tres punts); a la tauleta tots els botons són visibles. En horitzontal, el sonograma i els botons són a l'esquerra, i les targetes d'espècies a la dreta. El panell "Espècies semblants" al telèfon està plegat i es pot desplegar.

**Micròfon mut durant la reproducció:** si PIROL reprodueix una detecció pel altaveu durant la gravació, el micròfon es silencia perquè la reproducció no torni a ser detectada ni gravada. A la pestanya Live apareix l'avís "Micròfon mut durant la reproducció". Amb auriculars connectats (cable, USB, Bluetooth) el micròfon continua actiu.

| Botó | Estat |
|-------|---------|
| verd, polsant | El buffer de preroll funciona (gravació encara no iniciada) |
| vermell, polsant | La gravació funciona |

**Línia d'estat:** durada en mm:ss. **Barra GPS:** precisió actual en ±m, en vermell si fa més de 30 s que no hi ha una posició vàlida.

**Observació:** per cada gravació es pot introduir un títol. Apareix a la llista de sessions, al nom del ZIP i a l'exportació Raven.

**Afegir manualment:** introduir una espècie a mà (amb nombre d'exemplars, codi d'atles, observació). Per detecció, a més, es poden registrar el nombre d'exemplars, el codi d'atles, l'observació i un memo de veu. Una espècie es pot bloquejar (només un avís per sessió).

**Agrupar:** les deteccions de la mateixa espècie s'agrupen espacial i temporalment en una sola entrada (radi i finestra temporal ajustables, Configuració > Model i detecció).

### Llista tranquil·la
L'ordre de les targetes d'espècies canvia com a màxim cada 5 s. Les espècies noves s'acumulen i s'insereixen juntes a dalt (la primera targeta apareix de seguida); per tant, poden ser visibles fins a 5 s més tard, però les alarmes arriben sempre de seguida. Si una espècie es torna a detectar dins de les 5 primeres targetes, es queda al seu lloc i parpelleja en blau. Una espècie més avall puja en el següent canvi i parpelleja. Esborrar, verificar i bloquejar espècies té efecte immediat. Les deteccions, l'alarma, el resum i l'anàlisi no es veuen afectades.

### Memo de sessió
El botó de memo a la pestanya Live (només visible durant una gravació) funciona com un walkie-talkie: **premeu-lo i mantingueu-lo mentre parleu**; en deixar-lo anar, el memo acaba. Durant la gravació el botó és vermell i mostra la durada; després apareix "Memo desat (n s)". Els memos de menys de 0.5 s es descarten ("Massa curt"); després de 3 minuts el memo acaba automàticament. El memo es desa amb la seva posició dins de la gravació (`memos.jsonl`, fitxers a `memos/`), apareix a l'anàlisi al bloc "Memos" (capítol 6) i com a línia "Memo" a l'exportació Raven (capítol 7). Els memos no s'amaguen mai com a veu (capítol 7). Els memos de veu sobre espècies individuals resten separats.

Quan es prem, PIROL anota l'últim fix GPS (no més antic de 2 min) i el desa amb el memo; al mapa el memo apareix llavors com a marcador de micròfon (capítol 8). Sense un fix actual, el memo no té ubicació. Esborrar, compartir i desar com a: capítol 6.

### Detecció múltiple i llindar de confiança
Dins de la mateixa finestra de 3 s es detecten totes les espècies que arriben al llindar, no només la primera. El regulador s'anomena "Llindar de confiança (totes les espècies)" (Configuració > Model i detecció, per defecte 50 %) i és el mateix per a totes les espècies. Les espècies per sota però per sobre del límit de candidates resten com a candidates a les deteccions de la finestra. Abans, el regulador controlava un altre llindar (TopK, per defecte 0.3); per això, després de l'actualització, cal revisar-lo. Les alarmes de la llista de seguiment només es disparen a partir del llindar.

### Segon model (mode expert)
Un segon model (p. ex. Perch v2 o BirdNET 2.4, capítol 13) torna a calcular les mateixes finestres. La targeta mostra aleshores, al costat de la confiança del model principal, el valor del segon model: en per cent, o com a rang en el cas de Perch ("Perch #2"). Les espècies que el segon model no coneix es marquen com a no verificables. El segon model no influeix en l'alarma, els filtres ni el resum. El llindar "Alternatives a partir de" (5/10/20/30 %, Configuració > Model i detecció) determina a partir de quina confiança es llisten les alternatives.

### Preroll
Si està activat, s'enregistra àudio **abans** d'iniciar la gravació (durada ajustable) i també s'analitza. El preroll està al principi de `recording.wav` sense interrupcions. El sonograma en mostra com a màxim 5 s.

### Solapament
Les finestres d'anàlisi es poden solapar: 0 % (per defecte, una finestra cada 3 s), 50 % (cada 1.5 s) o 67 % (cada segon). Més solapament detecta millor les crides curtes als límits de finestra, però necessita més temps de càlcul i bateria. Si el dispositiu és massa lent, PIROL redueix automàticament el solapament i ho notifica una vegada per sessió en una barra emergent. Amb models de finestra més llarga (Perch, 5 s), el regulador "Pas màxim" (3, 4 o 5 s) limita la distància entre dues finestres.

### Gravacions llargues (segments)
Després de la durada màxima configurada (per defecte 60 min, ajustable entre 10 i 80 min) comença automàticament un fitxer nou (`recording-002.wav`, etc.). La gravació continua sense interrupció. Les indicacions de temps a `detections.jsonl` i a Raven es compten des de l'inici de la sessió. La capçalera WAV s'actualitza cada 5 s: si l'app es tanca de manera forçada, es perden com a màxim uns 3 s d'àudio.

### Filtre passa-alts i processament
El filtre passa-alts (200 Hz) elimina el vent i el soroll de passes, activat per defecte. Només actua sobre la còpia per a la detecció; la gravació desada no es modifica. Els canvis s'apliquen a partir de la següent gravació.

---

## 3. Visualització: sonograma, espectre, oscil·lograma

A la pestanya Live i a la pestanya Anàlisi. L'eix de freqüències mostra 1/2/4/8/12 kHz. Per defecte es mostra fins a **12 kHz** (preajust per als ocells), ajustable fins a **24 kHz**. Els nivells del sonograma es mostren en dBFS.

**Modes de visualització (pestanya Live):** al panell de visualització (premuda llarga) canvieu entre **Sonograma**, **Espectre** i **Oscil·lograma**. L'espectre mostra el volum per freqüència amb FFT real (dBFS, mitjana, retenció de pics amb punts que s'esvaeixen, cursor). Al panell de l'espectre, l'eix de freqüències es pot canviar entre **Lineal** (per defecte, distàncies iguals en Hz) i **Mel** (freqüències baixes estirades, com al sonograma); la graella i l'indicador del cursor segueixen la tria. El regulador **Intensitat (gamma)** actua també a l'espectre (alçada de la corba i graella en dB) i és el mateix valor que al sonograma. L'oscil·lograma mostra l'evolució temporal amb indicador de nivell (pic/RMS), passa-banda i marca vermella en cas de saturació; la seva **finestra temporal** (de 5 a 30 s) s'ajusta al panell de l'oscil·lograma. Un regulador de gamma no pertany a l'oscil·lograma. Mentre encara no hi hagi dades, cada vista mostra el seu propi avís ("Carregant espectre", "Carregant oscil·lograma"). La freqüència, la dinàmica i la paleta de colors són comunes per a les tres vistes. A l'anàlisi també hi ha disponibles l'espectre i l'oscil·lograma.

**Adaptar la visualització:** la premuda llarga sobre el sonograma (Live o Anàlisi) obre el panell de configuració sota el sonograma; els mateixos reguladors són a Configuració > Visualització. El panell és comú per a Live, Anàlisi i Configuració. A dalt del panell hi ha "Fet" en una línia pròpia; els preajustos i les paletes de colors són rajoles que es divideixen en línies si no hi ha espai.

| Regulador | Efecte |
|--------|---------|
| Preajust | Estàndard / Crides suaus / Entorn sorollós |
| Autocontrast | ajusta contínuament el llindar de dB al senyal; si no, a mà (interval en dB) |
| Intensitat (gamma) | més petit = parts suaus més clares |
| Límit de brillantor | limita els senyals forts |
| Paleta de colors | Magma, Viridis, Escala de grisos, **Paper (clar)** |
| Freqüència | interval de freqüències mostrat en kHz |
| Finestra temporal | segons visibles (de 5 a 30 s) |

**Paper (clar):** paleta clara com un sonograma de paper clàssic, bona a la llum del dia. La paleta es tria al panell (premuda llarga sobre el sonograma); al telèfon ja no hi ha un botó propi a la pestanya Live.

**Canvi de pestanya:** si es deixa la pestanya Live o s'apaga la pantalla, PIROL recalcula el sonograma en tornar (fins a la finestra temporal configurada), perquè continuï sense interrupcions. Si l'absència ha durat més que la finestra temporal, comença de nou.

**Mode** (Configuració > Visualització): Ocells, Ratpenat o Banda ampla. El mode Ratpenat grava a 96 kHz (ultrasò); en aquest mode no hi ha preroll.

---

## 4. Verificació

Per a cada detecció hi ha aquestes accions disponibles (pestanyes Live, Anàlisi i Mapa):

| Acció | Estat | Representació |
|--------|-------------|-------------|
| Confirmar | Confirmada | vora verda |
| Incert (?) | Incerta | vora taronja |
| Rebutjar | Rebutjada | gris |
| Corregir | Corregida | nom substituït per l'entrada pròpia |
| Triar alternativa | Confirmada | L'espècie de la detecció es substitueix per l'alternativa (mateixa fila, hora i posició); l'espècie antiga queda com a candidata. Desfer a la barra emergent |

A la **pestanya Live** ja no hi ha barra emergent en verificar. Després de "Confirmar", la targeta mostra un botó verd "Desfer"; aquest torna la detecció a "Incerta". Els botons Confirmar, Incert, Rebutjar i Editar romanen visibles, així que l'estat es pot commutar en qualsevol moment. En triar una **alternativa**, en desplaçar a la targeta i en confirmar des de l'alarma de la llista de seguiment, continua apareixent una barra emergent amb **Desfer** (almenys 5 s). Totes les accions es registren a `verifications.jsonl`; també les deteccions rebutjades i substituïdes resten a l'exportació (registre d'auditoria).

**Compartir una detecció:** fragment (3 s abans, 5 s després, durada màxima ajustable) com a WAV, M4A (AAC), FLAC (si el dispositiu ho permet) o sonograma PNG amb etiqueta.

---

## 5. Sessions

Cada gravació crea una carpeta de sessió, per defecte `Downloads/PIROL/AAAA-MM-DD/{sessionId}/`. El nom de la carpeta porta l'hora local.

```
session.json              Metadades (durada, lloc, estadístiques GPS, model utilitzat)
detections.jsonl          totes les deteccions
verifications.jsonl       totes les accions de verificació
detections.updates.jsonl  Fitxer complementari durant la gravació (en aturar es fusiona a detections.jsonl)
recording.wav             Gravació (16 bits, mono, 48 kHz); en sessions llargues
recording-002.wav ...     més segments
recording.selections.txt  Raven Selection Table (automàtic en aturar)
memos.jsonl               Memos de sessió amb posició dins de la gravació (fitxers a memos/)
human_windows.jsonl       Llista per amagar veus humanes (mai exportada)
```

- **Espai necessari:** uns 5.5 MB per minut de gravació.
- **Marques de temps:** sempre hora local amb desfasament de zona (p. ex. `+02:00`).
- **Esborrat automàtic:** les sessions curtes sense detecció s'esborren (durada mínima ajustable, 0 = desactivat).
- **Esborrar** una sessió a l'anàlisi: amb confirmació.
- **Editar dades de sessions importades:** a les sessions importades, al menú de desbordament de la capçalera (icona de tres punts) hi ha l'entrada "Editar dades": hora d'inici, lloc i observació es poden canviar després. Una hora d'inici nova desplaça totes les hores de la sessió (deteccions, verificacions, variants, ruta) en la mateixa diferència; les posicions dins de la gravació no canvien. Si canvia la **data**, PIROL reanomena també la carpeta de la sessió (nou identificador amb la nova hora local) i la mou a la carpeta del dia corresponent (si les subcarpetes diàries estan activades). La data i l'hora d'inici es trien amb el selector de data i hora. Raven i GPX es tornen a escriure. El lloc s'aplica a la sessió i a totes les deteccions sense posició GPS pròpia; els punts desplaçats al mapa no canvien. No és possible durant una re-anàlisi; un canvi de data tampoc no és possible mentre hi hagi un upload d'aquesta sessió en cua o en curs.

### Canviar la ubicació d'emmagatzematge
Configuració > Gravació > Carpeta d'emmagatzematge: "Triar una altra carpeta" (SAF: memòria interna, targeta SD, carpeta al núvol). Opcionalment amb subcarpetes diàries. Si la carpeta no és accessible, PIROL desa a la carpeta de reserva de l'app i ho notifica. Les gravacions individuals també es poden moure des de la pestanya Anàlisi (capítol 6).

**Moure sessions:** en canviar, PIROL pregunta si les sessions existents s'han de **copiar** a la nova ubicació ("Copiar") o només canviar la ubicació ("Només canviar").

---

## 6. Pestanya Anàlisi

- **Llista de sessions:** per data, amb durada, mida i observació.
- **Obrir una sessió:** sonograma i llista de deteccions amb indicació horària. La detecció reproduïda en aquell moment està marcada, un indicador mostra la posició.
- **Reproducció:** per detecció (fragment amb zoom) o tota la gravació; els segments es reprodueixen seguits. Les gravacions llargues es transmeten en flux.
- **Diverses aparicions:** si una espècie s'ha detectat diverses vegades durant la gravació i s'ha agrupat en una sola targeta, la targeta mostra la línia "N aparicions", que es pot desplegar. Les finestres consecutives s'agrupen en **episodis** ("N aparicions en M episodis"). Cada episodi apareix en una línia amb hora d'inici i de fi, posició a la gravació ("@ m:ss") i durada ("N finestres, M s"); el seu botó de reproducció reprodueix l'episodi complet d'una vegada (també en cant llarg i continu). De finestres individuals només es coneix el moment, no la confiança.
- **Reproducció:** sempre només es reprodueix una entrada. Un segon toc al mateix botó atura, un toc a una altra entrada canvia, i un canvi de pestanya finalitza la reproducció. Això val a tota l'app (Live, Anàlisi, Mapa, Referències, memos de veu).
- **Verificar, corregir, afegir espècie:** igual que a la pestanya Live, també a posteriori.
- **Comparació:** detecció i referència una al costat de l'altra, amb reproducció sincronitzada.
- **Cerca:** la icona de lupa a la llista de sessions filtra pel nom de l'espècie (part del nom, nom alemany o científic). En una gravació oberta, la cerca filtra la llista de deteccions.
- **Capçalera i barra de reproducció:** enrere, data i identificador, reproducció amb indicació de posició, cerca, Raven i càrrega. El resultat de la càrrega apareix en una barra emergent ("Carregat", en cas d'error amb el motiu, o que PIROL espera una decisió, capítol 7). Amb "detecció anterior/següent" es salta entre les entrades.
- **Navegació:** un toc sobre el sonograma o l'oscil·lograma salta a aquell punt; si no hi ha reproducció en curs, PIROL recorda la posició inicial (indicador cian) per a la següent reproducció.
- **Espectre i oscil·lograma:** igual que a la pestanya Live (inclosos Lineal/Mel i gamma, capítol 3); a l'oscil·lograma un indicador mostra la posició de reproducció.
- **Zoom amb dos dits:** a la barra de visió general i al sonograma es fa zoom amb pinça (dos dits). El retall té com a mínim 3 s (en sessions més curtes, tota la sessió) i com a màxim tota la gravació; en allunyar fins a la gravació sencera, el zoom s'anul·la. Tocar i arrossegar amb un dit funcionen com abans.
- **+ Espècie:** pren l'hora de la posició de reproducció (+/-10 s). En sessions formades per diversos fitxers amb buits entre els segments, PIROL calcula a través del rellotge de paret del segment corresponent; la finestra es queda dins del segment. La ubicació es pot determinar amb "Triar al mapa".
- **Visió general:** una barra de resum mostra les deteccions de tota la gravació; la taula Raven les llista en forma de taula.
- **Tornar a generar el fitxer Raven**, p. ex. després d'una verificació posterior.
- **Compartir episodis:** cada episodi té el seu propi botó de compartir; es comparteix el fragment d'aquell episodi.
- **Memos:** si hi ha memos, l'anàlisi mostra sota el sonograma i la visió general el bloc plegable "Memos (n)" (com a màxim 200 dp d'alçada, amb desplaçament) amb reproducció. Llista els memos de sessió i els memos sobre espècies; en els memos d'espècies sense hora coneguda apareix "aprox. ... (hora de la detecció)". El menú de cada memo ofereix **Compartir** i **Desar com a** (el fitxer WAV sense canvis, nom `AAAAMMDDHHMMSS_Memo_Observació.wav`, sense amagar veus) i, per als memos de sessió, **Esborrar**: el memo desapareix de seguida, una barra emergent ofereix **Desfer** (10 s); només després el fitxer s'esborra definitivament. A la gravació en curs, esborrar està bloquejat ("Esborrar (gravació en curs)").
- **Re-anàlisi:** el botó de re-anàlisi al costat de la lupa (només si hi ha una gravació) obre la selecció de model, llista o grups d'espècies, llindar de confiança, solapament i segon model. La re-anàlisi s'executa en segon pla com a servei en primer pla, amb progrés i opció de cancel·lar; el resultat apareix com una etiqueta al costat de l'original ("Data hora · Model"). Els resultats apareixen **progressivament**: la variant s'obre de seguida, la llista de deteccions creix cada pocs segons i es pot verificar durant l'escaneig (capçalera "· en curs"). En cancel·lar, el resultat parcial es conserva ("cancel·lat al minut x"); el mateix passa si l'app es tanca durant l'escaneig. Les verificacions ja fetes mai se sobreescriuen. La re-anàlisi ha esdevingut més ràpida: fa servir més nuclis del processador i el segon model s'executa en paral·lel al model principal; els resultats són els mateixos que abans. Durant una re-anàlisi no és possible cap gravació i la variant en curs no es pot esborrar. Després de la importació d'arxius d'àudio, l'escaneig segueix el mateix camí (vegeu "Importar àudio").
- **Importar àudio:** el botó d'importació a la capçalera de la llista de sessions incorpora una sessió de PIROL (ZIP o carpeta) o arxius d'àudio (WAV, FLAC, MP3, M4A, OGG) com a sessió nova. L'hora d'inici ve preomplerta (p. ex. d'un comentari d'AudioMoth) i es pot canviar. Amb diversos arxius es tria: una sessió per arxiu o totes en una sola sessió (segments, ordenats per hora).
  - **Hora d'inici:** data i hora es trien amb el selector de data i hora. Els enregistradors sovint guarden com a hora del fitxer el final de la gravació; amb l'interruptor **"Hora del fitxer = final"** (per defecte activat) PIROL calcula inici = hora del fitxer menys la durada; desactivat, l'hora del fitxer es considera l'inici. Sota el camp s'indica d'on prové l'hora.
  - **Escaneig després de la importació:** després, PIROL ofereix l'escaneig (diàleg de re-anàlisi). Si s'han importat diversos fitxers, cadascun com a sessió pròpia, la tria s'aplica a **totes** les sessions noves, que s'escaneen una darrere l'altra. Si l'original d'una sessió encara és buit (sense deteccions), un escaneig complet esdevé l'**original**: `detections.jsonl`, `verifications.jsonl` i el fitxer Raven queden llavors directament a la carpeta de la sessió, sense cap pas manual. Això també val si ja hi ha escaneigs més antics; en obrir una sessió així, PIROL adopta l'escaneig complet més recent. Els escaneigs posteriors segueixen sent variants, els cancel·lats mai no s'adopten.
  - **Cancel·lació:** si es cancel·la la importació (o falla), s'esborra la sessió que s'estava important a mitges; les sessions ja acabades d'una importació múltiple es conserven. Això és diferent de la re-anàlisi, que conserva el seu resultat parcial. Si es cancel·la el diàleg d'escaneig, es descarten els escaneigs en espera.

**Capçalera:** data · municipi · observació en una línia; un toc a l'observació obre el diàleg. El commutador Compacte/Gran correspon al de la targeta d'espècies.

**Seleccionar diverses gravacions:** la premuda llarga sobre una gravació a la llista de sessions activa el mode de selecció; les altres gravacions es trien amb un toc. La barra superior mostra "N seleccionades" amb les accions "Totes", "Moure" i "Esborrar"; la X o la tecla Enrere surt del mode. La gravació que s'està fent no es pot seleccionar.
- **Moure:** la carpeta de destinació es tria lliurement (selecció de carpeta, també targeta SD o memòria USB). Cada sessió es copia amb tots els fitxers (segments de gravació, memos), es comproven les mides dels fitxers i després s'esborra l'original. En cas d'error o cancel·lació, l'original es conserva i la còpia parcial s'elimina. Si a la destinació ja existeix una sessió amb el mateix nom, s'afegeix `-2`, `-3`, etc. El progrés és visible i cancel·lable. Una carpeta de destinació dins de `Downloads/PIROL` (o la pròpia gravació) es rebutja; en aquest cas no es mou res.
- **Esborrar:** després de confirmar ("Esborrar N gravacions?"); no es pot desfer. L'esborrat de gravacions individuals amb la icona de paperera es manté com fins ara.

---

## 7. Exportació

**Raven Selection Table** (`recording.selections.txt`): s'escriu automàticament en aturar, al costat del WAV, separat per tabuladors, UTF-8. S'obre a Cornell Raven, Audacity (com a pista d'etiquetes) i Sonic Visualiser.

Hi ha, entre d'altres, Selection, Begin File (en segments, el WAV corresponent), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (amb desfasament), Species, Confidence, Status (amb segon model, Second Rank). Quines columnes addicionals s'escriuen es defineix a Configuració > Exportació i càrrega > Camps d'exportació Raven. L'exportació KML ja no existeix.

### Raven: una línia per episodi
Les deteccions consecutives d'una espècie formen un **episodi**; la taula té una línia per episodi (ja no una per entrada agrupada). Una línia nova comença quan la pausa és més llarga que una finestra d'anàlisi. Confidence és el màxim de l'episodi, Begin File el segment del seu inici. Columnes addicionals: `Cluster` (identificador de la primera entrada) i `Windows` (nombre de finestres). La taula Raven de l'app mostra les mateixes línies.

### Raven: memos
Cada memo de sessió (capítol 2) apareix com a línia pròpia amb Species `Memo` a la seva posició dins de la gravació. La columna `Memo` conté la ruta del fitxer de memo (també en memos d'espècies); es pot desactivar a Camps d'exportació Raven. Les línies de memo també apareixen a les taules de les variants de re-anàlisi.

**Paquet ZIP:** la sessió com a ZIP, per compartir manualment o per a la càrrega automàtica (capítol 12).

### Amagar veus humanes (privadesa)
PIROL substitueix les veus humanes per soroll a les **còpies d'exportació** (Desar com a ZIP o carpeta, ZIP de càrrega, compartir fragments): on el model detecta persones, es substitueix la banda de veu (aprox. 300-3900 Hz) amb un marge de 0.5 s; la resta no es modifica. La gravació original, les deteccions i Raven no es modifiquen mai. La llista `human_windows.jsonl` es crea en directe i durant la re-anàlisi; les zones encara no avaluades (sessions importades o antigues) PIROL les analitza en exportar (amb progrés, cancel·lable).
- **Configuració:** Configuració > Exportació i càrrega > Privadesa > "Amagar veus humanes a l'exportació" (per defecte activat). En desactivar-ho, PIROL avisa ("No amagar més les veus?"), perquè les exportacions poden contenir converses de persones alienes. En mode expert, "Llindar humà" fixa a partir de quina puntuació es amaga (més baix = més prudent).
- **Memos:** els memos resten sempre sense modificar; el període d'un memo en directe dins de la gravació queda exclòs de l'ocultació.
- **Model sense classe humana:** si el model d'una sessió no coneix les veus humanes, PIROL pregunta en desar, carregar o compartir: amb BirdNET 3.0, buscar persones (no durant una gravació, pot trigar minuts), exportar sense amagar (amb avís) o cancel·lar. La càrrega automàtica en aquest cas no puja res sense emmascarar, sinó que espera la decisió.

**Noms de fitxer:** les deteccions compartides s'anomenen `AAAAMMDDHHMMSS_Espècie_Observació.ext` (p. ex. `20260420143005_Merla_Jardi_darrere.wav`), els paquets ZIP `AAAAMMDDHHMMSS_Observació.zip` (hora = inici de la sessió). Si falta l'observació, aquella part s'omet; els espais i caràcters especials es converteixen en `_`.

---

## 8. Mapa

Deteccions georeferenciades en un mapa. El **menú del mapa** s'obre amb un lliscament des de la vora esquerra o amb la icona de menú a dalt a l'esquerra ("Obrir menú del mapa"). Conté visualització, font del mapa, mapes fora de línia i el filtre d'espècies.

- **Font del mapa:** automàtica (swisstopo a Suïssa, OpenStreetMap fora), OpenStreetMap, swisstopo Mapa, swisstopo Ortofoto.
- **Fora de línia:** per als mapes de swisstopo es pot descarregar la part visible ("Descarregar fragment", amb indicació del nombre de rajoles i la mida). Una cancel·lació conserva les rajoles ja carregades; en tornar a començar, només es carreguen les que falten. "Esborrar mapes fora de línia" elimina totes les rajoles desades.
- **Visualització:** totes les sessions, "Només Live" o "Últimes 3". Les observacions rebutjades estan amagades. Els marcadors que se solapen s'agrupen segons el zoom. El mapa comença a la pròpia posició.
- **Tocar un punt:** un toc sobre un punt obre un menú amb Verificar (Confirmar, Incert, Rebutjar), "Editar", "Moure posició" i Reproduir (Reproduir o "Aturar reproducció"). Una premuda llarga obre el mateix menú i permet, a més, vincular observacions com a observació simultània. Es reprodueix el fragment de la detecció de la gravació de la sessió; si no hi és, apareix un avís.
- **Marcadors de memo:** els memos de sessió amb posició (capítol 2) apareixen com a marcadors de micròfon. Un toc obre un diàleg amb l'hora i Reproduir. Són visibles amb "Totes les sessions" i "Últimes 3", no amb "Només Live".
- **Diversos punts superposats (clúster):** un toc sobre un clúster mostra la llista de deteccions. A cada entrada hi ha Verificar, Reproduir i "Moure posició".
- **Moure posició:** el mapa mostra a dalt l'avís "Desplaceu el mapa: creueta a la nova posició de ..., després Acceptar." Desplaceu el mapa de manera que la creueta del centre quedi sobre la nova posició, i després premeu "Acceptar" (o "Cancel·lar") a baix. Després apareix una barra emergent amb **Desfer**. El desplaçament també funciona per a punts en directe; si no hi ha cap sessió en marxa, la nova posició només és vàlida fins que es tanqui l'app.
- **Filtre d'espècies:** al menú del mapa hi ha el filtre: "Només espècies d'alarma" o amagar espècies individuals (Totes/Cap). El filtre és vàlid fins que es tanqui l'app; les rutes continuen visibles.
- **Ruta:** una ruta gravada durant la gravació apareix com a línia (en mode "Només en detecció" discontínua, només als punts de detecció). Vegeu el capítol 14.
- **Botó de localització:** centra el mapa a la pròpia posició i fa zoom perquè l'alçada visible del mapa sigui d'uns 200 m.
- **Botons de zoom:** els botons +/- són un 50 % més grans i estan sobre el botó de localització.

---

## 9. Referències i espècies semblants

Les deteccions verificades es poden desar com a referència ("Desar com a referència") i treure's de nou. La pestanya Referències mostra les gravacions desades; també és possible importar àudio. Les noves deteccions es comparen amb les referències mitjançant embeddings.

Configuració > Espècies semblants i referències: cerca de semblances activada/desactivada, nombre de línies mostrades (0 = desactivat, 0.5 = text desplaçable de les últimes 5, 1-10 = llista), límit de gravacions de comparació desades.

---

## 10. Llista de seguiment i alarma

**Llista de seguiment:** espècies objectiu que disparen una alarma. Gestió a Configuració > Llista de seguiment i alarma: cercar i afegir una espècie, treure-la, o carregar-la des de `watchlist.json`. Les majúscules i minúscules i els espais no influeixen en la comparació.

**Carpeta compartida:** a "Compartir carpeta de PIROL" trieu una vegada `Downloads/PIROL`. Llavors PIROL llegeix automàticament `watchlist.json` i les carpetes `lists/` (detalls al capítol 11). "Crear llista d'exemple" genera una plantilla.

**Sortida d'alarma** (activable i desactivable per separat): vibració, notificació, avís a pantalla completa. A la **pantalla de bloqueig**, l'alarma a pantalla completa només apareix si "Notificacions a pantalla completa" està permès per a PIROL a la configuració d'Android (botó "Obrir permís"). Si no, continua sent una notificació. Amb "Provar alarma" (després de 5 s, bloquegeu el dispositiu) podeu comprovar-ho.

Dins de l'alarma, l'espècie es pot **confirmar** directament; "Desfer" continua disponible després.

**Pausa d'alarma:** interval mínim entre alarmes de la mateixa espècie (0 = cada coincidència dispara una alarma).

---

## 11. Grups d'espècies i llistes d'espècies

El filtre d'espècies actua per grup: **Ocells, Llagostes, Amfibis, Mamífers**, cadascun **Desactivat / Tots / Automàtic / Llista**; **Altres** només **Desactivat / Tots** (per defecte Desactivat). "Automàtic" utilitza el geomodel (ubicació i setmana de BirdNET). Les espècies que el geomodel no coneix, així com els grups sense màscara del geomodel, utilitzen la llista acompanyant del grup (Ocells: llista de Suïssa; Llagostes i Amfibis: la llista de Suïssa de sota; Mamífers: totes). "Llista" tria una de les llistes incloses o una pròpia. S'inclou la llista "Llagostes i amfibis de Suïssa" (dades de GBIF, CC BY 4.0).

**Automàtic sense GPS:** si no hi ha cap posició actual, s'aplica l'última posició desada durant 30 dies com a màxim; després, "Automàtic" usa la llista de suport del grup. Un avís al començament de la gravació ho indica, i Configuració > Espècies i alarma (geomodel) mostra la base ("Sense GPS s'aplica l'última posició del ..." o "Sense posició actual: s'aplica la llista de suport del grup"). La posició desada només s'actualitza en un càlcul amb fix GPS. Opcionalment, la **llista anual** ignora l'època de l'any (interruptor a la secció del geomodel).

A l'editor de llistes es poden triar espècies per ordre, família i gènere; "Mostrar noms científics" commuta la forma del nom.

### Llistes d'espècies pròpies

Les llistes d'espècies pròpies es poden desar com a fitxers JSON a la carpeta `lists/` de la carpeta compartida de PIROL (`Downloads/PIROL/lists/*.json`). Apareixen com a llistes seleccionables als grups d'espècies (opció "Llista"). Les llistes es **tornen a llegir en cada inici de gravació**; els canvis s'apliquen, per tant, a partir de la següent gravació. Les llistes amb errors s'ometen i se senyala el motiu. Com a plantilla serveix "Crear llista d'exemple" (`lists/beispiel_liste.json`).

Els noms científics antics i nous (p. ex. després de canvis taxonòmics) es fusionen, de manera que les llistes funcionen entre models.

---

## 12. Càrrega

Configuració > Càrrega: les sessions gravades es poden carregar automàticament després de la gravació.

| Destinació | Dades |
|------|---------|
| Desactivat | només còpia local, exportació manual |
| Carpeta | qualsevol carpeta (dispositiu, Drive, OneDrive ...) per selecció de carpeta |
| NAS (SMB / carpeta compartida de Windows) | amfitrió, carpeta compartida, ruta, usuari, contrasenya |

La contrasenya del NAS es desa xifrada al dispositiu. "Provar connexió" comprova les dades. Opcions: carregar només amb WLAN, no conservar la còpia ZIP local després de la càrrega. L'estat de l'última càrrega apareix a sota.

---

## 13. Models

Configuració > Model i detecció > Model:

| Model | Propietats |
|--------|---------------|
| BirdNET 3.0 FP16 (recomanat) | Estàndard, 11'725 classes, 32 kHz, finestra de 3 s |
| BirdNET 3.0 FP16 compacte (pruned) | més petit (uns 70 MB), detecció pràcticament igual |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | versió prèvia anterior, 11'560 classes |

El model actiu es canvia seleccionant-lo, a partir de la següent anàlisi. El nombre d'espècies al costat del model s'actualitza de seguida després del canvi. Els models es poden descarregar (es pot cancel·lar i reprendre), importar i esborrar. Cada sessió recorda el model utilitzat.

**Taula de models (des de la v0.1.1):** la columna Principal (botó de ràdio) determina el model principal. Secundari (casella, només en mode expert) tria el segon model que comprova cada detecció; es pot desmarcar, i mai pot ser igual al principal. La paperera esborra un model instal·lat després de confirmar; el principal no es pot esborrar i està bloquejat durant una gravació. Els models no carregats mostren a la fila una icona de càrrega amb progrés i cancel·lació.

**Llindar de confiança:** el regulador "Llindar de confiança (totes les espècies)" és igual per a totes les espècies (per defecte 50 %). Les alarmes de la llista de seguiment només es disparen a partir d'aquest llindar.

**Confiança mínima:** el llindar de detecció és global i ajustable. Un model pot portar a més una confiança mínima pròpia (p. ex. 80 % per a Perch). Preval el valor més alt; el model el mostra.

### Importar models propis i altres
PIROL no està limitat a BirdNET. Amb "Importar un altre model..." se selecciona conjuntament un paquet de tres fitxers: `.onnx` (model), `.pirol.json` (perfil) i CSV d'etiquetes. L'app comprova les sumes de verificació, les entrades i sortides i el nombre d'etiquetes, i informa dels errors en text clar. La freqüència de mostreig i la durada de la finestra són lliures.

Estan verificats **BirdNET 2.4** (6'522 espècies, 48 kHz, aproximadament la meitat del temps de càlcul de 3.0), **Perch v2** (Google, 14'795 classes, finestra de 5 s) i **Nighthawk 0.3.1**. Els fitxers no formen part de l'app. Per a la conversió de models de TensorFlow, Keras i TFLite (també entrenaments propis) hi ha l'script `tools/convert_model.py`, vegeu `tools/README_convert_model.md` i `tools/MODEL_PROFILE_FORMAT.md`.

**Llicències:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 i Nighthawk només no comercial (CC BY-NC o BY-NC-SA respectivament). La llicència es mostra en importar.

**Nota:** Perch treballa a 32 kHz; per això les llagostes de fulla als ultrasons no són audibles.

**Estadística del segon model (mode expert):** a Configuració > Model i detecció, el botó "Estadística del segon model" obre un diàleg. PIROL llegeix per a això totes les sessions (només els originals, només de lectura; les variants de re-anàlisi i les entrades manuals no compten) i mostra una taula creuada: verificació (confirmada, rebutjada, incerta, oberta, corregida/substituïda) contra el resultat del segon model (Verificat, No verificable, Discrepa, Sense resultat), en total i per segon model. Hi ha a més barres de progrés cap a l'objectiu (200 deteccions confirmades i 50 rebutjades amb resultat del segon model) i la proporció de discrepàncies en les deteccions confirmades i en les rebutjades. Ajuda a jutjar si el segon model és útil com a ajut de verificació. En tancar el diàleg es cancel·la la lectura.

**Perch com a segon model:** necessita uns 650 MB d'espai addicional. L'app avisa en activar-lo si el dispositiu té menys de 6 GB de RAM. El segon model treballa amb l'àudio brut de la gravació, perquè les finestres de 5 s incloguin l'entorn real.

---

## 14. GPS

Configuració > Mapa i GPS (fins a la v0.0.10: Gravació i emmagatzematge):

| Configuració | Significat |
|-------------|-----------|
| Mode GPS "Permanent" | posicions contínues a l'interval configurat (2/5/10/20/60 s, per defecte 10 s) |
| Mode GPS "Només en detecció" | GPS només en una detecció, com a mínim 30 s entre dues posicions; l'interval no s'aplica. Estalvia uns dos terços de bateria |
| Imprecisió GPS màx. | les posicions més imprecises es descarten (per defecte 50 m) |
| Suavitzat GPS | mediana de 5 posicions, elimina valors atípics |

Cada detecció rep una posició nova, també amb la pantalla apagada. Els canvis s'apliquen a partir de la següent gravació.

**Posició inicial:** en iniciar el programa i en fer zoom al mapa, PIROL obté una posició una vegada sense que hi hagi una gravació en curs (sense seguiment permanent).

**Ruta:** la ruta ("Gravar ruta", per defecte activat) es va escrivint durant la gravació (`track.jsonl`) i en aturar es desa com a `track.gpx` al costat de la gravació (hores en UTC).

**Municipi:** el botó "Inserir municipi" escriu el municipi de la posició a l'observació (al diàleg de detall, a l'observació de la sessió i a l'anàlisi). Les dades de municipis (Suïssa) són fora de línia a l'app. Fora d'aquestes no es determina res, excepte si l'interruptor expert Configuració > Mapa i GPS > Avançat > "Determinar municipi en línia (Google)" està activat (per defecte desactivat; en aquest cas, les coordenades s'envien a Google).

---

## 15. Energia i bateria

Configuració > Model i detecció > Perfil d'energia:

| Perfil | Anàlisi |
|--------|---------|
| Precís | cada finestra de 3 s |
| Equilibrat | cada 6 s |
| Estalvi | cada 15 s, per a jornades de camp llargues |
| Ultra estalvi | cada 30 s, durada màxima |

**Valors pràctics** (tauleta Galaxy Tab A11+, 6'840 mAh, gravació contínua, WLAN activada, BirdNET 3.0 FP16; valors orientatius, aprox. ±15 %):

| Configuració | Consum | Durada amb bateria plena |
|-------------|-----------|----------------------|
| Precís, pantalla apagada, GPS permanent (10 s) | aprox. 2.2 %/h | aprox. 45 h |
| Igual que l'anterior, 67 % de solapament | aprox. 3.5 %/h | aprox. 28 h |
| Igual que l'anterior, **pantalla encesa** | aprox. 9 %/h | aprox. 12 h |
| Precís, GPS només en detecció | aprox. 1.5 %/h | aprox. 60 h |
| Estalvi (15 s), GPS 60 s | aprox. 0.9 %/h | aprox. 4-5 dies |

**Consells:**
1. **Apagar la pantalla** és la palanca més gran (més del doble que la gravació i l'anàlisi juntes). La gravació continua en el servei.
2. Triar el mode GPS "Només en detecció".
3. Deixar el solapament al 0 %; per a sortides llargues, el perfil "Estalvi".
4. La tria del model gairebé no importa (pruned és similar a final, Preview 3.1 uns 10 % més, FP32 no és millor que FP16).

---

## 16. Idiomes

Configuració > Idioma: idioma de l'app (idioma del sistema o selecció): alemany, anglès, francès, italià, català, portuguès, neerlandès, alemany suís. **L'alemany suís i el portuguès són esborranys**; els textos que falten apareixen en alemany. L'idioma dels **noms d'espècies** segueix l'idioma de l'app, però es pot configurar per separat (23 idiomes). Els noms d'espècies apareixen de manera coherent en l'idioma triat, també al mapa, a Raven i per a deteccions antigues (es resolen de nou en mostrar-los; els fitxers desats no canvien); les espècies reanomenades es troben també amb el seu nom antic. Si falta un nom en l'idioma triat, PIROL pren per a l'alemany el nom alemany de les llistes d'espècies incloses i per a altres idiomes primer el nom anglès.

---

## 17. Configuració en resum

Les opcions estan agrupades en seccions plegables; l'estat es recorda. Per defecte només és oberta la secció "Idioma".

| Secció | Contingut |
|---------|---------|
| Idioma | idioma de l'app, idioma dels noms d'espècies |
| Gravació | informació d'àudio, preroll, solapament, perfil d'energia, carpeta d'emmagatzematge (carpeta, subcarpetes diàries, esborrat automàtic, moure sessions), durada màxima de gravació i de fragment, remostrejador |
| Model i detecció | models (descàrrega, importació, esborrat), llindar de detecció (totes les espècies), alternatives a partir de, màxim d'espècies per finestra, preajustos, suggeriments d'espècies, agrupació de deteccions, espècies semblants i límit d'embeddings |
| Espècies i alarma | filtre regional i llistes pròpies, llista de seguiment, pausa i sortida d'alarma, provar alarma, carpeta de PIROL, estacionalitat i raresa |
| Visualització | idioma dels noms d'espècies, pantalla completa, sonograma (mode, preajust, contrast, gamma, límit, paleta, freqüència, finestra temporal), targeta d'espècie compacta |
| Mapa i GPS | mode GPS, gravar ruta, interval GPS, precisió i suavitzat; expert (Avançat): "Determinar municipi en línia (Google)" |
| Exportació i càrrega | camps d'exportació Raven (incl. memo), privadesa (amagar veus humanes, llindar humà), càrrega (destinació carpeta/NAS, contrasenya, prova de connexió, només WLAN) |
| Espècies semblants i referències | cerca de semblances, nombre de línies, gravacions de comparació (capítol 9) |
| Info | versió, fonts, novetats, diagnòstic (prova de rendiment), "Donar suport a PIROL" |

L'interruptor **Mode expert** (sota "Iniciar la configuració") amaga en el mode senzill tot el que és poc freqüent; en mode expert és tot visible. No es perd cap configuració. Les opcions poc freqüents es troben al subbloc plegable "Avançat" de cada secció (p. ex. filtre passa-alts, agrupació de deteccions, fenologia, durada màxima de gravació).

**Remostrejador:** Polifàsic (recomanat) filtra el soroll per sobre de 16 kHz, que altrament podria aparèixer com a senyals fantasma. "Lineal" és el mètode antic. El canvi mostra un avís i té efecte sense reiniciar la gravació.

**Donar suport a PIROL:** a Configuració > Info, el botó "Donar suport a PIROL" obre la pàgina de donatius al navegador. La donació és voluntària; PIROL continua sent gratuït i complet.

**Diagnòstic:** la prova de rendiment mesura el temps de càlcul del model actiu amb 1/2/4 fils (1-3 minuts, no durant una gravació). Amb "Triar fitxer ONNX i mesurar" també es pot provar qualsevol model abans de importar-lo.

---

## 18. Notes tècniques

- **Gravació:** 48 kHz, PCM de 16 bits, mono (96 kHz en mode Ratpenat). La detecció es converteix a 32 kHz (BirdNET 3.0).
- **Dispositiu mínim:** Android 8.0. Compatible amb pàgines de memòria de 16 KB (Android 15+).
- **Fairphone i altres dispositius:** la freqüència de mostreig es negocia amb el dispositiu; si hi ha problemes, l'app canvia a un altre controlador d'àudio.
- **Formats de dades:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven TSV; marques de temps en hora local amb desfasament.

---

## 19. Canvis

La llista completa és a `CHANGELOG.md` del projecte.

### Versió 0.1.3
- Memos de sessió: esborrar amb desfer, ubicació desada en prémer i mostrada com a marcador al mapa, compartir i desar com a (capítols 2, 6, 8)
- Importació: selector de data i hora, interruptor "Hora del fitxer = final", escaneig per a totes les sessions importades; un escaneig esdevé l'original si l'original és buit, amb Raven a la carpeta (capítol 6)
- Canviar la data d'una sessió importada reanomena la carpeta i la mou a la carpeta del dia correcte (capítol 5)
- "+ Espècie" té en compte els buits entre segments (capítol 6)
- Espectre: Lineal (per defecte) o Mel, gamma també a l'espectre, finestra temporal a l'oscil·lograma (capítol 3)
- Anàlisi: zoom amb dos dits; la càrrega comunica el resultat en una barra emergent (capítol 6)
- Re-anàlisi més ràpida, segon model en paral·lel (capítol 6)
- Filtre automàtic sense GPS: última posició fins a 30 dies, avís a l'inici, base a la configuració, llista anual (capítol 11)
- Estadística del segon model en mode expert (capítol 13)
- Noms d'espècies coherents en l'idioma triat, noms alemanys de les llistes d'espècies (capítol 16)
- Botó "Donar suport a PIROL" (voluntari) a Configuració > Info (capítol 17)
- El mode d'ajuda cobreix més controls, també marcadors del mapa i diàlegs (capítol 1)
- Nou capítol 20: Instal·lació i actualització

### Versió 0.1.2
- Editar a posteriori les dades de sessions importades (hora d'inici, lloc, observació) (capítol 5)
- "Desar com a" com a ZIP o carpeta (capítol 6)
- Raven: una línia per episodi, noves columnes `Cluster`, `Windows` i `Memo` (capítol 7)
- Re-anàlisi i importació: resultats visibles i verificables progressivament, en la re-anàlisi la cancel·lació conserva el resultat parcial; en la importació esborra la sessió importada a mitges (capítol 6)
- Les veus humanes s'amaguen a l'exportació, per defecte activat; consulta en cas de model sense classe humana (capítol 7)
- Municipi en línia a través de Google només com a opció, per defecte desactivat (capítol 17)
- Memo de sessió amb premuda sostinguda, bloc "Memos" a l'anàlisi, línia de memo a Raven (capítols 2, 6, 7)
- Targetes d'espècies en directe més tranquil·les: canvi com a màxim cada 5 s, les coincidències dins de les 5 primeres parpelleen en blau (capítol 2)
- Botó d'ajuda "?": mode d'ajuda i manual fora de línia (capítol 1)
- Manual corregit: filtre d'espècies al menú del mapa (capítol 8), noms de seccions de Configuració (capítols 1, 5, 7, 17)

---

## 20. Instal·lació i actualització

**Quina APK?** Cada versió està disponible com a release a GitHub (Nikiphedia/Pirol, secció "Releases") amb una APK per arquitectura de processador:
- `Pirol-<Versió>-arm64-v8a-release.apk`: per a gairebé tots els telèfons i tauletes actuals. En cas de dubte, aquest fitxer.
- `Pirol-<Versió>-armeabi-v7a-release.apk`: només per a dispositius antics de 32 bits.

**Instal·lació:**
1. Descarregueu l'APK adequada del release de GitHub al dispositiu i obriu-la. Android pregunta la primera vegada si el navegador o el gestor de fitxers utilitzat pot instal·lar aplicacions; permeteu-ho una vegada.
2. **Dispositius Samsung:** la funció **"Bloqueig automàtic"** (Auto Blocker; Configuració > Seguretat i privadesa) impedeix instal·lar aplicacions fora de la botiga. Desactiveu-la breument per a la instal·lació, instal·leu PIROL i torneu-la a activar després. Això es repeteix **a cada actualització**.
3. Toqueu Instal·lar i obriu l'app. En el primer inici, l'assistent de configuració us guia per les opcions bàsiques (capítol 1).

**Actualització:** instal·leu la nova APK sobre la instal·lació existent (mateixa arquitectura; en Samsung, de nou amb el Bloqueig automàtic desactivat breument). Sessions, models, llistes i configuració es conserven; no cal desinstal·lar res. Notes sobre versions anteriors són al capítol 1 (Actualització de versions anteriors). Després de l'actualització, PIROL mostra una vegada "Novetats".

---

*PIROL v0.1.3 — ETA Systems GmbH, 5004 Aarau — Estat 2026-11-25*
