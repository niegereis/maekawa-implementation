package br.ufop.bcc362.maekawa.node;

import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.Priority;
import br.ufop.bcc362.maekawa.common.RequestId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Estados do pedinte (secao 5 do protocolo). */
class RequesterTest {
    record Sent(MessageType type, int to, RequestId id) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final Outbox out = (t, to, id) -> sent.add(new Sent(t, to, id));
    private final RequestId id = new RequestId(0, 1);

    private Requester p0() {
        return new Requester(0, Set.of(0, 1, 2), out);
    }

    @Test
    void beginMandaRequestAosTres() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        assertEquals(Requester.State.COLLECTING, r.state());
        assertEquals(3, sent.size());
        assertTrue(sent.stream().allMatch(s -> s.type() == MessageType.REQUEST));
        assertEquals(Set.of(0, 1, 2), Set.of(sent.get(0).to(), sent.get(1).to(), sent.get(2).to()));
    }

    @Test
    void tresLockedFormamQuorum() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        assertFalse(r.onLocked(0, id));
        assertFalse(r.onLocked(2, id));
        assertTrue(r.onLocked(1, id));
        assertEquals(Requester.State.CRITICAL, r.state());
        assertEquals(Set.of(0, 1, 2), r.votes());
    }

    @Test
    void inquireAntesDoQuorumCedeOVoto() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        r.onLocked(0, id);
        r.onLocked(2, id);
        sent.clear();
        assertTrue(r.onInquire(2, id));
        assertEquals(Requester.State.YIELDING, r.state());
        assertEquals(Set.of(0), r.votes());
        assertEquals(List.of(new Sent(MessageType.RELINQUISH, 2, id)), sent);
    }

    @Test
    void inquireNaSecaoCriticaNaoCede() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        r.onLocked(0, id);
        r.onLocked(1, id);
        r.onLocked(2, id);
        sent.clear();
        assertFalse(r.onInquire(2, id));
        assertEquals(Requester.State.CRITICAL, r.state());
        assertTrue(sent.isEmpty());
    }

    @Test
    void depoisDeCederVoltaAColetarEConsegueQuorum() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        r.onLocked(0, id);
        r.onLocked(2, id);
        r.onInquire(2, id);
        assertFalse(r.onLocked(1, id));
        assertTrue(r.onLocked(2, id)); // o voto de p2 voltou
        assertEquals(Requester.State.CRITICAL, r.state());
    }

    @Test
    void releaseMandaAosTresEVoltaAoOcioso() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        r.onLocked(0, id);
        r.onLocked(1, id);
        r.onLocked(2, id);
        sent.clear();
        r.release(id);
        assertEquals(Requester.State.IDLE, r.state());
        assertEquals(3, sent.size());
        assertTrue(sent.stream().allMatch(s -> s.type() == MessageType.RELEASE));
    }

    @Test
    void lockedDePedidoAntigoEhIgnorado() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        assertFalse(r.onLocked(0, new RequestId(0, 99)));
        assertTrue(r.votes().isEmpty());
    }

    @Test
    void naoAceitaDoisPedidosAoMesmoTempo() {
        Requester r = p0();
        r.begin(id, new Priority(1, 0));
        assertThrows(IllegalStateException.class, () -> r.begin(new RequestId(0, 2), new Priority(2, 0)));
    }
}
