package br.ufop.bcc362.maekawa.client;

import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.common.LamportClock;
import br.ufop.bcc362.maekawa.common.Message;
import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.RequestId;
import br.ufop.bcc362.maekawa.common.Transport;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cliente: conhece um unico no do Cluster Sync. Gera request_id = (client_id, seq),
 * envia ACQUIRE_REQ, espera COMMITTED, pensa de thinkMin a thinkMax ms e repete.
 * continuous = true: pede sem parar ate o processo ser encerrado ou um pedido estourar o
 * timeout (accesses e ignorado). As rodadas oficiais usam continuous = false e 10..50 acessos.
 * accesses = 0 com continuous = false e um cliente que nao pede nada (cenario de deadlock).
 */
public final class Client implements AutoCloseable {
    /** @param seed semente do sorteio da pausa entre pedidos (registrada no START) */
    public record Config(int clientId, String target, int accesses, long thinkMinMs, long thinkMaxMs,
                         long startDelayMs, long requestTimeoutMs, boolean continuous, long seed) {
        public Config {
            if (accesses < 0) throw new IllegalArgumentException("ACCESSES deve ser >= 0: " + accesses);
        }

        public Config(int clientId, String target, int accesses, long thinkMinMs, long thinkMaxMs,
                      long startDelayMs, long requestTimeoutMs, boolean continuous) {
            this(clientId, target, accesses, thinkMinMs, thinkMaxMs, startDelayMs, requestTimeoutMs, continuous, clientId);
        }

        /** Rodada finita: accesses pedidos e fim. */
        public Config(int clientId, String target, int accesses, long thinkMinMs, long thinkMaxMs,
                      long startDelayMs, long requestTimeoutMs) {
            this(clientId, target, accesses, thinkMinMs, thinkMaxMs, startDelayMs, requestTimeoutMs, false);
        }
    }

    private final Config cfg;
    private final Transport transport;
    private final EventLog log;
    private final LamportClock clock = new LamportClock();
    private final Map<RequestId, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
    private final String name;
    private volatile int committed, timedOut, slow;

    public Client(Config cfg, Transport transport) {
        this(cfg, transport, new EventLog("client-" + cfg.clientId(), null));
    }

    public Client(Config cfg, Transport transport, EventLog log) {
        this.cfg = cfg;
        this.transport = transport;
        this.log = log;
        this.name = "client-" + cfg.clientId();
        this.rnd = new Random(cfg.seed());
    }

    private final Random rnd;
    /** Pausa entre pedidos em uso: comeca em cfg e muda quando um COMMITTED traz outra. */
    private volatile long thinkMin = -1, thinkMax = -1;

    public int committed() {
        return committed;
    }

    public int timedOut() {
        return timedOut;
    }

    /** Modo continuo: quantas vezes um pedido passou de requestTimeoutMs (e o cliente seguiu esperando). */
    public int slow() {
        return slow;
    }

    /** Pausa em uso agora: {min, max}. */
    public long[] think() {
        return new long[]{thinkMin >= 0 ? thinkMin : cfg.thinkMinMs(), thinkMax >= 0 ? thinkMax : cfg.thinkMaxMs()};
    }

    /**
     * Executa todos os acessos; retorna quando o ultimo COMMITTED chegou (ou estourou o timeout).
     * No modo continuo so retorna por timeout ou interrupcao.
     */
    public void run() throws InterruptedException {
        transport.start(this::onMessage);
        log.local("START", null, 0, null, Map.of("target", cfg.target(), "accesses", cfg.accesses(),
                "continuous", cfg.continuous(), "think_min_ms", cfg.thinkMinMs(), "think_max_ms", cfg.thinkMaxMs(),
                "seed", cfg.seed()));
        if (cfg.startDelayMs() > 0) Thread.sleep(cfg.startDelayMs());
        for (int seq = 1; cfg.continuous() || seq <= cfg.accesses(); seq++) {
            RequestId id = new RequestId(cfg.clientId(), seq);
            if (!access(id)) break;
            long tMin = thinkMin >= 0 ? thinkMin : cfg.thinkMinMs();
            long tMax = thinkMax >= 0 ? thinkMax : cfg.thinkMaxMs();
            if ((cfg.continuous() || seq < cfg.accesses()) && tMax > 0) {
                long think = tMin >= tMax ? tMin : tMin + Math.floorMod(rnd.nextLong(), tMax - tMin + 1);
                Thread.sleep(think);
            }
        }
        log.local("DONE", null, clock.now(), null, Map.of("committed", committed, "timed_out", timedOut));
    }

    private boolean access(RequestId id) throws InterruptedException {
        CompletableFuture<Message> fut = new CompletableFuture<>();
        pending.put(id, fut);
        long lamport = clock.tick();
        Message req = Message.of(MessageType.ACQUIRE_REQ, name, cfg.target(), lamport, id);
        long t0 = System.nanoTime();
        log.message("send", req, lamport, null);
        transport.send(req);
        try {
            Message resp;
            while (true) {
                try {
                    resp = fut.get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException e) {
                    // rodada finita: desiste (o verify.py usa isso para acusar travamento).
                    // continua: o pedido segue vivo nos nos; abandonar so derrubaria o Job inteiro
                    // (backoffLimit 0) por causa de uma secao critica longa. Avisa e espera mais.
                    if (!cfg.continuous()) throw e;
                    slow++;
                    log.local("SLOW_REQUEST", id, clock.now(), null, Map.of(
                            "waited_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)));
                }
            }
            long waitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            committed++;
            log.local("COMMITTED", id, clock.now(), null, Map.of("round_trip_ms", waitMs, "from", resp.from()));
            return true;
        } catch (TimeoutException e) {
            timedOut++;
            log.local("TIMEOUT", id, clock.now(), null, Map.of("after_ms", cfg.requestTimeoutMs()));
            return false;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            pending.remove(id);
        }
    }

    private void onMessage(Message m) {
        long lamport = clock.merge(m.lamport());
        if (m.type() != MessageType.COMMITTED) {
            log.info("mensagem inesperada no cliente: " + m.type());
            return;
        }
        log.message("recv", m, lamport, null);
        if (m.thinkMinMs() != null && m.thinkMaxMs() != null && (m.thinkMinMs() != thinkMin || m.thinkMaxMs() != thinkMax)) {
            thinkMin = m.thinkMinMs();
            thinkMax = m.thinkMaxMs();
            log.local("THINK_CHANGE", m.requestId(), clock.now(), null, Map.of(
                    "think_min_ms", thinkMin, "think_max_ms", thinkMax));
        }
        CompletableFuture<Message> fut = pending.get(m.requestId());
        if (fut != null) fut.complete(m);
    }

    @Override
    public void close() {
        transport.close();
    }
}
