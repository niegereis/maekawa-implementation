package br.ufop.bcc362.maekawa;

import br.ufop.bcc362.maekawa.common.DeliveryGate;
import br.ufop.bcc362.maekawa.common.RequestId;
import br.ufop.bcc362.maekawa.node.Node;
import br.ufop.bcc362.maekawa.node.Requester;
import br.ufop.bcc362.maekawa.node.Variant;
import br.ufop.bcc362.maekawa.node.Voter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Secao 7 do protocolo: p0, p1 e p2 pedem ao mesmo tempo; o gate segura tres REQUESTs
 * ate a espera circular estar formada. A versao basica trava; a variante conclui.
 */
class DeadlockScenarioTest {
    static final String GATE = "REQUEST:sync-0>sync-1,REQUEST:sync-1>sync-3,REQUEST:sync-2>sync-2";

    @Test
    @Timeout(30)
    void versaoBasicaEntraEmDeadlockSemFalhaNemPerda() throws Exception {
        try (TestCluster c = new TestCluster(Variant.BASIC, 50, 100, DeliveryGate.parse(GATE))) {
            AtomicInteger acquired = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                Node n = c.node(i);
                RequestId id = new RequestId(i, 1);
                Thread.ofVirtual().start(() -> {
                    try {
                        if (n.tryAcquire(id, 4000)) acquired.incrementAndGet();
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            waitForPartialVotes(c);
            assertEquals(3, c.net.gate().heldCount(), "os tres REQUESTs do ciclo estao segurados");
            c.net.releaseGate();

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(0, acquired.get(), "ninguem completa o quorum");

            // evidencia do ciclo: cada votante da interseccao esta com voto concedido e fila nao vazia
            for (int v : List.of(1, 2, 3)) {
                Voter voter = c.node(v).voter();
                assertEquals(Voter.State.GRANTED, voter.state(), "p" + v + " concedeu");
                assertTrue(voter.queueSize() >= 1, "p" + v + " tem pedido na fila");
            }
            assertEquals(Set.of(0, 2), c.node(0).requester().votes());
            assertEquals(Set.of(1, 5), c.node(1).requester().votes());
            assertEquals(Set.of(3, 6), c.node(2).requester().votes());
            assertEquals(0, c.observer.violations());
        }
    }

    @Test
    @Timeout(30)
    void varianteDeResolucaoDesfazOMesmoCiclo() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 50, 100, DeliveryGate.parse(GATE))) {
            AtomicInteger committed = new AtomicInteger();
            AtomicBoolean failure = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                Node n = c.node(i);
                RequestId id = new RequestId(i, 1);
                Thread.ofVirtual().start(() -> {
                    try {
                        n.acquire(id);
                        n.enter(id);
                        n.release(id);
                        committed.incrementAndGet();
                    } catch (Throwable t) {
                        failure.set(true);
                        t.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                });
            }

            waitForPartialVotes(c);
            c.net.releaseGate();

            assertTrue(done.await(15, TimeUnit.SECONDS), "os tres pedidos terminam");
            assertFalse(failure.get());
            assertEquals(3, committed.get());
            assertEquals(0, c.observer.violations(), "nunca houve dois em R");
            assertEquals(1, c.observer.maxActive());
            for (Node n : c.nodes) {
                assertEquals(Requester.State.IDLE, n.requester().state());
                assertEquals(Voter.State.FREE, n.voter().state(), "p" + n.pid() + " livre ao final");
            }
        }
    }

    /** Espera p0 ter {0,2}, p1 ter {1,5} e p2 ter {3,6}, como na secao 7 do protocolo. */
    private static void waitForPartialVotes(TestCluster c) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (c.node(0).requester().votes().equals(Set.of(0, 2))
                    && c.node(1).requester().votes().equals(Set.of(1, 5))
                    && c.node(2).requester().votes().equals(Set.of(3, 6))) {
                return;
            }
            Thread.sleep(10);
        }
        fail("votos parciais nao se formaram: p0=" + c.node(0).requester().votes()
                + " p1=" + c.node(1).requester().votes() + " p2=" + c.node(2).requester().votes());
    }
}
