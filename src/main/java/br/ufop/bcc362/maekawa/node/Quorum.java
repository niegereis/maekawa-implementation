package br.ufop.bcc362.maekawa.node;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Conjuntos de votacao. Padrao: plano de Fano, N = 7, K = 3. A validacao roda na
 * inicializacao e derruba o processo se a tabela for editada de forma invalida.
 */
public final class Quorum {
    public static final List<Set<Integer>> FANO = List.of(
            Set.of(0, 1, 2),
            Set.of(1, 3, 5),
            Set.of(2, 3, 6),
            Set.of(0, 3, 4),
            Set.of(2, 4, 5),
            Set.of(0, 5, 6),
            Set.of(1, 4, 6));

    private final List<Set<Integer>> sets;

    public Quorum(List<Set<Integer>> sets) {
        this.sets = List.copyOf(sets);
        validate();
    }

    public static Quorum fano() {
        return new Quorum(FANO);
    }

    public int size() {
        return sets.size();
    }

    /** Si: a quem pi pede votos. */
    public Set<Integer> votersOf(int pi) {
        return sets.get(pi);
    }

    /** Quem pede votos a pi: os j tais que pi pertence a Sj. */
    public Set<Integer> requestersOf(int pi) {
        Set<Integer> r = new TreeSet<>();
        for (int j = 0; j < sets.size(); j++) if (sets.get(j).contains(pi)) r.add(j);
        return r;
    }

    private void validate() {
        int n = sets.size();
        if (n == 0) throw new IllegalArgumentException("tabela vazia");
        int k = sets.get(0).size();
        for (int i = 0; i < n; i++) {
            Set<Integer> si = sets.get(i);
            if (si.size() != k) throw new IllegalArgumentException("S" + i + " tem tamanho " + si.size() + ", esperado " + k);
            if (!si.contains(i)) throw new IllegalArgumentException("p" + i + " nao pertence a S" + i);
            for (int x : si) if (x < 0 || x >= n) throw new IllegalArgumentException("S" + i + " referencia p" + x + " inexistente");
            for (int j = i + 1; j < n; j++) {
                Set<Integer> inter = new TreeSet<>(si);
                inter.retainAll(sets.get(j));
                if (inter.isEmpty()) throw new IllegalArgumentException("S" + i + " e S" + j + " nao se intersectam");
            }
        }
    }
}
