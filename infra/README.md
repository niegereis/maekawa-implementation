# Infra — Oracle Cloud Always Free (Ampere A1 + k3s)

Alvo: **uma** VM `VM.Standard.A1.Flex` (2 OCPU, 12 GiB, Ubuntu aarch64) na **home region**, com k3s de nó único. Os manifests em `k8s/` não mudam — o que muda é o kubeconfig (túnel SSH).

Fontes oficiais (novembro 2026):

- [Free Tier](https://www.oracle.com/cloud/free/) — trial de US$ 300 / 30 dias **mais** Always Free permanente
- [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)

## O que usamos (e o que não)

| Recurso | Limite Always Free | Neste TP |
|---|---|---|
| Ampere A1 (`VM.Standard.A1.Flex`) | 2 OCPU e 12 GiB no **total** da tenancy (1 500 OCPU-h e 9 000 GB-h/mês) | 1 VM 2 / 12 |
| E2.1.Micro (AMD) | 2 VMs de 1 GiB | **não usar** — k3s + 15 JVMs não cabem |
| Block Volume | 200 GiB na home region | boot de 50 GiB |
| IP público | 1 IPv4 na VNIC | sim, só para SSH |
| Flexible Load Balancer / NLB | 1 de cada, 10 Mbps | **não criar** |
| VCN | Always Free | 1 VCN, 1 subnet pública |
| OKE (Kubernetes gerenciado) | fora do Always Free | **não usar** |
| Autônomo / HeatWave / Object Storage | Always Free | **não criar** |

O trial de US$ 300 é opcional. Ficando no shape e nos volumes acima, o custo é **US$ 0** mesmo depois dos 30 dias, sem converter para Pay As You Go.

Não converter para PAYG só para “ter mais cota”. Em PAYG, passar de 2 OCPU / 12 GiB **cobra**. A conta Free Tier pura recusa o excesso em vez de faturar; o cartão do cadastro é verificação de identidade.

## Fase 0 — conferir a conta

No console (canto superior direito):

1. **Home region** — Always Free Compute só vale nela. Anote o identificador (`sa-saopaulo-1`, `us-ashburn-1`, …).
2. **Account type** — Free Tier / Always Free. Não precisa de upgrade.
3. Compute → **Ampere A1** com o selo Always Free Eligible.

Se a criação falhar com `Out of host capacity`, troque o availability domain (AD-1 → AD-2 → AD-3) e tente de novo. Não aumente OCPU nem mude de região.

Instâncias Always Free **ociosas 7 dias** (CPU, rede e, no A1, memória abaixo de 20% no p95) podem ser reclamadas. Fora dos experimentos: **Stop** a VM (`scripts/oci-stop.sh`). Conta parada 30+ dias também pode ser suspensa — ligue uma vez por mês.

## Criar (Terraform)

Precisa de: [Terraform](https://developer.hashicorp.com/terraform/install) ≥ 1.5, [OCI CLI](https://docs.oracle.com/en-us/iaas/Content/API/SDKDocs/cliinstall.htm) com `oci setup config` (gera `~/.oci/config` + chave API), e um par SSH.

```bash
cd infra/oci
cp terraform.tfvars.example terraform.tfvars   # preencher região, compartment, chave pública
terraform init
terraform apply
terraform output public_ip
```

`compartment_ocid` pode ser o OCID da própria tenancy (Profile → Tenancy).

## Criar (console, sem Terraform)

1. Networking → Virtual Cloud Networks → **Start VCN Wizard** → VCN with Internet Connectivity. Ou deixe o wizard da instância criar a VCN.
2. Compute → Instances → Create:
   - Image: **Canonical Ubuntu 22.04** (Always Free Eligible), **aarch64**
   - Shape: **VM.Standard.A1.Flex**, 2 OCPU, 12 GiB
   - Boot: 50 GiB
   - Networking: subnet pública, assign public IPv4
   - SSH key: a pública local
   - Show advanced → paste de `infra/oci/cloud-init.yaml` em **Initialization script**
3. Depois de Running: Security List / NSG da subnet — **ingress só TCP 22**. Remova 80/443 se o wizard tiver aberto.

## Subir a aplicação

```bash
export OCI_HOST=$(terraform -chdir=infra/oci output -raw public_ip)
# se criou no console: export OCI_HOST=<ip>

scripts/oci-kubeconfig.sh          # grava ./kubeconfig-oci (gitignored)
# noutro terminal:
scripts/oci-tunnel.sh

export KUBECONFIG=$PWD/kubeconfig-oci
kubectl get nodes

scripts/oci-load-image.sh          # docker build --platform linux/arm64 + import no k3s
IMAGE=maekawa:dev scripts/oci-apply-base.sh   # pods e o front (viz/) na VM
# noutro terminal: ssh -N -L 8766:127.0.0.1:8765 ubuntu@$OCI_HOST
# http://127.0.0.1:8766

IMAGE=maekawa:dev scripts/run-experiment.sh sem-protocolo 1
IMAGE=maekawa:dev scripts/run-experiment.sh deadlock 1
IMAGE=maekawa:dev scripts/run-suite.sh
```

A imagem é **arm64**. No Mac Apple Silicon o build é nativo; no Intel, o Docker usa QEMU (`docker build --platform linux/arm64`). O workflow do GHCR publica `linux/amd64` e `linux/arm64`; se a imagem estiver pública, o k3s consegue puxar e o import local deixa de ser obrigatório.

Nenhum `Service` é `LoadBalancer`. `kubectl` só pelo túnel.

## Parar e apagar

```bash
scripts/oci-stop.sh                # STOP — Always Free, k3s para
scripts/oci-start.sh               # START — o IP público pode mudar

# depois da entrega:
terraform -chdir=infra/oci destroy
# e apague o boot volume se o console perguntar (conta nos 200 GiB)
```

## Custo esperado

| Item | Always Free |
|---|---|
| VM A1 2 OCPU / 12 GiB na home region | US$ 0 |
| Boot 50 GiB | US$ 0 (dentro de 200 GiB) |
| IPv4 público da VNIC | US$ 0 |
| OKE, LB, NAT gateway extra, shape maior, outra região | **cobraria** — não criamos |
