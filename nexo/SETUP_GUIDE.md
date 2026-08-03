# Guia: configurar o Nexo pro CreateOnMinecraft

Este guia é para quem tem acesso ao **servidor** (pasta `plugins/`, resource pack final).
O lado do código (o plugin Java/Kotlin) já está pronto e commitado — aqui só falta a
configuração do Nexo em si: registrar os IDs de item e garantir que as texturas/modelos
apareçam no resource pack que o servidor entrega.

## Contexto rápido

`CreateOnMinecraft` é um plugin Paper que recria as mecânicas do mod Create
(engrenagens, eixos, motor, roda d'água, moinho, esteira, funil) inteiramente no
servidor. Cada peça é um item customizado (visual via modelo custom) que o jogador
segura e coloca com clique direito; a colocação vira uma entidade `ItemDisplay` +
bloco de barreira invisível — isso é 100% lógica própria do plugin, **não** usa (e não
deve usar) o sistema de blocos customizados do Nexo (`NexoBlocks`/`NexoFurniture`).
O Nexo entra só como **registro de item + modelo**: `NexoItems.itemFromId(id)` pra
construir o `ItemStack`, `NexoItems.idFromItem(stack)` pra identificar o que o jogador
tem na mão.

Isso já está todo implementado em `dev.createonmc.nexo.NexoIds` e `NexoCompat` no
código-fonte do plugin. **A fonte da verdade dos IDs é `NexoIds.kt`** — se algo aqui
divergir dele, o `.kt` manda.

## O que falta fazer (só do lado do servidor)

### 1. Confirmar a versão do Nexo

O plugin compila contra `com.nexomc:nexo:1.26.0` (ver `build.gradle.kts`). Precisa de
Nexo **1.22+** no mínimo (requisito pro Paper 26.1.2 / Minecraft 1.21.4 usado aqui).
Se o servidor tiver uma versão mais antiga, atualize o jar do Nexo antes de continuar.

### 2. Copiar o registro de itens

O arquivo `nexo/items/createonmc.yml` (neste mesmo repositório) já tem os 16 itens
prontos, com os IDs exatos que o código espera e os `Components.item_model` apontando
pros modelos que já existem no pack `ssggearmachine` (nenhum modelo novo precisa ser
criado — é reaproveitamento 1:1 do que já existia antes da migração).

- Copie `nexo/items/createonmc.yml` → `plugins/Nexo/items/createonmc.yml` no servidor.
- **Não renomeie as chaves** (`gear`, `biggear`, `eixo`, `motor`, `water_wheel`,
  `millstone`, `esteira`, `funel`, `water_wheel_spin`, `water_wheel_fixed`,
  `millstone_spin`, `millstone_fixed`, `esteira_spin`, `esteira_fixed`, `funel_in`,
  `funel_out`) — são comparadas literalmente no código.
- **Não troque `material: STICK`** em nenhum deles. O handler de colocação
  (`AxleInteractListener.onRightClickBlock`) exige que o item na mão seja
  `Material.STICK` antes mesmo de checar o ID do Nexo — outro material faz a colocação
  parar de funcionar silenciosamente, mesmo com o ID certo configurado.

### 3. Garantir que as texturas/modelos existem no pack final

Os modelos referenciados (`ssggearmachine:gear`, `ssggearmachine:parts/water_wheel_spin`,
etc.) **não usam texturas customizadas** — são modelos Blockbench que reaproveitam
texturas vanilla (ex: `block/blast_furnace_top`, `block/stripped_spruce_log`). Ou seja:
não tem PNG pra copiar, só os JSONs de modelo.

Fonte desses assets :
`/home/ubuntu/pluginDEV/references/SSGGearMachine`
(também tem um backup em `SSGGearMachine.rar` na raiz do `pluginDev`).

Estrutura relevante dentro dessa pasta:
```
assets/ssggearmachine/
  items/*.json              # apontam pro modelo real (ex: items/gear.json → block/gear)
  items/parts/*.json        # variantes "spin"/"fixed" usadas nas entidades ItemDisplay
  items/states/*.json       # funel_in / funel_out
  models/block/*.json       # os modelos Blockbench de verdade (geometria + texturas vanilla)
```

Duas formas de fazer esses assets chegarem no cliente — escolha uma:

- **Opção A (mais simples, zero risco):** mantenha o resource pack `SSGGearMachine`
  como um pack separado, aplicado junto com o pack que o Nexo gera (na lista de packs
  do servidor / `server-resource-pack` múltiplo, ou via algum plugin agregador de
  packs). Nada muda em relação a como já funcionava antes da migração.
- **Opção B:** copie a pasta `assets/ssggearmachine/` inteira pra dentro da pasta de
  override de assets do Nexo (normalmente `plugins/Nexo/pack/`, mas confirme o nome
  exato na versão instalada — `/nexo pack` ou a doc de "Pack" do Nexo mostra o caminho).
  O Nexo funde esse conteúdo no pack final que ele gera e serve, sem precisar de um
  segundo pack na lista.

Qualquer uma das duas funciona — o `item_model` no `createonmc.yml` só precisa apontar
pro namespace/caminho certo (`ssggearmachine:...`), não importa quem "hospeda" o asset.

### 4. Recarregar e validar

- `/nexo reload` (ou reiniciar o servidor).
- O plugin já tem uma checagem automática: ao carregar os itens do Nexo, ele loga no
  console `[Nexo] Todos os 16 itens do CreateOnMinecraft estão registrados.` — se
  aparecer `[Nexo] Itens não registrados em plugins/Nexo/items: ...` em vez disso,
  algum ID do passo 2 está faltando ou com o nome errado.
- Teste em jogo: `/ssggive gear` (e os outros tipos: `biggear`, `eixo`, `motor`,
  `water_wheel`, `millstone`, `esteira`, `funel`) — o item deve aparecer com o modelo
  certo, e o clique direito pra colocar deve continuar funcionando normalmente.
  Se o item vier como um stick sem modelo nenhum, é o fallback do `NexoCompat` — sinal
  de que o ID não bateu (voltar pro passo 2/4).

### 5. Receitas de crafting (opcional, mas já prontas)

`nexo/recipes/shaped/createonmc.yml` (neste repositório) tem receitas de crafting-table
pra 7 dos 8 itens "empunháveis" (`gear`, `biggear`, `eixo`, `water_wheel`, `millstone`,
`funel`, `esteira`), baseadas nas receitas reais do mod Create, mas achatadas pra usar só
ingredientes vanilla (sem inventar um item intermediário tipo "Andesite Alloy" — onde a
receita real do Create pedia Andesite Alloy, aqui entra ANDESITE puro).

Só `motor` **não tem receita de propósito** — no Create real, ele corresponde ao
Creative Motor, que é criativo-only e não tem receita nenhuma. Continua só via
`/ssggive` (admin).

`esteira` tem receita mesmo o Belt real do Create não tendo nenhuma (lá ele é criado
clicando com o botão direito em dois Shafts, nunca craftado) — nesse plugin `esteira` é
o item que de fato conecta dois `eixo`s, um mecanismo diferente do mod original, então
precisa ser obtido de algum jeito em sobrevivência.

- Copie `nexo/recipes/shaped/createonmc.yml` → `plugins/Nexo/recipes/shaped/createonmc.yml`.
- `/nexo reload` (ou restart) pra aplicar junto com o passo 2.

## Referências

- Código do plugin: `dev.createonmc.nexo.NexoIds` e `dev.createonmc.nexo.NexoCompat`
  (fonte da verdade dos IDs e do comportamento de fallback).
- Onde os IDs são consumidos: `GearManager.kt`, `BeltManager.kt`, `FunelManager.kt`,
  `AxleInteractListener.kt`, `FunelInteractListener.kt`, `SSGGiveCommand.kt`.
- Registro de itens a copiar: `nexo/items/createonmc.yml`.
- Docs oficiais do Nexo: https://docs.nexomc.com (seção `configuration/items` tem o
  schema completo de `Components`/`Pack` caso precise ajustar algo além do que já
  está no `createonmc.yml`).
