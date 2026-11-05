# Protocolo — Maekawa (A6)

Este documento fixa os nomes, estados e regras que o código implementa. Os diagramas correspondentes estão em `diagrama-processo.excalidraw` (seções 3, 8, 9 e 10). Qualquer mudança de regra passa por aqui antes de passar pelo código.

## 1. Participantes

- **N = 7** nós do Cluster Sync, `p0` … `p6`. No Kubernetes, `sync-i` é `pi`.
- **K = 3**. Cada nó `pi` tem um conjunto de votação `Si` com 3 membros, `pi ∈ Si`.
- Quaisquer dois conjuntos têm interseção de exatamente um nó (plano de Fano).

| Processo | Si |
|---|---|
| p0 | {p0, p1, p2} |
| p1 | {p1, p3, p5} |
| p2 | {p2, p3, p6} |
| p3 | {p0, p3, p4} |
| p4 | {p2, p4, p5} |
| p5 | {p0, p5, p6} |
| p6 | {p1, p4, p6} |

Cada nó faz dois papéis ao mesmo tempo, com estado separado:

- **Pedinte** (`Requester`): atende o próprio cliente; pede votos a `Si`.
- **Votante** (`Voter`): recebe REQUEST dos nós que o têm em seu conjunto; concede no máximo **um** voto por vez.

## 2. Identificadores e prioridade

- `request_id = (client_id, seq)`. Único por pedido; `seq` cresce por cliente.
- `Priority = (lamport, process_id)`. Comparação lexicográfica; **menor par ganha**. `lamport` é o valor do relógio de Lamport do pedinte **no envio do REQUEST**, e não muda enquanto o pedido estiver vivo, mesmo após RELINQUISH.
- Relógio de Lamport: `tick()` antes de todo envio; `merge(remoto)` (`max(local, remoto) + 1`) em todo recebimento.

## 3. Mensagens

| Tipo | De → para | Conteúdo | Versão |
|---|---|---|---|
| `REQUEST` | pedinte → votante | request_id, priority | básica |
| `LOCKED` | votante → pedinte | request_id | básica |
| `RELEASE` | pedinte → votante | request_id | básica |
| `FAILED` | votante → pedinte | request_id | resolução |
| `INQUIRE` | votante → pedinte detentor do voto | request_id (do detentor) | resolução |
| `RELINQUISH` | pedinte → votante | request_id | resolução |
| `ACQUIRE_REQ` | cliente → seu nó | request_id | instrumentação |
| `COMMITTED` | nó → cliente | request_id | instrumentação |
| `BEGIN` / `END` | nó → observador | request_id, process_id | instrumentação |
| `BEGIN_ACK` / `END_ACK` | observador → nó | request_id | instrumentação |

Toda mensagem leva `from`, `lamport` e `request_id`. Só as seis primeiras são **mensagens de protocolo** e entram na contagem comparada com 3√N–6√N.

O nó **espera o `BEGIN_ACK` antes de dormir e o `END_ACK` antes de enviar RELEASE** ("a aplicação aguarda o registro de BEGIN", no enunciado). Sem isso o observador pode receber o BEGIN de quem herdou o voto antes do END de quem o liberou — eles chegam por conexões diferentes — e acusar uma sobreposição que não existe. O ACK é só confirmação de anotação: o observador continua sem conceder, negar ou atrasar nada; se ele não responder em 10 s, o nó registra `OBSERVER_ACK_TIMEOUT` e segue.

## 4. Votante (`Voter`)

Estado por nó: `granted: Optional<Request>` (o pedido que detém o voto), `queue: PriorityQueue<Request>` ordenada por `Priority`, `inquired: boolean` (há um INQUIRE em aberto para `granted`).

Estados: **Livre** (`granted == null`), **Concedido** (`granted != null`, `inquired == false`), **Indagando** (`granted != null`, `inquired == true`).

| Estado | Evento | Ação | Próximo |
|---|---|---|---|
| Livre | REQUEST r | `granted = r`; envia LOCKED a r | Concedido |
| Concedido / Indagando | REQUEST r, `r` pior que `granted` | enfileira r; **variante:** envia FAILED a r | mesmo |
| Concedido | REQUEST r, `r` melhor que `granted` | enfileira r; **variante:** envia INQUIRE ao dono de `granted`; `inquired = true`. **Básica:** só enfileira | Indagando (variante) / Concedido (básica) |
| Indagando | REQUEST r, `r` melhor que `granted` | enfileira r (INQUIRE já está em aberto; não reenvia) | Indagando |
| Concedido / Indagando | RELINQUISH de `granted` | devolve `granted` à fila; `granted = fila.poll()`; envia LOCKED ao novo `granted`; `inquired = false`. Se o novo `granted` for pior que alguém na fila (não ocorre, pois a fila é ordenada), nada | Concedido |
| Concedido / Indagando | RELEASE de `granted` | `granted = fila.poll()` (ou null); se houver, envia LOCKED; `inquired = false` | Concedido ou Livre |
| qualquer | RELEASE / RELINQUISH de pedido que não é `granted` | remove da fila se estiver; ignora se não (mensagem tardia) | mesmo |

Invariante: no máximo um pedido com LOCKED vigente por votante.

## 5. Pedinte (`Requester`)

Estado por nó: `current: Optional<Request>` (pedido em andamento), `votes: Set<process_id>` (LOCKED vigentes), `failed: boolean` (chegou ao menos um FAILED neste pedido; o artigo usa o flag na regra do INQUIRE, mas aqui ele só é anotado).

Estados: **Ocioso**, **Coletando**, **Cedendo** (recebeu INQUIRE e devolveu votos), **Seção crítica**, **Liberando**.

| Estado | Evento | Ação | Próximo |
|---|---|---|---|
| Ocioso | ACQUIRE_REQ do cliente | `current = r`; `tick()`; envia REQUEST a cada membro de Si (o próprio inclusive, tratado localmente) | Coletando |
| Coletando | LOCKED de v | `votes += v`. Se `votes == Si`: registra ACQUIRE e vai para a seção crítica | Coletando ou Seção crítica |
| Coletando | FAILED de v | `failed = true` | Coletando |
| Coletando | INQUIRE de v | **se `votes != Si`:** envia RELINQUISH a v, `votes -= v` | Cedendo |
| Cedendo | LOCKED de v | `votes += v`; se `votes == Si` → Seção crítica | Cedendo ou Seção crítica |
| Cedendo / Coletando | INQUIRE de outro v' | mesma regra: cede se ainda não tem quórum | Cedendo |
| Seção crítica | INQUIRE de v | **não cede**; ignora (o voto volta no RELEASE) | Seção crítica |
| Seção crítica | fim do sleep | registra EXIT; envia RELEASE a cada membro de Si; `votes = ∅`; envia COMMITTED ao cliente | Liberando → Ocioso |

Seção crítica (`CriticalSection`): envia BEGIN ao observador, dorme 0,2–1,0 s, envia END. Ocorre **fora** do lock do nó, para que o nó continue votando.

O artigo descreve o INQUIRE como “cede se `failed` ou se o quórum está incompleto”. Ter os K votos é exatamente o estado Seção crítica, então o código cede só quando ainda não está nesse estado. O flag `failed` é anotado (`onFailed`) e não altera a decisão.

Regra de corrida: se um INQUIRE chegar **depois** do terceiro LOCKED (pedinte já em Seção crítica), ele é ignorado. O votante que indagou permanece em Indagando até o RELEASE, que então concede ao próximo da fila. Isso é o que o artigo prevê; não há preempção dentro da seção crítica.

## 6. Versão básica × variante

`VARIANT=basic`: o votante nunca envia FAILED nem INQUIRE; o pedinte nunca recebe INQUIRE e portanto nunca cede. Só REQUEST, LOCKED, RELEASE. Deadlock possível (seção 7).

`VARIANT=resolution`: tudo acima. Progresso: entre pedidos em disputa, o de menor `Priority` nunca recebe FAILED de ninguém e, por INQUIRE, recupera qualquer voto detido por pedido pior que ainda não entrou; pedidos piores que já entraram terminam em tempo finito (o sleep) e liberam.

## 7. Cenário de deadlock reproduzível

Pedidos simultâneos de p0, p1 e p2 (`S0 = {p0,p1,p2}`, `S1 = {p1,p3,p5}`, `S2 = {p2,p3,p6}`). O gerador de carga `deadlock` força a ordem de entrega dos REQUEST nos votantes:

1. p0 vota em p0; p1 vota em p1; p2 vota em p0 (REQUEST de p0 chega a p2 antes do próprio p2).
2. p5 vota em p1; p3 vota em p2; p6 vota em p2.
3. Só então chegam: REQUEST de p0 a p1 (fila), REQUEST de p1 a p3 (fila), REQUEST de p2 a p2 (fila).

Resultado: p0 tem {p0, p2} e espera p1; p1 tem {p1, p5} e espera p3; p2 tem {p3, p6} e espera p2. Ninguém completa; ninguém envia RELEASE. Timeout da rodada: 15 s.

Com `VARIANT=resolution`, com Lamport igual nos três (todos pediram em `lamport = 1`), as prioridades são (1,0) < (1,1) < (1,2): p1 recebe REQUEST de p0 melhor que o seu → INQUIRE a si mesmo → cede (não tem quórum) → p0 recebe LOCKED de p1 e entra. Em seguida p1 e p2 se resolvem pela mesma regra.

## 8. Implementação do gatilho de deadlock

Para o cenário ser determinístico sem depender de latência, o `Transport` aceita um `DeliveryGate` opcional: uma lista de regras `tipo:origem>destino`. Mensagens casadas são seguradas no remetente e liberadas, na ordem em que foram seguradas, quando o gate abre; as demais passam direto. No teste em memória o gate é aberto pelo próprio teste depois de conferir os votos parciais. Nos pods, cada nó abre o seu gate `GATE_RELEASE_AFTER_MS` depois de **segurar a primeira mensagem** (não depois de subir), para o ciclo se formar mesmo que os clientes do Job cheguem com segundos de diferença. O gate só existe no perfil `deadlock`; nos demais perfis não há gate.

## 9. Log

Uma linha JSON por evento em stdout, campos mínimos: `event`, `request_id`, `process_id`, `lamport`, `quorum_granted`, `ts_monotonic_ns`, `wall_clock`, `kind` (`protocol` | `instrumentation` | `local`). Eventos locais: ACQUIRE, ENTER, EXIT, COMMITTED, VIOLATION (observador).
