# TP2 BCC362 — Exclusão mútua distribuída com Maekawa (A6)

Sete processos independentes (`sync-0` … `sync-6`) sincronizam o acesso de escrita a um recurso hipotético **R** pelo algoritmo de Maekawa (1985), com conjuntos de votação do plano de Fano (K = 3). Há duas versões:

- **básica** (`VARIANT=basic`): REQUEST, LOCKED, RELEASE — entra em deadlock com espera circular de votos, mesmo sem falha nem perda de mensagem;
- **resolução** (`VARIANT=resolution`, padrão): acrescenta FAILED, INQUIRE e RELINQUISH com prioridade por (relógio de Lamport, id), como no artigo.

Um **observador** registra BEGIN/END, confirma o registro (`BEGIN_ACK`/`END_ACK`) e acusa sobreposições; ele não concede permissão nem bloqueia ninguém. O nó espera o ACK do END antes de enviar RELEASE, para que o observador veja o fim de um acesso antes do início do próximo. Sete **clientes**, um por nó, geram `request_id = (client_id, seq)` e esperam COMMITTED.

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
  observer/Observer.java    conta ativos em R, grava VIOLATION
src/test/java/...           QuorumTest, PriorityTest, VoterTest, RequesterTest, DeadlockScenarioTest, ClusterContentionTest
k8s/base                    namespace, ConfigMap, StatefulSet sync (7), Deployment observer, Job clients (7, indexado)
k8s/overlays/*              sem-protocolo · deadlock · normal · alta-contencao · desigual
infra/oci/                  Terraform Always Free (A1.Flex + VCN + NSG SSH) e cloud-init do k3s
scripts/                    local-run.sh · kind-up.sh · oci-*.sh · run-experiment.sh · verify.py
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

Portas: `localhost:7200..7206` e observador `:7300` (`BASE` muda a base; a 7000 é do AirPlay no macOS).

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

## Logs

Uma linha JSON por evento em stdout:

```json
{"event":"LOCKED","process":"sync-2","process_id":2,"lamport":14,"ts_monotonic_ns":...,"wall_clock":"...","kind":"protocol","direction":"send","peer":"sync-0","request_id":{"client_id":0,"seq":3},"quorum_granted":[]}
```

`kind` é `protocol`, `instrumentation` ou `local`. `wall_clock` é só informativo; nenhuma métrica compara horários de hosts diferentes.
