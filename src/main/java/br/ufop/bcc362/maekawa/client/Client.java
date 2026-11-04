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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cliente: conhece um unico no do Cluster Sync. Gera request_id = (client_id, seq),
 * envia ACQUIRE_REQ, espera COMMITTED, pensa de thinkMin a thinkMax ms e repete.
 */
public final class Client implements AutoCloseable {
    public record Config(int clientId, String target, int accesses, long thinkMinMs, long thinkMaxMs,
                         long startDelayMs, long requestTimeoutMs) {
    }

    private final Config cfg;
    private final Transport transport;
    private final EventLog log;
    private final LamportClock clock = new LamportClock();
    private final Map<RequestId, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();
    private final String name;
    private int committed, timedOut;

    public Client(Config cfg, Transport transport) {
        this(cfg, transport, new EventLog("client-" + cfg.clientId(), null));
    }

    public Client(Config cfg, Transport transport, EventLog log) {
        this.cfg = cfg;
        this.transport = transport;
        this.log = log;
        this.name = "client-" + cfg.clientId();
    }

    public int committed() {
        return committed;
    }

    public int timedOut() {
        return timedOut;
    }

    /** Executa todos os acessos; retorna quando o ultimo COMMITTED chegou (ou estourou o timeout). */
    public void run() throws InterruptedException {
        transport.start(this::onMessage);
        log.local("START", null, 0, null, Map.of("target", cfg.target(), "accesses", cfg.accesses(),
                "think_min_ms", cfg.thinkMinMs(), "think_max_ms", cfg.thinkMaxMs()));
        if (cfg.startDelayMs() > 0) Thread.sleep(cfg.startDelayMs());
        for (int seq = 1; seq <= cfg.accesses(); seq++) {
            RequestId id = new RequestId(cfg.clientId(), seq);
            if (!access(id)) break;
            if (seq < cfg.accesses() && cfg.thinkMaxMs() > 0) {
                long think = cfg.thinkMinMs() >= cfg.thinkMaxMs() ? cfg.thinkMinMs()
                        : ThreadLocalRandom.current().nextLong(cfg.thinkMinMs(), cfg.thinkMaxMs() + 1);
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
            Message resp = fut.get(cfg.requestTimeoutMs(), TimeUnit.MILLISECONDS);
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
        CompletableFuture<Message> fut = pending.get(m.requestId());
        if (fut != null) fut.complete(m);
    }

    @Override
    public void close() {
        transport.close();
    }
}
