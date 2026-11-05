package br.ufop.bcc362.maekawa;

import br.ufop.bcc362.maekawa.client.Client;
import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.common.RequestId;
import br.ufop.bcc362.maekawa.node.Node;
import br.ufop.bcc362.maekawa.node.Requester;
import br.ufop.bcc362.maekawa.node.Variant;
import br.ufop.bcc362.maekawa.node.Voter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Ligar e desligar o Maekawa em tempo de execucao, pelo observador. */
class ModeSwitchTest {

    @Test
    @Timeout(30)
    void observadorAnunciaOModoInicialAosSeteNos() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 5, 20, null, false)) {
            c.awaitSettled(); // MODE_QUERY de cada no ao subir
            assertEquals(0, c.observer.epoch());
            for (Node n : c.nodes) assertEquals(new Node.Mode(false, 0), n.mode());
        }
    }

    @Test
    @Timeout(60)
    void ligarEmTempoDeExecucaoEliminaASobreposicao() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 20, 50, null, false)) {
            c.awaitSettled();
            c.switchMode(true);
            for (Node n : c.nodes) assertEquals(new Node.Mode(true, 1), n.mode());

            List<Client> clients = runClients(c, 4, 0);
            assertEquals(28, clients.stream().mapToInt(Client::committed).sum());
            assertEquals(0, c.observer.violations());
            assertEquals(1, c.observer.maxActive());
            assertAllFree(c);
        }
    }

    @Test
    @Timeout(60)
    void desligarEmTempoDeExecucaoOObservadorVeSobreposicao() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 50, 100, null, true)) {
            c.awaitSettled();
            c.switchMode(false);
            for (Node n : c.nodes) assertFalse(n.mode().syncEnabled());

            List<Client> clients = runClients(c, 3, 0);
            assertEquals(21, clients.stream().mapToInt(Client::committed).sum());
            assertTrue(c.observer.violationsInEpoch(1) > 0, "sem Maekawa os acessos se sobrepoem");
            assertTrue(c.observer.maxActive() > 1);
        }
    }

    /** O pedido sai como entrou: votos pegos com o Maekawa ligado voltam mesmo se ele for desligado no meio. */
    @Test
    @Timeout(30)
    void pedidoAdmitidoComMaekawaDevolveOsVotosMesmoDepoisDeDesligar() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 5, 10, null, true)) {
            c.awaitSettled();
            Node p0 = c.node(0);
            RequestId id = new RequestId(0, 1);
            p0.acquire(id);

            c.switchMode(false);
            p0.enter(id);
            p0.release(id); // tem que mandar RELEASE: o pedido foi admitido na epoca 0, com Maekawa

            c.switchMode(true);
            // todo par de quoruns de Fano se cruza: se p0 tivesse prendido algum voto, nenhum outro entraria
            for (int i = 1; i < 7; i++) {
                RequestId other = new RequestId(i, 1);
                assertTrue(c.node(i).tryAcquire(other, 3_000), "p" + i + " consegue o quorum depois do release de p0");
                c.node(i).release(other);
            }
            assertAllFree(c);
        }
    }

    @Test
    @Timeout(90)
    void trocarNoMeioDaRodadaNaoTravaENaoHaViolacaoNasEpocasComMaekawa() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 5, 20, null, true)) {
            c.awaitSettled();
            int accesses = 12;
            List<Client> clients = new ArrayList<>();
            List<Thread> threads = startClients(c, accesses, clients);

            awaitBegins(c, 15);
            c.observer.setSyncEnabled(false);   // epoca 1, sem Maekawa
            awaitBegins(c, 40);
            c.observer.setSyncEnabled(true);    // epoca 2, Maekawa de novo
            for (Thread t : threads) t.join();
            c.awaitSettled();

            assertEquals(7 * accesses, clients.stream().mapToInt(Client::committed).sum(), "ninguem travou");
            assertEquals(0, clients.stream().mapToInt(Client::timedOut).sum());
            assertEquals(0, c.observer.violationsInEpoch(0), "epoca 0 (Maekawa) sem sobreposicao");
            assertEquals(0, c.observer.violationsInEpoch(2), "epoca 2 (Maekawa) sem sobreposicao");
            assertEquals(2, c.observer.epoch());
            assertAllFree(c);
        }
    }

    @Test
    @Timeout(30)
    void secaoCriticaMudaEmTempoDeExecucaoSemAbrirEpoca() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 5, 10, null, true)) {
            c.awaitSettled();
            c.observer.setCriticalSection(300, 300);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Integer.valueOf(7).equals(c.observer.modeStatus().get("cs_acked"))) {
                if (System.nanoTime() > deadline) fail("nos nao confirmaram a secao critica: " + c.observer.modeStatus());
                Thread.sleep(5);
            }
            assertEquals(0, c.observer.epoch(), "trocar a secao critica nao abre epoca");
            for (Node n : c.nodes) assertEquals(new Node.CriticalSection(300, 300), n.criticalSection());

            Node p3 = c.node(3);
            RequestId id = new RequestId(3, 1);
            p3.acquire(id);
            long t0 = System.nanoTime();
            p3.enter(id);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            p3.release(id);
            assertTrue(ms >= 300, "enter() dormiu " + ms + " ms; esperado >= 300");

            assertThrows(IllegalArgumentException.class, () -> c.observer.setCriticalSection(500, 100));
            assertThrows(IllegalArgumentException.class, () -> c.observer.setCriticalSection(-1, 100));
        }
    }

    @Test
    @Timeout(60)
    void resetZeraOsContadoresSemMexerNoModo() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 50, 100, null, false)) {
            c.awaitSettled();
            runClients(c, 3, 0);
            assertTrue(c.observer.violations() > 0);
            c.switchMode(true);

            Map<String, Object> after = c.observer.resetCounters();
            assertEquals(0, after.get("violations"));
            assertEquals(0, after.get("begins"));
            assertEquals(0, after.get("transition_violations"));
            assertEquals(1, ((List<?>) after.get("epochs")).size(), "so a epoca atual");
            assertEquals(1, c.observer.epoch(), "o modo e a epoca ficam");
            assertTrue(c.observer.syncEnabled());

            runClients(c, 2, 10); // outra leva: clientes 10..16, outros endpoints
            assertEquals(0, c.observer.violations(), "com Maekawa, depois do reset, nada");
            assertEquals(14, (int) c.observer.summary().get("begins"));
            assertEquals(1, c.observer.maxActive());
        }
    }

    @Test
    @Timeout(30)
    void clienteContinuoPedeAteSerInterrompido() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 5, 10, null, true)) {
            c.awaitSettled();
            Client.Config cfg = new Client.Config(0, "sync-0", 3, 0, 0, 0, 10_000, true);
            assertTrue(cfg.continuous());
            Client cl = new Client(cfg, c.net.endpoint("client-0"), new EventLog("client-0", null, TestCluster.QUIET));
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    cl.run();
                } catch (InterruptedException ignored) {
                }
            });
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (cl.committed() < 25) {
                if (System.nanoTime() > deadline) fail("cliente continuo parou em " + cl.committed());
                Thread.sleep(10);
            }
            assertTrue(t.isAlive(), "modo continuo nao termina sozinho");
            t.interrupt();
            t.join(5_000);
            assertFalse(t.isAlive(), "interromper encerra o cliente");
            assertEquals(0, cl.timedOut());
            assertThrows(IllegalArgumentException.class, () -> new Client.Config(0, "sync-0", -1, 0, 0, 0, 1000));

            // ACCESSES=0 sem CONTINUOUS e um cliente parado (clientes 3..6 do overlay deadlock), nao continuo
            Client idle = new Client(new Client.Config(9, "sync-1", 0, 0, 0, 0, 1000),
                    c.net.endpoint("client-9"), new EventLog("client-9", null, TestCluster.QUIET));
            Thread it = Thread.ofVirtual().start(() -> {
                try {
                    idle.run();
                } catch (InterruptedException ignored) {
                }
            });
            it.join(2_000);
            assertFalse(it.isAlive(), "ACCESSES=0 termina na hora");
            assertEquals(0, idle.committed());
        }
    }

    @Test
    @Timeout(30)
    void clienteContinuoNaoDesisteDePedidoLento() throws Exception {
        // secao critica de 300 ms e timeout de 100 ms: todo pedido "estoura", e o cliente segue
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 300, 300, null, true)) {
            c.awaitSettled();
            Client cl = new Client(new Client.Config(0, "sync-0", 1, 0, 0, 0, 100, true),
                    c.net.endpoint("client-0"), new EventLog("client-0", null, TestCluster.QUIET));
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    cl.run();
                } catch (InterruptedException ignored) {
                }
            });
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (cl.committed() < 3) {
                if (System.nanoTime() > deadline) fail("cliente continuo parou em " + cl.committed());
                Thread.sleep(10);
            }
            assertTrue(t.isAlive());
            assertEquals(0, cl.timedOut(), "continuo nao desiste");
            assertTrue(cl.slow() > 0, "mas registra o pedido lento");
            t.interrupt();
            t.join(5_000);

            // finito com o mesmo timeout desiste no primeiro pedido
            Client fin = new Client(new Client.Config(1, "sync-1", 5, 0, 0, 0, 100),
                    c.net.endpoint("client-1"), new EventLog("client-1", null, TestCluster.QUIET));
            fin.run();
            assertEquals(1, fin.timedOut());
        }
    }

    @Test
    @Timeout(30)
    void pausaEntrePedidosMudaEmTempoDeExecucao() throws Exception {
        try (TestCluster c = new TestCluster(Variant.RESOLUTION, 5, 10, null, true)) {
            c.awaitSettled();
            c.observer.setThink(0, 0);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Integer.valueOf(7).equals(c.observer.modeStatus().get("think_acked"))) {
                if (System.nanoTime() > deadline) fail("nos nao confirmaram a pausa: " + c.observer.modeStatus());
                Thread.sleep(5);
            }
            assertEquals(0, c.observer.epoch(), "trocar a pausa nao abre epoca");

            // cliente configurado com 1 s de pausa: sem a troca faria ~2 pedidos em 1,5 s
            Client cl = new Client(new Client.Config(0, "sync-0", 0, 1000, 1000, 0, 10_000, true),
                    c.net.endpoint("client-0"), new EventLog("client-0", null, TestCluster.QUIET));
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    cl.run();
                } catch (InterruptedException ignored) {
                }
            });
            Thread.sleep(1_500);
            t.interrupt();
            t.join(5_000);
            assertArrayEquals(new long[]{0, 0}, cl.think(), "o COMMITTED trouxe a pausa nova");
            assertTrue(cl.committed() >= 10, "sem pausa o cliente pede bem mais: " + cl.committed());

            assertThrows(IllegalArgumentException.class, () -> c.observer.setThink(500, 100));
        }
    }

    // ------------------------------------------------------------------ apoio

    /** @param idOffset somado ao client_id e ao nome do endpoint, para uma segunda leva no mesmo cluster */
    private static List<Client> runClients(TestCluster c, int accesses, int idOffset) throws InterruptedException {
        List<Client> clients = new ArrayList<>();
        for (Thread t : startClients(c, accesses, clients, idOffset)) t.join();
        assertEquals(0, clients.stream().mapToInt(Client::timedOut).sum());
        return clients;
    }

    private static List<Thread> startClients(TestCluster c, int accesses, List<Client> out) {
        return startClients(c, accesses, out, 0);
    }

    private static List<Thread> startClients(TestCluster c, int accesses, List<Client> out, int idOffset) {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int id = i + idOffset;
            Client.Config cfg = new Client.Config(id, "sync-" + i, accesses, 0, 0, 0, 30_000);
            Client cl = new Client(cfg, c.net.endpoint("client-" + id), new EventLog("client-" + id, null, TestCluster.QUIET));
            out.add(cl);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    cl.run();
                } catch (InterruptedException ignored) {
                }
            }));
        }
        return threads;
    }

    private static void awaitBegins(TestCluster c, int n) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while ((int) c.observer.summary().get("begins") < n) {
            if (System.nanoTime() > deadline) fail("rodada nao chegou a " + n + " BEGINs");
            Thread.sleep(2);
        }
    }

    /** O ultimo RELEASE pode ainda estar na rede quando o cliente recebe COMMITTED: espera assentar. */
    private static void assertAllFree(TestCluster c) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline && !c.nodes.stream().allMatch(n ->
                n.requester().state() == Requester.State.IDLE && n.voter().state() == Voter.State.FREE
                        && n.voter().queueSize() == 0)) {
            Thread.sleep(5);
        }
        for (Node n : c.nodes) {
            assertEquals(Requester.State.IDLE, n.requester().state(), "p" + n.pid());
            assertEquals(Voter.State.FREE, n.voter().state(), "p" + n.pid());
            assertEquals(0, n.voter().queueSize(), "p" + n.pid());
        }
    }
}
