# PIROL — Manual de utilização

Reconhecimento acústico de espécies em tempo real para Android, pensado para o trabalho de campo.

*Válido a partir da versão v0.1.2 (estado 2026-11-24). As alterações entre versões estão em `CHANGELOG.md`.*

A app tem cinco separadores: **Live**, **Análise**, **Referências**, **Mapa** e **Definições**. O botão **?** (Ajuda) não é um separador: um toque ativa o modo de ajuda, um duplo toque abre este manual (capítulo 1, secção "Botão de ajuda").

---

## 1. Primeiro arranque

1. Abra a app. O assistente de configuração conduz pelo idioma, nível (principiante/especialista), utilização prevista, lista de espécies, modelo (incluindo o geomodelo), permissões (microfone, GPS, notificações) e resumo. Os valores só são aplicados ao premir "Concluído".
2. **Escolher a lista de espécies** (capítulo 11): os grupos de espécies (Aves, Gafanhotos, Anfíbios, Mamíferos, Outros) limitam a deteção a espécies plausíveis. Pode ser alterado mais tarde em Definições > Espécies e alarme.
3. **Transferir o modelo**: podem selecionar-se vários modelos de uma só vez. O modelo padrão é o BirdNET 3.0 FP16 (recomendado, cerca de 170 MB). A transferência continua em segundo plano, pode ser cancelada e retomada mais tarde. Em alternativa, pode escolher-se manualmente um ficheiro de modelo.
4. **Verificar a pasta de armazenamento** (Definições > Gravação). Predefinição: `Downloads/PIROL/`, visível por USB.

**Instalação (APK):** existe um APK de versão para cada arquitetura de processador. Para os dispositivos atuais (telemóveis e tablets), instale o ficheiro `Pirol-<Versão>-arm64-v8a-release.apk`; apenas para dispositivos antigos de 32 bits, o ficheiro `...-armeabi-v7a-release.apk`. Os APK ficam assim bastante mais pequenos do que o antigo APK comum. A app tem um novo ícone (pássaro amarelo), também como ícone monocromático para ícones temáticos.

**Novidades:** após uma atualização, o PIROL mostra uma vez um diálogo "Novidades" com as alterações mais importantes da versão. Pode ser reaberto a qualquer momento em Definições, na secção "Info".

### Botão de ajuda
O botão **?** (tablet e navegação estreita: em Definições; telemóvel: à direita, na barra) oferece duas formas de utilização:
- **Um toque** ativa ou desativa o **modo de ajuda**. No topo aparece a barra "Modo de ajuda: toque num botão para uma explicação". Um toque num botão ou controlo não executa então a ação, mas mostra uma explicação breve, se necessário com "Mais no manual" (salta para o capítulo correspondente). Para sair: X na barra, Voltar ou premir novamente **?**.
- **Duplo toque** abre este manual offline, no idioma da app (se a tradução faltar, em alemão). Um índice leva às secções.

**Atualização a partir de versões anteriores:** desde a v0.0.8, o PIROL está assinado. Quem tiver instalada a v0.0.7 ou anterior tem de desinstalar uma vez e, antes, guardar as sessões por USB (`Android/data/ch.etasystems.pirol/files/PIROL/`). Da v0.0.8 à v0.0.10 a atualização para a v0.0.11 faz-se sem desinstalar; sessões e modelos são mantidos. Atenção na v0.0.11: o limiar de confiança passou a ser um limiar comum a todas as espécies (capítulos 2 e 13); verifique depois o regulador. Da v0.0.11 para a v0.1.1 a atualização faz-se sem desinstalar. Depois, verifique os grupos de espécies (capítulo 11): os filtros antigos estavam em Aves Automático, o resto Desativado. Sessões da v0.0.6 e anteriores já não têm suporte específico.

---

## 2. Deteção em direto

**Início:** prima o botão de gravação no separador Live. **Parar:** prima o botão longamente. A sessão e a exportação Raven são guardadas automaticamente.

A gravação e a análise decorrem num mesmo serviço. Continuam quando a app passa para segundo plano ou o ecrã é desligado.

Ao parar, aparece no topo a indicação "Prima longamente para parar" (no topo, para que o dedo sobre o botão não a tape).

As espécies detetadas aparecem como cartões por baixo do sonograma:
- Nome da espécie no idioma escolhido, com o nome científico como complemento
- Confiança em percentagem (barra fina), hora, posição GPS
- Alternativas (expansíveis, tocáveis)
- Avisos "Raro", "Fora da época", marca de lista de vigilância
- **Reproduzir:** o botão de reprodução do cartão reproduz o excerto da deteção (3 s antes, 5 s depois), também durante a gravação. Um segundo toque para a reprodução.
- **Intermitência:** se uma espécie for novamente detetada, o seu cartão pisca brevemente a azul. A lista não salta (ver "Lista calma").
- **Sino de alarme:** no menu do cartão completo, a espécie é colocada de forma permanente na lista de alarmes ou por 24 h. Os alarmes de 24 h mostram um sino com o distintivo "24h"; após o termo, desaparece automaticamente.
- **Botão compacto:** alterna todos os cartões de espécies entre compacto e detalhado. No telemóvel está na barra de estado, no tablet no cabeçalho. As alternativas abrem-se e fecham-se a partir do cabeçalho do cartão.

**Vista compacta no telemóvel:** em formato vertical e horizontal, o separador Live aproveita o espaço com parcimónia: sem barra de título, a navegação só tem ícones, e a linha de botões dos cartões ocupa uma só linha. Em cartões estreitos, Partilhar, Referência, Comparar e "Alternativas" estão no menu "Mais ações" (ícone de três pontos); no tablet, todos os botões ficam visíveis. Em formato horizontal, à esquerda estão o sonograma e os botões, à direita os cartões de espécies. O painel "Espécies semelhantes" no telemóvel está recolhido e pode ser expandido.

**Microfone silenciado durante a reprodução:** se o PIROL reproduzir uma deteção pelo altifalante durante a gravação, o microfone é silenciado para que a reprodução não seja de novo detetada ou gravada. No separador Live aparece a indicação "Microfone silenciado durante a reprodução". Com auscultadores ligados (cabo, USB, Bluetooth) o microfone mantém-se ativo.

| Botão | Estado |
|-------|---------|
| verde, a pulsar | o buffer de pré-gravação está a funcionar (gravação ainda não iniciada) |
| vermelho, a pulsar | a gravação está a decorrer |

**Linha de estado:** duração em mm:ss. **Barra GPS:** precisão atual em ±m, a vermelho se houver mais de 30 s sem uma posição válida.

**Observação:** por gravação pode introduzir-se um título. Aparece na lista de sessões, no nome do ZIP e na exportação Raven.

**Adicionar manualmente:** introduzir uma espécie à mão (com número de exemplares, código de atlas, observação). Por deteção podem ainda registar-se o número de exemplares, o código de atlas, a observação e um memorando de voz. Uma espécie pode ser bloqueada (apenas uma comunicação por sessão).

**Agrupar:** as deteções da mesma espécie são agrupadas espacial e temporalmente numa só entrada (raio e janela temporal ajustáveis, Definições > Modelo e deteção).

### Lista calma
A ordem dos cartões de espécies altera-se no máximo a cada 5 s. As espécies novas são recolhidas e inseridas em conjunto no topo (o primeiro cartão aparece de imediato); podem, por isso, tornar-se visíveis até 5 s mais tarde, mas os alarmes chegam sempre de imediato. Se uma espécie for de novo detetada dentro dos 5 primeiros cartões, mantém-se no lugar e pisca a azul. Uma espécie mais abaixo sobe na próxima mudança e pisca. Eliminar, verificar e espécies bloqueadas têm efeito imediato. As deteções, o alarme, o resumo e a análise não são afetados.

### Memorando de sessão
O botão de memorando no separador Live (visível apenas durante uma gravação) funciona como um walkie-talkie: **mantenha-o premido e fale**; ao soltar, o memorando termina. Durante a gravação o botão fica vermelho e mostra a duração; depois aparece "Memorando guardado (n s)". Memorandos com menos de 0.5 s são descartados ("Muito curto"); após 3 minutos o memorando termina automaticamente. O memorando é guardado com a sua posição na gravação (`memos.jsonl`, ficheiros em `memos/`), aparece na análise no bloco "Memorandos" (capítulo 6) e como linha "Memo" na exportação Raven (capítulo 7). Os memorandos nunca são ocultados como voz (capítulo 7). Os memorandos de voz sobre espécies individuais permanecem separados.

### Deteção múltipla e limiar de confiança
Na mesma janela de 3 s são detetadas todas as espécies que atingem o limiar, não apenas a primeira. O regulador chama-se "Limiar de confiança (todas as espécies)" (Definições > Modelo e deteção, predefinição 50 %) e é igual para todas as espécies. As espécies abaixo, mas acima do limite de candidatas, mantêm-se como candidatas nas deteções da janela. Antes, o regulador controlava outro limiar (TopK, predefinição 0.3); por isso, após a atualização, verifique-o. Os alarmes da lista de vigilância só disparam a partir do limiar.

### Segundo modelo (modo especialista)
Um segundo modelo (p. ex. Perch v2 ou BirdNET 2.4, capítulo 13) recalcula as mesmas janelas. O cartão mostra então, ao lado da confiança do modelo principal, o valor do segundo modelo: em percentagem, ou como posição no caso do Perch ("Perch #2"). As espécies que o segundo modelo não conhece são marcadas como não verificáveis. O segundo modelo não influencia alarme, filtros nem resumo. O limiar "Alternativas a partir de" (5/10/20/30 %, Definições > Modelo e deteção) determina a partir de que confiança são listadas alternativas.

### Pré-gravação
Se estiver ativa, é guardado áudio **antes** do início da gravação (duração ajustável) e também é analisado. A pré-gravação fica sem falhas no início de `recording.wav`. O sonograma mostra no máximo 5 s dela.

### Sobreposição
As janelas de análise podem sobrepor-se: 0 % (predefinição, uma janela a cada 3 s), 50 % (a cada 1.5 s) ou 67 % (a cada segundo). Mais sobreposição deteta melhor os chamamentos curtos nas fronteiras das janelas, mas precisa de mais tempo de cálculo e bateria. Se o dispositivo for demasiado lento, o PIROL reduz automaticamente a sobreposição e informa uma vez por sessão numa barra de aviso. Com modelos de janela mais longa (Perch, 5 s), o regulador "Passo máx." (3, 4 ou 5 s) limita a distância entre duas janelas.

### Gravações longas (segmentos)
Após a duração máxima configurada (predefinição 60 min, ajustável entre 10 e 80 min) começa automaticamente um novo ficheiro (`recording-002.wav`, etc.). A gravação prossegue sem interrupção. As indicações de tempo em `detections.jsonl` e no Raven contam a partir do início da sessão. O cabeçalho WAV é atualizado a cada 5 s: se a app for terminada à força, perdem-se no máximo cerca de 3 s de áudio.

### Filtro passa-alto e tratamento
O filtro passa-alto (200 Hz) elimina vento e ruído de passos, ativo por predefinição. Atua apenas na cópia para a deteção; a gravação guardada não é alterada. As alterações aplicam-se a partir da próxima gravação.

---

## 3. Visualização: sonograma, espectro, oscilograma

No separador Live e no separador Análise. O eixo de frequências mostra 1/2/4/8/12 kHz. Por predefinição é mostrado até **12 kHz** (predefinição para aves), ajustável até **24 kHz**. Os níveis do sonograma são apresentados em dBFS.

**Modos de visualização (separador Live):** no painel de visualização (premer longamente) alterna-se entre **Sonograma**, **Espectro** e **Oscilograma**. O espectro mostra o volume por frequência com FFT real (dBFS, média, retenção de picos com pontos que se desvanecem, cursor). O oscilograma mostra a evolução temporal com indicação de nível (pico/RMS), passa-banda e marca vermelha em caso de sobrecarga. Frequência, dinâmica e paleta de cores são comuns às três vistas. Na análise, o espectro e o oscilograma também estão disponíveis.

**Ajustar a visualização:** premer longamente o sonograma (Live ou Análise) abre o painel de definições por baixo do sonograma; os mesmos reguladores estão em Definições > Visualização. O painel é comum a Live, Análise e Definições. No topo do painel está "Concluído" numa linha própria; as predefinições e paletas de cores são mosaicos que quebram linha quando falta espaço.

| Regulador | Efeito |
|--------|---------|
| Predefinição | Padrão / Chamamentos suaves / Ambiente ruidoso |
| Contraste automático | ajusta continuamente o limiar de dB ao sinal; caso contrário, manualmente (intervalo em dB) |
| Intensidade (gama) | menor = partes suaves mais claras |
| Teto de luminosidade | limita sinais muito fortes |
| Paleta de cores | Magma, Viridis, Escala de cinzentos, **Papel (claro)** |
| Frequência | intervalo de frequências mostrado em kHz |
| Janela temporal | segundos visíveis (de 5 a 30 s) |

**Papel (claro):** paleta clara como um sonograma de papel clássico, boa à luz do dia. A paleta escolhe-se no painel (premer longamente o sonograma); no telemóvel já não existe um botão próprio no separador Live.

**Mudança de separador:** se sair do separador Live ou desligar o ecrã, o PIROL recalcula o sonograma ao regressar (até à janela temporal configurada), para que continue sem falhas. Se a ausência tiver sido mais longa do que a janela temporal, recomeça.

**Modo** (Definições > Visualização): Aves, Morcego ou Banda larga. O modo Morcego grava a 96 kHz (ultrassons); neste modo não há pré-gravação.

---

## 4. Verificação

Para cada deteção estão disponíveis estas ações (separadores Live, Análise e Mapa):

| Ação | Estado | Representação |
|--------|-------------|-------------|
| Confirmar | Confirmada | contorno verde |
| Incerto (?) | Incerta | contorno laranja |
| Rejeitar | Rejeitada | esbatida |
| Corrigir | Corrigida | nome substituído pela entrada própria |
| Escolher alternativa | Confirmada | A espécie da deteção é substituída pela alternativa (mesma linha, hora e posição); a espécie antiga mantém-se como candidata. Anular na barra de aviso |

No **separador Live** já não há barra de aviso ao verificar. Após "Confirmar", o cartão mostra um botão verde "Anular"; este repõe a deteção em "Incerta". Os botões Confirmar, Incerto, Rejeitar e Editar mantêm-se visíveis, pelo que o estado pode ser alternado a qualquer momento. Ao escolher uma **alternativa**, ao mover o ponto no cartão e ao confirmar pelo alarme da lista de vigilância, continua a aparecer uma barra de aviso com **Anular** (pelo menos 5 s). Todas as ações são registadas em `verifications.jsonl`; também as deteções rejeitadas e substituídas permanecem na exportação (registo de auditoria).

**Partilhar uma deteção:** excerto (3 s antes, 5 s depois, duração máxima ajustável) como WAV, M4A (AAC), FLAC (se o dispositivo o suportar) ou sonograma PNG com legenda.

---

## 5. Sessões

Cada gravação cria uma pasta de sessão, por predefinição `Downloads/PIROL/AAAA-MM-DD/{sessionId}/`. O nome da pasta inclui a hora local.

```
session.json              metadados (duração, local, estatísticas GPS, modelo utilizado)
detections.jsonl          todas as deteções
verifications.jsonl       todas as ações de verificação
detections.updates.jsonl  ficheiro complementar durante a gravação (ao parar, é fundido em detections.jsonl)
recording.wav             gravação (16 bits, mono, 48 kHz); em sessões longas
recording-002.wav ...     segmentos adicionais
recording.selections.txt  Raven Selection Table (automático ao parar)
memos.jsonl               memorandos de sessão com posição na gravação (ficheiros em memos/)
human_windows.jsonl       lista para ocultar vozes humanas (nunca exportada)
```

- **Espaço necessário:** cerca de 5.5 MB por minuto de gravação.
- **Carimbos de tempo:** sempre hora local com desvio de fuso (p. ex. `+02:00`).
- **Apagar automaticamente:** sessões curtas sem deteção são apagadas (duração mínima ajustável, 0 = desligado).
- **Apagar** uma sessão na análise: com confirmação.
- **Editar dados de sessões importadas:** nas sessões importadas, o menu de sobreposição do cabeçalho (ícone de três pontos) tem a entrada "Editar dados": hora de início, local e observação podem ser alterados posteriormente. Uma nova hora de início desloca todas as horas da sessão (deteções, verificações, variantes, rota) na mesma diferença; as posições na gravação mantêm-se. O Raven e o GPX são reescritos. O local aplica-se à sessão e a todas as deteções sem posição GPS própria; pontos deslocados no mapa mantêm-se. Não é possível durante uma re-análise.

### Mudar o local de armazenamento
Definições > Gravação > Pasta de armazenamento: "Escolher outra pasta" (SAF: memória interna, cartão SD, pasta na nuvem). Opcionalmente com subpastas diárias. Se a pasta não estiver acessível, o PIROL guarda na pasta de recurso da app e informa. Gravações individuais também podem ser movidas no separador Análise (capítulo 6).

**Mover sessões:** ao mudar, o PIROL pergunta se as sessões existentes devem ser **copiadas** para o novo local ("Copiar") ou se só o local deve mudar ("Só mudar").

---

## 6. Separador Análise

- **Lista de sessões:** por data, com duração, tamanho e observação.
- **Abrir uma sessão:** sonograma e lista de deteções com indicação de hora. A deteção em reprodução está assinalada; um indicador mostra a posição.
- **Reprodução:** por deteção (excerto com zoom) ou a gravação inteira; os segmentos são reproduzidos sem interrupção. As gravações longas são transmitidas em fluxo.
- **Várias ocorrências:** se uma espécie foi detetada várias vezes durante a gravação e agrupada num só cartão, o cartão mostra a linha "N ocorrências", que se pode expandir. Janelas consecutivas são agrupadas em **episódios** ("N ocorrências em M episódios"). Cada episódio aparece numa linha com hora de início e fim, posição na gravação ("@ m:ss") e duração ("N janelas, M s"); o seu botão de reprodução reproduz o episódio completo de seguida (também em canto longo e contínuo). Para janelas individuais só se conhece o momento, não a confiança.
- **Reprodução:** reproduz-se sempre apenas uma entrada. Um segundo toque no mesmo botão para, um toque noutra entrada muda, e uma mudança de separador termina a reprodução. Isto vale em toda a app (Live, Análise, Mapa, Referências, memorandos de voz).
- **Verificar, corrigir, adicionar espécie:** como no separador Live, também a posteriori.
- **Comparação:** deteção e referência lado a lado, com reprodução sincronizada.
- **Pesquisa:** o ícone de lupa na lista de sessões filtra pelo nome da espécie (parte do nome, nome alemão ou científico). Numa gravação aberta, a pesquisa filtra a lista de deteções.
- **Cabeçalho e barra de reprodução:** voltar, data e identificação, reproduzir com indicação de posição, pesquisa, Raven e carregamento. Com "deteção anterior/seguinte" salta-se entre as entradas.
- **Navegação:** um toque no sonograma ou no oscilograma salta para esse ponto; se não houver reprodução em curso, o PIROL guarda a posição inicial (indicador ciano) para a próxima reprodução.
- **Espectro e oscilograma:** como no separador Live; no oscilograma um indicador mostra a posição de reprodução.
- **+ Espécie:** assume a hora da posição de reprodução (+/-10 s). A localização pode ser determinada com "Escolher no mapa".
- **Visão geral:** uma barra de resumo mostra as deteções de toda a gravação; a tabela Raven lista-as em forma de tabela.
- **Gerar de novo o ficheiro Raven**, p. ex. após uma verificação posterior.
- **Partilhar episódios:** cada episódio tem o seu próprio botão de partilha; partilha-se o excerto desse episódio.
- **Memorandos:** se existirem memorandos, a análise mostra por baixo do sonograma e da visão geral o bloco expansível "Memorandos (n)" (no máximo 200 dp de altura, com deslocação) com reprodução. Lista memorandos de sessão e memorandos sobre espécies; nos memorandos de espécies sem hora conhecida aparece "cerca de ... (hora da deteção)".
- **Re-análise:** o botão de re-análise ao lado da lupa (só se houver uma gravação) abre a escolha de modelo, lista ou grupos de espécies, limiar de confiança, sobreposição e segundo modelo. A re-análise decorre em segundo plano como serviço em primeiro plano, com progresso e opção de cancelar; o resultado aparece como etiqueta ao lado do original ("Data hora · Modelo"). Os resultados aparecem **progressivamente**: a variante abre-se de imediato, a lista de deteções cresce a cada poucos segundos e pode ser verificada durante a análise (cabeçalho "· a decorrer"). Ao cancelar, o resultado parcial mantém-se ("cancelado aos x min"); o mesmo acontece se a app for encerrada durante a análise. Verificações já feitas nunca são sobrescritas. Durante uma re-análise não é possível gravar e a variante em curso não pode ser apagada. A importação de ficheiros de áudio segue o mesmo caminho.
- **Importar áudio:** o botão de importação no cabeçalho da lista de sessões acolhe uma sessão PIROL (ZIP ou pasta) ou ficheiros de áudio (WAV, FLAC, MP3, M4A, OGG) como nova sessão. A hora de início vem pré-preenchida (p. ex. de um comentário do AudioMoth) e pode ser alterada. Com vários ficheiros, escolhe-se: uma sessão por ficheiro ou todos numa só sessão (segmentos, ordenados por hora).

**Cabeçalho:** data · município · observação numa linha; um toque na observação abre o diálogo. O comutador Compacto/Grande corresponde ao do cartão de espécie.

**Selecionar várias gravações:** premir longamente uma gravação na lista de sessões inicia o modo de seleção; outras gravações são escolhidas com um toque. A barra superior mostra "N selecionadas" com as ações "Todas", "Mover" e "Apagar"; o X ou a tecla Voltar sai do modo. A gravação em curso não é selecionável.
- **Mover:** a pasta de destino é livremente escolhível (seleção de pasta, também cartão SD ou memória USB). Cada sessão é copiada com todos os ficheiros (segmentos de gravação, memorandos), os tamanhos dos ficheiros são verificados e só depois o original é apagado. Em caso de erro ou cancelamento, o original mantém-se e a cópia parcial é removida. Se já existir no destino uma sessão com o mesmo nome, é acrescentado `-2`, `-3`, etc. O progresso é visível e pode ser cancelado. Uma pasta de destino dentro de `Downloads/PIROL` (ou a própria gravação) é recusada; nesse caso nada é movido.
- **Apagar:** após confirmação ("Apagar N gravações?"); não pode ser revertido. Apagar gravações individuais através do ícone de caixote mantém-se como até agora.

---

## 7. Exportação

**Raven Selection Table** (`recording.selections.txt`): é escrita automaticamente ao parar, ao lado do WAV, separada por tabulações, UTF-8. Abre no Cornell Raven, no Audacity (como faixa de etiquetas) e no Sonic Visualiser.

Incluem-se, entre outras, Selection, Begin File (em segmentos, o WAV correspondente), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (com desvio), Species, Confidence, Status (com segundo modelo, Second Rank). As colunas adicionais escritas definem-se em Definições > Exportação e carregamento > Campos de exportação Raven. A exportação KML já não existe.

### Raven: uma linha por episódio
Deteções consecutivas de uma espécie formam um **episódio**; a tabela tem uma linha por episódio (já não uma por entrada agrupada). Uma nova linha começa quando a pausa é maior do que uma janela de análise. Confidence é o máximo do episódio, Begin File o segmento do seu início. Colunas adicionais: `Cluster` (identificador da primeira entrada) e `Windows` (número de janelas). A tabela Raven da app mostra as mesmas linhas.

### Raven: memorandos
Cada memorando de sessão (capítulo 2) aparece como linha própria com Species `Memo` na sua posição na gravação. A coluna `Memo` contém o caminho do ficheiro de memorando (também em memorandos de espécies); pode ser desativada em Campos de exportação Raven. As linhas de memorando também aparecem nas tabelas das variantes de re-análise.

**Pacote ZIP:** a sessão como ZIP, para partilha manual ou carregamento automático (capítulo 12).

### Ocultar vozes humanas (privacidade)
O PIROL substitui vozes humanas por ruído nas **cópias de exportação** (Guardar como ZIP ou pasta, ZIP de carregamento, partilha de excertos): onde o modelo deteta pessoas, a banda de voz (aprox. 300-3900 Hz) é substituída, com uma margem de 0.5 s; o restante mantém-se inalterado. A gravação original, as deteções e o Raven nunca são alterados. A lista `human_windows.jsonl` é criada em direto e na re-análise; áreas ainda não avaliadas (sessões importadas ou antigas) o PIROL analisa na exportação (com progresso, cancelável).
- **Definição:** Definições > Exportação e carregamento > Privacidade > "Ocultar vozes humanas na exportação" (ativo por predefinição). Ao desligar, o PIROL avisa ("Deixar de ocultar vozes?"), porque as exportações podem então conter conversas de pessoas não envolvidas. No modo especialista, "Limiar humano" define a partir de que pontuação se oculta (mais baixo = mais cauteloso).
- **Memorandos:** os memorandos mantêm-se sempre sem alterações; o período de um memorando em direto na gravação fica excluído da ocultação.
- **Modelo sem classe humana:** se o modelo de uma sessão não conhecer vozes humanas, o PIROL pergunta ao guardar, carregar ou partilhar: com BirdNET 3.0, procurar pessoas (não durante uma gravação, pode demorar minutos), exportar sem ocultar (com aviso) ou cancelar. O carregamento automático não envia nada sem máscara nesse caso; aguarda a decisão.

**Nomes de ficheiro:** deteções partilhadas chamam-se `AAAAMMDDHHMMSS_Espécie_Observação.ext` (p. ex. `20260420143005_Melro_Jardim_traseiro.wav`), pacotes ZIP `AAAAMMDDHHMMSS_Observação.zip` (hora = início da sessão). Se faltar a observação, essa parte é omitida; espaços e caracteres especiais tornam-se `_`.

---

## 8. Mapa

Deteções georreferenciadas num mapa. O **menu do mapa** abre-se com um deslize a partir da margem esquerda ou com o ícone de menu no canto superior esquerdo ("Abrir menu do mapa"). Contém visualização, fonte do mapa, mapas offline e o filtro de espécies.

- **Fonte do mapa:** automática (swisstopo na Suíça, OpenStreetMap fora), OpenStreetMap, swisstopo Mapa, swisstopo Ortofoto.
- **Offline:** para os mapas swisstopo pode transferir-se o recorte visível ("Transferir recorte", com indicação do número de mosaicos e do tamanho). Um cancelamento mantém os mosaicos já carregados; um novo início carrega apenas os que faltam. "Apagar mapas offline" remove todos os mosaicos guardados.
- **Visualização:** todas as sessões, "Só Live" ou "Últimas 3". Observações rejeitadas estão ocultas. Marcadores sobrepostos são agrupados conforme o zoom. O mapa começa na própria localização.
- **Tocar num ponto:** um toque num ponto abre um menu com Verificar (Confirmar, Incerto, Rejeitar), "Editar", "Mover posição" e Reproduzir (Reproduzir ou "Parar reprodução"). Premir longamente abre o mesmo menu e permite, além disso, associar observações como observação simultânea. Reproduz-se o excerto da deteção a partir da gravação da sessão; se não existir, aparece uma mensagem.
- **Vários pontos sobrepostos (cluster):** um toque num cluster mostra a lista de deteções. Em cada entrada existem Verificar, Reproduzir e "Mover posição".
- **Mover posição:** o mapa mostra no topo a indicação "Mover o mapa: cruz sobre a nova posição de ..., depois Aceitar." Desloque o mapa de forma que a cruz no centro fique sobre a nova posição e depois prima "Aceitar" (ou "Cancelar") em baixo. Segue-se uma barra de aviso com **Anular**. O movimento também funciona para pontos em direto; se não houver sessão em curso, a nova posição só vale até fechar a app.
- **Filtro de espécies:** no menu do mapa está o filtro: "Só espécies de alarme" ou ocultar espécies individuais (Todas/Nenhuma). O filtro vale até a app ser fechada; as rotas continuam visíveis.
- **Rota:** uma rota gravada durante a gravação aparece como linha (no modo "Só na deteção" tracejada, apenas nos pontos de deteção). Ver capítulo 14.
- **Botão de localização:** centra o mapa na própria posição e aproxima o zoom para que a altura visível do mapa seja de cerca de 200 m.
- **Botões de zoom:** os botões +/- são 50 % maiores e ficam por cima do botão de localização.

---

## 9. Referências e espécies semelhantes

Deteções verificadas podem ser guardadas como referência ("Guardar como referência") e de novo removidas. O separador Referências mostra as gravações guardadas; também é possível importar áudio. Novas deteções são comparadas com as referências através de embeddings.

Definições > Espécies semelhantes e referências: pesquisa de semelhanças ligada/desligada, número de linhas mostradas (0 = desligado, 0.5 = texto em movimento das últimas 5, 1-10 = lista), limite de gravações de comparação guardadas.

---

## 10. Lista de vigilância e alarme

**Lista de vigilância:** espécies-alvo que disparam um alarme. Gestão em Definições > Lista de vigilância e alarme: pesquisar e adicionar uma espécie, remover, ou carregar a partir de `watchlist.json`. Maiúsculas/minúsculas e espaços não influenciam a comparação.

**Pasta partilhada:** em "Partilhar pasta PIROL" escolha uma vez `Downloads/PIROL`. Depois, o PIROL lê automaticamente `watchlist.json` e as pastas `lists/` (pormenores no capítulo 11). "Criar lista de exemplo" gera um modelo.

**Saída de alarme** (ativável e desativável individualmente): vibração, notificação, aviso em ecrã inteiro. No **ecrã de bloqueio**, o alarme em ecrã inteiro só aparece se "Notificações de ecrã inteiro" estiver permitido para o PIROL nas definições do Android (botão "Abrir permissão"). Caso contrário, mantém-se como notificação. Com "Testar alarme" (após 5 s, bloqueie o dispositivo) pode verificar-se.

No alarme, a espécie pode ser **confirmada** diretamente; "Anular" continua disponível depois.

**Pausa de alarme:** intervalo mínimo entre alarmes da mesma espécie (0 = cada correspondência gera alarme).

---

## 11. Grupos de espécies e listas de espécies

O filtro de espécies atua por grupo: **Aves, Gafanhotos, Anfíbios, Mamíferos**, cada um **Desligado / Todos / Automático / Lista**; **Outros** apenas **Desligado / Todos** (predefinição Desligado). "Automático" usa o geomodelo (localização e semana do BirdNET). Espécies que o geomodelo não conhece, bem como grupos sem máscara do geomodelo, usam a lista de apoio do grupo (Aves: lista da Suíça; Gafanhotos e Anfíbios: lista da Suíça abaixo; Mamíferos: todos). "Lista" escolhe uma das listas incluídas ou uma própria. Inclui-se a lista "Gafanhotos e anfíbios da Suíça" (dados GBIF, CC BY 4.0).

No editor de listas podem escolher-se espécies por ordem, família e género; "Mostrar nomes científicos" alterna a forma do nome.

### Listas de espécies próprias

Listas de espécies próprias podem ser guardadas como ficheiros JSON na pasta `lists/` da pasta partilhada PIROL (`Downloads/PIROL/lists/*.json`). Aparecem como listas selecionáveis nos grupos de espécies (opção "Lista"). As listas são **relidas a cada início de gravação**; as alterações valem, portanto, a partir da próxima gravação. Listas com erros são ignoradas e o motivo é comunicado. Como modelo serve "Criar lista de exemplo" (`lists/beispiel_liste.json`).

Nomes científicos antigos e novos (p. ex. após alterações taxonómicas) são fundidos, para que as listas funcionem entre modelos.

---

## 12. Carregamento

Definições > Carregamento: as sessões gravadas podem ser carregadas automaticamente após a gravação.

| Destino | Dados |
|------|---------|
| Desligado | apenas cópia local, exportação manual |
| Pasta | qualquer pasta (dispositivo, Drive, OneDrive ...) por seleção de pasta |
| NAS (SMB / partilha Windows) | servidor, partilha, caminho, utilizador, palavra-passe |

A palavra-passe do NAS é guardada cifrada no dispositivo. "Testar ligação" verifica os dados. Opções: carregar apenas por Wi-Fi, não manter a cópia ZIP local após o carregamento. O estado do último carregamento aparece por baixo.

---

## 13. Modelos

Definições > Modelo e deteção > Modelo:

| Modelo | Propriedades |
|--------|---------------|
| BirdNET 3.0 FP16 (recomendado) | padrão, 11'725 classes, 32 kHz, janela de 3 s |
| BirdNET 3.0 FP16 compacto (pruned) | mais pequeno (cerca de 70 MB), deteção praticamente igual |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | versão prévia anterior, 11'560 classes |

O modelo ativo é trocado por seleção, a partir da próxima análise. O número de espécies ao lado do modelo atualiza-se de imediato após a troca. Os modelos podem ser transferidos (cancelar e retomar é possível), importados e apagados. Cada sessão recorda o modelo utilizado.

**Tabela de modelos (desde a v0.1.1):** a coluna Principal (botão de rádio) determina o modelo principal. Secundário (caixa de verificação, apenas no modo especialista) escolhe o segundo modelo que verifica cada deteção; pode ser desmarcado e nunca pode ser igual ao principal. O caixote apaga um modelo instalado após confirmação; o principal não pode ser apagado e está bloqueado durante uma gravação. Modelos não carregados mostram na linha um ícone de carregamento com progresso e cancelamento.

**Limiar de confiança:** o regulador "Limiar de confiança (todas as espécies)" é igual para todas as espécies (predefinição 50 %). Alarmes da lista de vigilância só disparam a partir deste limiar.

**Confiança mínima:** o limiar de deteção é global e ajustável. Um modelo pode trazer ainda uma confiança mínima própria (p. ex. 80 % para o Perch). Prevalece o valor mais alto; o modelo mostra-o.

### Importar modelos próprios e outros
O PIROL não está limitado ao BirdNET. Através de "Importar outro modelo..." selecionam-se em conjunto três ficheiros: `.onnx` (modelo), `.pirol.json` (perfil) e CSV de rótulos. A app verifica somas de controlo, entradas e saídas e o número de rótulos, e comunica os erros em texto claro. A taxa de amostragem e a duração da janela são livres.

Verificados estão o **BirdNET 2.4** (6'522 espécies, 48 kHz, cerca de metade do tempo de cálculo do 3.0), o **Perch v2** (Google, 14'795 classes, janela de 5 s) e o **Nighthawk 0.3.1**. Os ficheiros não fazem parte da app. Para converter modelos TensorFlow, Keras e TFLite (também treinos próprios) existe o script `tools/convert_model.py`, ver `tools/README_convert_model.md` e `tools/MODEL_PROFILE_FORMAT.md`.

**Licenças:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 e Nighthawk apenas não comercial (CC BY-NC ou BY-NC-SA respetivamente). A licença é mostrada na importação.

**Nota:** o Perch trabalha a 32 kHz; por isso, gafanhotos de canto no ultrassom não são audíveis.

**Perch como segundo modelo:** precisa de cerca de 650 MB de armazenamento adicional. A app avisa ao ativar, se o dispositivo tiver menos de 6 GB de RAM. O segundo modelo trabalha com o áudio bruto da gravação, para que as janelas de 5 s incluam o ambiente real.

---

## 14. GPS

Definições > Mapa e GPS (até à v0.0.10: Gravação e armazenamento):

| Definição | Significado |
|-------------|-----------|
| Modo GPS "Contínuo" | posições em curso no intervalo definido (2/5/10/20/60 s, predefinição 10 s) |
| Modo GPS "Só na deteção" | GPS apenas numa deteção, pelo menos 30 s entre duas posições; o intervalo não se aplica. Poupa cerca de um terço da bateria |
| Imprecisão GPS máx. | posições menos precisas são descartadas (predefinição 50 m) |
| Suavização GPS | mediana de 5 posições, suaviza valores atípicos |

Cada deteção recebe uma localização nova, também com o ecrã desligado. As alterações valem a partir da próxima gravação.

**Posição inicial:** ao iniciar o programa e ao aproximar o zoom do mapa, o PIROL obtém uma vez uma localização sem gravação em curso (sem rastreio permanente).

**Rota:** a rota ("Gravar rota", predefinição ativa) é escrita durante a gravação (`track.jsonl`) e, ao parar, guardada como `track.gpx` ao lado da gravação (horas em UTC).

**Município:** o botão "Inserir município" põe o município da posição na observação (no diálogo de detalhe, na observação da sessão e na análise). Os dados dos municípios (Suíça) estão offline na app. Fora dessa área nada é determinado, a não ser que o interruptor especialista Definições > Mapa e GPS > Avançado > "Determinar município online (Google)" esteja ativo (predefinição desligado; neste caso as coordenadas são enviadas ao Google).

---

## 15. Energia e bateria

Definições > Modelo e deteção > Perfil de energia:

| Perfil | Análise |
|--------|---------|
| Preciso | cada janela de 3 s |
| Equilibrado | a cada 6 s |
| Poupança | a cada 15 s, para dias de campo longos |
| Poupança máxima | a cada 30 s, duração máxima |

**Valores práticos** (tablet Galaxy Tab A11+, 6'840 mAh, gravação contínua, Wi-Fi ligado, BirdNET 3.0 FP16; valores indicativos, cerca de ±15 %):

| Definição | Consumo | Autonomia com bateria cheia |
|-------------|-----------|----------------------|
| Preciso, ecrã desligado, GPS contínuo (10 s) | cerca de 2.2 %/h | cerca de 45 h |
| Igual ao anterior, 67 % de sobreposição | cerca de 3.5 %/h | cerca de 28 h |
| Igual ao anterior, **ecrã ligado** | cerca de 9 %/h | cerca de 12 h |
| Preciso, GPS só na deteção | cerca de 1.5 %/h | cerca de 60 h |
| Poupança (15 s), GPS 60 s | cerca de 0.9 %/h | cerca de 4-5 dias |

**Dicas:**
1. **Desligar o ecrã** é a maior alavanca (mais do dobro da gravação e da análise juntas). A gravação continua no serviço.
2. Escolher o modo GPS "Só na deteção".
3. Manter a sobreposição a 0 %; em saídas longas, o perfil "Poupança".
4. A escolha do modelo pouco importa (pruned é quase como o final, Preview 3.1 cerca de 10 % mais, FP32 não é melhor que FP16).

---

## 16. Idiomas

Definições > Idioma: idioma da app (idioma do sistema ou seleção): alemão, inglês, francês, italiano, catalão, português, neerlandês, alemão suíço. **Alemão suíço e português são rascunhos**; os textos em falta aparecem em alemão. O idioma dos **nomes das espécies** segue o idioma da app, mas pode ser definido em separado (23 idiomas).

---

## 17. Definições em resumo

As definições estão organizadas em secções recolhíveis; o estado fica guardado. Por predefinição só está aberta a secção "Idioma".

| Secção | Conteúdo |
|---------|---------|
| Idioma | idioma da app, idioma dos nomes das espécies |
| Gravação | informação de áudio, pré-gravação, sobreposição, perfil de energia, pasta de armazenamento (pasta, subpastas diárias, apagar automaticamente, mover sessões), duração máxima de gravação e de excerto, reamostragem |
| Modelo e deteção | modelos (transferir, importar, apagar), limiar de deteção (todas as espécies), alternativas a partir de, máx. espécies por janela, predefinições, sugestões de espécies, agrupamento de deteções, espécies semelhantes e limite de embeddings |
| Espécies e alarme | filtro regional e listas próprias, lista de vigilância, pausa e saída de alarme, testar alarme, pasta PIROL, sazonalidade e raridade |
| Visualização | idioma dos nomes das espécies, ecrã inteiro, sonograma (modo, predefinição, contraste, gama, teto, paleta, frequência, janela temporal), cartão de espécie compacto |
| Mapa e GPS | modo GPS, gravar rota, intervalo GPS, precisão e suavização; especialista (Avançado): "Determinar município online (Google)" |
| Exportação e carregamento | campos de exportação Raven (incl. memorando), privacidade (ocultar vozes humanas, limiar humano), carregamento (destino pasta/NAS, palavra-passe, teste de ligação, só Wi-Fi) |
| Espécies semelhantes e referências | pesquisa de semelhanças, número de linhas, gravações de comparação (capítulo 9) |
| Info | versão, fontes, novidades, diagnóstico (teste de desempenho) |

O interruptor **Modo especialista** (sob "Iniciar configuração") oculta no modo simples tudo o que é raro; no modo especialista tudo está visível. Nenhuma definição se perde. As opções raras estão no subbloco recolhível "Avançado" de cada secção (p. ex. filtro passa-alto, agrupamento de deteções, fenologia, duração máxima de gravação).

**Reamostragem:** Polifásico (recomendado) filtra ruídos acima de 16 kHz, que de outro modo podem aparecer como sinais fantasma. "Linear" é o método antigo. A mudança mostra uma indicação e tem efeito sem reiniciar a gravação.

**Diagnóstico:** o teste de desempenho mede o tempo de cálculo do modelo ativo com 1/2/4 threads (1-3 minutos, não durante uma gravação). Com "Escolher ficheiro ONNX e medir" pode testar-se também qualquer modelo antes de o importar.

---

## 18. Notas técnicas

- **Gravação:** 48 kHz, PCM 16 bits, mono (96 kHz no modo Morcego). A deteção é convertida para 32 kHz (BirdNET 3.0).
- **Dispositivo mínimo:** Android 8.0. Compatível com páginas de memória de 16 KB (Android 15+).
- **Fairphone e outros dispositivos:** a taxa de amostragem é negociada com o dispositivo; em caso de problemas, a app muda para outro controlador de áudio.
- **Formatos de dados:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven TSV; carimbos de tempo em hora local com desvio.

---

## 19. Alterações

A lista completa está em `CHANGELOG.md` no projeto.

### Versão 0.1.2
- Editar posteriormente os dados de sessões importadas (hora de início, local, observação) (capítulo 5)
- "Guardar como" como ZIP ou pasta (capítulo 6)
- Raven: uma linha por episódio, novas colunas `Cluster`, `Windows` e `Memo` (capítulo 7)
- Re-análise e importação: resultados visíveis e verificáveis progressivamente, o cancelamento mantém o resultado parcial (capítulo 6)
- Vozes humanas são ocultadas na exportação, predefinição ativa; aviso em caso de modelo sem classe humana (capítulo 7)
- Município online através do Google apenas como opção, predefinição desligada (capítulo 17)
- Memorando de sessão com premir e manter, bloco "Memorandos" na análise, linha de memorando no Raven (capítulos 2, 6, 7)
- Cartões de espécies em direto mais calmos: mudança no máximo a cada 5 s, correspondências nos 5 primeiros piscam a azul (capítulo 2)
- Botão de ajuda "?": modo de ajuda e manual offline (capítulo 1)
- Manual corrigido: filtro de espécies no menu do mapa (capítulo 8), nomes das secções de Definições (capítulos 1, 5, 7, 17)

---

*PIROL v0.1.2 — ETA Systems — Estado 2026-11-24*
