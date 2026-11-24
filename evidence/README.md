# Evidência das rodadas

Cada pasta é o `report.json` de uma rodada, gerado por `scripts/verify.py`.
Os JSON Lines completos ficam em `logs/<overlay>/<rep>/` (gitignored).

Rodadas de 2026-11-10, 7 nós, K = 3 (plano de Fano), seção crítica 200–1000 ms.

| Rodada | Acessos | Violações | Máx. em R | Msg/entrada | Espera mediana | Resultado |
|---|---|---|---|---|---|---|
| `sem-protocolo/1` | 35 | 33 | 7 | 0 | — | esperado: **tem** violação |
| `deadlock/1` | 0 de 3 pedidos | 0 | 0 | — | — | esperado: **nenhum** COMMITTED |
| `normal/1` | 140 | 0 | 1 | 10,52 | 673 ms | ok |
| `normal/2` | 140 | 5 | 3 | 10,05 | 681 ms | ver nota abaixo |
| `normal/3` | 140 | 15 | 7 | 10,29 | 1495 ms | ver nota abaixo |
| `alta-contencao/1` | 140 | 0 | 1 | 11,98 | 3724 ms | ok |
| `alta-contencao/2` | 140 | 0 | 1 | 12,02 | 3784 ms | ok |
| `alta-contencao/3` | 140 | 0 | 1 | 11,96 | 3619 ms | ok |
| `desigual/1` | 140 | 0 | 1 | 11,03 | 1212 ms | ok |

Em todas as rodadas, os 140 pedidos receberam COMMITTED e nenhum estourou o timeout
(exceto `deadlock`, onde isso é o objetivo).

## Nota sobre `normal/2` e `normal/3`

Essas duas rodadas **não** são falhas do algoritmo. Foram usadas para demonstrar a
troca de modo em execução (`POST /mode`), descrita no README: o Maekawa foi
**desligado** no meio da rodada e religado depois.

O `report.json` avalia a rodada inteira e não separa as épocas, então as violações
aparecem no total. Nos logs, todas elas estão na **época 1**, a época sem protocolo:

- `normal/2`: 5 violações, todas em `"epoch": 1` (`sync_enabled: false`);
- `normal/3`: 15 violações, todas em `"epoch": 1` e marcadas `"transition": true`.

As épocas 0 e 2, com o Maekawa ligado, não têm nenhuma violação. Para avaliar por
época, use `GET /stats` ou os campos `epoch` e `transition` dos eventos `VIOLATION`
em `logs/normal/<rep>/observer.jsonl`.

A rodada `normal` limpa, sem troca de modo, é a `normal/1`.

## Mensagens por entrada

A teoria de Maekawa prevê de 3√N a 6√N mensagens por entrada, ou seja **8 a 16**
para N = 7. O medido fica entre 10,05 e 12,02 nas rodadas com protocolo, dentro da
faixa. A contenção alta empurra o valor para cima porque aparecem mais FAILED.
