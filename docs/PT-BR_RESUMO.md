# AkashicWeb: resumo em português

Atualizado em 13/10/2026. Mantenha em dia com [`PROJECT_STATE.md`](PROJECT_STATE.md).

## O que é

Um mod novo para o Minecraft 1.7.10, feito sobre a stack do GTNH, que coloca um **navegador de verdade** em telas dentro do mundo, com a ideia do [Web Displays](https://github.com/montoyo/webdisplays). É para o Web Displays o que o AkashicFM é para o OpenFM: mesma ideia, engenharia nova. O Web Displays é domínio público, então as partes boas dele (multibloco, clique para pixel, rotação, permissões) podem ser reaproveitadas com crédito ao montoyo.

Os jogadores constroem telas, clicam, rolam, digitam e navegam. O telão do spawn é uma tela grande do Gabriel, onde ele abre a live pela barra de endereço. Não é um quiosque controlado por comandos nem um painel de resultados. Dados do TSE e a "ponte" para retransmitir lives foram abandonados (D-008): o foco é só o mod de navegador.

## Estado

**Implementação, onda 2 (correções das revisões).** Os cinco módulos existem e foram integrados em 13/10:
- **app do navegador:** Electron 44.7.0;
- **conexão Java com o navegador**;
- **instalador do motor:** download com SHA-256 conferido;
- **bloco da tela, permissões, rede e comandos** do servidor;
- **cliente:** renderização compatível com Angelica, teclado ABNT2 e sincronia de vídeo.

O build passa com 440 testes JUnit, e o app do navegador passa 123 testes de unidade e 29 de integração com o Electron real, tudo só num container Linux. Nada foi testado no Windows, dentro do Minecraft ou no servidor Crucible.

A revisão adversarial achou problemas sérios, e eles estão sendo corrigidos agora:
- o Electron desliga a proteção de rede local do Chromium, então o próprio app vai bloquear endereços internos em toda requisição;
- o volume por distância só valia depois que a página terminava de carregar;
- uma thread morta podia congelar a tela sem mostrar motivo.

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

## Decisões tomadas em 11/10

- Servidor: **Forbidden Series** (Technic) com servidor **Crucible**, não o GTNH.
- O AkashicFM já está no pacote, então o som 3D usa o motor dele.
- Chromium com codecs H.264/AAC: aceito.
- Equipe: Gabriel + assistente.
- Pack inventariado em 12/10: UniMixins, GTNHLib e ModularUI2 nos dois lados; Angelica 2.2.20 com shaders no cliente; servidor Crucible com Java 21, LuckPerms e plugins de proteção (ver `research/07-forbidden-pack-inventory.md`).

## Decisões técnicas de 12/10

- Revisão cética com sete especialistas, arquiteto e revisor: 16 decisões de arquitetura em [`adr/README.md`](adr/README.md).
- O navegador roda num processo próprio (AkashicWebHost) com o Electron oficial (Chromium 152, codecs e sandbox). Tudo o que o jogador vê e faz continua dentro do Minecraft: telas no mundo, cliques, teclado e sincronia vinda do servidor. Se o navegador travar, a tela pisca e volta, sem derrubar o jogo.
- Sem prazos fixos: produzir direto.

## Decisão técnica de 13/10 (D-016)

- **O que muda:** o que o jogador digita ou clica numa tela vai direto para o navegador do próprio PC.
- **Por quê:** antes ia ao servidor e voltava. Isso somava uma ida e volta de rede em cada tecla e fazia o texto digitado, até senhas, passar pelo servidor.
- **O que o servidor continua decidindo:** quem controla a tela, qual é o endereço e o relógio do vídeo, para todos verem a mesma coisa.

## Diretriz do Gabriel em 13/10 (D-017)

- **Tecnologia:** moderna e de ponta.
- **Interface:** todas as telas em **ModularUI2**, que já está no pack.
- **Interação:** reproduzir a **dinâmica do Web Displays**, com teclado e mouse interagindo com a tela dentro do mundo.
- **Próximo passo:** a onda 3 pesquisa e desenha isso antes de codar. Depois viram as decisões de arquitetura ADR-017 (interface e stack) e ADR-018 (modelo de interação).

## Decisões que faltam (ver [`DECISIONS.md`](DECISIONS.md))

1. **Sites:** lista de permitidos ou de bloqueados nas telas públicas? E quem são os admins?
2. **Canais:** quais duas lives vão para o telão?
3. **Licença** do repositório.
4. **Texto do consentimento** para instalar o navegador (P-12), em `pt_BR.lang`.
5. **Permissão no LuckPerms:** liberar `akashicweb.web` para o grupo padrão (P-13), senão só OP usa `/web` no Crucible.

## Onde está cada coisa

- [`PLAN.md`](PLAN.md): arquitetura, escopo, cronograma, portões e testes.
- [`HANDOFF.md`](HANDOFF.md): o que o próximo chat faz primeiro.
- [`research/`](research/): as pesquisas completas, com fontes.
- [`../spikes/`](../spikes): os protótipos usados na pesquisa.
