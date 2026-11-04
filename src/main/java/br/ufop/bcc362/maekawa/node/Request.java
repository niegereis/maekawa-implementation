package br.ufop.bcc362.maekawa.node;

import br.ufop.bcc362.maekawa.common.Priority;
import br.ufop.bcc362.maekawa.common.RequestId;

/** Um pedido visto por um votante: quem pediu, qual request_id e com que prioridade. */
public record Request(int ownerPid, RequestId id, Priority priority) implements Comparable<Request> {
    @Override
    public int compareTo(Request o) {
        int c = priority.compareTo(o.priority);
        return c != 0 ? c : id.compareTo(o.id);
    }
}
