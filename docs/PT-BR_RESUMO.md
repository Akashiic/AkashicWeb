# AkashicWeb: resumo em português

Atualizado em 10/10/2026. Mantenha em dia com [`PROJECT_STATE.md`](PROJECT_STATE.md).

## O que é

Um mod novo para o Minecraft 1.7.10, feito sobre a stack do GTNH, que coloca um **navegador de verdade** em telas dentro do mundo, com a ideia do [Web Displays](https://github.com/montoyo/webdisplays). É para o Web Displays o que o AkashicFM é para o OpenFM: mesma ideia, engenharia nova. O Web Displays é domínio público, então as partes boas dele (multibloco, clique para pixel, rotação, permissões) podem ser reaproveitadas com crédito ao montoyo.

Os jogadores constroem telas, clicam, rolam, digitam e navegam. O telão do spawn é uma tela grande do Gabriel, onde ele abre a live pela barra de endereço. Não é um quiosque controlado por comandos nem um painel de resultados.

## Estado

**Pesquisa concluída, nenhum código ainda.** Nada foi compilado para o Minecraft, testado no Windows ou visto dentro do jogo. Os testes da pesquisa rodaram num container Linux.

## O que a pesquisa mostrou

- **Base antiga não serve:** não existe código público do Web Displays 1.7.10, e o MCEF antigo da 1.7.10 é um Chromium de 2015, só para Windows.
- **As 590 issues antigas:** 19% são tela branca ou preta. A causa é o MCEF cair em silêncio quando algo falha. Cada problema tem correção em [`PROBLEM_FIX_MATRIX.md`](PROBLEM_FIX_MATRIX.md).
- **Chromium moderno em Java 8 funciona:** o protótipo tocou 1080p a 30 e 60 fps no Linux.
- **Codecs:** o Chromium padrão não toca H.264/AAC, e o áudio das lives do YouTube é só AAC. O OBS hospeda um Chromium 150 com codecs. Compilamos o JCEF (fork CCBlueX) em cima dele e ele tocou H.264, AAC e HLS no Linux. No Windows ainda falta testar; esse é o primeiro portão (G1, 13/10).
- **Som 3D:** o fork CCBlueX tem captura de áudio. Dá para ligar o som no motor do AkashicFM (3D, oclusão, reverb), mas o AkashicFM precisa de três ajustes antes.
- **Atraso:** o navegador soma dezenas de ms. O atraso da live é o da emissora; no YouTube fica abaixo de 5 s em latência ultrabaixa. Entre jogadores, a meta é 0,5–2 s com o relógio do servidor.
- **Mac:** não deve funcionar até o dia 25 (lwjgl3ify), então mostra um aviso.

## Cronograma resumido

| Data | Etapa |
|---|---|
| 11/10 | Decisões do dono; build do Chromium para Windows na CI; esqueleto do mod |
| 13/10 | Portão G1: Chromium no PC Windows do Gabriel |
| 16/10 | Portão G3: dentro do jogo; build nativo congelado; pré-instalação para os jogadores |
| 17/10 | Alpha com 5–10 jogadores |
| 18/10 | Ensaio público (G4) |
| 20/10 | Escopo congelado (RC1) |
| 22/10 | Ensaio 2 (G5) |
| 24/10 | Ensaio geral e decisão final (G6) |
| 25/10 | Telão no ar às 15:30 |

## Decisões que faltam (ver [`DECISIONS.md`](DECISIONS.md))

1. **Servidor:** qual recebe o mod, o GTNH (e em que versão) ou o Forbidden Series?
2. **AkashicFM:** vai estar no pacote? Ele é necessário para o som 3D.
3. **Codecs:** aceitar o Chromium com H.264/AAC?
4. **Equipe:** é só o Gabriel com o assistente?
5. **Sites:** lista de permitidos ou de bloqueados nas telas públicas? E quem são os admins?
6. **Canais:** quais duas lives vão para o telão?
7. **Licença** do repositório.

## Onde está cada coisa

- [`PLAN.md`](PLAN.md): arquitetura, escopo, cronograma, portões e testes.
- [`HANDOFF.md`](HANDOFF.md): o que o próximo chat faz primeiro.
- [`research/`](research/): as pesquisas completas, com fontes.
- [`../spikes/`](../spikes): os protótipos usados na pesquisa.
