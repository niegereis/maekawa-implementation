# Diagrama do processo — Maekawa (A6)

Para ver as figuras renderizadas, abra [diagrama-processo.html](diagrama-processo.html) no navegador.

Cada nó do Cluster Sync é, ao mesmo tempo, pedinte do próprio cliente e votante dos conjuntos de que faz parte. A prioridade de um pedido é o menor par `(relógio de Lamport, id do processo)`. Um votante concede no máximo um voto por vez. O observador só conta acessos ativos a R.

Os experimentos usam a variante com FAILED, INQUIRE e RELINQUISH. A versão básica, mais abaixo, é só a evidência de deadlock.

## Acesso a R

O cliente fala apenas com o seu nó. Esse nó pede LOCKED aos 3 membros de Si, inclusive a si mesmo. Com os 3 votos vigentes, entra na seção crítica e só então responde COMMITTED.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant Pi as Nó pi
    participant V as Votante de Si
    participant H as Detentor do voto
    participant O as Observador

    C->>Pi: pedido request_id = (client_id, seq)
    Note over Pi: acquire(R, request_id)

    loop para cada um dos 3 membros de Si
        Pi->>V: REQUEST
        alt ainda não votou
            V-->>Pi: LOCKED
            Note over V: voted = verdadeiro e guarda o request_id
            Note over Pi,V: se o membro é o próprio pi, o LOCKED é local
        else prioridade pior
            V-->>Pi: FAILED
            Note over V: enfileira o pedido
            Pi->>V: RELINQUISH aos membros que já tinham votado neste pedido
        else prioridade melhor
            V->>H: INQUIRE
            alt o detentor ainda não tem os 3 votos
                H-->>V: RELINQUISH
            else o detentor já tem os 3 votos ou já entrou
                Note over H: não cede durante o sleep
                H->>V: RELEASE ao sair da seção crítica
            end
            Note over V: voted livre e LOCKED ao pedido de maior prioridade na fila
            V-->>Pi: LOCKED
        end
    end

    Note over Pi: este trecho só ocorre com 3 LOCKED vigentes
    Pi->>O: BEGIN
    Note over O: acessos ativos a R aumenta em 1
    Note over Pi: ENTER e sleep de 0,2 a 1 s
    Pi->>O: END
    Note over O: acessos ativos a R diminui em 1
    Note over Pi: release(R, request_id)
    Pi->>V: RELEASE aos 3 membros de Si
    Note over V: LOCKED ao próximo da fila, ou voted = falso se a fila esvaziar
    Pi-->>C: COMMITTED
    Note over C: espera de 1 a 5 s e gera o próximo seq
```

Sem disputa são 9 mensagens de protocolo por entrada: 3 REQUEST, 3 LOCKED e 3 RELEASE. BEGIN e END são instrumentação e não entram nessa conta. Se a contagem do observador passa de 1, ele registra a sobreposição e não bloqueia ninguém.

Exemplo de quórum: p0 pede `{p0, p1, p2}`. Uma disputa com p2, que pede `{p2, p3, p6}`, se cruza em p2.

## Estados de quem pede

```mermaid
stateDiagram-v2
    [*] --> Ocioso
    Ocioso --> Coletando: pedido do cliente, REQUEST aos 3
    Coletando --> Coletando: LOCKED com quórum ainda incompleto
    Coletando --> Cedendo: FAILED, ou INQUIRE antes dos 3 votos
    Cedendo --> Coletando: RELINQUISH enviado, espera novo LOCKED
    Coletando --> SecaoCritica: 3 LOCKED vigentes, BEGIN
    SecaoCritica --> SecaoCritica: INQUIRE recebido, não cede
    SecaoCritica --> Liberando: fim do sleep, END
    Liberando --> Ocioso: RELEASE aos 3 e COMMITTED ao cliente
```

## Estados de quem vota

```mermaid
stateDiagram-v2
    [*] --> Livre
    Livre --> Concedido: REQUEST, responde LOCKED
    Concedido --> Concedido: REQUEST de prioridade pior, responde FAILED e enfileira
    Concedido --> Indagando: REQUEST de prioridade melhor, envia INQUIRE
    Indagando --> Concedido: RELINQUISH, ou RELEASE de quem não cedeu
    Concedido --> Concedido: RELEASE com fila, LOCKED ao próximo
    Concedido --> Livre: RELEASE com fila vazia
```

Na versão básica o votante não tem o estado Indagando. REQUEST com voto já concedido só enfileira. É isso que permite a espera circular abaixo.

## Deadlock da versão básica

Mensagens apenas REQUEST, LOCKED e RELEASE, sem preempção. Três pedidos bastam para ninguém completar o quórum. Não há falha nem perda de mensagem.

| Quem pede | Conjunto | Votos que já tem | Espera |
|---|---|---|---|
| p0 | {p0, p1, p2} | p0 e p2 | p1, que votou no pedido de p1 |
| p1 | {p1, p3, p5} | p1 e p5 | p3, que votou no pedido de p2 |
| p2 | {p2, p3, p6} | p3 e p6 | p2, que votou no pedido de p0 |

```mermaid
sequenceDiagram
    autonumber
    participant P0 as p0
    participant P1 as p1
    participant P2 as p2
    participant P3 as p3
    participant P5 as p5
    participant P6 as p6

    Note over P0: pede S0 = {p0, p1, p2}
    Note over P1: pede S1 = {p1, p3, p5}
    Note over P2: pede S2 = {p2, p3, p6}

    P0-->>P0: LOCKED
    P2-->>P0: LOCKED
    P1-->>P1: LOCKED
    P5-->>P1: LOCKED
    P3-->>P2: LOCKED
    P6-->>P2: LOCKED

    P0->>P1: REQUEST fica na fila
    P1->>P3: REQUEST fica na fila
    P2->>P2: REQUEST fica na fila

    Note over P0,P2: p0 espera o voto de p1, p1 espera o voto de p3, p2 espera o próprio voto, já concedido a p0. Ninguém envia RELEASE.
```

O gerador segura esses votos até o ciclo aparecer e encerra no timeout de 15 s. O log fica com `voted = verdadeiro` nos votantes e com o quórum incompleto em p0, p1 e p2. A carga oficial não usa esta versão.
