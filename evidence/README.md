# Evidência das rodadas (kind)

Gerado em 2026-11-04, cluster `kind-maekawa`, imagem `maekawa:dev` com `BEGIN_ACK`/`END_ACK`. Os JSON Lines completos ficam em `logs/<overlay>/<rep>/` (gitignored). Aqui só os `report.json` que entram no repositório.

Os números oficiais do relatório, quando o k3s na Oracle existir, saem da mesma suíte (`IMAGE=maekawa:dev scripts/run-suite.sh` com o túnel SSH e `KUBECONFIG=./kubeconfig-oci`).

## Resultado

| overlay | rep | violações | max em R | committed | msgs/entrada | espera mediana (ms) |
|---|---|---|---|---|---|---|
| sem-protocolo | 1 | 34 | 7 | 35/35 | 0 | — |
| deadlock | 1 | 0 | 0 | 0/3 | — | — (timeout de propósito) |
| normal | 1 | 0 | 1 | 140/140 | 10,68 | 739 |
| normal | 2 | 0 | 1 | 140/140 | 10,53 | 591 |
| normal | 3 | 0 | 1 | 140/140 | 10,91 | 957 |
| alta-contencao | 1 | 0 | 1 | 140/140 | 12,00 | 3716 |
| alta-contencao | 2 | 0 | 1 | 140/140 | 12,02 | 3643 |
| alta-contencao | 3 | 0 | 1 | 140/140 | 12,01 | 3527 |
| desigual | 1 | 0 | 1 | 140/140 | 10,85 | 804 |

Custo teórico sem contenção: 3√N = 9 mensagens de protocolo por entrada. Com contenção o artigo fala em até 6√N ≈ 16. As rodadas ficaram entre 10,5 e 12,0.

A alta contenção espera ~5× mais (3,5–3,7 s vs 0,6–0,9 s) e gera ~408 FAILED por rodada: a variante está em uso, não só o caminho sem disputa.

## O que a “violação” da primeira alta contenção era

Não era quebra do Maekawa. O nó mandava END e em seguida RELEASE; quem herdava o voto mandava BEGIN. END e BEGIN iam ao observador por conexões TCP diferentes e podiam chegar invertidos: o observador via 2 ativos por alguns milissegundos e gravava `VIOLATION`, embora o segundo só tivesse começado depois do `RELEASE`.

Correção (instrumentação, não protocolo): o nó espera `END_ACK` antes de enviar RELEASE. O observador continua sem autorizar nada — só confirma que anotou. Documentado em `docs/protocolo.md`.
