package br.ufop.bcc362.maekawa.node;

import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.common.LamportClock;
import br.ufop.bcc362.maekawa.common.Message;
import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.Priority;
import br.ufop.bcc362.maekawa.common.RequestId;
import br.ufop.bcc362.maekawa.common.Transport;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Um no do Cluster Sync: junta Requester e Voter, despacha mensagens e expoe
 * acquire / enter / release. Um unico lock protege o estado dos dois papeis; o sleep
 * da secao critica acontece fora do lock, para o no continuar votando enquanto dorme.
 */
public final class Node implements AutoCloseable {
    private final NodeConfig cfg;
    private final String name;
    private final Quorum quorum;
    private final Transport transport;
    private final LamportClock clock = new LamportClock();
    private final EventLog log;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition quorumReached = lock.newCondition();
    private final ReentrantLock clientSerial = new ReentrantLock(true);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private final Requester requester;
    private final Voter voter;

    private static final long OBSERVER_ACK_TIMEOUT_MS = 10_000;
    private final Map<String, CompletableFuture<Void>> pendingAcks = new ConcurrentHashMap<>();
    /** Lamport fixado em acquire() e repetido nos K REQUESTs do pedido. */
    private long pendingRequestLamport;

    public Node(NodeConfig cfg, Quorum quorum, Transport transport) {
        this(cfg, quorum, transport, new EventLog(NodeConfig.nodeName(cfg.pid()), cfg.pid()));
    }

    public Node(NodeConfig cfg, Quorum quorum, Transport transport, EventLog log) {
        this.cfg = cfg;
        this.name = NodeConfig.nodeName(cfg.pid());
        this.quorum = quorum;
        this.transport = transport;
        this.log = log;
        Outbox out = this::sendProtocol;
        this.requester = new Requester(cfg.pid(), quorum.votersOf(cfg.pid()), out);
        this.voter = new Voter(cfg.pid(), cfg.variant(), out);
    }

    public void start() {
        transport.start(this::onMessage);
        log.local("START", null, clock.now(), null, Map.of(
                "variant", cfg.variant().name(),
                "sync_enabled", cfg.syncEnabled(),
                "voters", quorum.votersOf(cfg.pid())));
    }

    public int pid() {
        return cfg.pid();
    }

    public Requester requester() {
        return requester;
    }

    public Voter voter() {
        return voter;
    }

    public LamportClock clock() {
        return clock;
    }

    // ------------------------------------------------------------------ API acquire / enter / release

    /** Bloqueia ate reunir os K votos. Com SYNC_ENABLED=false retorna na hora. */
    public void acquire(RequestId id) throws InterruptedException {
        tryAcquire(id, Long.MAX_VALUE);
    }

    /** @return false se o tempo acabou antes do quorum (o pedido continua pendente). */
    public boolean tryAcquire(RequestId id, long timeoutMs) throws InterruptedException {
        if (!cfg.syncEnabled()) {
            log.local("ACQUIRE", id, clock.now(), Set.of(), Map.of("sync_enabled", false));
            return true;
        }
        lock.lock();
        try {
            long lamport = clock.tick();
            Priority prio = new Priority(lamport, cfg.pid());
            pendingRequestLamport = lamport;
            requester.begin(id, prio);
            log.local("REQUEST_SENT", id, lamport, requester.votes(), Map.of("priority", prio));
            long deadline = timeoutMs == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (!requester.hasQuorum() || !requester.current().map(r -> r.id().equals(id)).orElse(false)) {
                if (timeoutMs == Long.MAX_VALUE) {
                    quorumReached.await();
                } else {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) return false;
                    quorumReached.awaitNanos(left);
                }
            }
            log.local("ACQUIRE", id, clock.now(), requester.votes(), null);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Secao critica simulada, fora do lock: BEGIN ao observador e espera o registro; sleep;
     * END e espera o registro. Esperar o ACK do END antes do RELEASE garante que o observador
     * ve o fim deste acesso antes do inicio de quem herdar o voto; sem isso ele acusaria
     * sobreposicao que nao existe. O observador continua sem autorizar nada: so confirma que anotou.
     */
    public void enter(RequestId id) throws InterruptedException {
        registerWithObserver(MessageType.BEGIN, MessageType.BEGIN_ACK, id);
        log.local("ENTER", id, clock.now());
        long ms = ThreadLocalRandom.current().nextLong(cfg.csMinMs(), cfg.csMaxMs() + 1);
        Thread.sleep(ms);
        log.local("EXIT", id, clock.now(), null, Map.of("held_ms", ms));
        registerWithObserver(MessageType.END, MessageType.END_ACK, id);
    }

    private void registerWithObserver(MessageType event, MessageType ack, RequestId id) throws InterruptedException {
        CompletableFuture<Void> fut = new CompletableFuture<>();
        pendingAcks.put(ackKey(ack, id), fut);
        try {
            sendInstrumentation(event, cfg.observerName(), id);
            fut.get(OBSERVER_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // o observador e instrumentacao: se ele sumir, o protocolo segue e o log registra
            log.local("OBSERVER_ACK_TIMEOUT", id, clock.now(), null, Map.of("event", event.name()));
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            pendingAcks.remove(ackKey(ack, id));
        }
    }

    private static String ackKey(MessageType ack, RequestId id) {
        return ack.name() + ":" + id.clientId() + ":" + id.seq();
    }

    /** Devolve os votos. Com SYNC_ENABLED=false nao faz nada. */
    public void release(RequestId id) {
        if (!cfg.syncEnabled()) {
            log.local("RELEASE_LOCAL", id, clock.now(), Set.of(), Map.of("sync_enabled", false));
            return;
        }
        lock.lock();
        try {
            requester.release(id);
            log.local("RELEASE_LOCAL", id, clock.now(), Set.of(), null);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ mensagens

    private void onMessage(Message m) {
        long lamport = clock.merge(m.lamport());
        switch (m.type()) {
            case ACQUIRE_REQ -> {
                log.message("recv", m, lamport, null);
                workers.submit(() -> serveClient(m));
            }
            case REQUEST, LOCKED, FAILED, INQUIRE, RELINQUISH, RELEASE -> onProtocol(m, lamport);
            case BEGIN_ACK, END_ACK -> {
                CompletableFuture<Void> fut = pendingAcks.get(ackKey(m.type(), m.requestId()));
                if (fut != null) fut.complete(null);
            }
            default -> log.info("mensagem inesperada em no: " + m.type());
        }
    }

    private void onProtocol(Message m, long lamport) {
        int from = m.processId() != null ? m.processId() : NodeConfig.pidOf(m.from());
        lock.lock();
        try {
            log.message("recv", m, lamport, requester.votes());
            switch (m.type()) {
                case REQUEST -> voter.onRequest(new Request(from, m.requestId(), m.priority()));
                case RELINQUISH -> voter.onRelinquish(m.requestId());
                case RELEASE -> voter.onRelease(m.requestId());
                case LOCKED -> {
                    if (requester.onLocked(from, m.requestId())) quorumReached.signalAll();
                }
                case FAILED -> requester.onFailed(from, m.requestId());
                case INQUIRE -> requester.onInquire(from, m.requestId());
                default -> throw new IllegalStateException(m.type().name());
            }
        } finally {
            lock.unlock();
        }
    }

    /** Fluxo completo de um pedido do cliente: acquire, enter, release, COMMITTED. */
    private void serveClient(Message req) {
        clientSerial.lock(); // um cliente por no, mas pedidos do mesmo cliente nunca se sobrepoem
        try {
            RequestId id = req.requestId();
            acquire(id);
            enter(id);
            release(id);
            sendInstrumentation(MessageType.COMMITTED, req.from(), id);
            log.local("COMMITTED", id, clock.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clientSerial.unlock();
        }
    }

    /** Chamado por Requester e Voter sob o lock. REQUEST leva a prioridade fixada no begin. */
    private void sendProtocol(MessageType type, int toPid, RequestId id) {
        long lamport;
        Message m;
        if (type == MessageType.REQUEST) {
            lamport = pendingRequestLamport; // mesmo valor nos K REQUESTs do pedido
            m = Message.of(type, name, NodeConfig.nodeName(toPid), lamport, id)
                    .withPriority(new Priority(lamport, cfg.pid()))
                    .withProcessId(cfg.pid());
        } else {
            lamport = clock.tick();
            m = Message.of(type, name, NodeConfig.nodeName(toPid), lamport, id).withProcessId(cfg.pid());
        }
        log.message("send", m, lamport, requester.votes());
        transport.send(m);
    }

    private void sendInstrumentation(MessageType type, String to, RequestId id) {
        long lamport = clock.tick();
        Message m = Message.of(type, name, to, lamport, id).withProcessId(cfg.pid());
        log.message("send", m, lamport, null);
        transport.send(m);
    }

    @Override
    public void close() {
        workers.shutdownNow();
        transport.close();
    }
}
