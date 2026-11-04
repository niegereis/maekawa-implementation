package br.ufop.bcc362.maekawa;

import br.ufop.bcc362.maekawa.client.Client;
import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.node.Node;
import br.ufop.bcc362.maekawa.node.Requester;
import br.ufop.bcc362.maekawa.node.Variant;
import br.ufop.bcc362.maekawa.node.Voter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Sete clientes disparando juntos, sem pausa: alta contencao em memoria. */
class ClusterContentionTest {

    @Test
    @Timeout(60)
    void altaContencaoSemSobreposicaoETodosCommitted() throws Exception {
        int accesses = 5;
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 5, 20, null)) {
            List<Client> clients = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                Client.Config cfg = new Client.Config(i, "sync-" + i, accesses, 0, 0, 0, 30_000);
                Client cl = new Client(cfg, c.net.endpoint("client-" + i), new EventLog("client-" + i, null, TestCluster.QUIET));
                clients.add(cl);
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        cl.run();
                    } catch (InterruptedException ignored) {
                    }
                }));
            }
            for (Thread t : threads) t.join();

            int committed = clients.stream().mapToInt(Client::committed).sum();
            assertEquals(7 * accesses, committed, "todos os pedidos chegaram a COMMITTED");
            assertEquals(0, clients.stream().mapToInt(Client::timedOut).sum());
            assertEquals(0, c.observer.violations(), "observador nao viu sobreposicao");
            assertEquals(1, c.observer.maxActive());
            assertEquals(7 * accesses, (int) c.observer.summary().get("begins"));
            for (Node n : c.nodes) {
                assertEquals(Requester.State.IDLE, n.requester().state());
                assertEquals(Voter.State.FREE, n.voter().state(), "p" + n.pid());
                assertEquals(0, n.voter().queueSize());
            }
        }
    }

    @Test
    @Timeout(60)
    void semProtocoloOObservadorAcusaSobreposicao() throws Exception {
        // SYNC_ENABLED=false: acquire e release sao no-op; com 7 clientes juntos deve haver sobreposicao
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 50, 100, null, false)) {
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                Client.Config cfg = new Client.Config(i, "sync-" + i, 3, 0, 0, 0, 10_000);
                Client cl = new Client(cfg, c.net.endpoint("client-" + i), new EventLog("client-" + i, null, TestCluster.QUIET));
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        cl.run();
                    } catch (InterruptedException ignored) {
                    }
                }));
            }
            for (Thread t : threads) t.join();
            assertTrue(c.observer.violations() > 0, "a instrumentacao precisa detectar acessos simultaneos");
            assertTrue(c.observer.maxActive() > 1);
        }
    }
}
