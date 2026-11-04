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
}
