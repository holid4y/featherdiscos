# FeatherDiscos — handoff / progress log

Task (Matheus, 2026-10-15): replace URLCustomDiscs with **Jukebox Extended Reborn (JEXT)**,
let players create custom discs **in-game, easily**, always working with whatever audio
they want, **max 5 discs per player per week**. Keep everything clean so another AI can
continue if the session dies. Review before finishing.

## Why a companion plugin
JEXT has no in-game "create disc" command — discs come from `jext.json` inside the
resource pack it hosts (`plugins/JukeboxExtendedReborn/resource-pack.zip`, served on
:9871). Its web generator just builds that zip (source: JEXT repo, branch `gh-pages`,
`src/lib/exporter/pluginExporter.ts`). FeatherDiscos reproduces that builder server-side:

`/disco <link> [nome]` → yt-dlp downloads → ffmpeg → mono OGG Vorbis → rebuild
`resource-pack.zip` (+ Bedrock `.mcpack` into Geyser) → `jextreload` → give disc →
push the new pack to online players.

## Layout
- Source + build: `~/Projects/featherdiscos` (`./build.sh` → `build/FeatherDiscos.jar`)
- Tools: `/opt/featherdiscos/bin/{yt-dlp,deno}` (owned by `minecraft`, so the plugin
  can `yt-dlp -U` itself). Not in the SMB-writable server dir on purpose.
- Plugin data: `/srv/games/minecraft/plugins/FeatherDiscos/` — `discos.yml` (disc db),
  `cotas.yml` (creation timestamps per player), `audio/<ns>.ogg` (source of truth).

## Decisions (with Matheus's mid-task requests)
- **Resource pack MUST load reliably** ("PRECISA carregar, sem bugs"). So FeatherDiscos hosts
  the pack itself (own multi-threaded HTTP server, port 9880, content-addressed
  `/pack/<sha1>.zip`, so the hash a client gets always matches the bytes it downloads),
  delivers it in the **configuration phase** (`AsyncPlayerConnectionConfigureEvent`, i.e. on
  the loading screen, like server.properties packs) and marks it **required** (a client that
  once clicked "No" still gets prompted). New discs are pushed live with the same pack UUID.
  Pack URL host = the host the player connected with (same route), fallback 100.105.145.99.
- JEXT keeps `enable-resource-pack-host: true` only because that is how it reads
  `resource-pack.zip` from its own folder; its :9871 server is unused. Its web API is OFF.
- **Branding:** pack.png / Bedrock pack_icon = live `server-icon.png` (gold Eye of Horus);
  disc textures use the brand gold. The JEXT web platform is not used at all.
- **Do NOT restart the live server** (people were playing on 2026-10-15 evening). Deploy
  happens at a restart Matheus chooses (auto-restart runs 05:30→06:00 and 17:30→18:00).
- Sandbox test = fresh flat world, only packetevents+JEXT+FeatherDiscos, heap 1G, cgroup
  MemoryMax 1.6G — NOT a copy of the 22 GB world (RAM ~3.8G free; box crashes under memory load).

## Status
- [x] Research JEXT (2.2.7 supports 26.2; needs packetevents 2.14.0)
- [x] Old URLCustomDiscs pack URL is 404 (remote API); the 6 old songs' audio is lost
- [x] Install yt-dlp 2026.08.19 + deno 2.9.7 in /opt/featherdiscos/bin (sha256 verified);
      YouTube download as user minecraft works (2.7 s), ffmpeg → mono Vorbis works
- [x] Write + build FeatherDiscos (release SHA256 028f3896…)
- [x] Sandbox test (fresh world in /srv/games/featherdiscos-test — see 10:14 entry)
- [x] Deploy: backup, swap URLCustomDiscs → JEXT + packetevents + FeatherDiscos (2026-10-16 10:32)
- [x] Verify live, update memory, final review (10:45 entry)
- [ ] First real player join + `/disco` on production (graphical client never tested)

## Retomada Codex 2026-10-16
Leia também docs/RESUMO-RETOMADA.md: corrige decisões anteriores de rede/cota.
Não houve implantação nem reinício. Dependências e fontes agora preservadas em vendor/.

### 10:14 — implementação compila; sandbox iniciado
- Implementados FeatherDiscos, AudioProcessor, PackBuilder, PackHttpServer e BedrockBridge.
- Recurso correto confirmado no version.json do Paper: resource_major=88, resource_minor=0.
  O 107.1 do gerador JEXT é DATA PACK e estava incorreto para resource pack.
- Banco único discos.yml inclui ledger de cotas, mesma gravação atômica.
- HTTP em loopback, upload com token aleatório 192 bits por jogador, validade 10min.
- Downloads passam por proxy com DNS fixado e bloqueio de redes privadas; conversor
  aceita somente protocolos file/pipe. Binários não se atualizam durante boot.
- Sandbox /srv/games/featherdiscos-test; unit featherdiscos-test.service; screen
  featherdiscos-test como minecraft; Java 127.0.0.1:25575; HTTP 19880; JEXT 19871.
  MemoryMax=1600M, heap 1G, CPUQuota=100%, Nice=10. Mundo novo, produção intacta.
- Próximo: testes de integração, revisão de falhas, HTTPS/nginx, instalação para
  próximo reinício autorizado (sem disparar restart agora).

### 10:25 — autorização e revisão
Matheus autorizou explicitamente: "por fim instale no servidor e o reinicie".
A proibição anterior de reiniciar foi substituída por essa autorização.
Também autorizou criar túnel Playit se necessário; não necessário: HTTPS existente
respondeu 200 e serviu todos os pacotes com SHA-1 correto.
Testes concluídos: docs/integration-final.log e docs/review-integration.log.
Incluem 5 criações e bloqueio da sexta (também OP), token de upload uso único,
áudio inválido sem cota, recuperação de inventário cheio, e pacote de som JEXT
minecraft:fd_11 após interação real com jukebox pelo protocolo.
Teste usa cliente de protocolo 26.1 via ViaVersion/ViaBackwards no sandbox;
não é teste visual/acústico de cliente gráfico.
Pack agora declara compatibilidade 55..88 com campos legados obrigatórios para
clientes antigos, conforme Mojang 1.21.9; 26.2 é 88.0.
Download YouTube jNQXAC9IVRw funcionou pelo proxy; corrigido RLIMIT_FSIZE que
matava o bootstrap PyInstaller do yt-dlp com limite 32MB. Limite por mídia32MB,
limite de processo128MB, monitor de tamanho do diretório e timeout continuam.
Release preparado em build/release; ops/deploy.sh --restart cria backup de plugin
antigo/configs, para produção com helper de save, instala e liga. AINDA NÃO EXECUTADO.

### 10:28 — teste de desconexão encontrou falha, implantação ainda suspensa
Download/convert/save offline funcionou. Entrega ao reconectar ficou pendente:
AsyncPlayerConnectionConfigureEvent retornava antes de ACK do resource pack,
Paper trocava listener config->play e perdia callbacks. Corrigido: evento async
aguarda SUCCESSFULLY_LOADED (até300s) antes de liberar entrada no mundo; falha
ou recusa desconecta com mensagem. Validar no sandbox antes de instalar.
Build recompilado e copiado para sandbox, que foi parado para atualizar.

### 10:30 — revisão aprovada para implantação
Correção do ACK validada: docs/offline-integration.log registra download YouTube
como minecraft, conversão, criação enquanto offline e entrega automática após
reconexão. Teste adicional recusou o pacote obrigatório e foi desconectado antes
do login. Não houve modificação da produção ainda.
Release final SHA256: 028f389649477f9cf477067fd2f73052d4a688638d8e8beb1daf7f24793ebd2b.
Sandbox recebeu stop pelo console; próximo passo: aguardar inactive e executar
sudo -n ./ops/deploy.sh --restart, então verificar produção/HTTPS/Geyser/serviços.

### 10:31 — implantação EM ANDAMENTO
Comando ops/deploy.sh --restart executado (sessão exec51895).
Backup: /srv/games/minecraft-plugin-backups/featherdiscos-20261016-103043.
Produção salvou todos os mundos às10:30:49; systemd ainda aguardava ExecStop.
Script instala release, arquiva URLCustomDiscs e customdiscs.sk, troca proxy
/discos/ de19880 para9880 e inicia minecraft.service. Há rollback automático
se o script falhar. Não disparar outro restart enquanto esse comando existir.
Próximo: verificar log "FeatherDiscos Pronto" + "Done", HTTPS pack/hash,
Geyser, serviços de acesso; depois registrar conclusão aqui.

### 10:45 — CONCLUÍDO (verificado pelo Claude Code)
- deploy.sh terminou sem rollback: minecraft.service active desde 10:32:17, `Done (64s)`,
  `[FeatherDiscos] Pronto: 0 discos. Pacote 85ab67cc…`. Nenhum ERROR novo (o "unsupported
  server version" do Essentials já existia em 2026-10-15). Warnings iguais aos de antes.
- URLCustomDiscs jar/dados e Skript customdiscs.sk removidos de produção; cópias em
  /srv/games/minecraft-plugin-backups/featherdiscos-20261016-103043 (root, 0700).
  Reverter: `sudo -n ./ops/rollback.sh --restart`.
- nginx /discos/ → 127.0.0.1:9880 (FeatherDiscos escuta só em loopback). Via Funnel:
  /discos/ 200; /discos/pack/<sha1>.zip 200 e o SHA-1 do download = hash anunciado.
- JEXT :9871 escuta em *, mas ufw nega tudo fora de tailscale0 e a API web está off;
  só serve o zip local. Sem exposição pública.
- Geyser iniciou (UDP 19132); .mcpack em plugins/FeatherDiscos/packs/, injetado por
  SessionLoadResourcePacksEvent (pasta packs/ do Geyser continua vazia: é o esperado).
- Acesso intacto: tailscaled, sshd, rustdesk, playit, nginx, smb, healthcheck timer.
- Sandbox parado (unit transitória sumiu). /srv/games/featherdiscos-test (497 MB) foi
  MANTIDO para testar atualizações futuras do plugin; pode apagar se precisar do espaço.
- Pendente: nenhum jogador entrou desde o restart. A produção é online-mode, então o bot
  de teste não entra; confirmar com um cliente real (pacote na tela de carregamento,
  `/disco <link>`, tocar na jukebox, e um jogador Bedrock).

## NOVOS REQUISITOS — 2026-10-16 10:32 (prioridade da continuação)
Matheus rejeitou textura quadrada da logo no item. Implementar textura de disco
Minecraft padrão, com cores escolhidas pelo jogador na página do link de criação.
Manter logo somente na plataforma e ícone do pacote. Relatou música Minecraft
sobreposta ao disco: investigar música ambiente categoria MUSIC e faixa vanilla11
categoria RECORDS; impedir sobreposição enquanto o disco toca, preservar música
normal ao terminar. Melhorar validação/antiabuso (upload real, duração, limites,
fila, repetição, nomes, SSRF), revisar e testar antes do deploy revisado.
A implantação v1.0 já estava em andamento quando chegou esse pedido; não deixar
produção parada durante desenvolvimento. Conferir estado do deploy/serviço.
Não considerar tarefa concluída até esses novos pedidos e instalação final.

### 11:35 — v1.1 em desenvolvimento (novos requisitos)
Produção v1.0 está ativa desde10:32; usuário criou fd_1 (Just Overture - Aloboi),
NÃO apagar nem resetar dados/cota. Autorização de reinício final permanece.
Implementados (ainda não implantados): DiscColors (12cores; template vanilla13
extraído do cliente26.2 Mojang com SHA1 verificado), cor no DiscRecord/discos.yml
(defaultouro na migração), texturas separadas porcor e escolha/preview no upload.
JukeboxSoundGuard cancela worldevent1010 de jukeboxcustom e envia stop MUSIC+11
só ao redor de custom ativo; termina quando disco acaba/é removido.
Validação ffprobe de formatos/canais/taxa, decode estrito e normalização loudnorm;
10tentativas/10min porUUID; reserva de fila/cota ANTES de ler upload.
Build passou antes da última alteração de reserva; recompilação+start sandbox
execsession78672. Sandbox config URL corrigida para http://127.0.0.1:19880;
NÃO alterar nginx produção para testar. tests/client/review-v1.1.cjs usa loopback.
Próximo: confirmar build/start; testes de cor, som vanilla/ambiente/finisher e
antiabuso; preparar update v1.1 PRESERVANDO plugins/FeatherDiscos/* de produção;
backup v1.0 e dados, stop/restart autorizado, verificar logs/HTTPS e atualizar docs.

### 11:38 — v1.1 testes passaram, pronta para instalar
Release SHA256 bf8d8f3d8fb70391956e4083ff3b579e0866e58e616d83f00a84d8fd0c9ef59b.
CoreTest: 12 cores diferentes, contorno/alpha16x16 igual ao disco vanilla, nomes
maliciosos recusados, 11ª tentativa bloqueada; quota/restart/pack/audio/SSRF passam.
review-v1.1.log:5criações azuis, inventário cheio, OP limitado, hashpack, somcustom;
12pacotes stop MUSIC durante5s, nenhum worldevent1010 nativo, silêncio de MUSIC
cessa após disco terminar. Modelo fd_18 aponta explicitamente para disco_azul.
Produção ainda v1.0; próximo: parar sandbox, backup jar+dados produção, instalar
SOMENTE FeatherDiscos.jar v1.1 (não copiar banco/config vazios), restart autorizado.

### 11:39 — atualização v1.1 EM ANDAMENTO
Sandbox parado. Backup v1.0+dados+JEXT feito em
/srv/games/minecraft-plugin-backups/featherdiscos-1.1-20261016-113842
(ops/v1.1-backup.txt). SHA256 fd_1.ogg antes da troca:
5bc892c8c9bb85242aff9ccb2a810b586a46b52f7e1386e9be2e72f20e25641c.
Executado sudo systemctl stop minecraft.service (session84290); aguardando
minecraft-stop, que espera até90s por sockets screen antigos 'Remote or dead'.
NÃO deixar parado: ao terminar, instalar build/FeatherDiscos.jar em plugins/
com owner minecraft mode644 e systemctl start minecraft. Não substituir dados.
Depois validar Pronto:1disco, hash HTTPS, /colors e /disc/azul.png, logs novos,
SHA do áudio e cota existentes preservados; atualizar este arquivo com conclusão.
