# Roteiro da apresentação: algoritmo de Maekawa

Duração estimada: **12 a 14 minutos** + perguntas.

Material:
- [maekawa-topologia.excalidraw](maekawa-topologia.excalidraw): quem fala com quem (grupos, matriz de interseções, custo)
- [maekawa-cenarios.excalidraw](maekawa-cenarios.excalidraw): o que acontece (cenários 1 a 4)
- demo opcional: overlays `sem-protocolo`, `deadlock` e `normal`

Convenção: **🗣️** é o que falar; **👉** é onde apontar no diagrama.

---

## 0. Abertura: o problema (30 s)

🗣️ "Temos 7 processos que querem escrever no mesmo recurso, e só um pode estar lá por vez. Isso é **exclusão mútua**. A dificuldade é que não existe coordenador: ninguém manda em ninguém, e os processos só trocam mensagens. O algoritmo que usamos para resolver isso é o de **Maekawa**."

---

## 1. Origem: quem criou e o que mudou (1 min 30 s)

🗣️ "O algoritmo é de **Mamoru Maekawa**, da Universidade de Tóquio, publicado em **1985** na *ACM Transactions on Computer Systems*, com o título *'A √N Algorithm for Mutual Exclusion in Decentralized Systems'*. O título já diz a contribuição: **√N**."

🗣️ "Antes dele, as soluções eram:"

| Ano | Algoritmo | Mensagens por acesso | Problema |
|---|---|---|---|
| — | Centralizado | 3 | um coordenador: ponto único de falha e gargalo |
| 1978 | Lamport | 3(N−1) | pede a todos |
| 1981 | Ricart-Agrawala | 2(N−1) | ainda pede a todos |
| **1985** | **Maekawa** | **~3√N** | pede só a um grupo |

🗣️ "Todos os algoritmos distribuídos partiam da ideia de que **todos precisavam concordar**. O Maekawa inovou em quatro pontos:"

1. **Pedir a um grupo, não a todos.** Basta que quaisquer dois grupos tenham alguém em comum: esse alguém vira o árbitro.
2. **Grupos pequenos e simétricos.** Com geometria finita (planos projetivos), cada grupo tem ≈ √N nós, e todo nó aparece no mesmo número de grupos. Ninguém trabalha mais que ninguém.
3. **Voto no lugar de permissão.** Cada nó tem **um voto** e o empresta a **um pedido de cada vez**.
4. **Tratamento de deadlock.** Votar em um por vez pode gerar espera circular; ele resolveu isso com prioridade pelo relógio de Lamport e as mensagens FAILED, INQUIRE e RELINQUISH.

🗣️ "Essa ideia de **quorum que se cruza** foi muito além da exclusão mútua: é a base da replicação em bancos como Cassandra e Dynamo e do consenso no Paxos e no Raft, onde a maioria é um quorum que sempre se cruza."

🗣️ Transição: "Vamos ver como esses grupos ficam com 7 processos."

---

## 2. Topologia: quem fala com quem

📄 [maekawa-topologia.excalidraw](maekawa-topologia.excalidraw)

### 2.1 Cada nó tem um grupo (1 min 30 s)

👉 Subtítulo: N = 7, K = 3.

🗣️ "Com 7 processos usamos o **plano de Fano**, o menor plano projetivo: 7 grupos de 3 nós. Note que 3 ≈ √7."

👉 Quadro **S0**.

🗣️ "Cada nó tem uma lista de 3 'fiscais' que precisam autorizar a entrada dele. Esse é o grupo do p0: ele precisa do voto de p0, p1 e p2. O p0 em amarelo é quem pede. Os cinzas nem ficam sabendo do pedido."

👉 Passe por S1 … S6.

🗣️ "Todo grupo tem o mesmo formato, só girado. Nenhum nó é especial."

### 2.2 Pedir × votar (1 min)

🗣️ "Cada nó tem dois papéis. Vamos usar o p1:"

- "Como **dono**, o p1 pede voto ao grupo dele: S1 = {p1, p3, p5}."
- "Como **fiscal**, o p1 aparece nos grupos S0, S1 e S6. Então ele só vota nos donos desses grupos: **p0, p1 e p6**."

👉 Quadros S0, S1 e S6 (p1 colorido) e depois o quadro **Todos sobrepostos**.

🗣️ "Atenção à direção: o p3 está no grupo do p1, então o p1 **pede** ao p3. Mas o p1 não está no grupo do p3, então o p1 **nunca vota** no p3. Cada nó pede a 3 e vota para 3: a carga é igual para todos."

| Nó | Pede a (Si) | Vota em |
|---|---|---|
| p0 | p0, p1, p2 | p0, p3, p5 |
| p1 | p1, p3, p5 | p0, p1, p6 |
| p2 | p2, p3, p6 | p0, p2, p4 |
| p3 | p0, p3, p4 | p1, p2, p3 |
| p4 | p2, p4, p5 | p3, p4, p6 |
| p5 | p0, p5, p6 | p1, p4, p5 |
| p6 | p1, p4, p6 | p2, p5, p6 |

### 2.3 Matriz de interseções: a garantia (1 min 30 s)

👉 Matriz **Interseções: Si ∩ Sj**.

🗣️ "Essa tabela compara os grupos dois a dois. Na linha fica um grupo, na coluna outro, e na célula o nó que os dois têm em comum."

👉 Célula vermelha **S0 × S6 = p1**.

🗣️ "S0 tem p0, p1 e p2. S6 tem p1, p4 e p6. Em comum: o **p1**. Ele tem um único voto e o empresta a **um de cada vez**. Se p0 e p6 pedirem juntos, o p1 vota em um e o outro espera."

🗣️ "E o mais importante: **nenhuma célula está vazia**. Quaisquer dois grupos sempre têm um fiscal em comum. Por isso dois nós nunca entram juntos no recurso. **Essa é a garantia de exclusão mútua.**"

### 2.4 Custo (1 min)

👉 Caixa verde **Custo por acesso**.

🗣️ "No Ricart-Agrawala, o p0 pergunta aos 6 outros e espera 6 respostas: **12 mensagens**. No Maekawa, ele só fala com o grupo dele: 3 REQUEST, 3 LOCKED e 3 RELEASE, no máximo **9**. Como o voto dele em si mesmo não passa pela rede, na prática são 6."

🗣️ "Com 7 nós a diferença é pequena. Mas o grupo cresce com a raiz de N:"

| N | K | Maekawa (3K) | Ricart-Agrawala (2(N−1)) |
|---|---|---|---|
| 7 | 3 | 9 | 12 |
| 31 | 6 | 18 | 60 |
| ~1000 | ~32 | ~96 | ~2000 |

🗣️ Transição: "Agora vamos ver o algoritmo funcionando."

---

## 3. Cenários: o que acontece

📄 [maekawa-cenarios.excalidraw](maekawa-cenarios.excalidraw)

### 3.1 Cenário 1: sem disputa (1 min)

👉 Siga ① a ⑥.

🗣️ "O cliente pede (①), o p0 manda REQUEST ao grupo (②), os três estão livres e respondem LOCKED (③), o p0 tem 3 de 3 e entra (④). Ao sair, manda RELEASE (⑤) e responde ao cliente (⑥)."

👉 Caixa **Fora do quorum**.

🗣️ "p3 a p6 nem ficam sabendo. Esse é o custo de √N na prática."

### 3.2 Cenário 2: voto ocupado (1 min 30 s): a matriz na prática

🗣️ "Lembram do S0 ∩ S6 = p1? Aqui ele trabalha."

🗣️ "O p6 está no recurso, com o voto do p1. O p0 pede: o p2 vota, mas o p1 já emprestou o voto, e o pedido do p0 vai para a fila. O p6 sai e manda RELEASE, o p1 passa o voto para o p0, e o p0 entra."

🗣️ "**O p0 nunca fala com o p6.** Quem impede os dois de entrarem juntos é o p1, o fiscal em comum."

### 3.3 Cenário 3: deadlock na versão básica (1 min 30 s): o problema

🗣️ "Só com REQUEST, LOCKED e RELEASE existe um problema. O p0, o p1 e o p2 pedem ao mesmo tempo, e os votos se dividem:"

- "o p0 tem {p0, p2} e **espera o p1**;"
- "o p1 tem {p1, p5} e **espera o p3**, que votou no p2;"
- "o p2 tem {p3, p6} e **espera o p2**, que votou no p0."

🗣️ "Cada um tem 2 de 3 e segura o voto que o outro precisa. Ninguém solta. Ninguém entrou junto, então a exclusão mútua continua garantida, mas o sistema **para**, sem nenhuma falha e sem perda de mensagem."

### 3.4 Cenário 4: prioridade (2 min): a solução

**Primeiro, o relógio de Lamport (30 s):**

🗣️ "Para decidir quem tem prioridade, cada processo mantém um **relógio de Lamport**. Não é um relógio de verdade, é um contador com duas regras:"

- "**antes de enviar**, soma 1: `relógio = relógio + 1`;"
- "**ao receber**, pega o maior e soma 1: `relógio = max(local, recebido) + 1`."

🗣️ "Assim, se um evento causou outro, o primeiro sempre tem número menor."

**Depois, a prioridade (30 s):**

🗣️ "Cada pedido leva o par **(relógio, id)**. O menor relógio ganha, e no empate ganha o menor id. Assim nunca há empate. Aqui os três começam em 0 e pedem juntos, então todos ficam com 1:"

| Pedido | Prioridade | |
|---|---|---|
| p0 | (1, 0) | ganha |
| p1 | (1, 1) | segundo |
| p2 | (1, 2) | terceiro |

**Por fim, as mensagens novas (1 min):**

👉 Siga ① a ⑤ da caixa verde.

🗣️ "Um fiscal recebe o pedido do p0, que é melhor que o do dono atual do voto. Ele pergunta 'já entrou?' (**INQUIRE**). O dono ainda não entrou e responde 'não, devolvo' (**RELINQUISH**). O voto vai para o p0, que fica com 3 de 3 e entra. Quando sai, entram o p1 e depois o p2."

🗣️ "Quem tem prioridade pior recebe **FAILED**: sabe que vai esperar e, por isso, aceita devolver votos quando alguém perguntar. O ciclo se quebra."

🗣️ "E não há starvation: o relógio só cresce, então quem acabou de entrar volta para o fim da fila, e quem espera há mais tempo acaba tendo a melhor prioridade."

---

## 4. Demo (opcional, 1 a 2 min)

| Overlay | O que mostrar | Liga com |
|---|---|---|
| `sem-protocolo` | o observador acusa **VIOLATION** (mais de um processo no recurso) | por que precisamos do algoritmo |
| `deadlock` | sem violações, mas o sistema trava | Cenário 3 |
| `normal` | zero violações, todos os pedidos COMMITTED | Cenário 4 |

Para ligar e desligar o Maekawa ao vivo, use `POST /mode` com `{"sync_enabled": false}` e mostre as violações aparecendo e sumindo.

---

## 5. Fechamento (30 s)

🗣️ "Em 1985, o Maekawa mostrou que não é preciso pedir a todos. Três ideias resumem o algoritmo:"

1. "**grupos que sempre se cruzam** garantem a exclusão mútua;"
2. "**um voto por fiscal, emprestado a um de cada vez**, faz a interseção funcionar como árbitro;"
3. "**prioridade com relógio de Lamport**, mais INQUIRE e RELINQUISH, desfaz o deadlock."

🗣️ "Tudo isso falando com 3 nós, e não com 7."

---

## Perguntas prováveis

**O p1 pode votar no p3?**
Não. O p3 está no grupo do p1, então é o p1 que **pede** ao p3. O p1 só vota em quem tem ele no grupo: p0, p1 e p6.

**"Voto único" quer dizer que vota uma vez só?**
Não. É **um por vez**: empresta (LOCKED), recebe de volta (RELEASE ou RELINQUISH) e empresta de novo.

**Por que o nó vota em si mesmo?**
Porque Si sempre contém pi. Assim, um pedido de outro grupo que passa por pi também disputa com o próprio pi.

**E se um fiscal cair?**
Quem depende do voto dele trava. O Maekawa clássico não tolera falhas de processo.

**Por que não pedir à maioria?**
A maioria também se cruza, mas exige 4 de 7. Com o Fano bastam 3, que é o mínimo para N = 7, e a carga fica igual para todos.

**Quantas mensagens com disputa?**
Entram FAILED, INQUIRE e RELINQUISH, e o custo chega a ~5√N. Continua crescendo com a raiz de N, bem abaixo dos 2(N−1) do Ricart-Agrawala.

**Por que o relógio de Lamport e não o relógio do sistema?**
Os relógios físicos das máquinas não estão sincronizados. O de Lamport garante uma ordem coerente com a causalidade usando só as mensagens, e o id desempata.

---

## Referências

- M. Maekawa. *A √N Algorithm for Mutual Exclusion in Decentralized Systems*. ACM Transactions on Computer Systems, 3(2):145–159, 1985.
- G. Ricart, A. K. Agrawala. *An Optimal Algorithm for Mutual Exclusion in Computer Networks*. Communications of the ACM, 24(1):9–17, 1981.
- L. Lamport. *Time, Clocks, and the Ordering of Events in a Distributed System*. Communications of the ACM, 21(7):558–565, 1978.
