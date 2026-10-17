# Retomada Codex — 2026-10-16
Servidor ao vivo: /srv/games/minecraft, minecraft.service, PID Java 962 na retomada.
Não parar/reiniciar/recarregar o servidor ao vivo. Desktop desligado; boot texto.
Só havia 6 classes de dados/config; não havia plugin principal nem build.
Dependências recuperadas de Modrinth (hash SHA512 conferido) em vendor/;
código upstream preservado em vendor/jext-source e vendor/jext-web.

Correções ao plano original:
- O domínio Playit NÃO encaminha HTTP :9880. Usar HTTPS público existente,
  https://cachy.tailc71a24.ts.net:8443/discos/, nginx -> loopback:9880.
- Links externos não são garantidos. Adicionar envio de áudio pelo navegador com
  token individual emitido por /disco enviar, identidade obtida no jogo e cota igual.
- Limite 5 para TODOS por padrão, inclusive OP. Não criar bypass implícito.
- Unificar disco e registro da cota numa gravação atômica; excluir disco não devolve cota.
- Pacotes Java/Bedrock distintos; Bedrock precisa reconectar para atualizar sons.
- Não prometer verificação visual/áudio real sem cliente Minecraft.

Em andamento: implementação. Atualizar HANDOFF.md antes de encerrar.
