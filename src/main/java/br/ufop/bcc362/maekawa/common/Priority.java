package br.ufop.bcc362.maekawa.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Prioridade de um pedido: o par (relogio de Lamport no envio do REQUEST, id do processo).
 * O menor par ganha. O relogio desempata a maioria dos casos; o id desempata o resto,
 * o que torna a ordem total.
 */
public record Priority(@JsonProperty("lamport") long lamport,
                       @JsonProperty("process_id") int processId) implements Comparable<Priority> {

    @JsonCreator
    public Priority {
    }

    @Override
    public int compareTo(Priority o) {
        int c = Long.compare(lamport, o.lamport);
        return c != 0 ? c : Integer.compare(processId, o.processId);
    }

    /** true se esta prioridade e melhor (menor) que a outra. */
    public boolean beats(Priority o) {
        return compareTo(o) < 0;
    }

    @Override
    public String toString() {
        return "(" + lamport + "," + processId + ")";
    }
}
