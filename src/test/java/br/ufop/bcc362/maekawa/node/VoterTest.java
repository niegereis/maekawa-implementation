package br.ufop.bcc362.maekawa.node;

import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.Priority;
import br.ufop.bcc362.maekawa.common.RequestId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Os tres ramos do votante (secao 4 do protocolo), em memoria, sem rede. */
class VoterTest {
    record Sent(MessageType type, int to, RequestId id) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final Outbox out = (t, to, id) -> sent.add(new Sent(t, to, id));

    private static Request req(int owner, long lamport, int seq) {
        return new Request(owner, new RequestId(owner, seq), new Priority(lamport, owner));
    }

    @Test
    void livreConcedeLocked() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(0, 5, 1));
        assertEquals(Voter.State.GRANTED, v.state());
        assertEquals(List.of(new Sent(MessageType.LOCKED, 0, new RequestId(0, 1))), sent);
    }

    @Test
    void pedidoPiorRecebeFailedEEntraNaFila() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(0, 5, 1));
        sent.clear();
        v.onRequest(req(4, 9, 1)); // (9,4) e pior que (5,0)
        assertEquals(Voter.State.GRANTED, v.state());
        assertEquals(1, v.queueSize());
        assertEquals(List.of(new Sent(MessageType.FAILED, 4, new RequestId(4, 1))), sent);
    }

    @Test
    void pedidoMelhorDisparaInquireAoDetentor() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(4, 9, 1));
        sent.clear();
        v.onRequest(req(0, 5, 1)); // (5,0) e melhor que (9,4)
        assertEquals(Voter.State.INQUIRING, v.state());
        assertEquals(List.of(new Sent(MessageType.INQUIRE, 4, new RequestId(4, 1))), sent);
    }

    @Test
    void segundoPedidoMelhorNaoReenviaInquire() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(4, 9, 1));
        v.onRequest(req(0, 5, 1));
        sent.clear();
        v.onRequest(req(3, 4, 1)); // melhor ainda; INQUIRE ja esta em aberto
        assertTrue(sent.isEmpty());
        assertEquals(2, v.queueSize());
    }

    @Test
    void relinquishConcedeAoMelhorDaFila() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(4, 9, 1));
        v.onRequest(req(0, 5, 1));
        v.onRequest(req(3, 7, 1));
        sent.clear();
        v.onRelinquish(new RequestId(4, 1));
        assertEquals(Voter.State.GRANTED, v.state());
        assertEquals(new RequestId(0, 1), v.granted().orElseThrow().id());
        assertEquals(List.of(new Sent(MessageType.LOCKED, 0, new RequestId(0, 1))), sent);
        assertEquals(2, v.queueSize()); // p4 voltou para a fila, p3 continua
    }

    @Test
    void releaseConcedeAoProximoOuFicaLivre() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(0, 5, 1));
        v.onRequest(req(4, 9, 1));
        sent.clear();
        v.onRelease(new RequestId(0, 1));
        assertEquals(Voter.State.GRANTED, v.state());
        assertEquals(List.of(new Sent(MessageType.LOCKED, 4, new RequestId(4, 1))), sent);
        v.onRelease(new RequestId(4, 1));
        assertEquals(Voter.State.FREE, v.state());
    }

    @Test
    void relinquishTardioEhIgnorado() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(0, 5, 1));
        sent.clear();
        v.onRelinquish(new RequestId(6, 3));
        assertTrue(sent.isEmpty());
        assertEquals(Voter.State.GRANTED, v.state());
    }

    @Test
    void versaoBasicaSoEnfileira() {
        Voter v = new Voter(2, Variant.BASIC, out);
        v.onRequest(req(4, 9, 1));
        sent.clear();
        v.onRequest(req(0, 5, 1)); // melhor, mas a basica nao indaga
        v.onRequest(req(3, 12, 1)); // pior, mas a basica nao avisa
        assertTrue(sent.isEmpty());
        assertEquals(Voter.State.GRANTED, v.state());
        assertEquals(2, v.queueSize());
    }

    @Test
    void nuncaHaDoisVotosVigentes() {
        Voter v = new Voter(2, Variant.RESOLUTION, out);
        v.onRequest(req(0, 5, 1));
        v.onRequest(req(4, 9, 1));
        v.onRequest(req(3, 1, 1));
        long lockedCount = sent.stream().filter(s -> s.type() == MessageType.LOCKED).count();
        assertEquals(1, lockedCount);
    }
}
