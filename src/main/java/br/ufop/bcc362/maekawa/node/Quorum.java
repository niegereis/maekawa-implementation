package br.ufop.bcc362.maekawa.node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Define a quem cada processo pede votos. N = 7 usa a tabela de Fano; N = 4 e 13 sao calculados.
 * Dois conjuntos sempre se cruzam; senao o processo nao sobe.
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

    /** @param sets S_0..S_{N-1}; valida na hora e lanca excecao se a tabela nao serve ao Maekawa */
    public Quorum(List<Set<Integer>> sets) {
        this.sets = List.copyOf(sets);
        validate();
    }

    /** Plano de Fano: N = 7, K = 3, cada par de conjuntos se cruza em exatamente um no. */
    public static Quorum fano() {
        return new Quorum(FANO);
    }

    /**
     * Olha o tamanho do cluster e escolhe a construção de comitês que funciona para ele,
     * de preferência a que pede menos votos.
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
     * Arrume os processos num tabuleiro; o comite de cada um e a sua linha
     * mais a sua coluna. A linha de um sempre cruza a coluna do outro, entao dois comites
     * sempre tem alguem em comum (e cada processo esta no proprio comite).
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
     * Dois comitês quaisquer têm pelo menos uma pessoa em comum.
     * Essa pessoa só vota em um pedido por vez, então dois pedidos nunca são aprovados juntos
     */
    public static Quorum projective(int q) {
        // porteiro: com q nao primo a conta mod q abaixo nao garante que as retas se cruzem
        if (!isPrime(q)) throw new IllegalArgumentException("ordem " + q + " nao e prima");

        // PONTOS: cada processo vira um "endereco" de 3 numeros mod q. Triplas que sao
        // multiplas uma da outra contam como o mesmo ponto, entao so guardamos uma de cada:
        // (1, x, y), (0, 1, y) e (0, 0, 1). Total: q^2 + q + 1 = N processos.
        List<int[]> pts = new ArrayList<>();
        for (int x = 0; x < q; x++) for (int y = 0; y < q; y++) pts.add(new int[]{1, x, y});
        for (int y = 0; y < q; y++) pts.add(new int[]{0, 1, y});
        pts.add(new int[]{0, 0, 1});
        int n = pts.size();

        // RETAS (os comites): cada tripla "a" tambem nomeia uma reta. A reta a junta os pontos x
        // que "zeram a conta" a.x = 0 (mod q). Cada reta sai com q + 1 pontos (K), e duas retas
        // diferentes tem sempre exatamente um ponto em comum.
        List<Set<Integer>> lines = new ArrayList<>();
        for (int[] a : pts) { // mesma parametrizacao para retas (dualidade)
            Set<Integer> line = new TreeSet<>();
            for (int i = 0; i < n; i++) {
                int[] x = pts.get(i);
                if ((a[0] * x[0] + a[1] * x[1] + a[2] * x[2]) % q == 0) line.add(i);
            }
            lines.add(line);
        }

        // DONOS: ate aqui ha N comites certos, mas sem dono. Cada processo precisa ficar com um
        // comite que o contenha; o augment distribui, trocando os outros de lugar quando preciso.
        int[] lineOf = new int[n]; // processo -> reta
        int[] ownerOf = new int[n]; // reta -> processo
        Arrays.fill(lineOf, -1);
        Arrays.fill(ownerOf, -1);
        for (int p = 0; p < n; p++) {
            if (!augment(p, lines, lineOf, ownerOf, new boolean[n])) {
                throw new IllegalStateException("sem emparelhamento para p" + p);
            }
        }

        // tabela final: S_p e a reta que ficou com o processo p
        List<Set<Integer>> sets = new ArrayList<>();
        for (int p = 0; p < n; p++) sets.add(Set.copyOf(lines.get(lineOf[p])));
        return new Quorum(sets);
    }

    /**
     * Cada processo faz parte do próprio comitê (p_i ∈ S_i).
     */
    private static boolean augment(int p, List<Set<Integer>> lines, int[] lineOf, int[] ownerOf, boolean[] seen) {
        for (int l = 0; l < lines.size(); l++) {
            // so serve reta que contem p e que ainda nao foi tentada nesta corrente
            if (seen[l] || !lines.get(l).contains(p)) continue;
            seen[l] = true;
            // reta livre, ou o dono atual conseguiu mudar para outra reta dele
            if (ownerOf[l] < 0 || augment(ownerOf[l], lines, lineOf, ownerOf, seen)) {
                lineOf[p] = l;
                ownerOf[l] = p;
                return true;
            }
        }
        return false; // nenhuma reta de p ficou disponivel
    }

    /**
     * Porteiro do projective. A conta "mod q" que gera as retas so garante que toda reta cruza
     * toda reta quando q e primo; com q nao primo os comites sairiam errados. Por isso N = 21
     * (q = 4) e recusado, mesmo existindo plano de ordem 4 (exigiria aritmetica de GF(4)).
     */
    private static boolean isPrime(int q) {
        if (q < 2) return false;
        for (int d = 2; d * d <= q; d++) if (q % d == 0) return false;
        return true;
    }

    /** K: tamanho de cada conjunto de votacao. */
    public int k() {
        return sets.get(0).size();
    }

    /** N: quantos nos (e conjuntos) existem. */
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

    /**
     * Confere as regras do Maekawa: todos os conjuntos com o mesmo K, p_i pertence a S_i, so
     * referencias a nos existentes e todo par S_i, S_j se intersecta (garantia da exclusao mutua).
     */
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
