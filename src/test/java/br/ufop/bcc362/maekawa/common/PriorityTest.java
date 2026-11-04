package br.ufop.bcc362.maekawa.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PriorityTest {

    @Test
    void menorRelogioGanha() {
        assertTrue(new Priority(4, 2).beats(new Priority(7, 0)));
        assertFalse(new Priority(7, 0).beats(new Priority(4, 2)));
    }

    @Test
    void empateNoRelogioDecidePeloMenorId() {
        assertTrue(new Priority(4, 2).beats(new Priority(4, 5)));
        assertFalse(new Priority(4, 5).beats(new Priority(4, 2)));
    }

    @Test
    void ordemEhTotal() {
        List<Priority> ps = new ArrayList<>(List.of(
                new Priority(7, 0), new Priority(4, 5), new Priority(4, 2), new Priority(1, 6), new Priority(4, 0)));
        Collections.sort(ps);
        assertEquals(List.of(new Priority(1, 6), new Priority(4, 0), new Priority(4, 2), new Priority(4, 5), new Priority(7, 0)), ps);
    }

    @Test
    void igualNaoVenceIgual() {
        Priority p = new Priority(3, 3);
        assertFalse(p.beats(new Priority(3, 3)));
        assertEquals(0, p.compareTo(new Priority(3, 3)));
    }

    @Test
    void lamportClockMergeAvancaAlemDoRemoto() {
        LamportClock c = new LamportClock();
        assertEquals(1, c.tick());
        assertEquals(11, c.merge(10));
        assertEquals(12, c.merge(3));
        assertEquals(13, c.tick());
    }
}
