# TP2 BCC362 — Exclusão mútua distribuída com Maekawa (A6)

Sete processos independentes (`sync-0` … `sync-6`) sincronizam o acesso de escrita a um recurso hipotético **R** pelo algoritmo de Maekawa (1985), com conjuntos de votação do plano de Fano (K = 3). Há duas versões:

- **básica** (`VARIANT=basic`): REQUEST, LOCKED, RELEASE — entra em deadlock com espera circular de votos, mesmo sem falha nem perda de mensagem;
- **resolução** (`VARIANT=resolution`, padrão): acrescenta FAILED, INQUIRE e RELINQUISH com prioridade por (relógio de Lamport, id), como no artigo.

Um **observador** registra BEGIN/END, confirma o registro (`BEGIN_ACK`/`END_ACK`) e acusa sobreposições; ele não concede permissão nem bloqueia ninguém. O nó espera o ACK do END antes de enviar RELEASE, para que o observador veja o fim de um acesso antes do início do próximo. Sete **clientes**, um por nó, geram `request_id = (client_id, seq)` e esperam COMMITTED.

O Maekawa pode ser **ligado e desligado em tempo de execução** pela API HTTP do observador (`GET`/`POST /mode`); veja [Ligar e desligar o Maekawa em execução](#ligar-e-desligar-o-maekawa-em-execução).

O Kubernetes só orquestra (nomes estáveis, subida, logs). Nenhum `Lease`, lock do etcd ou serviço de nuvem entra na sincronização.

Documentos: [plano](notes/plano-maekawa-a6.md) · [protocolo](docs/protocolo.md) · [estados do protocolo](docs/diagrama-processo.excalidraw) · [fluxo do código](docs/fluxo-implementacao.excalidraw) (abrir em excalidraw.com).

## Versões

| Ferramenta | Versão usada |
|---|---|
| Java | OpenJDK 21 (Temurin/Homebrew 21.0.13) |
| Maven | 3.9 |
| Jackson | 2.17.2 · JUnit 5.10.3 |
| Docker | 27+ (runtime `eclipse-temurin:21-jre-alpine`) |
| kubectl / kind | 1.30+ / 0.24+ |
| Terraform / OCI CLI | 1.5+ / opcional (Stop/Start); k3s na VM Ampere |

## Layout

```
src/main/java/br/ufop/bcc362/maekawa/
  Main.java                 ROLE=node|client|observer; tudo por variável de ambiente
  common/                   Message, RequestId, Priority, LamportClock, Transport (TCP e em memória), DeliveryGate, EventLog
  node/                     Quorum (Fano), Requester, Voter, Node (acquire/enter/release)
  client/Client.java        gera pedidos, espera COMMITTED
  observer/Observer.java    conta ativos em R, grava VIOLATION, guarda e anuncia o modo (épocas)
  observer/ObserverHttp.java API HTTP do frontend: /mode, /stats, /healthz
src/test/java/...           QuorumTest, PriorityTest, VoterTest, RequesterTest, DeadlockScenarioTest, ClusterContentionTest,
                            ModeSwitchTest, ObserverHttpTest
k8s/base                    namespace, ConfigMap, StatefulSet sync (7), Deployment observer, Job clients (7, indexado)
k8s/overlays/*              sem-protocolo · deadlock · normal · alta-contencao · desigual
k8s/observability           Grafana + Loki + Promtail (logs ao vivo; sem LoadBalancer)
viz/                        replay dos 7 nós a partir dos JSON Lines
infra/oci/                  Terraform Always Free (A1.Flex + VCN + NSG SSH) e cloud-init do k3s
scripts/                    local-run.sh · kind-up.sh · oci-*.sh · run-experiment.sh · verify.py · viz.sh · obs-up.sh
```

## Rodar

### Testes (sem rede, sem Docker)

```bash
mvn test
```

`DeadlockScenarioTest` liga os 7 nós por uma rede em memória: a versão básica trava com p0={p0,p2}, p1={p1,p5}, p2={p3,p6}; a variante conclui os três pedidos sem sobreposição.

### Processos locais em TCP (sem Kubernetes)

```bash
mvn -q -DskipTests package
scripts/local-run.sh 5 0 0                 # 7 nós + observador + 7 clientes, 5 acessos, sem pausa
python3 scripts/verify.py logs/local/<timestamp>
```

Portas: `localhost:7200..7206`, observador `:7300` e API HTTP do observador `:7380` (`BASE` muda a base; `HTTP_PORT` muda a da API; a 7000 é do AirPlay no macOS).

### Ligar e desligar o Maekawa em execução

`SYNC_ENABLED` é só o modo **inicial**. Com tudo rodando, o observador expõe uma API HTTP (porta 8080 no cluster; `HTTP_PORT=0` desliga) para o frontend de observabilidade:

| Rota | O que faz |
|---|---|
| `GET /mode` | modo atual, época, quantos nós confirmaram (`acked`/`nodes`), `settled` e a época de cada nó |
| `POST /mode` | corpo `{"sync_enabled": false}` (ou `?sync_enabled=false`): troca o modo e anuncia aos 7 nós |
| `GET /stats` | contadores do observador, no total e por época, mais o modo |
| `POST /reset` | zera os contadores (violações, BEGIN/END, máximo em R, tabela por época); devolve o mesmo que `/stats` |
| `GET /healthz` | `{"status":"ok"}` |

As respostas são JSON com `Access-Control-Allow-Origin` (`HTTP_CORS_ORIGIN`, padrão `*`).

```bash
kubectl -n maekawa port-forward svc/observer 8080:8080
curl -s localhost:8080/mode
curl -s -XPOST -H 'Content-Type: application/json' -d '{"sync_enabled": false}' localhost:8080/mode
curl -s localhost:8080/stats
```

Como funciona:

- **O observador é a fonte do modo.** Ele guarda `sync_enabled` e uma **época** em memória. Cada troca abre uma época nova, e o observador manda `MODE` aos 7 nós. Cada nó guarda o valor num `AtomicReference` e responde `MODE_ACK`. O observador continua sem conceder nada: o modo só diz se os nós pedem votos antes de entrar.
- **Cada pedido fixa o modo no `acquire`** e sai com o mesmo modo no `release`. Um pedido que pegou votos sempre os devolve, mesmo que o Maekawa seja desligado enquanto ele está na seção crítica. A troca vale a partir do próximo pedido de cada nó.
- **Transição:** por alguns instantes convivem pedidos dos dois modos. Uma violação só conta para a época quando os 7 nós já confirmaram a época e todos os acessos ativos foram admitidos nela; as demais aparecem em `transition_violations` e no log como `VIOLATION` com `"transition": true`.
- **Reinício:** ao subir, o nó manda `MODE_QUERY` e adota a resposta. Se o observador reiniciar, ele volta ao `SYNC_ENABLED` do ConfigMap, na época 0, e reanuncia o modo a cada nó no primeiro contato. Por isso o observador e os nós precisam ler o mesmo `SYNC_ENABLED` (no cluster, ambos usam o ConfigMap `experiment`).

O `verify.py` continua avaliando a rodada inteira: numa rodada `normal` em que o Maekawa foi desligado no meio, ele vai acusar as violações da época sem protocolo. Para comparar as fases, use `GET /stats` ou os campos `epoch`/`transition` dos eventos `VIOLATION`.

### Kubernetes local (kind)

```bash
scripts/kind-up.sh                         # cria o cluster, build da imagem, kind load, apply k8s/base
IMAGE=maekawa:dev scripts/run-experiment.sh sem-protocolo 1
IMAGE=maekawa:dev scripts/run-experiment.sh deadlock 1
IMAGE=maekawa:dev scripts/run-experiment.sh normal 1 --accesses 30   # 10-50 por cliente; default do overlay
IMAGE=maekawa:dev scripts/run-suite.sh      # 1+1+3+3+1; copia report.json para evidence/
IMAGE=maekawa:dev scripts/run-suite.sh --accesses 10
```

### Oracle Cloud Always Free (k3s numa VM Ampere)

Ver [infra/README.md](infra/README.md): uma `VM.Standard.A1.Flex` (2 OCPU, 12 GiB, arm64) na home region, k3s, túnel SSH. Os manifests são os mesmos do kind.

```bash
cd infra/oci && cp terraform.tfvars.example terraform.tfvars   # preencher
terraform init && terraform apply
export OCI_HOST=$(terraform output -raw public_ip)
cd ../..
scripts/oci-kubeconfig.sh
# noutro terminal: scripts/oci-tunnel.sh
export KUBECONFIG=$PWD/kubeconfig-oci
scripts/oci-load-image.sh
IMAGE=maekawa:dev scripts/oci-apply-base.sh
IMAGE=maekawa:dev scripts/run-suite.sh
scripts/oci-stop.sh
```

## Experimentos e verificação

`scripts/run-experiment.sh <overlay> <rep>` aplica o overlay, reinicia nós e observador (estado e relógios zerados), dispara o Job, recolhe `kubectl logs` em `logs/<overlay>/<rep>/` e roda `scripts/verify.py`, que imprime `report.json`:

- violações e máximo de ativos em R (observador);
- pedidos sem COMMITTED, timeouts;
- mensagens **de protocolo** por tipo e por entrada (BEGIN/END/COMMITTED ficam fora da conta);
- espera até o quórum (mediana, p90, máximo), medida com relógio monotônico no próprio nó.

O verificador falha se: rodada normal tiver violação ou pedido sem COMMITTED; rodada `sem-protocolo` **não** tiver violação; rodada `deadlock` tiver algum COMMITTED.

| Overlay | O que mostra |
|---|---|
| `sem-protocolo` | `SYNC_ENABLED=false`: o observador acusa sobreposição (a instrumentação enxerga) |
| `deadlock` | versão básica, 3 pedidos simultâneos, gate segura 3 REQUESTs por 20 s (a partir da primeira mensagem retida): ninguém entra, Job falha de propósito |
| `normal` ×3 | 20 acessos por cliente, pausa 1–5 s, zero violação |
| `alta-contencao` ×3 | sem pausa; aparecem FAILED, INQUIRE, RELINQUISH |
| `desigual` | client-0 sem pausa, demais normais; distribuição de espera |

Resultado de referência numa máquina local (7 clientes × 5 acessos, sem pausa, CS 20–80 ms): 35 entradas, 0 violações, 11,9 mensagens de protocolo por entrada (teoria: 3√N a 6√N ≈ 8 a 16 para N = 7), 96 FAILED, 2 INQUIRE, 2 RELINQUISH.

## Ver os nós e os logs

O desenho dos 7 processos **não** é o Grafana. É a página `viz/`, que abre **ao vivo**: votos (`LOCKED`/`RELEASE`), quem está em R (`BEGIN`/`END`) e sobreposições aparecem enquanto a rodada acontece.

```bash
scripts/viz.sh          # rodada do scripts/local-run.sh · http://127.0.0.1:8765
scripts/viz.sh --k8s    # rodada no kind/k3s (run-experiment.sh) · http://127.0.0.1:8765
```

- **Sem flag:** segue os `*.jsonl` da rodada mais recente em `logs/` (troca sozinha quando começa outra) e fala com o observador em `127.0.0.1:7380`.
- **`--k8s`:** segue `kubectl logs -f` do observador e dos `sync-i` e mantém sozinho um `port-forward` do observador em `127.0.0.1:18080`, refeito quando o `run-experiment.sh` recria o pod. Não precisa de `port-forward` manual.
- `OBSERVER_URL`, `LIVE_DIR` e `K8S_NAMESPACE` sobrescrevem os padrões.

Aba **Ao vivo**:

- **Experimento:** liga e desliga o Maekawa, mostra a época, quais dos 7 nós confirmaram a troca e as violações por época (as da troca ficam à parte).
- **Seção crítica:** muda o sleep dentro de R (`cs_min_ms`–`cs_max_ms`) em todos os nós, a partir do próximo acesso de cada um. "Sleep ×2" dobra o valor atual, o que deixa os votos e as sobreposições mais fáceis de acompanhar. Não abre época nova. Também pela API: `POST /mode` com `{"cs_min_ms": 2000, "cs_max_ms": 4000}`.
- **Em R agora / Violações** e os últimos eventos.
- **Zerar contadores:** zera violações, BEGIN/END, máximo em R e a tabela por época no observador, para medir um trecho da rodada do zero (por exemplo, depois de religar o Maekawa). Não mexe no modo, na época nem em quem está em R. Fica no log como `COUNTERS_RESET`, com os valores anteriores. O `verify.py` continua contando todas as violações do log da rodada.
- **Pods** (só com `--k8s`), com o número de clientes rodando ao lado:
  - **Reiniciar pods:** recria o observador e os 7 nós com estado limpo (modo, sleep e contadores voltam ao ConfigMap) e dispara de novo o Job de clientes com a mesma especificação. É o miolo do `run-experiment.sh`, sem trocar de overlay nem recolher logs.
  - **Rodada contínua:** igual, mas os clientes pedem sem parar (`ACCESSES=0` no env do Job, que vence o ConfigMap) até "Parar clientes". Serve para demonstração; as rodadas oficiais continuam finitas.
  - **Parar clientes:** apaga o Job de clientes; nós e observador continuam no ar.

Por que a tela "para": cada cliente faz `ACCESSES` pedidos e sai (o enunciado pede 10 a 50, e o `verify.py` confere um COMMITTED por pedido). O Job tem `restartPolicy: Never` e `backoffLimit: 0`, então não recria clientes, e é apagado uma hora depois de terminar (`ttlSecondsAfterFinished: 3600`). Nós e observador ficam no ar, mas sem carga. `ACCESSES=0` liga o modo contínuo também no `scripts/local-run.sh` (`scripts/local-run.sh 0 1000 3000`), que então roda até Ctrl-C.

A aba **Replay** reproduz rodadas gravadas: `logs/<overlay>/<rep>/*.jsonl` ou arquivos escolhidos na tela, com pausa, passo e velocidade. A rodada "exemplo sintético" só ensina os botões; não é a suíte da Oracle. Espaço pausa, setas andam um evento.

O Grafana mostra **gráficos e a lista de logs**, no mesmo cluster, namespace `observability` (o experimento não apaga). Sem porta pública:

```bash
scripts/obs-up.sh
kubectl -n observability port-forward svc/grafana 3000:3000
```

Login `admin` / `maekawa`. Pasta **Maekawa**: mensagens por tipo, violações e o texto JSON. Promtail só lê pods de `maekawa`.

## Logs

Uma linha JSON por evento em stdout:

```json
{"event":"LOCKED","process":"sync-2","process_id":2,"lamport":14,"ts_monotonic_ns":...,"wall_clock":"...","kind":"protocol","direction":"send","peer":"sync-0","request_id":{"client_id":0,"seq":3},"quorum_granted":[]}
```

`kind` é `protocol`, `instrumentation` ou `local`. `wall_clock` é só informativo; nenhuma métrica compara horários de hosts diferentes.
