package br.ufop.bcc362.maekawa.node;

import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.Priority;
import br.ufop.bcc362.maekawa.common.RequestId;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Papel de pedinte (secao 5 do protocolo). Um pedido por vez.
 * Todos os metodos sao chamados sob o lock do Node.
 */
public final class Requester {
    public enum State { IDLE, COLLECTING, YIELDING, CRITICAL }

    private final int pid;
    private final Set<Integer> voters;
    private final Outbox out;

    private State state = State.IDLE;
    private Request current;
    private final Set<Integer> votes = new TreeSet<>();
    private boolean failed;

    public Requester(int pid, Set<Integer> voters, Outbox out) {
        this.pid = pid;
        this.voters = Set.copyOf(voters);
        this.out = out;
    }

    public State state() {
        return state;
    }

    public Optional<Request> current() {
        return Optional.ofNullable(current);
    }

    /** Votantes com LOCKED vigente para o pedido atual. */
    public Set<Integer> votes() {
        return Set.copyOf(votes);
    }

    public boolean hasQuorum() {
        return state == State.CRITICAL;
    }

    /** Inicia o pedido: REQUEST a cada membro de Si (o proprio pi incluido). */
    public void begin(RequestId id, Priority priority) {
        if (state != State.IDLE) throw new IllegalStateException("p" + pid + " ja tem pedido em andamento: " + current);
        current = new Request(pid, id, priority);
        votes.clear();
        failed = false;
        state = State.COLLECTING;
        for (int v : voters) out.send(MessageType.REQUEST, v, id);
    }

    /** @return true se este LOCKED completou o quorum. */
    public boolean onLocked(int from, RequestId id) {
        if (!isCurrent(id) || state == State.CRITICAL) return false;
        votes.add(from);
        if (votes.containsAll(voters)) {
            state = State.CRITICAL;
            return true;
        }
        return false;
    }

    public void onFailed(int from, RequestId id) {
        if (!isCurrent(id)) return;
        failed = true;
    }

    /**
     * Um votante pergunta se cedemos o voto dele. Cede quem ainda nao tem o quorum;
     * quem ja esta na secao critica nao cede, e o voto volta so no RELEASE.
     * @return true se cedeu
     */
    public boolean onInquire(int from, RequestId id) {
        if (!isCurrent(id) || state == State.CRITICAL) return false;
        if (!votes.remove(from)) return false; // nao tinhamos esse voto: nada a devolver
        state = State.YIELDING;
        out.send(MessageType.RELINQUISH, from, id);
        return true;
    }

    /** Saiu da secao critica: RELEASE a cada membro de Si. */
    public void release(RequestId id) {
        if (!isCurrent(id)) throw new IllegalStateException("release de pedido que nao e o atual: " + id);
        for (int v : voters) out.send(MessageType.RELEASE, v, id);
        votes.clear();
        failed = false;
        current = null;
        state = State.IDLE;
    }

    /** Usado pela rodada sem protocolo e por testes: limpa sem enviar nada. */
    public void abandon() {
        votes.clear();
        failed = false;
        current = null;
        state = State.IDLE;
    }

    public boolean failedSeen() {
        return failed;
    }

    private boolean isCurrent(RequestId id) {
        return current != null && current.id().equals(id);
    }
}
