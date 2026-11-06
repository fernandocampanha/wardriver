# Formato dos dados e protocolo Bluetooth

Este documento descreve os dois "contratos" entre o firmware e o app Android. **Os dois lados precisam ser
alterados juntos:** o registro binário é decodificado por `WardriverRecordCodec.kt` e o protocolo é
implementado por `bt_spp_sync.c` (ESP32) e `WardriverSyncService.kt` (app). Não há número de versão no
registro: se o layout mudar, apague a partição de armazenamento do ESP32 (`esptool.py erase_region 0x190000 0x270000`)
para não misturar formatos.

## 1. Registro de captura (`wardriver_record_t`, 71 bytes)

Definido em [`firmware/components/wardriver_proto/include/wardriver_record.h`](../firmware/components/wardriver_proto/include/wardriver_record.h).
Tamanho fixo, *little-endian*, sem *padding* (`#pragma pack(1)`). Um registro é gravado por rede (BSSID) a cada
`CONFIG_WARDRIVER_LOG_REWRITE_INTERVAL_MS` (2 s por padrão), além de um registro imediato na primeira detecção
e quando o SSID de uma rede é revelado.

| Offset | Tam. | Campo | Tipo | Descrição |
|-------:|-----:|-------|------|-----------|
| 0  | 6  | `bssid` | `uint8[6]` | Endereço MAC do ponto de acesso |
| 6  | 33 | `ssid` | `char[33]` | Nome da rede, terminado em `\0` (vazio se oculta) |
| 39 | 1  | `flags` | `uint8` | Bits: 0 = SSID oculto, 1 = tem fix de GPS, 2 = WPS presente, 3 = WPS configurado, 4 = WPS bloqueado |
| 40 | 1  | `channel` | `uint8` | Canal Wi-Fi (o declarado no beacon, senão o do rádio) |
| 41 | 1  | `rssi` | `int8` | Sinal recebido, em dBm |
| 42 | 1  | `encryption` | `uint8` | 0 = OPEN, 1 = WEP, 2 = WPA, 3 = WPA2, 4 = WPA3 |
| 43 | 4  | `times_seen` | `uint32` | Beacons vistos desde que a rede entrou na tabela em RAM (recomeça a cada poda/reinício) |
| 47 | 2  | `wps_config_methods` | `uint16` | Máscara de métodos de configuração WPS (atributo 0x1008) |
| 49 | 4  | `lat_e7` | `int32` | Latitude × 10⁷ (válida só com o bit `HAS_FIX`) |
| 53 | 4  | `lon_e7` | `int32` | Longitude × 10⁷ (idem) |
| 57 | 2  | `alt_m` | `int16` | Altitude em metros (idem; 0 se a fonte for o celular) |
| 59 | 2  | `hdop_x100` | `uint16` | Módulo GPS: HDOP × 100. Celular: precisão em metros × 100 |
| 61 | 4  | `utc_hhmmss` | `uint32` | Hora UTC do fix do módulo GPS (`hhmmss` decimal); 0 se não houver |
| 65 | 1  | `gps_quality` | `uint8` | Qualidade do fix GGA (≥ 1 = **módulo GPS**); **0 com `HAS_FIX` = posição veio do celular** |
| 66 | 1  | `satellites` | `uint8` | Satélites usados no fix do módulo (0 se a fonte for o celular) |
| 67 | 4  | `uptime_s` | `uint32` | Segundos desde o boot do ESP32 |

Notas:

- `gps_quality` e `satellites` identificam a fonte da posição (módulo × celular); ver a seção
  "Fonte da posição" do README.
- Arquivos de sessão (`sess_NNNN.wdr`) são uma sequência de registros, sem cabeçalho nem delimitador. O leitor
  sabe onde cada registro começa só pelo *offset*. Um reset no meio de uma gravação pode deixar um registro final
  truncado; o CRC32 do protocolo abaixo detecta isso.
- O app valida faixas fisicamente plausíveis (canal 1–14, RSSI −100…0 dBm, `encryption` 0–4) e descarta o que
  estiver fora, para que um arquivo desalinhado não crie "redes fantasma".

## 2. Protocolo Bluetooth (SPP, Bluetooth Classic)

O ESP32 anuncia-se como **`WARDRIVER`** (RFCOMM, canal 1, Serial Port Profile). O app conecta ao dispositivo
**já pareado** com esse nome. O pareamento usa **PIN fixo `1234`** (`bt_spp_sync.c`, evento `PIN_REQ`).

O fluxo é misto texto + binário. Linhas de controle são ASCII (sem acentos, exceto o travessão do banner) terminadas em `\r\n`. O conteúdo das capturas é
binário de tamanho fixo. O receptor distingue os dois pelo enquadramento descrito abaixo.

### 2.1 ESP32 → celular

| Mensagem | Quando | Significado |
|----------|--------|-------------|
| `=== WARDRIVER — sincronizando <raiz> ===` | Ao conectar | Banner. O app o usa para saber que o stream está íntegro (sem ele em 15 s, reconecta). |
| `--- BEGIN <arquivo> bytes=<N> ---` | Backlog / `SYNC` | Anuncia um arquivo de sessão fechado e seu tamanho. Os próximos **N bytes brutos** são o conteúdo. |
| *(N bytes brutos)* | | Registros de 71 bytes, sem delimitador. |
| `--- CHECKSUM <arquivo> crc32=<hex8> bytes=<N> ---` | Fim do arquivo | CRC-32 (o mesmo de `java.util.zip.CRC32` / zlib) dos N bytes. |
| `OK removido <arquivo>` | Depois de um `ACK` válido | O arquivo foi apagado da memória do ESP32. |
| `ERRO <descrição>` | Falhas | Comando não reconhecido, checksum desconhecido, remoção que falhou, backlog cheio, falha ao rotacionar a sessão, sem *backend* de storage. |
| `=== FIM DO BACKLOG (n sessao(oes) pendente(s) de confirmacao) ===` | Depois do backlog inicial | Fim do envio dos arquivos antigos. |
| `--- SESSION <id> ---` | Antes do streaming e a cada `SYNC` | Sessão à qual os registros ao vivo seguintes pertencem. |
| `0x01` + 71 bytes | Streaming ao vivo | Marcador de registro ao vivo seguido de um registro. Nunca entra na conta de nenhum CRC. |
| `STATS t=… heap=… redes=… tabela=… sem_nome=… beacon=… probe=… tam0=… zeros=… rev_probe=… rev_beacon=… preservado=… falhas_alloc=… gps=… mod_fix=… mod_sats=… mod_view=… mod_trk=… mod_snr=… mod_hdop=… oled_up=… oled_ok=… oled_fail=… oled_rec=… oled_reinit=… assoc=… rev_assoc=… hops=… hopfail=… ch=…` | A cada ~30 s | Diagnóstico do firmware (ver a tabela abaixo). Pares `chave=valor`: o app ignora chaves que não conhece, então campos novos podem ser acrescentados ao fim. O app grava em `firmware_stats.log`. Descartado se houver transferência em curso. |

Campos do `STATS`:

| Chave | Significado |
|-------|-------------|
| `t` | Segundos desde o boot. **Um `t` que volta a um valor pequeno indica que a placa reiniciou.** |
| `heap` | Heap livre, em bytes |
| `redes`, `tabela`, `sem_nome` | Redes únicas da sessão (contador cumulativo), entradas na tabela de deduplicação em RAM, e quantas ainda sem nome |
| `beacon`, `probe` | Beacons e probe responses processados |
| `tam0`, `zeros` | SSIDs em branco: de tamanho 0 e de N bytes `0x00` |
| `rev_probe`, `rev_beacon`, `rev_assoc`, `preservado` | Nomes revelados por probe response, beacon e association request; quadros sem nome que **não** apagaram um nome já conhecido |
| `assoc` | Association/reassociation requests aceitos (usados só para descobrir o nome do AP; o MAC do cliente é zerado ainda no callback do rádio) |
| `hops`, `hopfail` | Trocas de canal do *channel hopping* e trocas recusadas pelo driver |
| `ch` | Quadros aceitos por canal: `1:n,2:n,…,13:n` |
| `gps`, `mod_*` | Fonte da posição em uso (`celular`/`modulo`/`nenhuma`) e estado do módulo GPS |
| `oled_up`, `oled_ok`, `oled_fail`, `oled_rec`, `oled_reinit` | Saúde do display: inicializado, quadros enviados, falhas de I2C, recuperações do barramento, reinicializações a frio |
| `falhas_alloc` | Redes descartadas por falta de heap na tabela |

### 2.2 Celular → ESP32

| Linha | Efeito |
|-------|--------|
| `ACK <crc32_hex>` | Confirma o arquivo cujo CRC bate; **só então** o ESP32 o apaga. |
| `ACK ALL` | Confirma todos os pendentes. |
| `LOC <lat> <lon> <precisão_m>` | Posição do celular (a cada ~2 s enquanto conectado). Vale por `CONFIG_WARDRIVER_PHONE_GPS_STALE_MS` (10 s). |
| `SYNC` | Fecha a sessão ativa, oferece-a como backlog e abre uma sessão nova, sem parar a captura. |

### 2.3 Garantias e recuperação de falhas

- **Nada é apagado sem confirmação.** O app só envia `ACK` depois de o CRC bater **e** de gravar os registros no
  banco; sem `ACK` (queda de conexão, CRC divergente), o arquivo continua no ESP32 e é reoferecido na
  próxima conexão.
- **Envio interrompido:** se um `write()` falhar no meio de um arquivo, o firmware **não** manda o `CHECKSUM`
  (o CRC de um arquivo inteiro contra dados incompletos seria enganoso) e derruba a conexão de propósito; o app
  reconecta sozinho e o backlog recomeça do zero.
- **App:** fecha o socket se um arquivo ficar 15 s sem receber bytes ou se o banner não chegar em 15 s; em CRC
  divergente reconecta (até 3 vezes por arquivo). Os registros de um arquivo de backlog só entram no banco
  depois de o CRC bater.
- **Memória cheia:** com a partição de armazenamento cheia o firmware continua sincronizando (o backlog e o
  `SYNC` funcionam) e reabre a sessão de gravação sozinho quando os `ACK` liberarem espaço.
- **Crash de coexistência Wi-Fi + Bluetooth:** bug conhecido do ESP-IDF (ver `docs/BACKLOG.md`, item 1). O
  ESP32 reinicia sozinho em segundos; os dados já gravados não se perdem.

## 3. Envio à nuvem (app → Wardriver Web)

Depois de cada sincronização com o ESP32 (ou quando o usuário aperta *Sincronizar e enviar*), o app envia à nuvem as
redes vistas desde a última entrega. Implementação: `CloudUploader.kt` / `IngestJson.kt` (app) e
`web/backend/app/schemas.py` (servidor); **os dois lados precisam mudar juntos**. Visão geral em [`web/README.md`](../web/README.md).

`POST /api/v1/ingest` com `Authorization: Bearer <token>` e `Content-Type: application/json`:

```json
{"device": "android-1a2b3c4d",
 "networks": [{"bssid": "70:AA:BB:00:00:01", "ssid": "Nome", "hidden": false, "channel": 6, "rssi": -55,
               "enc": "WPA2", "seen": 12, "wps": false, "lat": -10.1, "lon": -50.2,
               "session": "sess_0007", "last": 1760000000000}]}
```

| Campo | Regra no servidor |
|-------|-------------------|
| `bssid` | `AA:BB:CC:DD:EE:FF` (normalizado para maiúsculas) |
| `ssid` | até 64 caracteres, UTF-8 válido; `\0` removido; vazio se `hidden` |
| `channel` / `rssi` | 0–196 / −127…0 dBm |
| `enc` | `OPEN`, `WEP`, `WPA`, `WPA2`, `WPA3` ou `UNKNOWN` |
| `seen` | 1…2·10⁹ (vezes vista) |
| `lat` / `lon` | −90…90 / −180…180, só valem juntos; `(0,0)` significa "sem fix" |
| `last` | instante da captura, em ms desde a época (0…ano 3000) |
| lote | 1 a 5.000 redes; corpo até 8 MB (`413` acima) |

O servidor junta cada rede com a que já tem pela mesma regra do app (`NetworkMerge.mergeObservations`): um nome já conhecido
nunca é trocado por "oculta"; se os dois têm nome e diferem, vale o de `last` mais recente; a posição e o RSSI ficam os da
captura mais forte; `seen` é o maior dos dois. **Reenviar um lote não muda nada**, então o app pode repetir com segurança.
Respostas: `200` com o resumo (`added`, `updated`, `names_filled`, `total`); `401` token ausente/inválido (o app para na hora);
`413`/`422` lote grande demais ou com registro inválido (o app divide o lote ao meio até isolar e pular o registro ruim);
`5xx`/`429` (o app repete com espera e, se persistir, retoma no próximo ciclo). `GET /api/v1/ping` valida endereço e token sem
enviar dado.

O token só **envia**: não abre o painel nem lê dados. O app aceita apenas `https://` (e `http://` só para `localhost`,
`127.0.0.1` e `10.0.2.2`), nunca segue redirecionamento e guarda o token cifrado pelo Keystore do Android.
