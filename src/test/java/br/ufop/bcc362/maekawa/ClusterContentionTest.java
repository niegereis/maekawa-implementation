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

    @Test
    @Timeout(60)
    void trezeNosComKQuatroSemSobreposicao() throws Exception {
        try (TestCluster c = new TestCluster(br.ufop.bcc362.maekawa.node.Quorum.forSize(13), Variant.RESOLUTION, 5, 20, null, true)) {
            List<Client> clients = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                Client.Config cfg = new Client.Config(i, "sync-" + i, 3, 0, 0, 0, 30_000);
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
            assertEquals(39, clients.stream().mapToInt(Client::committed).sum());
            assertEquals(0, c.observer.violations());
            assertEquals(1, c.observer.maxActive());
        }
    }

    @Test
    void startRegistraSementeNEsecaoCritica() {
        var buf = new java.io.ByteArrayOutputStream();
        var net = new br.ufop.bcc362.maekawa.common.InMemoryNetwork();
        net.endpoint("observer").start(m -> { });
        var cfg = br.ufop.bcc362.maekawa.node.NodeConfig.defaults(2).withSeed(4242).withCriticalSection(300, 700);
        try (var node = new Node(cfg, br.ufop.bcc362.maekawa.node.Quorum.fano(), net.endpoint("sync-2"),
                new EventLog("sync-2", 2, new java.io.PrintStream(buf, true)))) {
            node.start();
        }
        net.close();
        String start = buf.toString().lines().filter(l -> l.contains("\"START\"")).findFirst().orElseThrow();
        assertTrue(start.contains("\"seed\":4242"), start);
        assertTrue(start.contains("\"n_nodes\":7"), start);
        assertTrue(start.contains("\"cs_min_ms\":300") && start.contains("\"cs_max_ms\":700"), start);
    }

    @Test
    @Timeout(60)
    void quatroNosEmGradeSemSobreposicao() throws Exception {
        try (TestCluster c = new TestCluster(br.ufop.bcc362.maekawa.node.Quorum.forSize(4), Variant.RESOLUTION, 5, 20, null, true)) {
            List<Client> clients = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Client.Config cfg = new Client.Config(i, "sync-" + i, 5, 0, 0, 0, 30_000);
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
            assertEquals(20, clients.stream().mapToInt(Client::committed).sum());
            assertEquals(0, c.observer.violations());
            assertEquals(1, c.observer.maxActive());
        }
    }
}
