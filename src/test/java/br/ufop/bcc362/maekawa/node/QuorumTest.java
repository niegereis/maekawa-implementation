package br.ufop.bcc362.maekawa.node;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QuorumTest {

    @Test
    void fanoTemSeteConjuntosDeTresComInterseccaoUnitaria() {
        Quorum q = Quorum.fano();
        assertEquals(7, q.size());
        for (int i = 0; i < 7; i++) {
            assertEquals(3, q.votersOf(i).size(), "S" + i);
            assertTrue(q.votersOf(i).contains(i), "p" + i + " em S" + i);
            for (int j = i + 1; j < 7; j++) {
                Set<Integer> inter = new HashSet<>(q.votersOf(i));
                inter.retainAll(q.votersOf(j));
                assertEquals(1, inter.size(), "S" + i + " ∩ S" + j);
            }
        }
    }

    @Test
    void cadaProcessoVotaParaExatamenteTresPedintes() {
        Quorum q = Quorum.fano();
        for (int i = 0; i < 7; i++) {
            assertEquals(3, q.requestersOf(i).size(), "pedintes de p" + i);
            assertTrue(q.requestersOf(i).contains(i));
        }
    }

    @Test
    void exemploDoPlanoCruzaEmP2() {
        Quorum q = Quorum.fano();
        Set<Integer> inter = new HashSet<>(q.votersOf(0));
        inter.retainAll(q.votersOf(4));
        assertEquals(Set.of(2), inter);
    }

    @Test
    void rejeitaTabelaSemInterseccao() {
        assertThrows(IllegalArgumentException.class, () -> new Quorum(List.of(Set.of(0, 1), Set.of(1, 2), Set.of(2, 3), Set.of(3, 0))));
    }

    @Test
    void rejeitaProcessoForaDoProprioConjunto() {
        assertThrows(IllegalArgumentException.class, () -> new Quorum(List.of(Set.of(1, 2), Set.of(0, 1), Set.of(0, 2))));
    }

    @Test
    void rejeitaTamanhosDiferentes() {
        assertThrows(IllegalArgumentException.class, () -> new Quorum(List.of(Set.of(0, 1, 2), Set.of(0, 1), Set.of(0, 2))));
    }

    @Test
    void planoProjetivoDeOrdem3TemTrezeConjuntosDeQuatro() {
        Quorum q = Quorum.forSize(13);
        assertEquals(13, q.size());
        assertEquals(4, q.k());
        int[] load = new int[13];
        for (int i = 0; i < 13; i++) {
            assertEquals(4, q.votersOf(i).size());
            assertTrue(q.votersOf(i).contains(i), "p" + i + " no proprio conjunto");
            for (int v : q.votersOf(i)) load[v]++;
            for (int j = i + 1; j < 13; j++) {
                java.util.Set<Integer> inter = new java.util.TreeSet<>(q.votersOf(i));
                inter.retainAll(q.votersOf(j));
                assertEquals(1, inter.size(), "S" + i + " e S" + j + " se cruzam em um so no");
            }
        }
        for (int i = 0; i < 13; i++) assertEquals(4, load[i], "p" + i + " vota em K conjuntos");
    }

    @Test
    void forSizeMantemFanoParaSeteERecusaNSemPlano() {
        assertEquals(Quorum.FANO, java.util.stream.IntStream.range(0, 7).mapToObj(Quorum.fano()::votersOf).toList());
        assertEquals(Quorum.fano().votersOf(3), Quorum.forSize(7).votersOf(3));
        assertEquals(31, Quorum.forSize(31).size());
        assertThrows(IllegalArgumentException.class, () -> Quorum.forSize(10));
        assertThrows(IllegalArgumentException.class, () -> Quorum.forSize(5));
        assertThrows(IllegalArgumentException.class, () -> Quorum.forSize(21)); // q = 4 nao e primo
    }

    @Test
    void gradeDoisPorDoisParaQuatroNos() {
        Quorum q = Quorum.forSize(4);
        assertEquals(4, q.size());
        assertEquals(3, q.k());
        assertEquals(Set.of(0, 1, 2), q.votersOf(0)); // linha 0 + coluna 0
        assertEquals(Set.of(0, 1, 3), q.votersOf(1));
        assertEquals(Set.of(0, 2, 3), q.votersOf(2));
        assertEquals(Set.of(1, 2, 3), q.votersOf(3));
        for (int i = 0; i < 4; i++) assertEquals(3, q.requestersOf(i).size(), "cada no vota em 3 grupos");
    }
}
