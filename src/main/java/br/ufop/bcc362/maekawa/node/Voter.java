package br.ufop.bcc362.maekawa.node;

import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.RequestId;

import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Papel de votante (secao 4 do protocolo). Concede no maximo um voto por vez.
 * Todos os metodos sao chamados sob o lock do Node.
 *
 * Estados: Livre (granted == null), Concedido (granted != null, !inquired),
 * Indagando (granted != null, inquired).
 */
public final class Voter {
    public enum State { FREE, GRANTED, INQUIRING }

    private final int pid;
    private final Variant variant;
    private final Outbox out;

    private Request granted;
    private boolean inquired;
    private final PriorityQueue<Request> queue = new PriorityQueue<>();

    /** @param variant BASIC so concede e enfileira; RESOLUTION tambem envia FAILED e INQUIRE */
    public Voter(int pid, Variant variant, Outbox out) {
        this.pid = pid;
        this.variant = variant;
        this.out = out;
    }

    /** FREE (voto livre), GRANTED (voto concedido) ou INQUIRING (pediu o voto de volta ao detentor). */
    public State state() {
        if (granted == null) return State.FREE;
        return inquired ? State.INQUIRING : State.GRANTED;
    }

    /** Pedido que esta com o voto deste no, se houver. */
    public Optional<Request> granted() {
        return Optional.ofNullable(granted);
    }

    /** Quantos pedidos esperam o voto deste no. */
    public int queueSize() {
        return queue.size();
    }

    /**
     * Chegou um pedido de voto (REQUEST). Voto livre: concede (LOCKED). Ocupado: enfileira por
     * prioridade (Lamport, id). Na variante RESOLUTION, se o pedido novo e melhor que o do detentor,
     * pede o voto de volta (INQUIRE, uma vez so); se e pior, avisa o novo que o voto nao vem agora (FAILED).
     */
    public void onRequest(Request r) {
        if (granted == null) {
            grant(r);
            return;
        }
        queue.add(r);
        if (variant == Variant.BASIC) return;

        if (r.priority().beats(granted.priority())) {
            // pedido novo e melhor: pergunta ao detentor se ele cede
            if (!inquired) {
                inquired = true;
                out.send(MessageType.INQUIRE, granted.ownerPid(), granted.id());
            }
        } else {
            // pedido novo e pior: avisa que o voto nao vem agora
            out.send(MessageType.FAILED, r.ownerPid(), r.id());
        }
    }

    /** O detentor devolveu o voto sem ter entrado (RELINQUISH): ele volta para a fila e o voto vai ao melhor pendente. */
    public void onRelinquish(RequestId id) {
        if (granted == null || !granted.id().equals(id)) {
            // tardio ou duplicado: nada a fazer
            return;
        }
        queue.add(granted);
        granted = null;
        inquired = false;
        grantNext();
    }

    /** O detentor saiu da secao critica (RELEASE): libera o voto e concede ao proximo da fila. */
    public void onRelease(RequestId id) {
        if (granted != null && granted.id().equals(id)) {
            granted = null;
            inquired = false;
            grantNext();
            return;
        }
        // RELEASE de alguem que estava na fila (nao deveria ocorrer; defensivo)
        queue.removeIf(q -> q.id().equals(id));
    }

    /** Concede o voto ao pedido de melhor prioridade na fila, se houver. */
    private void grantNext() {
        Request next = queue.poll();
        if (next != null) grant(next);
    }

    /** Entrega o voto deste no ao pedido r (LOCKED). */
    private void grant(Request r) {
        // a fila e ordenada por prioridade, entao quem sai dela e sempre o melhor pendente:
        // nao ha ninguem na fila que justifique um INQUIRE imediato
        granted = r;
        inquired = false;
        out.send(MessageType.LOCKED, r.ownerPid(), r.id());
    }
}
