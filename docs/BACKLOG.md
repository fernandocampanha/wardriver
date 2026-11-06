# Registro de desenvolvimento (BACKLOG)

> **Diario tecnico do projeto**, mantido durante o desenvolvimento: cada rodada de testes vira um item
> numerado com sintoma, causa raiz, correcao e o que foi (ou nao) validado em campo. Os comentarios do
> codigo citam estes itens ("ver BACKLOG.md item 7").
>
> Notas para quem le:
> - O texto foi escrito sem acentos (comentarios de codigo seguem o mesmo padrao).
> - Nomes de pastas como `projeto-final`, `projeto-final-backup`, `android-app-backup` ou `trabalho_e2ppt` se
>   referem a copias de trabalho anteriores que **nao fazem parte deste repositorio**. As pastas atuais sao
>   `firmware/` e `android-app/`.
> - Dados pessoais (coordenadas, BSSIDs/SSIDs de redes reais) foram removidos ou generalizados antes da publicacao.
> - A ordem e cronologica; itens posteriores podem corrigir conclusoes de itens anteriores (ex.: 12f corrige uma
>   hipotese do 12).

Problemas conhecidos, registrados para correção futura. Não bloqueiam o uso atual do
projeto — cada item documenta o sintoma, a causa e o estado atual do contorno.

## 1. Crash de coexistência WiFi + Bluetooth Classic (Guru Meditation Error)

**Status:** conhecido, não corrigido. Sem impacto de perda de dado (ver "Por que não é
urgente" abaixo), mas interrompe a sincronização em andamento.

**Sintoma observado (2026-08-13):** durante um teste real com o app Android
(`android-app/`), logo na transição do backlog de sessões antigas para o streaming ao
vivo da sessão atual — ou seja, bem no início de uma conexão SPP bem-sucedida — o ESP32
travou:

```
Guru Meditation Error: Core  0 panic'ed (IllegalInstruction). Exception was unhandled.
```

Backtrace decodificado (`xtensa-esp32-elf-addr2line -pfiaC -e build/app-template.elf ...`):

```
coex_bt_release_wrapper (bt/controller/esp32/bt.c:1156)
  -> coex_bt_release        (binario, sem simbolos de debug)
  -> coex_core_release       (binario, sem simbolos de debug)
  -> coex_timer_ts_end_disalarm (binario, sem simbolos de debug)
  -> esp_coex_common_timer_disarm_wrapper (esp_coex/esp32/esp_coex_adapter.c:106)
  -> ets_timer_disarm        (esp_timer/src/ets_timer_legacy.c:97)
  -> esp_timer_stop          (esp_timer/src/esp_timer.c:257)
```

O crash acontece dentro de `coex_bt_release` — código binário fechado da Espressif
(biblioteca de coexistência WiFi/BT, sem símbolos de debug disponíveis), chamado a
partir de uma interrupção de alta prioridade (HLI). Não há nada do código deste projeto
no caminho da falha além do ponto de entrada (`bt.c:1156`, parte do próprio ESP-IDF).

**Causa raiz:** bug conhecido e não resolvido do ESP-IDF na coexistência entre WiFi em
modo promíscuo contínuo (`wifi_sniffer`, canal fixo, sem hopping) e Bluetooth Classic
ativo (`bt_spp_sync`) simultâneos — especificamente em transições de estado da conexão
BT (aqui, o momento em que o backlog termina e o link entra em modo "sniff"/idle antes
do streaming ao vivo). Confirmado por busca que o mesmo padrão de crash (`coex_bt_*`,
`IllegalInstruction`, contexto de coexistência) já foi reportado contra múltiplas
versões do ESP-IDF (4.4.5 a 5.1.1+), e foi reproduzido aqui na v5.5.3 — ou seja, não é
uma regressão introduzida por este projeto, é uma fragilidade de longa data da pilha de
coexistência da Espressif nesse combo específico (WiFi promíscuo + BT Classic).

Referências levantadas (issues públicas com sintoma equivalente):
- https://github.com/espressif/esp-idf/issues/12274
- https://github.com/espressif/arduino-esp32/issues/4521
- https://github.com/espressif/arduino-esp32/issues/3176

**Por que não é urgente:**
1. O handler de panic padrão do ESP-IDF reinicia o chip automaticamente após o crash —
   o dispositivo volta sozinho ao funcionamento normal (SD → fallback flash → BT →
   WiFi, tudo reinicializado limpo) em poucos segundos, sem intervenção manual.
2. O protocolo de sincronização (`bt_spp_sync.c`) já foi desenhado para nunca apagar
   dado local sem confirmação explícita (ACK com checksum CRC32) — se o crash acontecer
   no meio de uma sincronização, nenhum arquivo é perdido: ele simplesmente continua
   pendente e é reoferecido na próxima conexão.
3. Não é corrigível no código deste projeto — o ponto de falha está dentro de um
   binário fechado da Espressif (`libcoexist.a` / `libbtdm_app.a`), fora do nosso
   alcance.

**Mitigação parcial implementada (2026-08-14):** o boot agora abre uma janela de
5s (`CONFIG_WARDRIVER_BT_BOOT_WAIT_MS`) em que o Wi-Fi promíscuo só sobe depois
que a janela fecha sem conexão, ou depois que uma sincronização iniciada nela
termina (`bt_spp_sync_is_connected()`, ver `app_main` em
`main_orchestrator.c.ref`). Isso cobre o fluxo pretendido de uso (resetar o
device, sincronizar, só então a captura Wi-Fi começa) mas **não** protege
conexões BT feitas depois que o Wi-Fi já está rodando — o crash ainda pode
ocorrer se o celular conectar em pleno funcionamento da captura. Testado e
validado na bancada: sem conexão no boot, a captura Wi-Fi só inicia após os
~5s da janela.

**Mitigações a explorar no futuro** (nenhuma tentada ainda):
- Estender a mesma lógica de pausa para conexões BT feitas com o Wi-Fi já
  ativo (pausar `wifi_sniffer` enquanto uma conexão SPP está em andamento,
  não só na janela do boot) — reduziria a garantia de "tempo real" da
  captura durante qualquer sincronização, não só no boot.
- Acompanhar releases futuras do ESP-IDF em busca de correções na pilha de
  coexistência (`esp_coex`/`libcoexist`) — o bug é antigo mas pode ser corrigido em
  alguma versão posterior à 5.5.3.
- Se o crash se mostrar frequente o suficiente para atrapalhar o uso real (ainda não
  medido — ocorreu 1x em uma única sessão de testes), considerar desabilitar
  `CONFIG_ESP_COEX_SW_COEXIST_ENABLE` ou ajustar prioridades de coexistência via
  Kconfig, com o trade-off de possivelmente degradar captura WiFi ou throughput BT.

## 2. Arquivos de sessão de teste permanentemente corrompidos no storage

**Status:** cosmético, sem risco — apenas lixo acumulado de testes.

Resets manuais repetidos do ESP32 (via `esptool`/RTS-DTR, comum durante
desenvolvimento) truncam o arquivo de sessão que estava aberto no momento do reset,
porque o reset acontece numa janela entre gravações e o próximo `flush()` periódico
(a cada `CONFIG_WARDRIVER_STATUS_UPDATE_INTERVAL_MS` = 2000ms) — sem um `fclose()`
limpo, o storage fica com um arquivo mais curto do que deveria (ou, no formato
binário atual, ver item 3, com um registro final truncado no meio). O protocolo de
checksum detecta corretamente a corrupção e nunca confirma esses arquivos — mas por
serem dados de teste (não de uma coleta real), eles nunca serão "corrigidos" via
resync (o conteúdo já corrompido em disco é sempre o mesmo, o checksum nunca vai
bater). Ficam ocupando espaço indefinidamente até serem apagados manualmente.

Exemplo já visto: `sess_0013/14/15.jsonl` (2026-08-13, formato JSON antigo — a
partição SPIFFS foi apagada em 2026-08-14 especificamente para descartar esse lixo).
`sess_0068/69.wdr`, inicialmente suspeitos de serem outro caso deste item, na
verdade NÃO estavam corrompidos — ver item 3 (foi um falso alarme, causado por
instabilidade transitória do Bluetooth do celular durante uma conexão específica).

Não é um bug do protocolo (o comportamento — nunca confirmar dado corrompido — é o
correto e desejado), mas falta um mecanismo para identificar "arquivo que falhou
verificação N vezes seguidas, provavelmente corrompido de forma permanente" e oferecer
uma forma de descartá-lo manualmente (via um comando adicional no protocolo, ex.
`FORCE_DELETE <arquivo>`, usado com cautela — alternativa mais simples usada até agora:
apagar a partição inteira via `esptool erase_region` quando o conteúdo é só lixo de
dev, ver item 3).

## 3. Migração para formato binário fixo (2026-08-14) — RESOLVIDO

**Status:** concluído e validado. O sintoma pontual encontrado durante a validação
foi reproduzido, investigado e confirmado como instabilidade transitória do
Bluetooth do celular (não um bug de firmware/protocolo) — mitigação defensiva
mantida por robustez, mas o caso original não voltou a ocorrer.

Trocamos o formato de armazenamento de JSONL (texto, ~250-400 bytes/registro) para
um registro binário de tamanho fixo (`wardriver_record_t`, 71 bytes, ver
`main/wardriver_record.h`), reduzindo o uso de storage em ~4-5x. Arquivos de sessão
passaram de `.jsonl` para `.wdr`. O protocolo de sincronização BT ganhou um novo
framing (`--- BEGIN <arquivo> bytes=<N> ---` anunciando o tamanho antes do conteúdo,
e um marcador de 1 byte antes de cada registro em streaming ao vivo) para o celular
distinguir bytes de controle (texto) de conteúdo binário no mesmo stream.

Durante a primeira validação, uma conexão (numa janela em que o Bluetooth do
celular já estava dando sinais de instabilidade — várias tentativas de conexão
adjacentes falharam com timeout/"socket closed") reportou `sess_0068.wdr` e
`sess_0069.wdr` com checksum não batendo. Pior: `sess_0068.wdr` produziu uma "rede
fantasma" na lista (canal 45, RSSI +45dBm, criptografia "UNKNOWN" — valores
fisicamente impossíveis) — o parser aparentemente tratou parte do conteúdo binário
como texto de controle, e um byte `0x01` (o marcador de registro ao vivo) apareceu
por coincidência no meio disso, disparando uma decodificação espúria.

**Investigado e resolvido:** numa reconexão limpa e estável logo em seguida, os
DOIS mesmos arquivos (`sess_0068.wdr` e `sess_0069.wdr`) foram reoferecidos e
verificaram com o **mesmo CRC32 exato** que antes tinha sido reportado como
"anunciado" (não batendo) — ou seja, os arquivos NUNCA estiveram corrompidos em
disco; o problema foi bytes perdidos/corrompidos em trânsito durante uma conexão
Bluetooth especificamente instável naquele momento. O protocolo de checksum
funcionou exatamente como projetado: recusou confirmar dado suspeito, e uma
sincronização limpa subsequente recuperou os dados intactos sem perda.

**Mitigação defensiva mantida (robustez, não fix de um bug real):**
`WardriverRecordCodec.decode()` (app Android) valida faixas fisicamente plausíveis
(canal 1-14, RSSI entre -100 e 0 dBm, código de criptografia 0-4) antes de aceitar
um registro — qualquer coisa fora disso é descartada e logada, nunca chega a poluir
o Room/UI. Vale manter esse guard mesmo com a causa raiz esclarecida: uma conexão
BT ruidosa pode voltar a acontecer, e essa validação é a última linha de defesa
contra decodificar lixo como se fosse uma rede real. As duas linhas fantasma que
chegaram a ser persistidas foram removidas manualmente do banco (`sqlite3` local,
sem afetar as demais ~899 redes reais).

## 4. Contador de redes travava em 256 (2026-08-15) — RESOLVIDO

**Status:** corrigido e validado (boot limpo, sem erro de alocacao).

**Sintoma:** em qualquer coleta, o contador de redes unicas parava de crescer
exatamente em 256, independente do tempo de captura.

**Causa raiz:** `components/wifi_sniffer/wifi_sniffer.c` mantinha a tabela de
deduplicacao (`s_networks`) num array estatico de tamanho fixo
`WIFI_SNIFFER_MAX_NETWORKS = 256`. `upsert_network()` descartava silenciosamente
(sem log) qualquer BSSID novo além da 256a vaga — o comentario original ja
documentava essa intencao ("tabela cheia: descarta silenciosamente"), entao nao
era um overflow acidental de tipo (o contador `s_network_count` sempre foi
`int`), so um teto de capacidade baixo demais para uso real de wardriving
(uma unica rua densa em area urbana facilmente excede 256 AP's unicos).

**Fix:** tabela agora e alocada dinamicamente (`calloc` na inicializacao,
`realloc` dobrando de capacidade quando enche). Sem log de crescimento
(`ESP_LOGI`, "tabela de redes cresceu para N entradas") a cada realloc, para
visibilidade. So se o heap real esgotar (bem improvavel — cada entrada tem
~190 bytes, milhares de redes cabem nos ~200KB+ de heap livre do ESP32) o
codigo cai de volta a descartar a rede nova (com `ESP_LOGE` explicito dessa
vez, nao mais silencioso) — as ja vistas continuam sendo atualizadas
normalmente mesmo nesse caso extremo.

## 5. GPS demorando ate 30 min pra fechar fix, mesmo a ceu aberto (2026-08-15) — EM INVESTIGACAO

**Status:** sem causa raiz confirmada ainda. Nenhum bug de firmware encontrado
(parsing NMEA, checksum, baud rate, prioridade de task, ordem de boot — tudo
revisado e correto). Diagnostico de campo instrumentado, aguardando dados de
uma caminhada real a ceu aberto.

**Sintoma:** TTFF (time-to-first-fix) de ate 30 minutos reportado pelo
usuario, mesmo a ceu aberto. O proprio comentario do dev num teste isolado
anterior (`main/main_gps_test.c.ref`) esperava 30-60s — 30min e ~30-60x pior
que a expectativa original do projeto.

**Investigado e descartado como causa:** baud rate (9600 confere com o
GY-GPS6MV2/NEO-6M documentado no Kconfig), parsing de RMC/GGA (checksum e
split de campos corretos, sem overflow), prioridade/starvation da task de
UART (prioridade 5, buffer de 1024B suficiente pro volume de dados),
ordem de boot (GPS ja inicia antes do Wi-Fi subir). Firmware e 100% passivo
— nunca manda comando PMTK/UBX pro modulo, so le o que ele manda por padrao
de fabrica.

**Bug real encontrado e corrigido (nao explica os 30min, mas era bug):**
em `parse_gga` (`components/gps_nmea/gps_nmea.c`), `has_fix` so era setado
para `true`, nunca resetado para `false` quando `fix_quality` volta a 0 —
diferente do `parse_rmc`, que sempre atualiza nos dois sentidos. Corrigido.

**Instrumentado para diagnostico (2026-08-15):**
1. Parsing de `$GxGSV` (`parse_gsv` em `gps_nmea.c`) — unica sentenca NMEA
   com telemetria de aquisicao (satelites visiveis + SNR) mesmo sem fix
   fechado. Novos campos em `gps_data_t`: `sats_in_view`, `sats_tracked`,
   `best_snr_db`. Permite diferenciar "nao enxerga satelite nenhum"
   (antena/RF) de "enxerga mas nao fecha fix" (outra causa).
2. Log serial de status (`main.c`) ampliado com essa telemetria.
3. Novo arquivo `gps_diag.log` (CSV, no storage ativo — SD ou flash),
   uma amostra a cada `CONFIG_WARDRIVER_STATUS_UPDATE_INTERVAL_MS` (~2s),
   independente de fix ou nao. Abre/escreve/fecha a cada amostra (nao
   mantem o arquivo aberto) para sobreviver a um reset inesperado no meio
   do teste (ver item 1, crash de coexistencia WiFi+BT). Nao rotaciona nem
   e limpo automaticamente — feature de diagnostico temporaria, remover ou
   dar rotacao se for ficar permanente.
4. `gps_diag_dump_previous_log()` em `main.c`: a cada boot, despeja o
   conteudo do `gps_diag.log` da execucao anterior no log serial (entre
   marcadores `--- GPS_DIAG_DUMP_BEGIN/END ---`) — permite recuperar o
   diagnostico so plugando o USB de volta e resetando, sem precisar de BT
   conectado durante o teste de campo. Validado: sobrevive a reflash da
   app (particao de storage nao e tocada) e a reset simples.

**Teste em campo pendente:** usuario vai caminhar com o dispositivo a ceu
aberto por um tempo prolongado; ao voltar, plugar USB e resetar despeja o
`gps_diag.log` completo no serial pra analise (quantos satelites o modulo
via, com que SNR, ao longo do tempo, ate fechar fix ou nao).

**Hipoteses restantes (nao verificaveis via codigo, precisam do dado de
campo ou inspecao fisica):** ausencia de bateria VBAT de backup (cold start
completo a cada boot — normal seria ainda assim fechar em ~30-60s a ceu
aberto, entao sozinho nao explica 30min), antena degradada/mal posicionada,
ruido de RF de componentes proximos no layout da PCB (WiFi PA, SD card SPI,
OLED I2C), ou possivel queda de tensao durante transmissao WiFi afetando o
modulo GPS (regulador compartilhado) — essa ultima e particularmente
plausivel: WiFi TX gera picos de corrente que podem causar brownout
localizado, potencialmente fazendo o modulo GPS reiniciar sua aquisicao
repetidamente em vez de completar uma janela continua.

**Atualizacao (2026-08-15) — reencaixe do conector U.FL testado, sem
melhora:** antena reencaixada e testada em ceu aberto (caminhada de 20min)
e depois numa janela genuina (10+ min) — padrao identico em ambos os casos
(quase sempre 0 satelites visiveis, ocasionalmente 1, SNR bom quando trava
mas nunca mais que 1 satelite simultaneo). Isso praticamente descarta
"conector solto" como causa isolada. Sem antena GPS sobressalente para
testar substituicao, a causa raiz especifica (antena degradada vs. cabo
danificado ao longo do comprimento vs. LNA do modulo) fica em aberto —
decisao do projeto foi seguir para o item 6 (GPS do celular como fonte
alternativa) em vez de continuar investigando o hardware do modulo.

## 6. GPS do celular como fonte alternativa/prioritaria (2026-08-15) — IMPLEMENTADO

**Status:** implementado, buildado, flashado e validado em hardware real
(end-to-end, celular real conectado, coordenadas do celular aparecendo
corretamente nos registros capturados).

**Motivacao:** modulo GPS fisico (GY-GPS6MV2/NEO-6M) com antena
degradada/mal posicionada sem causa raiz isolavel (ver item 5) e sem
antena sobressalente para substituir e confirmar. Solucao aprovada:
arquitetura hibrida — celular conectado via Bluetooth fornece a propria
localizacao (GPS+A-GPS, tipicamente mais preciso e com fix quase instantaneo)
como fonte prioritaria, com fallback automatico para o modulo fisico
quando o celular nao estiver conectado ou parar de mandar localizacao.
Nenhuma capacidade standalone foi removida — o modulo continua funcionando
exatamente como antes quando nao ha celular conectado.

**Copia de trabalho:** esta mudanca foi implementada em copias separadas
(`firmware/` e `android-app/`),
mantendo `projeto-final/` e `android-app/` (mais os respectivos `-backup/`)
intocados como referencia/fallback caso essa abordagem nao funcione bem em
campo.

**Firmware:**
- Novo componente `components/phone_gps/` (`phone_gps.h/.c`): mantem o
  ultimo fix recebido do celular, com janela de validade
  (`CONFIG_WARDRIVER_PHONE_GPS_STALE_MS`, default 10s) — sem atualizacao
  dentro da janela, `phone_gps_get_data()` retorna `false` e o chamador
  cai de volta pro modulo fisico. Reaproveita `gps_data_t` (gps_nmea.h)
  para nao duplicar o tipo nem o codigo consumidor em main.c.
- `bt_spp_sync.c`: novo comando no protocolo, linha `LOC <lat> <lon>
  <accuracy_m>` (ver bt_spp_sync.h para o protocolo completo atualizado) —
  parseada e repassada para `phone_gps_update()`. Log de confirmacao a
  cada linha recebida (`LOC recebida do celular: lat=... lon=...
  accuracy=...`).
- `main.c`: nova funcao `get_best_gps_data()` (fix do celular tem
  prioridade; fallback pro modulo) usada em `on_beacon` (registro gravado)
  e no display OLED (`has_fix` mostrado reflete a melhor fonte disponivel).
  O log de status/diagnostico (`gps_diag.log`, ESP_LOGI periodico com
  sats_view/tracked/snr) continua usando o modulo fisico especificamente —
  nao foi alterado, continua util se a investigacao da antena for retomada
  no futuro.

**Android app:**
- Nova permissao `ACCESS_FINE_LOCATION` (opcional — negar nao bloqueia a
  conexao Bluetooth, so desativa o envio de "LOC"; ver
  `MainActivity.permissionLauncher` e `WardriverSyncService.startLocationUpdates`).
- `WardriverSyncService`: `LocationManager` (`GPS_PROVIDER`, intervalo 2s)
  ativado ao conectar, desativado ao desconectar. Cada atualizacao de
  localizacao e enviada como linha "LOC" pelo mesmo `OutputStream` da
  conexao SPP (agora com lock explicito, `writeLock`, ja que duas threads
  podem escrever nele — a de leitura para ACKs e a main/looper do
  LocationListener para LOC).

**Validado em campo (bancada, 2026-08-15):** conectado via BT, permissao
de localizacao concedida ("Durante o uso do app"), log do app confirmou
"GPS do celular ativado", log serial do ESP32 confirmou recebimento
continuo de linhas LOC (precisao ~3.8m), e as redes capturadas durante o
teste passaram a mostrar a coordenada do celular (valores omitidos)
na listagem do app — pipeline completo (celular -> BT -> firmware ->
registro -> BT -> app -> UI) confirmado funcionando.

## 7. Heap RAM, nao storage em disco, era o limite real de capacidade (2026-08-15) — RESOLVIDO

**Status:** corrigido e validado em hardware (medicao real de heap livre
antes/depois).

**Contexto:** a pedido do usuario, antes de uma coleta real pela cidade
("centenas ou milhares de redes"), revalidei a capacidade do dispositivo
para garantir ausencia de limitacao/perda de dado. A investigacao revelou
que o gargalo real nao era o storage em disco (item 4, ja corrigido —
tabela de dedup la e so um indice, cresce dinamicamente) nem o SD
card/flash (~2.4MB via flash interna, cartao SD ainda nao monta —
`ESP_ERR_INVALID_RESPONSE`, item aberto, nao investigado hoje), mas sim o
**heap RAM disponivel**, medido em apenas **~32KB livres** com Wi-Fi+BT
ativos (bem menos que os ~200KB+ estimados antes de medir de verdade).

**Causa raiz 1 — `wifi_sniffer_network_t` maior que o necessario:**
`wifi_sniffer_wps_info_t` guardava 4 strings de 32 bytes (device_name,
manufacturer, model_name, model_number — 128 bytes) extraidas do IE de
WPS, mas o registro persistido (`wardriver_record_t`) nunca guardou essas
strings (decisao de escopo ja documentada em `wardriver_record.h`, so
`wps_config_methods` e persistido). Essas 128 bytes eram ~62% do tamanho
de cada entrada da tabela de dedup (~208 bytes) sem nenhum consumidor
downstream — puro desperdicio de heap. **Fix:** removidas as 4 strings de
`wifi_sniffer_wps_info_t` (e o parsing correspondente em `parse_wps_ie`,
`wifi_sniffer.c`) — entrada da tabela caiu para ~80 bytes. Heap livre
medido no dispositivo saltou de 32KB para **65KB** so com essa mudanca
(a tabela inicial de 256 posicoes, alocada no boot, caiu de ~53KB para
~20KB).

**Causa raiz 2 (mais importante) — tabela de dedup nunca era zerada:**
`s_networks`/`s_network_count` (RAM, `wifi_sniffer.c`) acumulava TODA rede
unica vista desde o boot, pela duracao inteira da captura — sem limite.
Numa coleta curta de bancada isso nunca apareceu (poucas dezenas de redes),
mas numa coleta real pela cidade, com heap total disponivel na faixa de
dezenas de KB (nao centenas), a tabela estouraria a capacidade de
`realloc` (ver item 4) bem antes de "milhares de redes" — o crescimento
dinamico do item 4 resolve o CRASH/overflow do array de tamanho fixo, mas
nao resolve o limite real de memoria disponivel numa sessao longa.

**Fix:** nova funcao `wifi_sniffer_reset_table()` (esvazia a tabela,
mantém a capacidade alocada) chamada apos toda rotacao de sessao bem
sucedida (`rotate_session_and_reset_dedup()`, novo wrapper em `main.c`,
registrado em `bt_spp_sync_set_rotate_backend` no lugar dos ponteiros
diretos para `sd_storage_rotate_session`/`flash_storage_rotate_session`).
Como a rotacao acontece a cada `CONFIG_WARDRIVER_BT_LIVE_ROTATE_INTERVAL_MS`
(60s, so enquanto o celular estiver conectado) e so retorna sucesso depois
de fechar e oferecer o arquivo da sessao antiga para sincronizacao, o
reset e seguro — o historico completo ja foi persistido/sincronizado antes
de zerar o indice em RAM. Isso muda o limite de capacidade de "redes
unicas na viagem inteira" (ilimitado, cresce sem fim) para "redes
simultaneamente visiveis numa janela de 60s" (limitado pela fisica de
alcance de radio — nem de longe chega a centenas, muito menos milhares,
mesmo em area urbana densa).

**Dependencia importante:** esse mecanismo so funciona com o celular
conectado via Bluetooth durante toda a coleta — o que ja e requisito da
arquitetura de GPS do celular (item 6). Se o BT cair por um periodo longo
durante a coleta, a rotacao para de acontecer e a tabela volta a crescer
sem limite ate a proxima reconexao — nesse cenario (heap realmente
esgotado), o codigo ja tratava isso sem crash (`realloc` falha,
`ESP_LOGE`, rede descartada — ver item 4), mas nao seria mais totalmente
livre de perda de dado. Bluetooth instavel nesse celular especifico ja e
um problema documentado nesta sessao (reconexoes ocasionais necessarias).

**Visibilidade adicionada:** `on_beacon` (`main.c`) agora checa o retorno
de `sd_storage_write_record`/`flash_storage_write_record` e loga
`ESP_LOGE` (rate-limited a 1x/10s) se uma gravacao falhar (ex.: storage
sem espaco) — antes, uma falha de escrita era descartada em silencio, sem
crash mas tambem sem nenhum aviso de que dados estavam sendo perdidos.
Log de status tambem ganhou `heap_livre=<bytes>` para acompanhamento em
campo.

**Validado:** build limpo, heap livre medido no dispositivo real em cada
etapa: 32KB (antes) -> 65KB (so cortando as strings WPS) -> **80KB idle /
~60KB estavel com celular conectado e streaming** apos tambem reduzir
`WIFI_SNIFFER_INITIAL_CAPACITY` de 256 para 64 (seguro agora que a tabela
reseta a cada rotacao, nao precisa mais partir grande). Heap conectado
monitorado por varias leituras consecutivas sem queda continua (nao e
vazamento, so o custo normal do stack BT ativo). Reset de tabela validado
via leitura de codigo (chamada corretamente encadeada apos rotacao bem
sucedida) — nao testado em campo com uma rotacao real de 60s decorrida
end-to-end.

**Observado durante a validacao (nao root-causado):** o ESP32 reiniciou
espontaneamente uma vez durante os testes (`rst:0x1 POWERON_RESET`, sem
backtrace de panic — ou seja, NAO e o crash de coexistencia do item 1,
que sempre deixa um backtrace). Sem perda de dado (arquivos anteriores
already ACKed e apagados antes do reset). Causa mais provavel: instabilidade
do cabo/porta USB da bancada (ja documentada nesta sessao, ver secao de
depuracao do USB) ou pressao de heap durante um pico de atividade BT
simultanea (rotacao + streaming + reconexao). Nao investigado a fundo por
tempo — **implicacao pratica pro usuario:** o app NAO reconecta sozinho
apos uma queda; durante a coleta real, verificar o celular periodicamente
e tocar "Conectar" de novo se aparecer "Desconectado" (a perda e so de
alguns segundos de captura + a rotacao/GPS do celular ficam pausados ate
reconectar, nunca de dado ja gravado).

## 8. Ajustes pos-primeira-captura real pela cidade (2026-08-16) — RESOLVIDO

Apos a 1a captura real com a arquitetura hibrida (item 6), 5 problemas foram reportados
e corrigidos:

**1. Janela de 5s de espera por BT no boot removida.** Fazia sentido quando o uso
esperado era "resetar, sincronizar, so entao capturar" — mas com o celular ficando
conectado o tempo todo (unica forma do GPS dele funcionar, ver item 6), a janela so
atrasava a captura sem beneficio. `CONFIG_WARDRIVER_BT_BOOT_WAIT_MS` removido do
Kconfig; `app_main` agora sobe `wifi_sniffer_start()` direto apos inicializar GPS,
sem bloquear. Validado em campo: rede detectada ~5.4s apos power-on (esse tempo e so
overhead de tentativas de montagem do SD + subida do BT, nao um atraso artificial).

**2 e 4. Sincronizacao trocada de rotacao automatica por tempo para botao no app,
com limpeza garantida apos a sincronizacao.** Antes, `bt_spp_sync.c` fechava e
oferecia a sessao ativa a cada `CONFIG_WARDRIVER_BT_LIVE_ROTATE_INTERVAL_MS` (60s)
enquanto conectado. Agora o ESP32 so faz isso quando recebe a linha "SYNC" do celular
(nova funcao `do_manual_sync`, disparada pelo botao "Sincronizar agora" no app —
`WardriverSyncService.requestSync()`/`MainActivity`). O dispositivo coleta
continuamente entre uma sincronizacao e outra; ao sincronizar, a sessao ativa e
fechada, oferecida pelo mesmo protocolo BEGIN/CHECKSUM/ACK de sempre (so apagada do
storage apos confirmacao do celular) e uma nova sessao abre sem interromper a
captura. Validado em campo (app conectado, botao pressionado): log do app mostra
"Sincronizacao solicitada..." -> "verificado e confirmado: sess_XXXX.wdr" -> "OK
removido sess_XXXX.wdr" — sessao corretamente fechada, confirmada e apagada.

**3. GPS do celular parava com tela apagada/app em segundo plano — corrigido.** O
`WardriverSyncService` ja rodava como foreground service, mas o
`foregroundServiceType` no manifest so incluia `connectedDevice`, nao `location` —
a partir do Android 10, atualizacoes de localizacao em segundo plano exigem que o
foreground service declare o tipo `location` (e, a partir do Android 14/API 34, a
permissao `FOREGROUND_SERVICE_LOCATION`). Corrigido: manifest agora declara
`connectedDevice|location` e a permissao nova; `startForegroundCompat()` (novo,
substitui a chamada direta a `startForeground`) so inclui o tipo `location` no
`startForeground(id, notification, type)` quando a permissao de localizacao foi de
fato concedida — evita `SecurityException` no Android 14+ quando o usuario nega
localizacao (que continua opcional, so desativa o complemento). Validado: conexao
BT + GPS do celular ativo confirmados sem crash via logcat (`startForegroundCompat`
chamado, "GPS do celular ativado" logado, sem exception).

**5. Contador de redes travava perto de 512 — causa raiz identificada e corrigida.**
Cada entrada da tabela de deduplicacao do `wifi_sniffer` ocupa ~80 bytes; a tabela
cresce dobrando de capacidade (64->128->256->512->1024...). Ir de 512 para 1024
precisa de ~80KB contiguos de heap — mas o heap livre tipico com Wi-Fi+BT ativos e
so ~60-80KB (ver item 7), entao o realloc falhava e a tabela parava de crescer ali,
descartando silenciosamente qualquer rede nova depois disso (so logava erro, sem
crash). Antes desse item, o unico reset da tabela era via rotacao de sessao — que
virou sob demanda (itens 2/4), entao numa coleta continua a tabela cresceria sem
limite ate bater nesse teto de qualquer forma. Corrigido com um piso de heap
independente de BT/sincronizacao: a cada ciclo do loop de status (~2s), se o heap
livre cair abaixo de `CONFIG_WARDRIVER_DEDUP_RESET_HEAP_FLOOR_BYTES` (40KB, novo
Kconfig), `wifi_sniffer_reset_table()` e chamado direto em `main.c` — zera so a
deduplicacao em RAM, nunca dado ja gravado (pode gerar uma linha duplicada por BSSID
no arquivo, inofensivo). Validado: heap livre observado em 80616/80588 bytes durante
os testes (bem acima do piso de 40KB), sem tentativa de crescer a tabela alem do que
o heap suporta.

**Achado extra durante a validacao (nao pedido, mas bloqueava a captura real):** o
recurso de diagnostico do GPS do item 5 (`gps_diag_log`/`gps_diag_dump_previous_log`,
que despejava um CSV de diagnostico no log serial a cada boot) tinha acumulado
~1.4MB no SPIFFS ao longo dos testes anteriores — e o dump linha-a-linha desse
arquivo a cada boot demorava o suficiente para estourar o watchdog da task IDLE0,
travando o ESP32 num loop de crash/reboot (`task_wdt`, sem conseguir nunca terminar
o boot). Como o diagnostico ja cumpriu seu papel (antena do modulo confirmada
defeituosa, decisao ja tomada de usar o GPS do celular, ver item 6), o recurso foi
removido inteiramente de `main.c` (as duas funcoes e as duas chamadas), e o SPIFFS
foi apagado (`esptool erase_region 0x190000 0x270000`) para descartar o arquivo
acumulado. Validado: boot limpo, SPIFFS em 0/2287 KB, sem watchdog, captura ativa
em ~5.4s.

**Risco residual observado durante a validacao de campo:** apos um "SYNC" bem
sucedido, a conexao BT caiu ~7s depois (`bt socket closed, read return: -1` no
app) — mesmo padrao do crash de coexistencia WiFi+BT do item 1 (transicao de
estado da conexao BT com Wi-Fi promiscuo ativo). Sem perda de dado (a sessao
recem-sincronizada ja tinha sido confirmada e apagada antes da queda) e o ESP32
reiniciou e retomou a captura sozinho. Nao e um problema novo, e o mesmo risco ja
documentado no item 1 — inerente a manter o celular conectado o tempo todo (item 6),
sem solucao no nosso codigo (bug fechado da Espressif).

## 9. Ajustes pos-segunda-captura real: filtros/analise, estabilidade BT, SSID oculto, OLED (2026-08-18) — RESOLVIDO

Segunda rodada de testes de campo com o app phone-gps (arquitetura do item 8),
com uma quantidade razoavel de redes coletadas. Quatro observacoes levantadas:

**1. Falta de filtros e analise no app.** Adicionados ao app Android (so na
camada de apresentacao — nao afeta o que e persistido no banco nem a exportacao
CSV, que continuam completos):
- Busca por texto (nome ou BSSID) e filtro por criptografia/"somente ocultas" em
  `MainActivity` (`activity_main.xml`: `editSearch`, `spinnerEncryption`,
  `checkboxHiddenOnly`), aplicados sobre a lista completa em memoria
  (`allNetworks`) via `applyFilters()`.
- Tela nova `AnalysisActivity` (botao "Analisar Redes"), consultando todas as
  redes persistidas (`NetworkDao.getAll()`) e gerando um resumo: distribuicao por
  criptografia, % de SSID oculto, % de WPS ativo, % sem criptografia forte
  (OPEN+WEP), contagem de nomes de fabrica nao alterados (regex com prefixos
  comuns de operadoras/fabricantes no Brasil), SSIDs e canais mais frequentes.
  Validado em campo (dados reais persistidos, 1947 redes): a analise rodou sem
  travar e sem excecao, e foi o que expos concretamente a escala do item 3
  abaixo (61% classificadas como "SSID oculto" — claramente anormal).

**2. Estabilidade da conexao Bluetooth.** A causa mais provavel de quedas sem
padrao especifico e o crash de coexistencia WiFi+BT ja documentado (item 1) —
inerente a manter o celular sempre conectado (item 6), sem correcao possivel no
nosso codigo (bug da pilha Bluedroid/coexistencia da Espressif). Como o ESP32 ja
se recupera sozinho (watchdog reset + reabertura do servidor SPP em poucos
segundos), o ponto de alavancagem ficou no lado do celular: antes, uma queda de
conexao deixava o app parado em "Desconectado" ate reconexao manual.
`WardriverSyncService.kt` ganhou reconexao automatica (`scheduleReconnect`,
`userInitiatedDisconnect`): qualquer queda que nao tenha sido o usuario apertando
"Desconectar" agenda uma nova tentativa de `connect()` em 4s, cancelavel e
idempotente. Validado por build + instalacao (sem hardware ESP32 disponivel
nesta sessao para validar a reconexao fim-a-fim em campo).

**3. Quantidade anormal de SSID oculto (61% na analise de campo).** Causa raiz:
o reset periodico da tabela de deduplicacao em RAM do `wifi_sniffer`
(`wifi_sniffer_reset_table`, disparado pelo piso de heap do item 7/8 ou pelo
SYNC) faz um BSSID ja conhecido "renascer" como novo na proxima vez que for
visto. Se o primeiro beacon capturado apos o reset nao carregar o SSID (ou o
throttle de gravacao deixa passar justo esse), o registro chega ao celular como
oculto — e como o app so tinha `upsert` bruto (ultima captura sempre sobrescreve
a anterior pelo mesmo BSSID), uma rede com nome ja confirmado podia acabar
permanentemente marcada como oculta se essa fosse a ultima captura recebida
antes do carro sair de alcance. O firmware ja tinha protecao equivalente dentro
de uma mesma janela (`upsert_network` em `wifi_sniffer.c` preserva o SSID
conhecido se o beacon novo vier vazio), mas o reset da tabela apaga esse
conhecimento inteiro. Corrigido do lado do celular (nao exige RAM extra no
ESP32): `WardriverSyncService.mergeSsid()` prioriza o nome ja conhecido no mapa
em memoria (`networks`) — um registro novo com SSID vazio/oculto nunca apaga um
nome ja visto para o mesmo BSSID; o inverso (nome novo chegando) sempre
atualiza normalmente. Validado por build + instalacao; a correcao so vale para
capturas novas (nao reprocessa retroativamente os 1204 registros ja
persistidos como ocultos antes dela existir).

**4. Display OLED desligou apos coleta longa, dispositivo continuou funcional,
nao reproduziu apos reiniciar.** Sintoma classico de barramento I2C travado
(ruido/queda momentanea de tensao deixando o SDA preso) — o driver I2C legado
(`driver/i2c.h`) nao se recupera sozinho disso, e o codigo antigo nem verificava
o retorno de `oled_display_update()`/`oled_display_show_status()`, entao uma
falha ficava permanentemente silenciosa ate o proximo boot. Corrigido em
`oled_display.c`: apos `OLED_MAX_CONSECUTIVE_FAILURES` (3) falhas de escrita I2C
seguidas, `oled_display_update()` chama `oled_recover_bus()`, que reinstala o
driver I2C do zero e reenvia a sequencia de inicializacao do SSD1306 (com guarda
de reentrancia `s_recovering` contra recursao). `main.c` agora loga (`ESP_LOGW`)
quando `oled_display_show_status()` falha, para ficar visivel no serial durante
uma coleta real. Validado por build; nao foi possivel reproduzir o glitch
original em bancada nesta sessao (e transiente e ja nao ocorria mais antes desta
mudanca), entao esta e uma mitigacao best-effort — se a causa for um escravo
fisicamente segurando o SDA em vez de corrupcao do estado do periferico do
ESP32, um recovery manual de barramento (toggle de SCL) seria o proximo passo.

Build e instalacao validados nesta sessao: firmware compilou limpo
(`idf.py build`), app compilou limpo (`assembleDebug`) e foi instalado/testado
via adb no celular real (filtros, analise e navegacao sem crash, log limpo).
**Atualizacao (mesma janela de trabalho, ESP32 conectado por USB logo em
seguida):** a reconexao automatica (item 2) foi validada em campo real — duas
quedas de conexao genuinas (nao provocadas) dispararam retry automatico em 4s
cada, reconectando sozinho na 3a tentativa sem intervencao manual, alem de SYNC
manual fim-a-fim (fechamento de sessao, checksum, ACK, remocao) sem interromper
a captura. O merge de SSID (item 3) em captura ao vivo nao pode ser validado
nesta sessao (parado/indoor, nenhum evento de revelacao de SSID novo ocorreu) —
mas ver item 10 abaixo: o proprio item 3 se revelou incompleto e recebeu uma
correcao adicional no firmware. O item 4 (OLED) tambem recebeu correcao
adicional — ver item 10.

## 10. Regressao do OLED e SSID oculto persistente apos varias passagens (2026-08-19) — RESOLVIDO

Terceira rodada de campo revelou que as mitigacoes do item 9 (itens 3 e 4)
eram incompletas.

**1. OLED "piorou": apagou e nao voltou mais, nem apos reset.** A recuperacao
implementada no item 9 (`oled_recover_bus`) so apagava e reinstalava o driver
I2C do lado do ESP32 — isso nao resolve um barramento travado quando o
problema esta do lado do ESCRAVO (SSD1306 preso segurando o SDA em nivel baixo
no meio de uma transacao interrompida por ruido/queda de tensao): o escravo so
solta a linha depois de ver alguns pulsos de clock, algo que reinstalar o
driver do mestre nao produz. Ou seja, a "auto-recuperacao" do item 9 na
pratica quase nunca recuperava nada — so mascarava o problema tentando de novo
a cada 3 falhas, sem nunca ter chance real de sucesso, e reportando ao usuario
uma garantia de recuperacao automatica que na pratica nao existia (dai a
percepcao de "piora": a expectativa criada nao correspondia ao
comportamento real). Corrigido com o procedimento padrao de bus recovery I2C
(bus clear, UM10204 da NXP): `oled_i2c_bus_recover_pins()` em
`oled_display.c` assume SDA/SCL como GPIO open-drain manual, pulsa o SCL ate 9
vezes enquanto o SDA estiver preso em baixo, gera uma condicao de STOP manual,
e so entao devolve os pinos para o driver I2C reinstalar e reenviar a
sequencia de init do SSD1306. Chamado de dentro de `oled_recover_bus()` antes
de `oled_display_init()`. Validado por build + flash + boot real (sem falhas
de I2C nesta sessao para exercitar o caminho de recuperacao propriamente
dito — o glitch original continua sendo transiente e nao reproduzido sob
demanda); se o display ainda assim nao voltar apos essa correcao, o proximo
passo e investigar hardware (fonte/regulador do modulo, solda) em vez de
software, ja que bus clear e o ultimo recurso do lado do protocolo.

**2. SSID oculto nao atualiza mesmo passando varias vezes pelo mesmo lugar.**
Duas causas raiz no firmware, nao no app (o merge do item 9 ja estava correto
do lado do celular):
- `wifi_sniffer` so processava frames de **beacon** (subtipo 0x08). Uma rede
  configurada para nao anunciar o nome no beacon (oculta de verdade) so revela
  o SSID respondendo a um **probe request direcionado** de um cliente que ja
  conhece a rede — o proprio celular do usuario ou qualquer outro nas
  redondezas faz isso o tempo todo. Esse **probe response** (subtipo 0x05) tem
  o mesmo layout de campos fixos + IEs de um beacon (mesma origem, addr2 =
  BSSID do AP) e agora tambem e capturado e parseado — `wifi_sniffer_promisc_cb`
  aceita os dois subtipos, `parse_beacon_frame` ja funcionava sem alteracao
  para o novo caso. Isso e captura 100% passiva (o ESP32 nao transmite nada,
  so escuta trafego alheio ja existente no ar).
- Mesmo quando um frame revelador chegava, o throttle de regravacao
  (`CONFIG_WARDRIVER_LOG_REWRITE_INTERVAL_MS`, 10s) podia engolir a correcao:
  `upsert_network` ja atualizava a tabela em RAM corretamente, mas `on_beacon`
  em `main.c` so grava/transmite por Bluetooth quando o throttle libera — numa
  janela de captura curta (carro passando rapido por uma rua), o throttle podia
  nunca liberar antes do carro sair de alcance, perdendo a correcao para
  sempre naquela sessao. Corrigido: `upsert_network` agora calcula e propaga
  `ssid_just_revealed` (BSSID que estava sem nome/oculto na tabela e este
  frame trouxe o nome pela 1a vez — ver `wifi_sniffer.h`), e `on_beacon` fura o
  throttle quando essa flag vem `true`, garantindo que a correcao seja gravada
  e transmitida na hora, nao na proxima janela de 10s.

Redes genuinamente cloacadas continuam sem solucao (por definicao, nunca
incluem o SSID em nenhum frame publico exceto probe response a um probe ja
direcionado — se nenhum cliente por perto conhece/procura essa rede, nao ha
frame publico nenhum que carregue o nome; nada muda isso sem transmitir algo
proprio, o que sairia do escopo passivo do projeto). Mas a fracao de "SSID
oculto" que era na verdade so uma limitacao de captura (nunca ouvir probe
response, e/ou perder a correcao pro throttle) deve cair perceptivelmente.
Validado por build + flash + boot real (heap estavel ~80KB, captura ativa,
sem crash) — observado ao vivo neste boot um caso real do padrao do bug:
duas interfaces de um roteador de referencia (bit "administrado localmente"
no 1o byte do BSSID) capturadas sem nome, enquanto a terceira interface do
mesmo roteador veio com o SSID normal — confirma que a mesma origem fisica pode ter interfaces
"silenciosas" no beacon que so o probe response resolve. Nao foi possivel
validar o evento de revelacao em si nesta sessao (parado/indoor, sem cliente
por perto sondando essas redes especificas) — validacao real fica para a
proxima saida em movimento.

## 11. Cartao SD removido do hardware; throttle de gravacao reduzido de 10s para 2s (2026-08-19) — RESOLVIDO

**1. Remocao do cartao SD.** O hardware nao usa mais cartao SD (decisao do
usuario) — removido `components/sd_storage/` inteiro (era so usado como
opcao preferencial com fallback automatico para a flash interna, ver item
8). `main.c` simplificado: `storage_backend_t`/`s_storage` (enum de 3
estados) virou um unico `bool s_storage_ok`, toda a logica condicional
SD-vs-flash em `app_main` (montagem, escrita, rotacao de sessao, flush,
`bt_spp_sync_set_storage_root`) colapsou pra chamar `flash_storage_*`
direto. `main/CMakeLists.txt` (REQUIRES), `main/Kconfig.projbuild` (menu
inteiro "Cartao SD (SPI / HW-125)" removido) e os comentarios em
`flash_storage.{c,h}`, `bt_spp_sync.h` e `partitions.csv` que descreviam a
flash como "fallback" foram atualizados — flash interna (SPIFFS, ~2.4MB) e
agora o unico storage do dispositivo. Efeito colateral positivo: boot ficou
~2.8s mais rapido (sem as 5 tentativas de montagem SD com retry/delay que
sempre falhavam, ja que o cartao nao esta mais no hardware).

**2. Throttle de regravacao reduzido de 10s para 2s.** Relato do usuario: "os
10 segundos podem me fazer perder algumas informacoes". Motivo: numa
passagem de carro, a janela de alcance de um AP pode durar so alguns
segundos — com throttle de 10s, `on_beacon` (main.c) so grava 1-2 amostras
de RSSI/posicao por rede por passagem (a 1a deteccao e sempre imediata,
independente do throttle, mas so uma). Isso empobrece a trilha de
RSSI+GPS usada pra estimar a posicao real do AP (quanto mais amostras em
pontos diferentes da rua, melhor a triangulacao). Reduzido o default de
`WARDRIVER_LOG_REWRITE_INTERVAL_MS` para 2000 — ainda filtra a esmagadora
maioria dos beacons (AP transmite a cada ~100ms, entao e um throttle de
~20x, nao grava todo beacon), mas guarda de 3 a 5x mais amostras por
passagem. Custo: mais escrita na flash — mitigado por dois fatores: (a) o
registro binario tem so ~71 bytes (`wardriver_record_t`), e a particao
`storage` tem ~2.4MB de folga, entao mesmo no intervalo mais curto o
espaco dura uma coleta longa antes de precisar de SYNC; (b) sem cartao SD
como fallback (ver item 1 acima), monitorar o percentual de uso
(`mem=%` no OLED/log) e sincronizar com regularidade passa a ser mais
importante do que antes.

Validado por build + flash + boot real (heap estavel ~81-82KB, SPIFFS
montado em ~200ms, captura ativa em ~1.9s de uptime — antes eram ~4.7s por
causa das tentativas de SD). Nao foi possivel validar em campo o ganho de
granularidade de amostras (parado/indoor) — fica para a proxima saida em
movimento, junto com a validacao pendente dos itens 9 e 10.

## 12. Rodada pos-1-mes de testes: OLED, contagem, SSID oculto, resquicios de SD (2026-09-18) — IMPLEMENTADO, PENDENTE DE VALIDACAO EM CAMPO

Compilado (firmware `idf.py build` limpo, sem warnings nos arquivos alterados; app `assembleDebug`
+ testes unitarios JVM). NAO testado em hardware nesta sessao (sem ESP32 nem celular conectados).

**1. OLED apagando com powerbank.** Causa provavel: uma oscilacao no 5V reseta so o SSD1306
(o ESP32 atravessa a queda — o brownout dele so dispara abaixo de ~2.43V, `LVL_SEL_0`). O painel
volta ao estado de fabrica (display OFF, charge pump desligado) mas continua respondendo ACK no
I2C, entao o driver nunca via erro e o `oled_recover_bus` (item 10) nunca disparava. Como o
SSD1306 nao tem leitura de status em modo I2C, "detectar que apagou" nao e possivel. Correcao em
`oled_display.c`: `oled_display_update()` reenvia a configuracao completa (charge pump, contraste,
modo de enderecamento, display ON — todos idempotentes, sem piscar) e a janela de escrita
(0x21/0x22) a cada quadro, um comando por transacao I2C (mesmo primitivo da init, ja validado). Um
painel que resetou reacende no proximo quadro (~2s). O bus clear do item 10 continua para o caso
de I2C travado por erro. Hardware, se persistir: capacitor 100-470uF entre 5V e GND perto do
modulo do OLED, e/ou alimentar o modulo pelo 3V3.
Boot agora loga o motivo do reset (`esp_reset_reason`): `BROWNOUT`/`POWERON` = o ESP32 caiu de
verdade; qualquer outro com a tela apagada = so o painel resetou.

**Contagem de redes.** `REDES` no OLED era o tamanho da tabela de dedup, zerada pelo piso de heap
e pelo SYNC — a contagem "voltava a zero" sozinha. Agora e um contador CUMULATIVO (filtro de
Bloom de 4KB, k=3, so subconta: ~0.5% com 2000 redes, ~5% com 5000), limpo apenas no SYNC. Apos
reinicio, `seed_count_task` (main.c) le os `.wdr` ainda nao sincronizados e restaura a contagem
(BSSID = 6 primeiros bytes de cada registro). Semantica: "redes unicas ainda nao sincronizadas".

**2. Resquicios de SD removidos.** OLED mostrava `SD: OK/FALHA` quando o percentual de uso
nao estava disponivel; agora `MEM: --`/`MEM: FALHA`. Comentarios e Kconfig que citavam SD,
`CONFIG_FATFS_LFN_HEAP` em `sdkconfig.defaults` (era so do FAT do SD) e `main_sd_test.c.ref`
(teste isolado que dependia do `sd_storage.h`, que nao existe mais) removidos. Mantido de
proposito: `main_orchestrator.c.ref` (snapshot historico do firmware completo, ainda com a logica
de SD — nao faz parte do build).

**6. SSID oculto.** Bugs reais achados em `wifi_sniffer.c`:
- SSID IE com N bytes 0x00 ("cloaking por zeros", comum em roteador de operadora) nao era
  detectado: `ssid_hidden = (tag_len == 0)`. O AP era tratado como "com nome", o SSID de zeros
  (string vazia pro C) ainda SOBRESCREVIA um nome ja descoberto, e a revelacao posterior do nome
  nao setava `ssid_just_revealed` (nao furava o throttle). Agora tamanho 0 e "so zeros" sao
  normalizados para oculto, e um frame sem nome nunca apaga um nome conhecido. Frame sem SSID IE
  (truncado) tambem passa a contar como sem nome.
- O reset TOTAL da tabela (piso de heap / SYNC) apagava o SSID ja descoberto de toda rede ainda em
  alcance. Agora o heap baixo poda so as entradas sem sinal ha `WARDRIVER_DEDUP_STALE_SECONDS`
  (120s), depois 1/4 disso, e so entao zera tudo. O SYNC nao mexe mais na tabela.
- Estatisticas a cada ~30s no log (`ssid: tabela=... sem_nome=... | frames: beacon= probe_resp= |
  em_branco: tam0= zeros= | revelados: probe= beacon= | nome_preservado=`) para medir em campo o
  que realmente acontece.
Bug encontrado no caminho e corrigido (nao pedido): o throttle de gravacao de `main.c` era uma
tabela linear de 256 BSSIDs que nunca era limpa; depois do 256o BSSID distinto `should_write`
devolvia SEMPRE true, ou seja, cada beacon (~10/s por AP) virava um registro na flash e no link BT.
Trocada por cache associativo 128x4 (5KB) com despejo do mais antigo — que, por ja ter passado do
intervalo do throttle, nao custa gravacao extra.
Limite conhecido, NAO alterado: canal fixo (`WARDRIVER_WIFI_CHANNEL`=6). Um AP oculto so revela
o nome a um probe response ouvido no canal de captura, e o que esta em outro canal nao e visto.
Channel hopping (ex.: 1/6/11) e a maior alavanca restante, mas e mudanca de escopo da V1 e afeta
a taxa de captura por canal.
Hipotese que o app agora ajuda a testar (tela "Analisar Redes"): boa parte das "ocultas" sao
interfaces extras de roteadores de operadora/mesh (visitante, rede da operadora, backhaul) com
BSSID irmao de uma rede visivel — sem nome no beacon por desenho, nao por perda de captura.

### 12b. Primeira sincronizacao real depois do item 12 (2026-09-18) — deadlock do backlog e crash do app

**Sintoma:** apos flashar o firmware do item 12 o ESP32 nao gravava nada (`errno=28: No space left on
device` ao abrir `sess_0304.wdr`; SPIFFS 1714/2287 KB usados) e o "Sincronizar" do app devolvia `ERRO
sincronizacao sem backend de storage configurado`.
**Causa (firmware):** deadlock. `app_main` tratava "nao abriu a sessao" como "storage indisponivel":
mandava `storage_root=""` e nao registrava o backend de rotacao, entao o backlog nunca era oferecido e o
SYNC falhava — justamente o unico jeito de liberar espaco. **Correcao:** `s_storage_mounted` (particao
montada => sincroniza) separado de `s_storage_ok` (sessao aberta => grava); com a memoria cheia o backlog
e o SYNC funcionam e a sessao e reaberta sozinha a cada 10s (`STORAGE_RETRY_EVERY_CYCLES`).
`flash_storage_start_session` ficou idempotente; o contador de sessao em NVS so e gravado depois que o
`fopen` funciona (antes cada tentativa falha gastava um numero e uma escrita na NVS); o `errno` agora e
logado. `bt_spp_sync`: `offer_backlog_batch` continua oferecendo lotes de 16 sozinho enquanto o lote
anterior for todo confirmado (antes so varria ao conectar: 16 arquivos por conexao); o SYNC tambem oferece
o backlog e nao tenta oferecer "sessao antiga" quando nao havia sessao aberta.
**Causa (app), pre-existente:** com o app em segundo plano, a reconexao automatica chamava
`startForeground()` de novo (o servico saia do foreground ao cair a conexao) e o Android 12+ proibe isso
para servico com tipo `location` => `ForegroundServiceStartNotAllowedException` derrubava o app a cada
queda de BT com o celular no bolso. **Correcao:** o servico continua foreground durante a reconexao e a
excecao e capturada (`inForeground`, `startForegroundCompat`).
**Resultado observado:** 1a tentativa — `sess_0294.wdr` (1.741.132 bytes, ~24,5 mil registros) chegou
corrompido em transito (CRC local `f9ace519` != anunciado `930c5c5c`; 24.425 registros descartados pela
validacao de faixa do codec) e NAO foi confirmado nem apagado (o protocolo funcionou como projetado); o
ESP32 reiniciou ~10s depois. 2a tentativa, pela reconexao automatica: mesmo arquivo com CRC `930c5c5c`
verificado, confirmado e removido. Depois: `mem=0%`, captura voltou a gravar, celular conectado enviando
`LOC`. App: 3297 redes (1945 com posicao, 51% sem nome, 0 registros fora de faixa).
**Em aberto:** (1) causa da corrupcao da 1a transferencia nao determinada — suspeitos: crash/instabilidade
de coexistencia WiFi+BT sob carga (item 1) e a `seed_count_task` lendo o mesmo arquivo em paralelo; (2) o
reset do ESP32 ~10s depois da falha (motivo lido no boot: POWERON, nao PANIC/BROWNOUT); (3) o `OK removido`
que o firmware manda no ACK nao depende do retorno de `remove()` (`handle_ack_line`) — vale checar o
retorno antes de responder; (4) `seed_count_task` demora ~30s+ para restaurar a contagem de 1,7 MB.

**12c (2026-09-18):** `handle_ack_line` agora so responde `OK removido` se `remove()` funcionou
(`remove_confirmed_file`; ENOENT conta como removido); em falha responde `ERRO nao foi possivel remover`,
loga o `errno` e desliga a continuacao automatica do backlog nessa conexao (senao um arquivo que nao apaga
seria reofertado sem fim, ~1,7MB por volta). `ACK ALL` tambem conta e reporta falhas. Validado em campo: os
dois arquivos pendentes (`sess_0304`, `sess_0305`) foram verificados por CRC e removidos na reconexao
automatica. **Em aberto, agora com 2 ocorrencias:** a transferencia do backlog as vezes falha logo depois
de conectar — o app nao recebe o banner e trata os primeiros bytes como registros (24.425 e 119 descartes
`canal fora da faixa: 0`); na 1a vez o CRC nao bateu e nada foi apagado, na 2a a transferencia ESTAGNOU
por ~4 min (sem CHECKSUM, sem timeout dos dois lados) ate o ESP32 ser reiniciado. Tentativas seguintes
funcionaram. Suspeita nao confirmada: `send_all` retorna em silencio quando `write()` devolve <=0 (descarta o
resto do trecho sem log) e nao ha timeout de leitura no app durante o blob. Proximo passo sugerido:
logar `errno` em `send_all`, tratar EAGAIN com retentativa e adicionar timeout de inatividade no blob do app.

**12d (2026-09-18, tarde) — diagnostico + robustez do envio do backlog.** Firmware (`bt_spp_sync.c`): `send_all` deixou de
abandonar em silencio o resto do trecho quando `write()` devolve <=0 — retenta EAGAIN/EWOULDBLOCK/EINTR/ENOMEM/0 a cada 10ms
enquanto houver progresso (5s no backlog, 200ms no streaming ao vivo, que roda na task de captura) e loga retorno+errno+bytes;
falha => `s_conn_broken`, o arquivo NAO recebe CHECKSUM e a conexao e derrubada de proposito (o app reconecta e o backlog e
reoferecido do zero). Cada arquivo loga "oferecendo" e "enviado (bytes, crc32)"; leitura curta do SPIFFS aborta em vez de
mandar CHECKSUM inconsistente; marcador+registro ao vivo saem num unico `write`. App (`WardriverSyncService.kt`): watchdog —
fecha o socket se um arquivo de backlog ficar 15s sem chegar byte, ou se o banner nao chegar em 15s; CRC divergente reconecta
para reenvio (ate 3x por arquivo); registros de backlog ficam retidos ate o CRC bater e sao gravados no banco ANTES do ACK
(o ACK faz o ESP32 apagar o arquivo), em vez de irem para o banco durante a transferencia.
**Validado em campo:** um ciclo completo com o firmware novo (boot -> reconexao -> `oferecendo sess_0310.wdr (2400 bytes)` ->
`enviado ... crc32=2f8accc0` -> `ACK ... removido=1` em ~300ms -> `LOC` do celular). 0 crashes, 0 descartes, 0 watchdog no logcat.
**NAO validado:** o watchdog do app, a reconexao por CRC divergente e o aborto por falha de envio nunca dispararam (a falha
intermitente nao voltou), entao so compilaram. A sincronizacao das 16:24 nao tem log do ESP32 (o logger de 15 min ja tinha
terminado) nem log do app (o `onLog` so escreve no logcat com a tela aberta).
Correcao de leitura: as 170 redes com sessao `sem_storage` no banco do app sao de 29/ago (coleta com o storage indisponivel,
so streaming ao vivo), nao de hoje.

**12e (2026-09-18):** importacao de CSV validada em campo pelo usuario no celular real — o dialogo de resumo apareceu com redes
novas, atualizadas e quantas tem posicao (mapa). Continuam sem validacao manual: busca (lista e mapa), mapa (posicao atual e
centralizacao ao abrir), tela "Analisar Redes" com a quebra de ocultas, e o texto `MEM:` / reacendimento do OLED.

**12f (2026-09-19) — analise da coleta de campo (banco do app: 3418 redes; +121 desde 18/09 17h).** Coleta = `sess_0317`
(77 redes, recebida 18/09 19:03) + `sess_0319` (155, recebida 19/09 11:55): 232 redes, ~3 km x 5 km, 99.6% com posicao (GPS do celular
funcionando), 0 registros fora de faixa. A sincronizacao das 19:48 (`sess_0320` 0 bytes, `sess_0321` 15975 bytes) confirmou e removeu
sem CRC divergente, sem watchdog, sem crash. O logcat do celular so cobre desde 19:07 de 19/09, entao NAO ha log das sincronizacoes da
coleta em si; tambem nao ha o log do ESP32 (linha `ssid:` inexistente para essa coleta).
**Sem nome: 48.3% na coleta vs 51.4% na base (ontem 51.4%) — diferenca dentro do ruido (n=232, erro ~3pp). NAO ha evidencia de
melhora.** Motivo, que devia ter sido dito no item 12: o app ja contava `ssid vazio` como sem nome, entao detectar SSID de zeros
nao muda esse percentual; as correcoes protegem nomes ja descobertos (nao sobrescrever, nao esquecer na poda), o que so aparece em
`names_revealed_*`/`name_kept` da linha `ssid:`, nao no %.
**Correcao da hipotese do item 12:** so 16-17% das ocultas tem irma visivel (mesmo OUI, <=1 dos 3 ultimos bytes diferente) e 38-41%
tem o bit "administrado localmente"; o que se viu no WARDRIVE.csv antigo (56% / 90%) nao se repete no app. Interfaces extras de roteador
explicam so uma parte.
Correlacoes (base inteira): sem nome ~independente do RSSI (42-52% em todas as faixas) — nao e sinal fraco; cai com o numero de
sightings (1x: 57.9%, 2-4x: 47.4%, 5-19x: 43.0%, >=20x: 35.6%); sem WPS 60.1% vs com WPS 42.4%; WPA3 64% vs WPA2 50%.
Achado de precisao de posicao: 81% das redes tem RSSI armazenado <= -86 dBm — o app grava a ULTIMA captura por BSSID (posicao e RSSI
sobrescritos), que e a mais fraca (AP saindo de alcance). A importacao de CSV ja usa a de RSSI mais forte; o caminho ao vivo
(`WardriverSyncService.mergeSsid`/`persistBlob`) nao. Sugestao nao implementada: manter posicao+RSSI da captura mais forte.
Para separar "nome nunca divulgado" de "nome perdido" falta a linha `ssid:` do firmware numa coleta em movimento (zeros vs tamanho 0,
reveladas por probe/beacon, sem_nome na tabela).

## 12g. Posicao do AP pela captura mais forte; modulo GPS + celular; diagnostico do firmware pelo BT (2026-09-19) — IMPLEMENTADO, NAO GRAVADO NO ESP32 NEM INSTALADO

**1. App: posicao e RSSI da captura mais forte** (`NetworkMerge.mergeCapture`, usada no caminho ao vivo, no backlog e dentro de
cada arquivo de backlog). Antes a ultima captura sobrescrevia tudo — em campo 81% das redes ficaram com RSSI armazenado <= -86 dBm.
Regras: captura nova assume posicao+RSSI so se tem posicao e (rede sem posicao, ou RSSI maior; empate mantem); captura sem fix
nunca apaga posicao (antes apagava); nome novo atualiza, captura sem nome nao apaga nome; cripto/canal da mais recente; timesSeen =
maior. Vale so para capturas novas — as posicoes ja no banco continuam as "da ultima captura". 13 testes JVM.

**2. Diagnostico do firmware sem computador.** Ligar a serial num PC nao serve para uma saida de campo (o ESP32 anda com o celular),
entao o firmware manda a linha `STATS t=.. redes=.. sem_nome=.. beacon=.. probe=.. tam0=.. zeros=.. rev_probe=.. rev_beacon=..
preservado=.. gps=.. mod_fix=.. mod_sats=.. mod_view=.. mod_trk=.. mod_snr=.. mod_hdop=..` a cada ~30s pelo SPP
(`bt_spp_sync_send_status_line`; descartada se ha transferencia de arquivo em curso, para nao corromper o CRC). O app grava em
`Android/data/com.wardriver.sync/files/firmware_stats.log` (`adb pull`, sem run-as; rotaciona em 512KB) e a tela "Analisar Redes" mostra a
ultima leitura e a variacao desde o inicio do boot. Responde o que ficou em aberto no 12f: SSID em branco de tamanho 0 x zeros,
nomes revelados por probe/beacon. So aparece com celular conectado durante a coleta.

**3. Modulo GPS + celular.** O modulo (`gps_uart_task`) ja lia a UART o tempo todo; o que faltava era USAR e ENXERGAR: o celular
vencia sempre que respondia, entao um fix do modulo nunca entrava no registro e o progresso dele era invisivel. Agora
`get_best_gps_data` escolhe a fonte de MENOR erro estimado (celular: precisao informada pelo Android; modulo: HDOP x 5 m), com
histerese de 30% e empate para o celular. O fix do modulo so vale se atualizado ha < 3s (o driver nao zera `has_fix` quando o modulo
para de falar, entao um fix velho carimbava a mesma coordenada em toda captura; sem fonte valida o registro sai sem posicao).
Marca no registro: modulo => `gps_quality` >= 1; celular => 0 com FLAG_HAS_FIX. OLED: `GPS:CEL|MOD|-- SATS:n`, com n = satelites do modulo
(usados no fix; ou rastreados agora se ainda sem fix). Log: `fonte=`, `mod_*`, e transicoes ("GPS do modulo: FIX obtido pela 1a vez apos N s
de boot", "fix PERDIDO", "fonte de posicao: X -> Y"). Verificado num harness no PC com o codigo real extraido de `main.c`
(14 cenarios: so celular, so modulo, modulo velho, empate, histerese nos dois sentidos, sem precisao informada).
**NAO validado no hardware:** se o modulo consegue fix (o item 5 diz que a antena esta degradada); o que vier em `mod_view`/`mod_trk`/
`mod_snr` no STATS responde isso. **Nao feito:** assistencia ao modulo (UBX-AID-INI com posicao/hora do celular para acelerar o fix
a frio) — exige hora GPS que o `LOC` nao carrega e nao da para conferir se o TX do ESP32 (GPIO17) chega ao RX do modulo.

## 13. OLED sempre ativo e nomes de SSID atualizados no app (2026-09-20) — IMPLEMENTADO, NAO GRAVADO NO ESP32 NEM INSTALADO

**1. OLED (`oled_display.c`, reescrito).** Brechas achadas no driver do item 12: (a) uma falha no `oled_display_init()` do
boot era PERMANENTE — `main.c` fazia `s_oled_ok=false` e nunca mais chamava o display, e um 2o init falhava sozinho com
`ESP_ERR_INVALID_STATE` porque o driver I2C ja ficara instalado; (b) o re-arme so acontecia quando o laco principal rodava
(a cada 2s); (c) sem bus clear no boot: o painel e alimentado pelos 5V e NAO reseta com o ESP32, entao um reset no meio de uma
transacao podia deixar o SDA preso e o init falhar para sempre (o sintoma "nao voltou nem apos reiniciar" do item 10);
(d) sem contadores nem indicador visual de saude. Agora: **guardiao** (task propria, 1 Hz, incondicional) refaz o painel mesmo
com o laco principal parado; cada quadro comeca com 3 NOPs (0xE3) e reenvia toda a configuracao; **reinicio a frio** (display
OFF + charge pump desligado/religado) a cada `WARDRIVER_OLED_FULL_REINIT_INTERVAL_S` (300 s; 0 desliga); init no boot com 3
tentativas e **bus clear em toda subida do driver**; falha no boot/recuperacao nao e definitiva (guardiao insiste a cada 5 s, sem
prender o laco principal); diagnostico do bus clear (SDA/SCL presos; "SCL em baixo" nao se resolve por software e e logado);
mutex recursivo; `oled_display_get_stats()` no log a cada ~30 s (`oled: ativo=.. quadros_ok=.. falhas=.. recuperacoes=..`) e no
`STATS` (`oled_up/ok/fail/rec/reinit`, tela "Analisar Redes"); **pontinho** no canto superior direito que pisca a cada segundo
(`WARDRIVER_OLED_HEARTBEAT`): parou de piscar = tela congelada.
**Verificado no PC** contra um emulador do SSD1306 (`host_tests/oled/run.sh`, com AddressSanitizer/UBSan): 41 verificacoes, incluindo
o painel que reseta mas continua dando ACK (reproduz o defeito), laco principal parado, painel no meio de um comando de 2
parametros, init que falha no boot, SDA preso (9 pulsos de clock), barramento morto por 2 min, SCL preso, reinicio a frio e 47
quedas aleatorias em 10 min simulados — **pior tempo de tela apagada: 931 ms**. O 1o desenho (guardiao so empurrando quadro se o
ultimo tinha >900 ms) media 1331 ms no emulador e pior caso teorico ~1,9 s: o emulador achou o erro; virou incondicional.
**NAO validado em hardware:** o emulador modela o SSD1306 pelo datasheet, nao o seu modulo; o reinicio a frio pode piscar a tela
por alguns ms a cada 5 min (ajustavel/desligavel no Kconfig); o custo de ~1,5 quadros/s no I2C (poucos % do barramento) nao foi
medido no aparelho. Se o OLED ainda apagar em campo, os contadores dizem se foi erro de I2C (`falhas`/`recuperacoes` > 0) ou reset
silencioso do painel (tudo zerado e `guardiao` crescendo).

**2. Nomes de SSID atualizados no app.** A regra de "rede vista de novo" estava duplicada em 3 lugares (ao vivo, backlog,
importacao) sem teste. Extraida para `NetworkStore`: oculta vista de novo COM nome passa a ter o nome; rede renomeada passa a ter
o nome novo; captura sem nome nunca apaga nome; posicao/RSSI da captura mais forte. Corrige: (a) o app gravava a MEMORIA antes do
banco — falha de gravacao deixava a lista mostrando o que nao estava salvo; agora banco primeiro; (b) o MAPA so lia o banco ao abrir
e o titulo "(SSID oculto)" ficava congelado ate reabrir mesmo com o nome revelado — agora reconcilia sozinho quando o banco muda
(incremental, so os marcadores alterados, com espera de 1,2 s para juntar rajadas); (c) a importacao de CSV nao renomeava: agora o
nome MAIS NOVO vence (data conhecida e posterior; data 0, como no CSV antigo do ESP32, nunca vence). A tela inicial mostra
"N nome(s) revelado(s)" e o resumo da importacao diz quantos nomes ocultos foram preenchidos. 14 testes novos (60 no total);
sabotagem de mutacao confirmou que eles falham se a memoria voltar a ser gravada antes do banco ou se uma captura sem nome apagar
o nome.
**Limite que continua:** nada disto cria informacao nova. Um nome so entra se o firmware o ouvir (probe response no canal 6,
ver item 12f); redes cujo nome nunca aparece em quadro publico nesse canal continuam ocultas. Alavancas fora do escopo atual:
channel hopping e association requests (quadros de clientes; nao implementado por decisao de privacidade).
Observacao nao investigada: na leitura de bancada `nome_preservado` (18) igualou `probe_resp` (18), o que sugere que as probe
responses chegam do BSSID nomeado do roteador com SSID em branco — possivelmente o transmissor (addr2) difere do BSSID (addr3).
Vale um diagnostico de quadros brutos.

## 14. Channel hopping (1-13) e association requests para achar nomes (2026-09-20) — GRAVADO NO ESP32, COEXISTENCIA COM O BLUETOOTH AINDA NAO TESTADA

**Channel hopping** (`wifi_sniffer_hop_task`, `WARDRIVER_CHANNEL_HOPPING`, padrao ligado): o radio percorre os canais 1-13 (o ESP32
classico so ouve 2,4 GHz; 13 e o maximo permitido no Brasil — o pais do radio e fixado em BR 1-13 no boot). Canais 1/6/11 (onde ficam
quase todos os APs) ficam 200 ms, os demais 110 ms (>= 1 beacon de ~102 ms): volta completa ~1,7 s. Substitui o "canal fixo 6" da V1
(a limitacao apontada no 12f). `WARDRIVER_WIFI_CHANNEL` passou a valer so com o hopping desligado. **Custo:** cada AP e ouvido so em ~1/8 do
tempo (o canal 6, que era ouvido 100%, agora ~12%), entao `times_seen` e a taxa de beacons por AP caem; a cobertura de redes e de
revelacoes de SSID (probe response / association request) sobe para todos os canais.
**Medido no hardware (5 redes em casa, ~156 s de uptime, sem reboot):** 1181 trocas de canal (~7,6/s, como previsto), 0 recusadas;
quadros por canal `1:28 4:9 5:234 6:527 7:200 8:3`, resto 0 — o AP de referencia no canal 6 aparece tambem nos canais 5 e 7 (vazamento fisico
de canais adjacentes), o que confirma que o radio realmente passa por eles; nao da para separar "hopping nao ouve o canal 11" de "nao ha
AP no 11 aqui", so uma coleta em movimento diz. Primeira ocorrencia de SSID de N zeros no ambiente: `zeros=2`.

**Association requests / reassociation requests** (subtipos 0x00/0x20): usados SO para descobrir o NOME de um AP (o pedido de um
cliente carrega o SSID e o BSSID do AP, inclusive de APs ocultos). **Privacidade tratada na origem:** dentro da callback do radio, antes de
enfileirar, o MAC do cliente (addr2) e o "AP anterior" do reassociation sao ZERADOS; so se le BSSID (addr1 == addr3, unicast) e SSID; RSSI/
canal/posicao do pedido NAO viram dado do AP (o sinal e o do cliente). Nome de AP ainda fora da tabela vira uma "dica" por 30 s
(anel de 16) e e aplicado quando o beacon dele chegar (com o hopping, ate uma volta depois). Nunca sobrescreve um nome ja conhecido.
Probe requests, autenticacao, deauth, disassoc, respostas de assoc, action e dados continuam descartados.
**Verificado no PC** (`host_tests/sniffer/run.sh`, quadros 802.11 montados byte a byte, AddressSanitizer): 42 verificacoes, incluindo
"o MAC do cliente nao aparece em NENHUM byte da fila, da tabela de redes, das dicas nem do que e entregue ao consumidor". **Mutacao:**
removi cada protecao e o teste falhou (MAC do cliente nao zerado: 3 falhas; AP anterior nao zerado: 2; aceitar probe request: 1).
**NAO validado no hardware:** `assoc_req=0` nos ~150 s de bancada (nenhum cliente se associou no alcance) — o caminho so foi exercitado no PC.
**Nao testado ainda: hopping + Bluetooth conectado.** Trocar de canal ~7x/s mexe na coexistencia Wi-Fi/BT (o item 1 ja e um crash
conhecido nesse par). Se a conexao com o celular ficar instavel, desligar `WARDRIVER_CHANNEL_HOPPING`.
Novos campos em `STATS`: `assoc`, `rev_assoc`, `hops`, `hopfail`, `ch=1:n,2:n,...` (tela "Analisar Redes" mostra).

**Armadilha de build que quase gravou o firmware errado:** depois de editar `main/Kconfig.projbuild`, `idf.py build` (ninja) NAO reexecutou
o CMake no build isolado, o `sdkconfig` nao ganhou os simbolos novos e o firmware compilou "com sucesso" no caminho de canal FIXO (`#ifdef
CONFIG_WARDRIVER_CHANNEL_HOPPING` falso). Sempre que o Kconfig mudar: `idf.py ... reconfigure` e conferir `build/config/sdkconfig.h`. O mesmo
vale para o build da IDE.

## 15. Rotina automatica no app (SYNC periodico + envio a nuvem) e Wardriver Web (2026-09-20) — IMPLEMENTADO, NAO INSTALADO NEM TESTADO EM APARELHO
**Decisao do usuario:** a gravacao no ESP32 **continua como esta** (regrava cada rede a cada `WARDRIVER_LOG_REWRITE_INTERVAL_MS`=2 s, mesmo
parado). A proposta de "gravar so quando algo mudou" (motivada pelo `MEM:` chegar a 40% na bancada, ~12%/h) foi **descartada**; o que
limita o crescimento agora e o esvaziamento periodico pela sincronizacao.
**App:** rotina em `WardriverSyncService` (`startAutoSync`, `runCycle`): a cada 5/10/15/30/60 min enquanto a coleta esta ativa, manda `SYNC`,
espera o fim (`SyncTracker`: sessao nova anunciada + nenhum bloco aberto + 3 s de silencio; o firmware NAO manda marcador de fim, a
heuristica vem da leitura de `bt_spp_sync.c`) e, em sequencia, envia a nuvem (`CloudUploader`: lotes de 1000, marca d'agua so avanca apos o
lote aceito, retry 2 s/5 s, 401/403 para na hora, 413/422 isola o registro ruim por bissecao). Envia mesmo se o ESP32 nao sincronizou.
Config em `CloudSettingsActivity` (so https; token cifrado no Keystore e fora do backup; confirmacao LGPD antes do 1o envio).
**Efeito no firmware, ja existente e agora periodico:** cada `SYNC` chama `rotate_session_and_reset_count` e ZERA o contador `REDES` do OLED.
**Backend/frontend:** ver `web/README.md` (FastAPI + MySQL, React).
**Verificado:** 105 testes JVM (2 sabotagens de regra detectadas); contrato do JSON do app contra o backend REAL (SSID hostil identico, reenvio
idempotente). **NAO verificado:** nada em aparelho (tela nunca renderizada, Keystore, agendamento com tela apagada/Doze, fim do SYNC contra o
firmware real) e o backend contra MySQL (sem acesso ao Docker nesta maquina).

### 15b. Reinicios espontaneos do ESP32 por watchdog, vistos no primeiro teste real da rotina (2026-09-20) — CAUSA NAO ESTABELECIDA
**Sintoma:** com o app conectado, a placa reiniciou sozinha varias vezes em ~5 h (boots inferidos = hora do `STATS` - uptime `t`: 14:54, 14:59,
17:52, 19:17, 19:30, 19:35; intervalos de 5, 172, 84, 14 e 4,5 min); cada reinicio derruba o Bluetooth e o app reconecta. Nao foram causados
pelos `SYNC` da rotina (o de 19:17 ocorreu antes de qualquer `SYNC` daquele ciclo; o de 19:30 nao coincide com ciclo nenhum).
**Evidencia (serial, `/dev/ttyACM0`, o firmware atual — hopping 1-13):** `rst:0x8 (TG1WDT_SYS_RESET)`, `PRO CPU has been reset by WDT`, e os DOIS
nucleos no mesmo PC `0x40081965`; o firmware confirma `motivo do ultimo reset: WDT (watchdog)`. Convertendo o PC no ELF de um build isolado do
mesmo codigo: **`panicHandler` (esp_system/port/panic_handler.c:300)** — ou seja, houve um panic e o watchdog de hardware estourou DENTRO do tratador.
O comentario do proprio ESP-IDF diz que `panicHandler` cobre "cache error, wdt expiry"; o sdkconfig tem `CONFIG_ESP_INT_WDT=1` com
`CONFIG_ESP_INT_WDT_TIMEOUT_MS=300` e `CHECK_CPU1=1`. Leitura mais provavel: **Interrupt WDT** (algo manteve as interrupcoes bloqueadas > 300 ms).
**Ressalvas:** (1) o ELF foi reconstruido depois (build isolado), nao e o binario gravado byte a byte, embora o endereco caia exatamente num
simbolo coerente (panicHandler, ativo nos dois nucleos); (2) o texto do panic (tipo, backtrace) NAO foi capturado — o reset ocorre dentro do
tratador e a serial so pegou o boot seguinte; (3) NAO sei o que segurou as interrupcoes. Suspeitas nao verificadas: gravacao/GC do SPIFFS (cache
de flash desabilitado), `esp_wifi_set_channel` ~7x/s do hopping em coexistencia com o BT (item 14 ja marcava isso como nao testado), ou o
proprio tratamento de `SYNC`/ACK. **Proximos passos sugeridos:** capturar a serial ate o proximo panic para ler o texto; comparar a taxa de reinicios
com `WARDRIVER_CHANNEL_HOPPING=n` (exige regravar; lembrar do `reconfigure`); so entao mexer no codigo.
**Coleta parada na bancada (14 redes):** o backlog antigo `sess_0328.wdr` tinha 811530 bytes = 11.333 registros de 14 redes — a regravacao a cada 2 s
(mantida por decisao do usuario) explica o `MEM:` crescendo parado. A 1a transferencia dele chegou corrompida (CRC nao bateu, o app nao gravou nada
e reconectou) e a 2a veio integra: o problema intermitente do item 12b continua, e arquivos grandes parecem mais expostos (nao provado).
**Atualizacao (19:42-19:43, serial capturada ao vivo): panic real confirmado.** Dois panics seguidos, ambos
`Guru Meditation Error: Core 0 panic'ed (Interrupt wdt timeout on CPU0)`, com o nucleo 0 "running in ISR context" e o nucleo 1 ocioso
(`esp_vApplicationIdleHook`); o firmware confirma no boot seguinte `motivo do ultimo reset: INT_WDT`. Backtraces decodificados (ELF de build isolado;
o SHA256 do ELF nao bate com o gravado — o binario embute data/hora de compilacao —, o que sustenta a leitura e a coerencia dos simbolos):
 1. 19:42:40: `i2c_isr_handler_default` (driver/i2c/i2c.c:578/591/633), entrado por `_xt_lowint1`, interrompendo a `btdm_controller_task`
    (`xQueueReceive` em `bt.c:806`). O driver I2C legado tem um caminho em que, com o estado ocioso, a ISR faz `return` SEM limpar a interrupcao
    (comentario "Do nothing if there is no proper event") — no ESP32 isso vira tempestade de interrupcoes. Mas os contadores do OLED estavam
    `falhas=0 recuperacoes=0` ate o crash, entao NAO ha prova de que o I2C tenha causado.
 2. 19:43:45: `coex_arbit_insert` <- `coex_core_request` <- `coex_bt_request` <- `coex_bt_request_wrapper` (bt/controller/esp32/bt.c:1147), ou seja,
    dentro do arbitrador de coexistencia Wi-Fi/Bluetooth (biblioteca fechada), em ISR.
**Leitura honesta:** o backtrace mostra ONDE o nucleo 0 estava quando o watchdog (300 ms) venceu, nao QUEM o prendeu; dois pontos diferentes, ambos em ISR
com o BT conectado. O denominador comum e o Bluetooth ativo + Wi-Fi em modo promiscuo com **hopping ~7 trocas/s** (item 14 ja marcava a coexistencia como nao
testada) — e o suspeito mais forte, mas NAO provado. Antes do hopping (canal fixo 6) o STATS de 19/09 mostra uptime de 1323 s sem reinicio (amostra
pequena). **Teste que decide:** gravar uma vez com `WARDRIVER_CHANNEL_HOPPING=n` (canal fixo) e comparar a taxa de reinicios nas mesmas condicoes
(BT conectado, celular parado). Se cair, o proximo passo e reduzir/coordenar as trocas de canal com a coexistencia; se nao, investigar o I2C do OLED.

## 16. Viagem real (10 mil+ redes): mapa lento no app e deadlock de backlog travado (2026-10-09) — RESOLVIDO

Usuario levou o dispositivo numa viagem (>10 mil redes coletadas). Dois problemas relatados ao voltar:
mapa do app lento/pesado com o volume, e Bluetooth que degradou nos ultimos dias ao ponto de nao
sincronizar mais (reconectando sem parar, ~1000 redes presas na memoria do ESP32).

**1. Mapa lento (app, `MapActivity`).** Causa: um `Marker` osmdroid por rede (objeto com drawable,
balao, ancora) dentro de um `FolderOverlay` — com 12,6 mil+ redes com posicao (de 20,1 mil no banco),
cada desenho/toque percorre todos os `Marker`, inclusive os desabilitados pela busca. Corrigido:
trocado por um unico `SimpleFastPointOverlay` (desenha direto no Canvas, sem objeto de View por
rede) com `RenderingAlgorithm.MAXIMUM_OPTIMIZATION` (agrupa por grade de pixels: o custo passa a
depender da area visivel, nao do total de redes). O balao de informacao, antes automatico no
`Marker`, agora e aberto manualmente (`NetworkInfoWindow`, layout `map_info_window.xml`) na posicao
tocada. Verificado: compila limpo, 105 testes JVM (nenhum mexe em `MapActivity`, que nao tem teste
de UI) e `assembleDebug` ok. **NAO verificado em campo com o volume real** (sem o dispositivo em
movimento nesta sessao) — only verificado que a classe compila e os testes existentes continuam
passando; a melhora de fluidez com 12 mil+ pontos foi avaliada pelo mecanismo (clustering em vez de
objeto por marcador), nao medida em quadros por segundo.

**2. Backlog travado num arquivo permanentemente corrompido + SPIFFS cheio (firmware, `bt_spp_sync.c`)
— causa raiz real da "instabilidade que so piora", NAO e o watchdog do item 15b.** Diagnostico pela
serial (USB, so leitura, sem tocar DTR/RTS) com o app reconectando a cada ~10-20s: o motivo NAO era
reinicio por watchdog (nenhum `rst:`/panic apareceu) — era um **deadlock de design**. `sess_0559.wdr`
tinha o tamanho do diretorio (2560 bytes, via `fstat`) maior que o conteudo de fato legivel no SPIFFS
(2432 bytes) — corrupcao permanente do tipo que o item 2 ja catalogava (reset no meio de uma
gravacao antiga). `offer_backlog_batch` varre o diretorio e oferece os arquivos em ordem; ao chegar
nesse arquivo, `offer_file_for_backlog` ja tinha mandado `--- BEGIN ... bytes=2560 ---` ao celular
e soh conseguia entregar 2432 — a unica saida seguravel era derrubar a conexao (como o codigo ja
fazia, de proposito, para nao dessincronizar o stream). Como o celular reconecta e **reoferece o
backlog do zero**, toda conexao nova caia no MESMO arquivo antes de chegar aos arquivos saudaveis
depois dele — nenhum ACK dos arquivos ja enviados chegava a ser processado (a conexao morria bem
na hora do ACK), entao nada nunca era removido e o ciclo se repetia para sempre. Em paralelo, o
SPIFFS (~2,4 MB) estava 100% cheio (`errno=28: No space left on device` ao abrir `sess_0570.wdr`),
perdendo capturas novas silenciosamente ("FALHA AO GRAVAR... capturas estao sendo perdidas").

**Correcao:** `offer_file_for_backlog` agora VALIDA o arquivo (le o conteudo inteiro sem mandar nada,
`file_fully_readable`) ANTES de anunciar o `BEGIN` — se a leitura nao bate com o `fstat`, o arquivo e
permanentemente corrompido: loga, `remove()` do SPIFFS (libera espaco) e devolve um resultado
`BACKLOG_OFFER_SKIPPED` novo (em vez do antigo `bool`) que `offer_backlog_batch` trata seguindo para
o PROXIMO arquivo do diretorio, sem derrubar a conexao. Isso implementa o mecanismo que o item 2 tinha
deixado como pendencia ("falta identificar um arquivo permanentemente corrompido e descarta-lo").
`do_manual_sync` (que tambem chama `offer_file_for_backlog`) nao precisou mudar — ja ignorava o
retorno e so olhava `s_conn_broken`, que o novo caminho `SKIPPED` deixa como estava (false).

**Verificado em hardware real (serial + app no celular, mesma sessao):** gravado via
`idf.py build` isolado (scratchpad, mesmo ESP-IDF 5.5.3 da IDE) + `esptool write_flash` so da
particao `factory` (0x10000) — `storage` (0x190000) intocada, nenhum dado perdido pelo flash em si.
Apos o flash: `sess_0559.wdr permanentemente corrompido (fstat=2560 bytes, leitura incompleta) —
apagando`, sem derrubar a conexao; os 7 arquivos de backlog seguintes (3,4 a 202 KB) foram enviados e
TODOS confirmados (`ACK ... removido=1`) na mesma conexao — primeira vez que isso aconteceu desde que
o problema comecou. `mem` caiu de cheio (errno=28) para 56% e depois 0%; log confirmou "espaco
liberado — sessao sess_0570 aberta, capturas voltaram a ser gravadas". Conexao ficou estavel por
4+ minutos (recebendo `LOC` do celular a cada ~2s sem cair), contra os ~10-20s de antes. Banco do app
foi de 19.225 para 20.131 redes (+906, prox. das "quase 1000" relatadas) so com o primeiro lote;
arquivos maiores (sess_0568 120 KB, sess_0561 97 KB e **sess_0569, 1.098.036 bytes** — o maior backlog
unico ja visto no projeto) foram enviados e confirmados numa reconexao seguinte, sem acrescentar redes
novas distintas (mesmas redes de sessoes ja sincronizadas). APK atualizado (com o fix do mapa) foi
instalado por cima (`adb install -r`) e reconectou normalmente.

**Ainda presente, NAO relacionado a esta correcao:** quedas de conexao BT esporadicas e transitorias
(`BluetoothSocket.connect()` falhando por alguns ciclos antes de pegar) — o mesmo tipo de
instabilidade do Bluetooth deste celular ja documentada em sessoes anteriores; a reconexao automatica
do app absorve isso. O OLED continua sem inicializar (`ESP_FAIL` a cada tentativa, centenas de
`init_retries`) — falha de hardware/fiacao preexistente, fora do escopo deste item.

## 17. Novo tipo de panic (InstructionFetchError) + travamento silencioso, visto depois do fix do item 16 (2026-10-09) — CAUSA NAO ESTABELECIDA

**Sintoma:** horas depois do fix do item 16 (conexao BT ja estavel), pegado ao vivo na serial:
`Guru Meditation Error: Core 0 panic'ed (InstructionFetchError)`, `PC: 0x3ffcf614` — um endereco em
RAM (regiao `0x3ffxxxxx`), nao em codigo mapeado; `EXCVADDR == PC`; `EXCCAUSE: 0x00000002`.
Backtrace: `<-CORRUPTED` (pilha ja corrompida, nao deu pra desenrolar). Diferente dos dois panics
ja catalogados (item 1, `coex_bt_release`/`IllegalInstruction`; item 15b, `Interrupt wdt timeout`) —
aqui o nucleo pulou para executar um endereco de DADOS, assinatura classica de ponteiro de funcao ou
endereco de retorno corrompido (estouro de buffer, use-after-free, ou pilha estourada sobrescrevendo
o retorno salvo). `A0` (0x8008319c, registrador de retorno) nao resolveu a nenhum simbolo coerente no
ELF do build isolado (nem direto nem mascarando os 2 bits altos de call-size do ABI em janela do
Xtensa) — sem pista de qual funcao fez a chamada/salto ruim.

O reinicio automatico apos o panic funcionou (boot limpo, `motivo do ultimo reset: PANIC`, BT
reconectou, `LOC` voltou a fluir). Mas **~45s depois** a serial ficou **completamente muda** (nenhuma
linha, nem `STATS`, nem erro) por tempo indeterminado — nao um reinicio novo (sem log de boot), um
travamento real. So recuperou apos reset forcado via `esptool chip_id` (pulso RTS). Esse tipo de
travamento silencioso e mais grave que os panics documentados: ali pelo menos o watchdog/handler
reinicia sozinho; aqui nao reiniciou por conta propria dentro da janela observada.

**Ressalvas:** uma unica ocorrencia, sem repeticao proposital; nao sei se o travamento silencioso tem
relacao com o panic anterior ou e um evento separado; o ELF usado pro addr2line e de um build isolado
(mesmo risco de nao bater 100% com o binario gravado, ja documentado no item 15b). Depois do reset
forcado, ~5 min de observacao sem nenhum novo panic/silencio.

**Proximos passos sugeridos (nenhum feito ainda, aguardando pedido do usuario):** deixar o logger
de serial so-leitura rodando por mais tempo pra capturar outra ocorrencia (idealmente com o endereco
do panic resolvendo a um simbolo real desta vez); se travar em silencio de novo, tentar o watchdog de
hardware (RTC) em vez de depender so do INT_WDT pra forcar reinicio automatico mesmo num travamento
sem panic visivel; considerar se e o mesmo denominador comum do item 15b (hopping + BT + heap
pressure) ou algo novo introduzido pelo fix do item 16 (o path de validacao de arquivo em
`file_fully_readable` le o arquivo inteiro ANTES do envio — nao deveria interagir com o storage ao
mesmo tempo que o streaming ao vivo, mas vale revisar se ha alguma condicao de corrida nova ali).

## 18. Diagnostico TEMPORARIO de SSID oculto: log bruto de "que tipo de quadro revelou/nao revelou o nome" (2026-10-09) — IMPLEMENTADO, A SER REMOVIDO DEPOIS

**Motivacao do usuario:** depois da viagem (10 mil+ redes), avaliar se as redes que ficaram marcadas
como "oculta" sao realmente ocultas (nunca anunciam o nome em quadro publico nenhum) ou se e perda de
cobertura (o hopping/alcance nao deu tempo de ouvir o quadro que revelaria). Inspirado no log bruto de
pacotes de um pwnagotchi tradicional (Raspberry Pi + bettercap) — mas um pcap de verdade NAO cabe neste
hardware: um quadro bruto (beacon/probe com os IEs) tem 150-400+ bytes, contra os 71 do registro
persistido, e a particao `storage` (~2,4 MB) acabou de ficar 100% cheia com o formato atual (item 16).
**Decisao:** em vez de gravar quadros crus, uma linha de texto por BSSID na 1a vez que ele aparece em
cada TIPO de quadro (nao por quadro recebido) — poucas linhas por rede, nao milhares. **Combinado
explicitamente com o usuario que isto e temporario e sera removido** quando a investigacao terminar
(nao faz parte do escopo definitivo do projeto).

**Implementacao:**
- `wifi_sniffer.h`/`.c`: campo `diag_seen` (bitmask, 1 byte) em `wifi_sniffer_network_t` — so em RAM,
  nunca persistido no registro de 71 bytes nem no protocolo BT. Bits: beacon/probe com ou sem nome,
  nome revelado por association request. Em `upsert_network` (as duas situacoes: BSSID novo na tabela
  e BSSID ja conhecido) e em `apply_name_hint` (revelacao por assoc quando o AP ja esta na tabela), na
  1a vez que um bit novo liga para aquele BSSID, uma linha `DIAG <bssid> <evento> ch=<canal> rssi=<rssi>`
  entra numa fila circular pequena (12 slots, ~860 bytes) e e logada na serial na hora
  (`wifi_sniffer_diag_pop_line`).
- `main.c`: a cada ciclo do laco de status (~2s, nao so a cada 30s do STATS), esvazia a fila e repassa
  cada linha pelo BT via `bt_spp_sync_send_status_line` (mesma funcao do STATS: travada por mutex,
  descartada se ha transferencia de arquivo em curso — zero risco pro protocolo de sync que foi
  estabilizado no item 16).
- App (`WardriverSyncService.kt`): reconhece o prefixo `DIAG ` e grava em `firmware_stats.log` (mesmo
  arquivo do STATS, `FirmwareStatsLog.append`) — legivel com `adb pull` sem `run-as`, sem precisar da
  serial ligada no PC durante uma coleta de campo real.

**Como usar:** depois de uma saida de coleta, `adb pull .../firmware_stats.log` e procurar por `DIAG` e
pelo BSSID de interesse. Uma rede com `times_seen` alto (visto em muitos ciclos do hopping) e SEM
nenhuma linha `probe_sem_nome`/`probe_com_nome`/`assoc_revelou` e evidencia de oculta de verdade (nunca
respondeu a um probe nem foi associada por perto); uma rede so com uma ou duas linhas de
`beacon_1o_sem_nome` e `times_seen` baixo sugere so falta de tempo de alcance, nao ocultacao real.

**Verificado:** compila limpo (firmware `idf.py build`; app `assembleDebug`); `host_tests/sniffer`
continua com as 42 verificacoes (o campo novo nao quebrou nada). Testado na bancada ponta a ponta:
evento apareceu na serial (`DIAG 70:AA:BB:FC:84:30 beacon_1o_com_nome ch=6 rssi=-73`) e, na mesma
conexao, chegou ao `firmware_stats.log` do celular com timestamp. **NAO verificado em campo/movimento**
(so bancada, poucas redes fixas).

**Limitacao conhecida (aceita, por ser diagnostico temporario):** `diag_seen` vive na entrada da tabela
de deduplicacao em RAM — se a entrada for podada (piso de heap, ver item 7/8) e a rede for vista de
novo, o bitmask comeca zerado outra vez e os eventos de "1a vez" podem repetir. Para a pergunta que o
usuario quer responder (oculta de verdade x falta de alcance numa UNICA passagem) isso nao invalida a
leitura, so significa que o "1a vez" e por entrada da tabela, nao pela vida inteira do BSSID.

**Remocao planejada:** quando a investigacao terminar, remover: o campo `diag_seen` e o bloco
`DIAG_SEEN_*`/fila/`diag_push_locked`/`diag_note_locked`/`wifi_sniffer_diag_pop_line` inteiro em
`wifi_sniffer.c`/`.h`; o bloco `#if CONFIG_WARDRIVER_BT_ENABLED` de drenagem em `main.c`; o branch
`DIAG ` em `WardriverSyncService.kt`. Nenhuma outra parte do sistema depende disso.

**Observado durante o teste desta sessao (nao relacionado ao item 18 em si):** na bancada, em menos de
uma hora, reapareceram DUAS falhas ja catalogadas — o crash de coexistencia `IllegalInstruction` do
item 1 e o `TG1WDT_SYS_RESET`/`panicHandler` do item 15b (mesmo PC `0x40081965` nos dois nucleos) — e
ambas se recuperaram sozinhas via reinicio automatico, sem perda de dado. Reforca que a instabilidade
do item 15b (hopping + BT) continua ativa e frequente; nenhuma mudanca de codigo foi feita por causa
disso nesta sessao.

## 19. Remocao do diagnostico temporario de SSID oculto (item 18) — RESOLVIDO

Investigacao de SSID oculto encerrada (decisao do usuario); removida toda a instrumentacao combinada
como temporaria no item 18. Removido: campo `diag_seen` e o bloco `DIAG_SEEN_*`/fila circular/
`diag_push_locked`/`diag_note_locked`/`wifi_sniffer_diag_pop_line` em `wifi_sniffer.h`/`.c`; o bloco
`#if CONFIG_WARDRIVER_BT_ENABLED` de drenagem pelo BT em `main.c`; o branch `DIAG ` em
`WardriverSyncService.kt` (app). Nenhuma outra parte do sistema dependia disso — registro de 71 bytes
e protocolo de sync inalterados. **Verificado:** build limpo do firmware e `host_tests/sniffer`
continuam passando; app compila e os testes JVM continuam passando.
