# As 4 propriedades — Maekawa (A6)

Referência: slide 8 da aula. Para cada propriedade: o que o algoritmo garante **por construção** e o que só se **observa na prática**.

Contexto: N = 7 nós, K = 3, conjuntos de votação do plano de Fano (`docs/protocolo.md` §1). Duas variantes: `VARIANT=basic` e `VARIANT=resolution`.

## Resumo

| Propriedade | Tipo | Maekawa básico | Maekawa com resolução |
|---|---|---|---|
| Exclusão mútua | Segurança | Garantida por construção | Garantida por construção |
| Sem deadlock | Vivacidade | **Viola** | Garantida por argumento |
| Sem starvation | Justiça | Não vale (o deadlock vem antes) | Garantida por argumento; observada nas rodadas |
| Ordem | Justiça | Sem ordem global | Sem ordem global |

## 1. Exclusão mútua

No máximo um processo na seção crítica a cada instante.

- **Por construção.** Quaisquer dois conjuntos Si se cruzam em exatamente um nó, e cada votante concede no máximo um LOCKED por vez (invariante do `Voter`, protocolo §4). Dois pedintes não conseguem quórum completo ao mesmo tempo.
- **Resolução.** INQUIRE e RELINQUISH só devolvem votos de quem ainda não tem quórum. Quem já está em seção crítica ignora o INQUIRE (protocolo §5), então não há preempção dentro dela.
- **Observado.** O observador registra `VIOLATION` quando há sobreposição de BEGIN/END. O esperado é 0 em todas as rodadas.

## 2. Sem deadlock

Se alguém quer entrar, alguém entra.

- **Básico: viola.** Pedidos simultâneos de p0, p1 e p2 com ordens de chegada diferentes nos votantes formam um ciclo de espera. Cada um segura parte dos votos e espera o voto que o outro segura. O perfil `deadlock` reproduz o cenário (protocolo §7 e §8).
- **Resolução: garantida por argumento.** Entre os pedidos em disputa, o de menor `Priority = (lamport, process_id)` nunca recebe FAILED. Por INQUIRE, ele recupera qualquer voto detido por um pedido pior que ainda não entrou. Os piores que já entraram saem em tempo finito (a seção crítica termina) e liberam.
- **Observado.** No perfil `deadlock`, o básico trava até o timeout da rodada (15 s). A variante com resolução completa todos os pedidos.

## 3. Sem starvation

Todo pedido é atendido em tempo finito.

- **Básico.** Não se aplica, pois o deadlock impede o progresso antes de qualquer análise de justiça.
- **Resolução: por argumento.** A `Priority` do pedido é fixa durante a vida dele, mesmo após RELINQUISH. Como os relógios de Lamport avançam, todo pedido pendente acaba sendo o de menor prioridade e então entra.
- **Observado.** Nas rodadas, todos os pedidos concluem (ACQUIRE seguido de COMMITTED). Dá para medir o tempo de espera máximo por pedido nos logs.

## 4. Ordem

Pedidos atendidos na ordem dos relógios lógicos?

- **Não garantida globalmente.** A `Priority` só ordena a fila **local de cada votante**. Um votante pode ter concedido LOCKED a um pedido com timestamp maior antes de o REQUEST menor chegar. O resultado é uma ordem aproximada, diferente de Lamport e Ricart–Agrawala, que atendem na ordem global dos relógios.
- **O que "justo" significa aqui.** Prioridade `(lamport, id)` aplicada em cada votante. Não é a ordem do anel (token) nem a ordem global de relógio (permissão).
- **Observado.** Dá para contar nos logs as inversões: pares em que um pedido de Lamport menor entrou depois de um de Lamport maior.

## Resposta curta ao slide 8

- **Por construção:** exclusão mútua.
- **Por argumento (só na variante com resolução):** ausência de deadlock e de starvation.
- **Só se observa:** `VIOLATION = 0`, a conclusão de todos os pedidos e o grau de inversão de ordem.
- **Não garantida:** ordem global.
