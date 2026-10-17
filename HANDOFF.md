# FeatherDiscos — estado atual / continuidade

Atualizado: 2026-10-17 19:10, Vanaheim (America/Sao_Paulo).

## 1.2.0 — preparado, entra no próximo reinício

Pedido do Matheus em 17/10: pegar de volta discos já criados, links do Spotify/YouTube
e envio de link pela página, sem limite para admins e um /disco apagar que mostre
qual disco é.

- `/disco pegar <nº>` / `/disco receber <nº>`, `/disco meus` e `/disco lista` com botões.
- `/disco apagar`: disco na mão ou lista, confirmação por clique.
- Spotify → embed público → busca no YouTube por duração. Página aceita link (POST /link).
- `featherdiscos.ilimitado` (filho de `featherdiscos.admin`, padrão OP): sem cota semanal
  nem limite de tentativas.
- `main()` desembrulha ExecutionException: a página mostra o motivo real do erro.
- Títulos longos cortam em palavra inteira com "…"; mensagens do download.py não são
  mais cortadas em 64 caracteres.
- Testes: CoreTest PASS; sandbox (Paper 26.2, porta 25575) 31/31 com bots, incluindo
  Spotify, YouTube pela página, álbum recusado, pegar/apagar e permissões.
- Jar em `/srv/games/minecraft/plugins/update/FeatherDiscos.jar` (SHA256 d4571b5b…):
  o Paper troca o jar sozinho no próximo boot. Nada de dados foi alterado.
- Backup do jar 1.1.0 e dos dados: `/srv/games/minecraft-plugin-backups/2026-10-17-pre-origins1.4.2-discos1.2.0`.
  Para voltar: parar o servidor, copiar o FeatherDiscos.jar desse backup para plugins/.
- Código no GitHub: UnusualHatter/featherdiscos (privado). `vendor/` não vai para o git:
  baixar JEXT 2.2.7 e packetevents 2.14.0 do Modrinth (metadados em vendor/*.json).

---

## Estado anterior (1.1.0, 2026-10-16)

## Concluído e em produção

- Servidor /srv/games/minecraft: Paper26.2, Java25, minecraft.service ativo.
- JEXT Reborn2.2.7 + PacketEvents2.14.0 + FeatherDiscos **1.1.0** instalados.
- Último reinício autorizado por Matheus; servidor pronto às11:41:25,
  FeatherDiscos pronto às11:41:27 com **1 disco existente preservado**.
- SHA256 plugin: bf8d8f3d8fb70391956e4083ff3b579e0866e58e616d83f00a84d8fd0c9ef59b.
- Resource pack atual SHA1: 0eeaa218676ad2edc985f1c4bfb452ef562d7bd7.
- HTTPS: https://cachy.tailc71a24.ts.net:8443/discos/ → nginx →127.0.0.1:9880.
- Sandbox /srv/games/featherdiscos-test está **desligado**. Portas25575/19880/19871.
- Desktop continua desligado; SSH/Tailscale/RustDesk/nginx ativos. Nenhuma alteração
  de firewall, rede, pacotes pacman ou acesso remoto nesta atualização.

## O que foi feito na1.1 (último pedido do usuário)

- Item deixou de ser um quadrado com logo. Usa textura vanilla music_disc_13,
  mantendo alpha/contorno/sulcos e recolorindo o miolo em12opções.
- /disco enviar abre página FeatherFlux com escolha de cor e prévia verdadeira.
  A mesma função gera PNG para página e pack. Logo mantida na página/ícone do pack.
- Disco existente migra visualmente para dourado sem mudar ID, áudio ou cota.
- JukeboxSoundGuard bloqueia worldevent1010 nativo na jukebox personalizada;
  interrompe categoria MUSIC e faixa11 enquanto o disco toca num raio64.
  Para de silenciar ao terminar/ejetar/remover jukebox/descarregar chunk.
- 5criações/semana porUUID, inclusive OP. Falhas não consomem cota.
- Mais validação:10tentativas/10min, reserva antes de receber upload, título sem
  formatação/invisíveis, formato real/canais/taxa/duração, decode estrito,
  normalização -16LUFS/pico-1,5dB. Upload32MB, áudio3–480s, pack180MB.
- Proxy continua bloqueando DNS/IP privados e FFmpeg só usa file/pipe.

## Dados que não podem ser perdidos

Produção plugins/FeatherDiscos/discos.yml: catálogo+cota+entregas em gravação atômica.
Áudio original: audio/fd_1.ogg, Just Overture - Aloboi, Holid4y_Bird,101s.
SHA256 preservado:5bc892c8c9bb85242aff9ccb2a810b586a46b52f7e1386e9be2e72f20e25641c.
Cota existente:1criação; banco/áudio conferidos antes e depois do reinício.
NUNCA copiar build/release/plugins/FeatherDiscos sobre dados existentes ao atualizar.
Atualizações: parar com systemctl, substituir SÓ o JAR, iniciar. Não usar /reload.

## Backups / reversão

- Última atualização (v1.0+dados):
  /srv/games/minecraft-plugin-backups/featherdiscos-1.1-20261016-113842
  Também em ops/v1.1-backup.txt. Para voltar à1.0: parar serviço, restaurar
  FeatherDiscos-1.0.jar como plugins/FeatherDiscos.jar e iniciar; arquivar antes
  quaisquer dados novos. Esse retorno restaura o comportamento antigo indesejado.
- Migração original URLCustomDiscs:
  /srv/games/minecraft-plugin-backups/featherdiscos-20261016-103043
  ops/rollback.sh --restart volta ao URLCustomDiscs quebrado original, não à1.0.
- Os6registros antigos não tinham áudio nem URL recuperáveis; estão arquivados.

## Testes e limites de verificação

- tests/java/CoreTest: cota, persistência, exclusão sem reembolso, semana,
  DB inválido, pack/hash, áudio, SSRF,12cores/alpha16x16, nomes e tentativa11.
- docs/review-v1.1.log: protocolo26.1 via ViaVersion/ViaBackwards em Paper26.2;
  criação/entrega/5porsemana/OP/inventário cheio/cor azul/somcustom;
  12stops MUSIC durante reprodução, nenhum início vanilla, fim da supressão ao terminar.
- Produção: HTTP200 e SHA1 conferido;12PNGs da página iguais aos do pack;
  fd_1 usa modelo dourado padrão. Nenhuma exceção nova do FeatherDiscos.
- Essentials reporta versão não suportada como já fazia antes; não relacionado.
- Não houve audição/renderização em cliente gráfico nesta revisão. Usuário testou
  v1.0 e apontou os problemas; confirmar experiência real da1.1 se trouxer feedback.
- Bedrock usa mcpack por sessão e precisa reconectar para sons novos. Mapeamentos
  individuais JEXT/Geyser dependem do startup; não houve teste de cliente Bedrock.

Código, build e documentação neste diretório; ./build.sh gera build/FeatherDiscos.jar.
Leia README.md para comandos e docs/HANDOFF-HISTORICO.md para o histórico completo.
Não há implantação pendente. Próxima IA deve partir deste estado, sem recomeçar.
