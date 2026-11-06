# Matter Control — NeoForge 1.21.1

Manipulação de matéria 100% controlada pelo jogador: absorver, levantar, mover, girar,
compactar, dividir, juntar, construir, aprisionar, agarrar com mãos gigantes e arremessar.

## Como compilar / testar

1. Instale o **JDK 21**.
2. Baixe o MDK do NeoForge 1.21.1 (https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
   e copie para esta pasta os arquivos **`gradlew`, `gradlew.bat` e a pasta `gradle/`**
   (o wrapper do Gradle não vem neste código).
3. Se o MDK usar versões mais novas, copie de lá os valores de `neo_version` (gradle.properties)
   e a versão do plugin `net.neoforged.moddev` (build.gradle).
4. Rode:
   - `gradlew runClient` → abre o Minecraft com o mod
   - `gradlew build` → gera o .jar em `build/libs/`

## Controles (todos remapeáveis em Opções → Controles → Matter Control)

| Tecla | Ação |
|---|---|
| **V** | Liga/desliga o controle de matéria (ataque/uso vanilla ficam bloqueados enquanto ligado) |
| **G** (segurar) | Absorve a região mirada. **G + roda** = raio de absorção |
| **C** / Shift+C | Próximo / anterior modo |
| **Botão esquerdo** | Seleciona estrutura sob a mira (Shift = adicionar à seleção). No modo *Manipular*, em terreno: arranca os blocos e levanta |
| **Botão direito** (segurar) | Seleção segue exatamente o mouse. Nos modos de construção: a estrutura cresce enquanto segura |
| **Roda** | Distância da matéria (modos de manipulação) ou tamanho (modos de construção) |
| **Alt + roda** | Tamanho nos modos de manipulação / distância nos modos de construção |
| **Ctrl + roda** | Parâmetro secundário (espessura, espaçamento...) |
| **Z + roda** | Girar (Z+Alt: inclinar, Z+Shift: rolar, Ctrl: passos finos) |
| **Shift / Ctrl** | Movimento rápido / preciso. Em voo: acelera / freia |
| **R** (segurar) | Compacta em esfera, cada vez mais densa. Em prisões e mãos: esmaga |
| **Y** (segurar) | Expande matéria compactada |
| **X** | Arremessa em direção à mira (Shift = mais forte). **Ctrl+X** = soltar (cai com gravidade) |
| **B** | Formato do modo (torre quadrada/redonda/cruz, parede vertical/inclinada/curva/horizontal, plataforma área/caminho/escada, nº de pilares...). Nos modos *Mãos* e *Projéteis*: cria |
| **H** / Shift+H | Divide em duas metades / em vários pedaços |
| **J** | Junta os pedaços selecionados numa massa só |
| **N** / Shift+N | Desintegra em uma nuvem de partículas / espalha pedaços em todas as direções |
| **U** | Reconstrói (junta a nuvem/pedaços de volta) |
| **K** | Remodela: esfera → parede → torre → plataforma → cubo |
| **P** | Solidifica no mundo (matéria compactada vira blocos densos e muito resistentes) |
| **M** | Cada projétil persegue um alvo diferente dentro da mira |
| **Backspace** | Dissolve de volta em matéria (devolve à barra). **Ctrl+Backspace** = liberta o prisioneiro |

### Modos
- **Manipular** — levante pedaços de terreno (raio pela roda com Alt) e controle-os livremente.
- **Torre / Parede / Pilares** — segure o botão direito: cresce camada a camada; soltar para na hora.
- **Plataforma** — área que se expande, *caminho/ponte* pintado exatamente onde o mouse aponta, ou *escada* que sobe/desce conforme você olha.
- **Esfera** — segure o botão direito: uma esfera de matéria cresce na mira e segue o mouse.
- **Projéteis** — B/direito cria pedras que orbitam você; selecione individualmente, mova, lance com X, guie em voo segurando o botão direito, ou use M para perseguição.
- **Prisão** — clique esquerdo num inimigo: a matéria sobe do chão e o envolve. Depois mova, levante, esmague (R), arremesse (X) ou liberte (Ctrl+Backspace).
- **Mãos** — B cria uma mão gigante; direito move; segurar esquerdo fecha os dedos (Ctrl+esquerdo abre); fechar sobre criaturas/estruturas agarra; R esmaga; X arremessa o que estiver na mão.

## Configuração
- `config/mattercontrol-server.toml` (por mundo, em `serverconfig/`): capacidade máxima, velocidade de absorção,
  raio, filtro (ANY/WHITELIST/BLACKLIST + lista de blocos ou `#tags`), valor de cada bloco, alcance,
  limites de estruturas/blocos/mãos, custos e dano.
- `config/mattercontrol-client.toml`: multiplicador de partículas, distância de LOD, blocos desenhados por frame.

## Arquitetura
- `entity/MatterConstructEntity` — uma entidade carrega milhares de blocos reais (forma + escala + rotação + dispersão).
  Física com colisão amostrada por blocos de superfície, impacto com onda de choque, prisioneiro.
- `entity/MatterHandEntity` — mão articulada (palma, 4 dedos × 3 falanges, polegar).
- `server/*` — absorção, construção, manipulação contínua, ações e transformações. Tudo é autoritativo no servidor
  (funciona em multiplayer; outros jogadores veem tudo).
- `client/*` — entrada, HUD, renderização com culling de superfície + LOD, partículas de fragmentos reais dos blocos.
