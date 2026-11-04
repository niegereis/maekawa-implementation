package br.ufop.bcc362.maekawa.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Identificador unico de um pedido: (client_id, seq). Nao usa relogio fisico. */
public record RequestId(@JsonProperty("client_id") int clientId,
                        @JsonProperty("seq") long seq) implements Comparable<RequestId> {

    @JsonCreator
    public RequestId {
    }

    @Override
    public int compareTo(RequestId o) {
        int c = Integer.compare(clientId, o.clientId);
        return c != 0 ? c : Long.compare(seq, o.seq);
    }

    @Override
    public String toString() {
        return "(" + clientId + "," + seq + ")";
    }
}
