# Plano — TP2 BCC362: Maekawa (A6)

**Disciplina:** BCC362 — Sistemas Distribuídos  
**Professor:** Joubert Lima  
**Opção:** A6 Maekawa (trilha A, avançado)  
**Referência-base:** Maekawa, 1985. *A √N Algorithm for Mutual Exclusion in Decentralized Systems.* https://doi.org/10.1145/214438.214445  
**Infraestrutura:** processos em contêineres, hospedados na Azure

Grupo de até 3 alunos. Este documento é o plano de execução: o que o enunciado cobra, como o protocolo será implementado e como a infraestrutura sobe na Azure.

---

## 1. Objetivo

Construir um Cluster Sync em que processos independentes sincronizam o acesso de escrita a um recurso hipotético **R** pelo algoritmo de Maekawa.

A exclusão mútua não fica num coordenador fixo. Cada processo pede permissão apenas ao seu **conjunto de votação**. Como esses conjuntos se intersectam par a par e cada votante concede no máximo uma permissão por vez, dois processos não entram juntos em R.

O trabalho entrega duas coisas distintas:

1. A **versão básica** (REQUEST, LOCKED, RELEASE), com um cenário determinístico em que ela entra em deadlock mesmo sem falhas nem perda de mensagens.
2. Uma **variante documentada de resolução**, com as mensagens adicionais FAILED, INQUIRE e RELINQUISH e prioridade por relógio lógico de Lamport. É essa variante que roda nos experimentos do cenário comum.

O enunciado assume participantes disponíveis e canais sem perda. Não há cenário de falha, failover nem replicação.

---

## 2. O que o enunciado cobra desta opção

| Exigência | Como atendemos |
|---|---|
| Conjuntos de votação com interseção par a par | Plano projetivo de ordem 2 (plano de Fano), N = 7, cada conjunto com 3 processos |
| Cada votante concede no máximo uma permissão por vez | Estado `voted` por nó; segundo pedido entra na fila ou dispara a resolução |
| Apresentar os conjuntos e justificar a exclusão mútua | Seção 4 deste plano, repetida nos slides com um par concreto de pedidos |
| Mostrar deadlock da versão básica, sem falhas | Cenário reproduzível com espera circular de votos (seção 6) |
| Variante documentada de prevenção ou resolução, com mensagens extras | Resolução do próprio artigo: FAILED, INQUIRE, RELINQUISH |
| 7 ou 9 nós, se facilitar a construção | 7 nós do Cluster Sync |
| `acquire(R, request_id)` e `release(R, request_id)` separados da seção crítica | A seção crítica é só o `sleep` aleatório de 0,2 a 1 s |
| Pelo menos 5 clientes, cada um conhecendo um único elemento do cluster | 7 clientes, um por nó (acima do mínimo de 5) |
| 10 a 50 acessos de escrita por cliente | Parâmetro do gerador de carga; padrão 20 na carga normal |
| `request_id` único `(client_id, seq)` | Relógio físico só aparece no log, nunca como critério de ordem |
| Logs REQUEST, ACQUIRE, ENTER, EXIT, RELEASE, COMMITTED | JSON Lines, com relógio lógico e conjunto de votos já obtidos |
| Observador de BEGIN/END que só detecta sobreposição | Processo à parte; não autoriza e não bloqueia ninguém |
| Uma execução com a sincronização desligada | Mesma instrumentação, protocolo desabilitado por configuração |
| Carga normal e alta contenção, pelo menos 3 repetições cada | Mais carga desigual |
| Código, README, versões, contêineres, scripts, logs, verificador e 10–15 slides | Seção 11 |

Extensões da seção 6 do enunciado (barreira, semáforo, leitores/escritores) são opcionais na trilha A. O aprofundamento obrigatório do A6 é a variante de deadlock. Não vamos somar outra primitiva, para o seminário caber em 25 minutos.

Comparar 5 e 10 processos do Cluster Sync não cabe numa construção simétrica de Maekawa: o plano de Fano pede N = 7. A discussão de crescimento usa o custo teórico 3√N a 6√N e, se der tempo, uma segunda malha com N = 13 (plano projetivo de ordem 3, quórum de tamanho 4). Isso fica como experimento extra, fora do caminho crítico.

---

## 3. Decisões já tomadas

| Decisão | Escolha | Motivo |
|---|---|---|
| Nós do Cluster Sync | 7 | Quórum simétrico de tamanho 3; qualquer par de conjuntos se intersecta |
| Clientes | 7, um por nó | Atende o mínimo de 5 e deixa a carga desigual fácil de explicar |
| Variante | Resolução por FAILED / INQUIRE / RELINQUISH | É a variante do artigo [5], com mensagens adicionais explícitas |
| Prioridade | Relógio de Lamport e, no empate, menor ID de processo | Critério total; necessário para a resolução não ciclar |
| Linguagem | Python 3.12, `asyncio` | Qualquer linguagem vale; Python deixa a máquina de estados legível no seminário |
| Transporte | TCP entre contêineres, mensagens JSON por linha | Biblioteca de comunicação é permitida; o lock distribuído pronto não |
| Empacotamento | Docker Compose | Um processo por contêiner, rede entre eles |
| Nuvem | Azure for Students: grupo de recursos, rede virtual, NSG e uma VM Linux com Docker | Cabe no crédito e nas horas gratuitas; a VM fica desalocada fora dos experimentos |
| Tamanho da VM | `Standard_B2ats_v2` (2 vCPU, 1 GiB, x64) | É um dos tamanhos com 750 horas gratuitas por mês. `Standard_B2s` não entra nessa cota e, na prática, a assinatura de estudante recusa |
| IaC | Bicep + cloud-init | Reprodutibilidade entra na rubrica (15%) |
| Região | A primeira região em que a política da assinatura e o SKU coincidirem | `brazilsouth` só vale se o portal listar a região e o tamanho como disponíveis |

Mutex local só protege as estruturas do próprio processo. Ele não decide quem entra em R.

---

## 4. Conjuntos de votação

Há 7 processos, `p0` … `p6`. Cada conjunto tem 3 membros, cada processo pertence a 3 conjuntos, e a interseção de qualquer par de conjuntos tem exatamente um processo. É o plano de Fano.

| Processo | Conjunto Si |
|---|---|
| p0 | {p0, p1, p2} |
| p1 | {p1, p3, p5} |
| p2 | {p2, p3, p6} |
| p3 | {p0, p3, p4} |
| p4 | {p2, p4, p5} |
| p5 | {p0, p5, p6} |
| p6 | {p1, p4, p6} |

Propriedades usadas na justificativa:

1. **Interseção par a par.** Se dois processos pudessem estar ao mesmo tempo na seção crítica, cada um teria trancado todos os membros do próprio conjunto. O processo que está na interseção teria concedido duas permissões ao mesmo tempo, o que o protocolo proíbe.
2. **O próprio processo vota em si.** `pi ∈ Si`, então ninguém entra só com votos alheios.
3. **Tamanho constante K = 3.** O custo de uma entrada sem contenção é da ordem de √N, não de N−1 como em Ricart–Agrawala. Para N = 7, √7 ≈ 2,65 e K = 3.
4. **Carga equilibrada.** Cada processo recebe pedidos de exatamente 3 conjuntos.

Exemplo para o seminário: p0 precisa de p0, p1 e p2; p2 precisa de p2, p3 e p6. Os dois conjuntos se cruzam em p2. Se p2 já votou em um, o outro espera. Logo os dois não ocupam R juntos.

Um teste automatizado confere, na inicialização, que todo par de conjuntos tem interseção não vazia, que todo conjunto tem tamanho 3 e que cada processo pertence ao próprio conjunto. O teste falha o processo se a tabela for editada de forma inválida.

---

## 5. Protocolo

O fluxo completo de um acesso, os estados de pedinte e votante e o deadlock da versão básica estão em [docs/diagrama-processo.md](docs/diagrama-processo.md). A versão para abrir no navegador é [docs/diagrama-processo.html](docs/diagrama-processo.html).

### 5.1 Versão básica

Mensagens: `REQUEST`, `LOCKED`, `RELEASE`.

Para o cliente `c` com `request_id = (c, seq)`, o nó dono daquele cliente executa:

1. `acquire(R, request_id)` envia `REQUEST` aos 3 membros de Si e espera `LOCKED` dos 3.
2. Quem recebe `REQUEST` e ainda não votou responde `LOCKED` e marca `voted = true`, guardando o `request_id`.
3. Quem já votou enfileira o pedido. Não há preempção.
4. Com 3 votos, o nó registra ENTER, dorme de 0,2 a 1 s (seção crítica simulada) e registra EXIT.
5. `release(R, request_id)` envia `RELEASE` aos 3 membros.
6. Quem recebe `RELEASE` vota no próximo da fila, ou zera `voted` se a fila estiver vazia.
7. O nó responde `COMMITTED` ao cliente. Isso confirma o fim do acesso simulado, não persistência.

Pedidos que chegam ao mesmo processo são tratados em série por uma fila. O relógio de Lamport é atualizado em todo envio e todo recebimento, mesmo na versão básica, para o log já nascer comparável.

### 5.2 Por que a versão básica entra em deadlock

Cada votante segura no máximo um voto. Dois (ou mais) pedidos podem obter subconjuntos disjuntos dos votos de que precisam e esperar um pelo outro. Não há falha de processo nem perda de mensagem: a espera circular é só da ordem de chegada.

Cenário que o gerador de deadlock força, segurando os votos até o ciclo fechar. Os três conjuntos se cruzam em p1, p2 e p3:

- p0 pede S0 = {p0, p1, p2} e já tem LOCKED de p0 e p2. Espera p1.
- p1 pede S1 = {p1, p3, p5} e já tem LOCKED de p1 e p5. Espera p3.
- p2 pede S2 = {p2, p3, p6} e já tem LOCKED de p3 e p6. Espera p2.

p1 votou no pedido de p1, p3 votou no pedido de p2 e p2 votou no pedido de p0. Ninguém completa o quórum, então ninguém envia RELEASE.

O script não depende de sorte: ele segura o voto de processos escolhidos até a espera circular estar formada e só então libera o escalonador. A execução tem timeout (por exemplo 15 s). Ao estourar, o log mostra cada votante com `voted = true` e cada pedinte com o conjunto de votos ainda incompleto. Essa execução é evidência, não a carga oficial.

### 5.3 Variante de resolução

A variante implementada é a do artigo de Maekawa: além de REQUEST, LOCKED e RELEASE, o protocolo usa **FAILED**, **INQUIRE** e **RELINQUISH**. A prioridade de um pedido é o par `(relógio lógico, id do processo)`. O menor par ganha.

Regras adicionais, no votante que já concedeu o voto a um pedido `atual` e recebe `REQUEST` de outro pedido `novo`:

- Se `novo` tem prioridade **pior** que `atual`, o votante responde **FAILED**. O pedinte sabe que esse voto não vem agora e pode ceder os votos que já reuniu.
- Se `novo` tem prioridade **melhor** que `atual`, o votante envia **INQUIRE** a quem está com o voto.
- Quem recebe INQUIRE e **ainda não está** na seção crítica (não reuniu os K votos, ou já recebeu FAILED de alguém) responde **RELINQUISH** e descarta aquele LOCKED.
- Quem recebe INQUIRE e **já entrou** ou **já tem os K votos** não cede. O voto volta só no RELEASE, ao sair da seção crítica.
- Quem recebe RELINQUISH marca o voto como livre e concede LOCKED ao pedido de maior prioridade na fila.

Assim a espera deixa de ser circular: um pedido de pior prioridade devolve votos, e o de melhor prioridade consegue o quórum. A seção crítica, uma vez iniciada, termina. Não há preempção no meio do `sleep`.

Mensagens por entrada, com K = 3:

| Situação | Mensagens de protocolo |
|---|---|
| Sem disputa | REQUEST + LOCKED + RELEASE = 3K = 9 |
| Com um voto indagado e devolvido | Somam-se INQUIRE, RELINQUISH e um novo LOCKED (até mais 3 por voto em disputa) |
| Teto usado na discussão | Entre 3√N e 6√N, como no artigo; para N = 7 isso é cerca de 9 a 18 mensagens de protocolo por entrada |

O log separa mensagem de protocolo de mensagem de instrumentação (BEGIN/END para o observador). A comparação com a teoria usa só as primeiras.

Antes de codar, o grupo lê a seção de deadlock do artigo e fixa o pseudocódigo em `docs/protocolo.md`, seguindo os estados de [docs/diagrama-processo.md](docs/diagrama-processo.md). A implementação segue esse arquivo. Ponto de atenção conhecido na literatura: a resolução original tem casos limite sutis; o teste de deadlock da seção 6.2 e a suíte de disputas concorrentes são a rede de segurança. Se um caso limite da resolução original travar de forma reproduzível, a referência de apoio é Sanders (1987), *The Information Structure of Distributed Mutual Exclusion Algorithms*, sem trocar o algoritmo no meio dos experimentos sem registrar a troca.

---

## 6. Arquitetura da aplicação

```
cliente-i  --TCP-->  nó pi do Cluster Sync  --TCP-->  membros de Si
                              |
                              +-- BEGIN/END --> observador
```

- **Cliente.** Gera `request_id = (client_id, seq)`, envia o pedido só ao seu nó, espera `COMMITTED`, dorme de 1 a 5 s e repete. Não fala com o restante do cluster.
- **Nó do Cluster Sync.** Implementa `acquire` e `release`, a máquina de estados de Maekawa e o `sleep` da seção crítica. Também vota nos pedidos dos outros conjuntos de que participa.
- **Observador.** Recebe BEGIN antes do `sleep` e END depois. Mantém a contagem de acessos ativos a R. Se a contagem passa de 1, grava uma violação. Não concede permissão, não atrasa BEGIN de outro processo e não é um mutex.

Operações equivalentes no código:

```text
acquire(R, request_id)   # bloqueia até o quórum
enter(R, request_id)     # BEGIN + sleep(0.2 .. 1.0)
release(R, request_id)   # EXIT + RELEASE aos votantes
# só então COMMITTED ao cliente
```

Com `SYNC_ENABLED=false`, `acquire` e `release` viram no-op e o `sleep` ainda ocorre. O observador deve acusar sobreposição. Essa execução vem antes da execução com o protocolo ligado, para mostrar que a instrumentação funciona.

### 6.1 O que cada log carrega

Uma linha JSON por evento, no mínimo:

- `event`: REQUEST, LOCKED, FAILED, INQUIRE, RELINQUISH, RELEASE, ACQUIRE, ENTER, EXIT, COMMITTED, BEGIN, END
- `request_id`: `[client_id, seq]`
- `process_id`
- `lamport`
- `quorum_granted`: lista de votantes que já responderam LOCKED e não foram devolvidos
- `ts_monotonic_ns`: relógio monotônico local, usado só para medir espera **no próprio processo**
- `wall_clock`: informativo, nunca usado para comparar hosts

A espera para aquisição é o intervalo, no nó, entre o início de `acquire` e o momento em que os K votos estão reunidos.

---

## 7. Infraestrutura na Azure

A aplicação inteira sobe na Azure. O enunciado aceita contêineres no mesmo computador; usamos isso a favor da reprodutibilidade: uma VM Linux executa o Compose, e cada papel (7 nós, 7 clientes, 1 observador) é um contêiner próprio. Eles se falam pela rede bridge do Docker, por TCP, não por memória compartilhada. Isso satisfaz a regra de processos independentes e evita o custo de oito máquinas virtuais para um protocolo que, neste TP, não avalia falha nem latência de WAN.

### 7.1 Recursos

| Recurso | Nome previsto | Função |
|---|---|---|
| Resource group | `rg-bcc362-tp2-maekawa` | Agrupa tudo e permite apagar o experimento de uma vez |
| Virtual network | `vnet-maekawa` / `snet-app` | Rede da VM |
| Network security group | `nsg-maekawa` | SSH (22) apenas para os IPs do grupo; sem porta da aplicação exposta na internet |
| IP público | `pip-maekawa` | Acesso SSH para subir o experimento e recolher logs |
| VM | `vm-maekawa` | Ubuntu 24.04 LTS, tamanho `Standard_B2ats_v2` (2 vCPU, 1 GiB, AMD x64) |
| Disco | OS disk 64 GiB Standard SSD (P6) | Entra na franquia da VM gratuita |
| Identidade | usuário SSH com chave | Sem senha; a chave não entra no repositório |

Região: a que a assinatura permitir e na qual `Standard_B2ats_v2` aparecer sem `NotAvailableForSubscription`. Isso se descobre antes do Bicep, no portal em Policy → Assignments → *Allowed resource deployment regions*, e no CLI com `az vm list-skus`.

`Standard_B2pts_v2` também tem horas gratuitas, mas é ARM. Ficamos no `B2ats_v2` para a imagem Python/Docker continuar x64.

O cloud-init da VM instala Docker Engine e o plugin Compose. O deploy copia o repositório (ou faz pull) e sobe o Compose. Não há Kubernetes: a instalação do orquestrador não conta como solução de sincronização, e aqui ele não acrescenta nada ao protocolo.

Portas dos processos ficam só na rede Docker (`sync-0:7000` … `sync-6:7006`, `observer:7100`). O NSG não as publica.

### 7.2 Azure for Students

A assinatura é a Azure for Students (US$ 100 por 12 meses, sem cartão), não a Azure for Students Starter. A oferta publicada inclui, por mês, 750 horas de `B1s`, de `B2ats_v2` e de `B2pts_v2`, com disco P6 de 64 GiB. A assinatura não aceita aumento de cota.

O que isso muda na prática:

- Uma VM `B2ats_v2` ligada o mês inteiro cabe nas 750 horas. Mesmo assim ela é desalocada fora de experimento e ensaio (`az vm deallocate`).
- O IP público Standard continua debitando alguns dólares por mês enquanto existir. O resource group é apagado depois da entrega.
- `brazilsouth` e o tamanho `Standard_B2s` (4 GiB) não são o alvo. Em conta de estudante esse tamanho costuma voltar `NotAvailableForSubscription`, e `brazilsouth` muitas vezes nem lista o SKU gratuito.
- Com 1 GiB, os 15 contêineres só cabem com imagem `python:3.12-slim`, sem pacote sobrando, e 1 GiB de swap na VM. Os sete nós e o observador ficam de pé; os clientes sobem na hora da carga e encerram ao terminar.
- Se no portal, numa região permitida, existir um tamanho de 4 GiB sem restrição, ele passa a ser a VM do trabalho e o custo sai dos US$ 100. Dezenas de horas de experimento ficam na casa de poucos dólares, desde que a máquina não fique ligada à toa.

Nada de credencial, connection string ou chave privada no git. O `.gitignore` cobre `*.pem`, `.env` e `logs/`.

### 7.3 Arquivos de infra

```text
infra/
  main.bicep          # RG, VNet, NSG, IP, NIC, VM, cloud-init
  parameters.json     # região, tamanho, usuário; sem segredos
  README.md           # az deployment group create / deallocate / delete
deploy/
  docker-compose.yml  # 7 sync, 7 clients, 1 observer
  Dockerfile
scripts/
  up.sh               # sobe a VM se estiver desalocada e faz deploy
  run-experiment.sh   # dispara uma configuração e recolhe logs
  down.sh             # deallocate
```

Comandos de referência, a serem cravados no README com a versão do Azure CLI usada:

```bash
az deployment group create \
  --resource-group rg-bcc362-tp2-maekawa \
  --template-file infra/main.bicep \
  --parameters infra/parameters.json

az vm deallocate --resource-group rg-bcc362-tp2-maekawa --name vm-maekawa
```

A primeira subida inclui: criar o resource group, aplicar o Bicep, conferir SSH, conferir `docker compose ps` com 15 contêineres saudáveis (7 nós + 7 clientes + observador) e um ping de aplicação de cada cliente ao seu nó.

### 7.4 O que a Azure não é neste trabalho

A VM é o lugar onde os processos rodam. O mecanismo de exclusão mútua é o Maekawa implementado pelo grupo. Não usamos lock do Azure, Blob lease, Service Bus nem Cosmos como primitiva de sincronização. Isso misturaria a trilha A com a trilha B.

---

## 8. Experimentos

Sementes aleatórias, duração da seção crítica, intervalo entre pedidos e versão do protocolo ficam no cabeçalho de cada rodada. Cada repetição grava um diretório `logs/<experimento>/<rep>/`.

| Experimento | Configuração | O que se avalia |
|---|---|---|
| Instrumentação sem protocolo | `SYNC_ENABLED=false`, poucos pedidos simultâneos | O observador registra pelo menos uma sobreposição. Sem essa evidência, o verificador das rodadas seguintes não vale |
| Deadlock da versão básica | Escalona o cenário da seção 5.2, timeout 15 s | Log mostra espera circular e votos retidos. A rodada termina no timeout, de propósito |
| Carga normal × 3 | 7 clientes, 20 acessos cada (dentro de 10–50), sleep de cliente 1–5 s, seção crítica 0,2–1 s, variante ligada | Zero sobreposição; todos os pedidos em COMMITTED |
| Alta contenção × 3 | Igual à carga normal, sem o sleep de 1–5 s entre pedidos; clientes disparados juntos | Disputa real; aparecem FAILED, INQUIRE e RELINQUISH |
| Carga desigual × 1 ou 2 | Um cliente pede com intervalo mínimo; os outros no intervalo normal | Distribuição de acessos e espera; discussão de justiça, sem tratar uma execução curta como prova de ausência de starvation |

Métricas, por repetição:

- pedidos concluídos e pedidos que não chegaram a COMMITTED
- tempo total da rodada
- espera mediana (e máximo) até a aquisição, medida com relógio monotônico no processo
- acessos concedidos por cliente
- mensagens de protocolo por entrada, separadas das mensagens de instrumentação
- sobreposições acusadas pelo observador (esperado: zero com o protocolo ligado)

O verificador automático lê o log do observador e falha se a contagem de acessos ativos a R passar de 1, ou se algum `request_id` ficar sem COMMITTED dentro do tempo da rodada. Também confere que, na rodada sem protocolo, existe ao menos uma violação. Assim a ferramenta não “passa” por estar cega.

Interpretação que os slides precisam sustentar, com as garantias desta opção:

- **Exclusão mútua:** interseção dos quóruns mais um voto por vez.
- **Progresso:** a resolução por prioridade desfaz a espera circular da versão básica; a seção crítica tem duração finita (o sleep).
- **Deadlock:** existe na versão básica, mesmo sem falhas; a variante documentada é o que os experimentos usam.
- **Starvation:** uma rodada curta sem inanição é observação, não prova. A prioridade por Lamport favorece o pedido mais antigo, o que é o argumento de justiça que vamos apresentar, deixando claro o alcance dele.

Não comparamos tempos entre rodadas com seção crítica ou carga diferentes.

---

## 9. Seminário (25 min) e slides (10 a 15)

Sem contar slides de referência e de log.

| Tempo | Slides | Conteúdo |
|---|---|---|
| 3 min | 2 | R, exclusão mútua, por que não há coordenador fixo |
| 6 min | 4–5 | Conjuntos do plano de Fano, uma disputa p0 × p5 cruzando em p2, estados, as 6 mensagens, o ciclo da versão básica e como INQUIRE/RELINQUISH o desfaz |
| 8 min | 3 | Ao vivo na VM Azure: rodada curta sem protocolo (violação), cenário de deadlock, rodada com a variante e o observador em zero sobreposições |
| 3 min | 2–3 | Espera mediana, mensagens por entrada contra 3√N–6√N, justiça na carga desigual, limite do experimento |
| 5 min | — | Perguntas. Os três integrantes falam ao longo da apresentação, não só na abertura |

A demonstração usa a VM já ligada e os contêineres de pé antes da aula. O ensaio mede os 8 minutos com a rede da universidade em mente: se a VM estiver inacessível, entra um vídeo curto gravado da mesma execução, e os logs ficam no repositório.

---

## 10. Divisão de trabalho

Três frentes, com integração no meio. Os nomes entram quando o grupo fechar.

| Frente | Entrega |
|---|---|
| Protocolo | Máquina de estados, relógio de Lamport, quóruns, testes do deadlock e da resolução |
| Malha e Azure | Bicep, Compose, cloud-init, scripts de carga, coleta de logs |
| Evidência | Observador, verificador, roteiro dos experimentos, números e slides |

Todo integrante precisa conseguir explicar quórum, deadlock e a variante. A rubrica de apresentação cobra participação de todos.

---

## 11. Entregáveis

- Código-fonte com `acquire` / `release` separados do sleep
- `docs/protocolo.md` com os conjuntos, a versão básica e a variante
- README: versões (Python, Docker, Azure CLI, imagem Ubuntu), como criar a infra, como rodar, como desligar a VM
- `infra/` Bicep e `deploy/docker-compose.yml`
- Scripts de carga normal, alta contenção, carga desigual, deadlock e sincronização desligada
- Logs selecionados das três repetições e da execução sem protocolo
- Verificador das propriedades
- Slides de 10 a 15, mais referência e logs em apêndice
- Repositório sem credencial

Critérios do enunciado, para não perder de vista na reta final:

| Critério | Peso |
|---|---|
| Correção da sincronização e evidências | 30% |
| Domínio do algoritmo e explicação interna | 25% |
| Experimentos e interpretação | 20% |
| Reprodutibilidade, código e instrumentação | 15% |
| Apresentação e respostas | 10% |

---

## 12. Fases

| Fase | Resultado verificável |
|---|---|
| 1. Protocolo no papel | `docs/protocolo.md` revisado pelo grupo; tabela de quóruns testada |
| 2. Dois processos de mentira | `acquire` / `release` entre dois nós no Compose local, ainda sem os 7 |
| 3. Sete nós, versão básica | Carga pequena conclui quando não há ciclo; cenário da seção 5.2 estoura o timeout com o ciclo visível no log |
| 4. Variante | O mesmo cenário de deadlock termina com COMMITTED; suíte com vários pedidos concorrentes não sobrepõe R |
| 5. Azure | Resource group criado, VM no ar, `docker compose ps` saudável, um cliente recebe COMMITTED via SSH na VM |
| 6. Observador | Rodada com `SYNC_ENABLED=false` gera violação; rodada ligada gera zero |
| 7. Experimentos | 3 + 3 repetições mais carga desigual, verificador verde, tabela de métricas preenchida |
| 8. Empacote | README reproduz a subida do zero; slides ensaiados em 25 min; VM desalocada |

As fases 2 a 4 podem acontecer no Compose da máquina de desenvolvimento. A fase 5 repete a mesma composição na VM, sem mudar código de protocolo. A partir da fase 5, os números oficiais saem só da Azure, para a configuração do relatório ser uma só.

---

## 13. Riscos

| Risco | Resposta no plano |
|---|---|
| Resolução original travar num caso limite | Teste de disputa antes dos experimentos longos; se precisarmos do ajuste de Sanders (1987), isso é registrado no protocolo e nos slides |
| VM inacessível no dia do seminário | Ensaio com a VM ligada; vídeo e logs da mesma build como contingência |
| SKU ou região recusados na conta de estudante | Conferir a política de regiões e `az vm list-skus` antes do Bicep. Alvo: `Standard_B2ats_v2` numa região permitida. Sem pedido de aumento de cota |
| 1 GiB curto para 15 contêineres | Imagem slim, swap de 1 GiB, clientes só durante a carga. Se aparecer tamanho de 4 GiB disponível, usar esse e pagar com o crédito |
| Custo esquecido | `deallocate` no `down.sh`; apagar o resource group após a entrega, para o IP público não continuar debitando |
| Porta publicada sem necessidade | Aplicação só na rede Docker; NSG restringe o SSH aos IPs do grupo |
| Comparar N = 5 e N = 10 e quebrar o quórum simétrico | Não fazemos. N = 7 é a malha do trabalho; crescimento entra pela fórmula e, se houver folga, por N = 13 |
| Relógio de parede entre contêineres | Espera medida com relógio monotônico local; o observador usa BEGIN/END, não timestamps de hosts diferentes |

---

## 14. Referências

1. Maekawa, 1985. *A √N Algorithm for Mutual Exclusion in Decentralized Systems.* https://doi.org/10.1145/214438.214445 — artigo pedido pelo enunciado, referência [5].
2. Sanders, 1987. *The Information Structure of Distributed Mutual Exclusion Algorithms.* ACM TOCS. Apoio se a resolução original exigir um ajuste documentado.
3. Enunciado TP2, BCC362, professor Joubert Lima. Hipóteses do TP: sem queda e sem perda de mensagens; COMMITTED não significa persistência.
