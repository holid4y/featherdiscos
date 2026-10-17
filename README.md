# FeatherDiscos · FeatherFlux

Complemento para Jukebox Extended Reborn 2.2.7, Paper 26.2 / Java25.
Permite criar discos dentro do jogo, com até **5 criações por jogador por semana**.

## Para jogadores

- `/disco https://... [nome]` (ou `/disco enviar https://...`): YouTube, YouTube Music,
  SoundCloud, links diretos e **Spotify**. O Spotify tem DRM: o link de música é lido
  pela página pública de embed (artista, título, duração) e a mesma faixa é buscada no
  YouTube com janela de duração de ±8s e depois ±25s. Álbum/playlist/podcast é recusado.
- `/disco enviar`: link pessoal válido por10min. Na página dá para enviar um arquivo
  (MP3/WAV/OGG/FLAC/M4A ou vídeo MP4/WEBM) **ou colar um link**. Fique no servidor
  durante o envio; depois pode sair enquanto o áudio é processado.
- `/disco cota`: mostra quantas criações restam.
- `/disco meus`: lista seus discos com botão [pegar].
- `/disco pegar <nº>` (ou `/disco receber <nº>`): entrega outra cópia de um disco que
  você criou (admin: qualquer disco). Uma cópia a cada 3s.
- `/disco receber`: retira discos pendentes após liberar espaço no inventário.
- `/disco pacote`: reaplica o resource pack Java; no Bedrock orienta a reconectar.

O disco toca numa jukebox normal. O limite renova segunda-feira às00:00,
America/Sao_Paulo. Quem tem `featherdiscos.ilimitado` (OP, e todo `featherdiscos.admin`)
não tem limite semanal nem limite de tentativas. Falha de download/conversão não consome
cota. Apagar um disco não devolve a vaga. Links com login, DRM, bloqueio regional
ou conteúdo removido podem falhar: nesse caso envie o arquivo de áudio.
Limites padrão:3–480segundos, arquivo32MB e pacote total180MB. Há também um
limite de10 tentativas por10min por jogador, incluindo falhas. A reserva de fila
é feita antes da leitura do upload. Nomes com formatação/caracteres invisíveis
e formatos/canais/taxas fora dos limites são rejeitados. O áudio é normalizado
para -16LUFS / pico -1,5dB, com decode estrito.

A faixa vanilla do item-base é bloqueada por pacote de rede. A música ambiente
é silenciada só num raio64 da jukebox personalizada enquanto ela toca; o
controle para ao acabar, remover o disco, quebrar a jukebox ou descarregar o chunk.

## Pacotes e identidade visual

Página, favicon e pack.png usam `server-icon.png` do servidor. Os itens usam o
disco vanilla13 com miolo recolorido:12 opções com prévia na página de envio.
Discos existentes recebem dourado, preservando IDs, áudio e cota.
URL pública: https://cachy.tailc71a24.ts.net:8443/discos/.
`/disco enviar` acrescenta token individual no fragmento da URL; não compartilhar.
nginx encaminha `/discos/` ao HTTP do plugin em127.0.0.1:9880. A wiki, Nextcloud,
painel, Playit, SSH e RustDesk conservam suas rotas.

O ZIP Java tem URL imutável `/pack/<sha1>.zip`, hash enviado ao cliente,
Content-Length, cache imutável, GET e HEAD. O evento de configuração espera a
confirmação de carregamento antes da entrada no mundo. Atualizações durante o
jogo usam o mesmo UUID de pacote, sem remover pacotes de outros plugins.
Versão de recursos26.2 é **88.0**, não107.1 (datapack). Metadados incluem suporte
55–88 e os campos legados exigidos para versões anteriores a65.

Bedrock recebe um `.mcpack` próprio pelo evento de nova sessão do Geyser,
sem reiniciar o Geyser ou expulsar quem está jogando. **Precisa reconectar para
ouvir discos novos.** O ícone do pacote é FeatherFlux; mapeamentos de itens
individuais do JEXT dependem da inicialização do Geyser. Som usa o mesmo namespace.
O servidor já usa `force-resource-packs: true` no Geyser.

Não há garantia de que todo site sempre permitirá download, nem teste visual
ou acústico com cliente gráfico nesta sessão. Os testes de protocolo confirmam
hash/download, resposta de carregamento simulada e emissão do evento de som.

## Dados e manutenção

Produção: `/srv/games/minecraft/plugins/FeatherDiscos/`.
`discos.yml` contém catálogo, IDs crescentes, entrega e histórico de cotas em uma
única gravação atômica. `audio/fd_<id>.ogg` é a fonte dos pacotes.
Não apague esse banco nem os áudios. Inclua ambos no backup do servidor.
`packs/` retém versões antigas por24h para downloads em andamento.
`uploads/` e `jobs/` são temporários e limpos ao iniciar.

Administrador: `/disco status`, `/disco reconstruir`, `/disco lista` (todos os discos com
[pegar]/[apagar]) e `/disco apagar`: sem número usa o disco segurado na mão ou mostra a
lista; com número pede confirmação por clique (`/disco apagar <id> confirmar`). Pelo
console (painel) `/disco apagar <id>` apaga direto.
Reconstruir recupera pacote/JEXT a partir do catálogo e áudios. Não usar `/reload`
nem gerenciador de hot-load. Mudanças de config exigem reinício planejado.
JEXT usa API web desativada e hospedagem interna ligada para ler seu ZIP local;
só o FeatherDiscos entrega o pacote aos jogadores. Não usar o gerador JEXT externo
para editar o catálogo gerenciado por este complemento.

Downloads usam yt-dlp e Deno em `/opt/featherdiscos/bin`, FFmpeg/ffprobe do sistema.
`download.py` escreve `error.txt` (motivo para o jogador: álbum, longo demais, privado,
ao vivo) e `title.txt` (nome sugerido do Spotify) na pasta do job.
Não há `pacman -S` nem atualização automática durante boot. Para corrigir mudanças
nos sites, atualizar yt-dlp em uma manutenção (`sudo -u minecraft /opt/featherdiscos/bin/yt-dlp -U`)
e verificar download antes de liberar. Envio de arquivo continua sendo alternativa.
O proxy de download valida todos os IPs DNS e fixa o IP de conexão; bloqueia rede
privada/Tailscale/metadata. FFmpeg só abre protocolos locais file/pipe.

## Build, testes, reversão

`./build.sh` gera `build/FeatherDiscos.jar`, usando Java25 e bibliotecas do Paper
instalado. Dependências JEXT e PacketEvents estão em `vendor/`, com metadados
Modrinth e hashesSHA512 de origem. Fontes upstream também preservadas ali.

Testes em `tests/`; resultados em `docs/*integration.log`. Sandbox separado em
`/srv/games/featherdiscos-test`, unit `featherdiscos-test.service`, porta25575,
heap1G/MemoryMax1600M/CPUQuota100%. Ele deve ficar desligado após os testes.
Clientes de protocolo26.1 usam ViaVersion+ViaBackwards para testar Paper26.2.
Não confundir esse servidor offline de loopback com produção.

Instalação inicial: `sudo -n ./ops/deploy.sh --restart`, apenas após testes e
reinício autorizado. Backup em `/srv/games/minecraft-plugin-backups/`, apontado
por `ops/last-backup.txt`. Reversão: `sudo -n ./ops/rollback.sh --restart`.
O rollback arquiva os dados novos e restaura o plugin anterior; o antigo serviço
remoto de discos continuará quebrado como estava antes da troca.

Os seis registros antigos do URLCustomDiscs só têm nome/modelo/UUID, sem áudio ou
URL de origem; não são convertidos automaticamente. Arquivados no backup.

Leia `HANDOFF.md` para saber o estado real da implantação e os próximos passos.
