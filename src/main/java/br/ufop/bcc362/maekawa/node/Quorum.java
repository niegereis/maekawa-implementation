package br.ufop.bcc362.maekawa.node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Conjuntos de votacao. Padrao: plano de Fano, N = 7, K = 3. Outros N: plano projetivo de
 * ordem q primo (N = q^2 + q + 1, K = q + 1; ex.: 13) ou grade raiz(N) x raiz(N) quando N e
 * quadrado perfeito (S_i = linha + coluna de p_i, K = 2 raiz(N) - 1; ex.: N = 4, K = 3). A
 * validacao roda na inicializacao e derruba o processo se a tabela for invalida.
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

    /**
     * N = 7 usa a tabela de Fano acima; N = q^2 + q + 1 com q primo usa o plano projetivo (13, 31...);
     * N quadrado perfeito usa a grade (4, 9, 16...).
     */
    public static Quorum forSize(int n) {
        if (n == 7) return fano();
        for (int q = 2; q * q + q + 1 <= n; q++) {
            if (q * q + q + 1 == n && isPrime(q)) return projective(q);
        }
        int side = (int) Math.round(Math.sqrt(n));
        if (side >= 2 && side * side == n) return grid(side);
        throw new IllegalArgumentException("N_NODES=" + n + " sem construcao de conjuntos; use 4, 7, 9, 13...");
    }

    /**
     * Grade side x side: p_i fica na linha i / side e na coluna i % side; S_i = linha + coluna.
     * Dois conjuntos sempre se cruzam (a linha de um encontra a coluna do outro). K = 2 side - 1,
     * maior que o otimo raiz(N) do plano projetivo, mas existe para qualquer N quadrado.
     */
    public static Quorum grid(int side) {
        int n = side * side;
        List<Set<Integer>> sets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int row = i / side, col = i % side;
            Set<Integer> s = new TreeSet<>();
            for (int c = 0; c < side; c++) s.add(row * side + c);
            for (int r = 0; r < side; r++) s.add(r * side + col);
            sets.add(Set.copyOf(s));
        }
        return new Quorum(sets);
    }

    /**
     * Retas do plano projetivo PG(2, q), q primo: pontos e retas sao os vetores nao nulos de
     * (Z_q)^3 a menos de multiplo; o ponto x esta na reta a quando a.x = 0 (mod q). Sao
     * q^2 + q + 1 retas de q + 1 pontos, e duas retas quaisquer se cruzam em exatamente um
     * ponto. Cada processo recebe uma reta que passa por ele (emparelhamento perfeito entre
     * pontos e retas, que existe porque cada ponto esta em q + 1 retas).
     */
    public static Quorum projective(int q) {
        if (!isPrime(q)) throw new IllegalArgumentException("ordem " + q + " nao e prima");
        List<int[]> pts = new ArrayList<>();
        for (int x = 0; x < q; x++) for (int y = 0; y < q; y++) pts.add(new int[]{1, x, y});
        for (int y = 0; y < q; y++) pts.add(new int[]{0, 1, y});
        pts.add(new int[]{0, 0, 1});
        int n = pts.size();
        List<Set<Integer>> lines = new ArrayList<>();
        for (int[] a : pts) { // mesma parametrizacao para retas (dualidade)
            Set<Integer> line = new TreeSet<>();
            for (int i = 0; i < n; i++) {
                int[] x = pts.get(i);
                if ((a[0] * x[0] + a[1] * x[1] + a[2] * x[2]) % q == 0) line.add(i);
            }
            lines.add(line);
        }
        int[] lineOf = new int[n]; // processo -> reta
        int[] ownerOf = new int[n]; // reta -> processo
        Arrays.fill(lineOf, -1);
        Arrays.fill(ownerOf, -1);
        for (int p = 0; p < n; p++) {
            if (!augment(p, lines, lineOf, ownerOf, new boolean[n])) {
                throw new IllegalStateException("sem emparelhamento para p" + p);
            }
        }
        List<Set<Integer>> sets = new ArrayList<>();
        for (int p = 0; p < n; p++) sets.add(Set.copyOf(lines.get(lineOf[p])));
        return new Quorum(sets);
    }

    private static boolean augment(int p, List<Set<Integer>> lines, int[] lineOf, int[] ownerOf, boolean[] seen) {
        for (int l = 0; l < lines.size(); l++) {
            if (seen[l] || !lines.get(l).contains(p)) continue;
            seen[l] = true;
            if (ownerOf[l] < 0 || augment(ownerOf[l], lines, lineOf, ownerOf, seen)) {
                lineOf[p] = l;
                ownerOf[l] = p;
                return true;
            }
        }
        return false;
    }

    private static boolean isPrime(int q) {
        if (q < 2) return false;
        for (int d = 2; d * d <= q; d++) if (q % d == 0) return false;
        return true;
    }

    /** K: tamanho de cada conjunto de votacao. */
    public int k() {
        return sets.get(0).size();
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
