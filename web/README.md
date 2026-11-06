# Wardriver Web

Site de consulta do projeto Wardriver. O **app Android** sincroniza com o ESP32 e envia as redes a um **backend Python (FastAPI) com MySQL**; o **frontend em React** mostra os dados: números principais, mapa com filtros, lista e análise. A nuvem não tem acesso ao hardware: o ESP32 continua falando só com o celular por Bluetooth.

| Camada | Tecnologia |
|---|---|
| Frontend | React 19 + TypeScript + Vite, Leaflet (tiles OSM), IBM Plex hospedada no próprio site (sem CDN) |
| Backend | Python 3.12, FastAPI + Uvicorn, SQLAlchemy Core, PyMySQL |
| Banco | MySQL 8.4 (utf8mb4, InnoDB); SQLite só em desenvolvimento |
| Empacotamento | Dockerfile multi-stage + `docker-compose.yml` |

## Páginas

| Página | Acesso | Conteúdo |
|---|---|---|
| Sobre o projeto | aberta | o que é, o que **é** e **não é** coletado, limites dos dados, autoria |
| Entrar | aberta | senha do painel + texto do termo; o login libera as páginas de dados |
| Painel | após login | régua de números (clicáveis: aplicam o filtro), atalhos por cidade, mapa e painel de filtros |
| Redes | após login | tabela paginada e ordenável, com os mesmos filtros; ficha de cada rede |
| Análise | após login | criptografia, canais, sinal, fabricantes, cidades, origem das ocultas, nomes mais frequentes (respeitam os filtros) |

Os filtros ficam na URL (`#/redes?enc=OPEN,WEP&city=...`): uma visão se compartilha por link e o botão "voltar" funciona.

**Filtros:** busca (nome, BSSID com ou sem `:`, "oculta"), criptografia, canal, sinal mínimo/máximo, WPS, oculta/com nome, oculta de interface virtual, nome de fábrica, com posição, vezes vista, período da última captura, cidade, fabricante do roteador, área visível do mapa.

**Mapa:** pontos (cor = criptografia, contorno vazado = SSID oculto) quando cabem; acima de 8.000, células agrupadas calculadas no servidor; modo "Calor"; atalhos por cidade; "Enquadrar"; minha posição.

## Acesso e privacidade

- **Login por senha única** (um só segredo compartilhado, não por usuário): o visitante informa a senha do painel e aceita o termo; o servidor verifica a senha (hash scrypt, nunca em texto claro) e grava uma sessão (só o hash do token) com cookie `HttpOnly; SameSite=Strict; Secure` **sem validade** (some ao fechar o navegador). Sem a senha correta, `/api/networks`, `/api/map`, `/api/stats` e `/api/facets` respondem `403`/`401`, mesmo chamados direto — nada do site é consultável sem autenticar.
- Senha definida em `WARDRIVER_WEB_PANEL_PASSWORD_HASH` (gere com `python -m app.hashpw --password`); sem essa variável o servidor não sobe (falha fechada, igual ao banco).
- Mudou o texto do termo? Altere `TERM_VERSION` em `backend/app/main.py` (e o texto em `frontend/src/pages/Term.tsx`): todos precisam logar de novo.
- **Dados exibidos sem anonimização:** SSID, BSSID e posição exatos. SSID residencial costuma ter nome de pessoa e a posição de uma rede residencial aponta onde alguém mora (LGPD). O termo e a página Sobre avisam disso, mas **não impedem a raspagem**; há limite de requisições por IP (`WARDRIVER_WEB_RATE_PER_MINUTE`).
- **Sem exportação, sem importação, sem remoção pela interface.** Os dados entram só pelo app (`POST /api/v1/ingest`, com token). Corrigir ou apagar uma rede é SQL direto no MySQL.
- O token do app **só envia**, nunca lê. Só o SHA-256 dele fica no servidor.
- O navegador pede os *tiles* ao OpenStreetMap, que vê o IP de quem consulta e as regiões que olha (nunca os dados das redes). Sem cookies de rastreamento nem análise.

O que **quem opera** decide e este projeto não resolve: por quanto tempo a base fica no ar e quando é apagada.

## Campos derivados

Calculados na gravação (`backend/app/enrich.py`) e guardados em colunas, para o filtro rodar no banco:

- **Fabricante:** prefixo (OUI) do BSSID, tabela do IEEE em `backend/app/data/oui.tsv` (~40 mil entradas; atualize baixando `https://standards-oui.ieee.org/oui/oui.csv`). Endereços virtuais ou aleatórios podem ficar sem fabricante.
- **Cidade:** centro urbano mais próximo (até 20 km) da lista `backend/app/data/cities.tsv` (capitais, Catanduva e algumas cidades de SP). **Não é limite municipal**; para cobrir mais municípios, acrescente linhas (nome, UF, lat, lon) ao arquivo e reinicie: as redes já gravadas com cidade vazia **não** são recalculadas sozinhas (zere a coluna com `UPDATE networks SET city = NULL` e o servidor recalcula ao subir).
- Também: bit de BSSID "administrado localmente" e "nome de fábrica".

## Arquitetura

```
 ESP32 --BT--> App Android --HTTPS, token--> proxy (Caddy/nginx) --> API FastAPI --> MySQL
                              POST /api/v1/ingest                      |
                                                       navegador --HTTPS--> React (servido pela API)
```

| Rota | Acesso | O que faz |
|---|---|---|
| `GET /`, `/assets/*` | público | build do React (sem dados) |
| `GET /api/health`, `/api/me` | público | saúde do banco; se já autenticou e a versão atual do termo |
| `POST /api/term/accept` | público + senha + cabeçalho anti-CSRF | login (senha do painel + aceite do termo) |
| `POST /api/term/revoke` | sessão + cabeçalho anti-CSRF | logout |
| `GET /api/networks` | sessão (login) | página de redes (filtros, `sort`, `order`, `limit`≤200, `offset`) |
| `GET /api/map` | sessão (login) | pontos ou células agrupadas na área (`bbox`, `zoom`, `mode=auto\|cells\|points`) e limites do resultado |
| `GET /api/stats`, `/api/facets` | sessão (login) | estatísticas do filtro; opções de cidade e fabricante |
| `GET /api/v1/ping`, `POST /api/v1/ingest` | **só Bearer** | o app valida o token e envia lotes (até 5.000 redes) |

Parâmetros de filtro (iguais em todas as rotas de leitura): `q`, `enc` (vírgulas), `channel`, `rssi_min`, `rssi_max`, `wps`, `hidden=only|no`, `default_name`, `local_admin`, `has_pos`, `seen_min`, `since`, `until` (ms), `city`, `vendor` (separados por `|`), `bbox=sul,oeste,norte,leste`. Tudo vai como parâmetro SQL; o `LIKE` escapa `%` e `_`.

O servidor junta cada rede pela **mesma regra do app** (`NetworkMerge.mergeObservations`): um nome já conhecido nunca é trocado por "oculta", a posição e o RSSI ficam os da captura mais forte, `visto N vezes` é o maior dos dois. Reenviar o mesmo lote não muda nada.

## Subir localmente

Sem Docker (SQLite), para ver o site:

```bash
cd backend && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements-dev.txt
export WARDRIVER_DATABASE_URL='sqlite:///./dev.db' WARDRIVER_WEB_INSECURE_COOKIE=1
python -m app.hashpw            # imprime o TOKEN do app e WARDRIVER_WEB_UPLOAD_TOKEN_SHA256 (exporte-o)
python -m app                   # http://127.0.0.1:8080

cd ../frontend && npm ci && npm run dev    # http://localhost:5173, com proxy /api -> :8080
npm run build                              # gera dist/ (a API serve frontend_dist/ ou WARDRIVER_WEB_STATIC)
```

No app, em **Nuvem…**, informe o endereço e o token; use **Reenviar tudo** para subir o que já está no celular.

## Subir com Docker (depois)

```bash
cd web
cp .env.example .env && chmod 600 .env
openssl rand -hex 24               # rode duas vezes: WARDRIVER_DB_PASSWORD e MYSQL_ROOT_PASSWORD
docker build -t wardriver-web . && docker run --rm wardriver-web python -m app.hashpw   # -> TOKEN (guarde) + WARDRIVER_WEB_UPLOAD_TOKEN_SHA256
# edite o .env; para testar sem HTTPS: WARDRIVER_WEB_INSECURE_COOKIE=1
docker compose up -d --build
curl -s localhost:8080/api/health
```

- O **MySQL não publica porta**: só a API o alcança. A API escuta só em `127.0.0.1` do host.
- **HTTPS (obrigatório fora do teste local):** `DOMAIN=seu.dominio` e `WARDRIVER_WEB_TRUST_PROXY=1` no `.env`, e `docker compose --profile https up -d` (Caddy com certificado automático), ou nginx ([`deploy/nginx.conf.example`](deploy/nginx.conf.example)). Com `TRUST_PROXY`, o limite de requisições usa o IP que o *seu* proxy viu.
- **Sem Docker:** [`deploy/wardriver-web.service`](deploy/wardriver-web.service) (systemd endurecido) com um MySQL seu.
- **Migração mínima:** ao subir, o servidor cria as tabelas que faltam e **acrescenta** as colunas novas a tabelas antigas (e calcula os derivados das redes já gravadas). Não há Alembic.

## Variáveis de ambiente

Modelo em [`.env.example`](.env.example).

| Variável | Padrão | Função |
|---|---|---|
| `WARDRIVER_DB_HOST` `_USER` `_PASSWORD` `_NAME` `_PORT` | — | conexão MySQL por partes |
| `WARDRIVER_DATABASE_URL` | — | alternativa: URL SQLAlchemy completa (vence as partes) |
| `WARDRIVER_WEB_PANEL_PASSWORD_HASH` | — (obrigatória) | senha do painel; gere com `python -m app.hashpw --password` |
| `WARDRIVER_WEB_UPLOAD_TOKEN_SHA256` | vazio | habilita o token do app (só o SHA-256 fica no servidor) |
| `WARDRIVER_WEB_TRUST_PROXY` | `0` | `1` só atrás de proxy que **sobrescreve** `X-Forwarded-For` |
| `WARDRIVER_WEB_INSECURE_COOKIE` | `0` | `1` só em `http://localhost` de teste |
| `WARDRIVER_WEB_MAX_INGEST_MB` | `8` | teto de corpo do lote do app (413 acima) |
| `WARDRIVER_WEB_SESSION_HOURS` | `12` | validade, no servidor, do aceite do termo |
| `WARDRIVER_WEB_RATE_PER_MINUTE` / `_RATE_MAP_PER_MINUTE` | `240` / `90` | requisições por IP e por minuto (geral / mapa) |

## Modelo de segurança

- **Login por senha, verificado no servidor:** rotas de leitura exigem sessão válida (senha correta + versão atual do termo); `403` sem sessão, `401` com senha errada. Hash scrypt (lento de propósito, resiste a força bruta) — nunca a senha em texto claro. Balde de tentativas de login mais apertado (8/min por IP) que o das outras rotas. O token do app não abre a leitura, e o cookie não abre o envio.
- **CSRF:** `POST` com cookie exige `X-Requested-With: wardriver-web` (o servidor nunca autoriza CORS) e, se houver `Origin`, ele tem de ser o do próprio servidor.
- **XSS:** o SSID vem do ar e pode ser `<script>…`. React escapa todo texto; tooltips do Leaflet recebem *nós* de DOM. CSP `script-src 'self'; style-src 'self'; font-src 'self'`, sem `unsafe-inline`.
- **Corpo:** teto por rota, valendo também para corpo sem `Content-Length`. Envio do app validado por schema estrito.
- **Sem `/docs` público**, sem cabeçalho `Server`, `X-Robots-Tag: noindex`, HSTS atrás de proxy HTTPS confiável.
- **App:** só HTTPS, nunca segue redirecionamento, token cifrado com Keystore e fora do backup.

## Verificação

Foram verificados, em 2026-10-10, com **dados fictícios** (7.700 redes geradas, SQLite) e Chrome real: termo, bloqueio sem aceite, painel, filtros pela URL, mapa em pontos e agrupado, lista, análise e a largura de celular; sem erro no console. Os testes do backend (`python -m pytest -q`, 113) continuam no repositório, mas **não foram ampliados** para o frontend (decisão do projeto).

**Não testado:** MySQL real com as colunas novas (a migração mínima e a agregação por célula foram exercitadas só em SQLite), carga com dezenas de milhares de redes, HTTPS com domínio real, o app Android contra esta versão (o contrato de envio não mudou), leitores de tela e Safari/Firefox.

## Limitações

- A nuvem guarda a rede **consolidada** (uma linha por BSSID), não cada captura: não dá para reconstruir trajetos nem a data da primeira captura.
- Só 2,4 GHz (o ESP32 não escuta 5 GHz): não há filtro de banda.
- Cidade por centro mais próximo, não por município; fabricante só quando o OUI é conhecido.
- Sem remoção de dados pela interface nem canal de pedido de retirada (decisão desta versão).
- O limite de requisições fica em memória (reiniciar zera) e vale por processo.

## Estrutura

```
web/
├── backend/
│   ├── app/     main.py (rotas, termo, CSRF), query.py (filtros), db.py (SQLAlchemy), enrich.py (fabricante/cidade),
│   │            schemas.py (envio do app), config.py, auth.py, middleware.py, merge.py, analysis.py, hashpw.py
│   │   └ data/  oui.tsv (IEEE), cities.tsv
│   ├── tests/   pytest (SQLite)
│   └── requirements.txt (pins) · requirements-dev.txt
├── frontend/    React + Vite + TS: src/ (App, router, api, filters, mapController, pages/, components/, charts/)
├── deploy/      nginx, Caddy, systemd
└── Dockerfile · docker-compose.yml · .env.example
```
