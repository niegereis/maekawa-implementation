package br.ufop.bcc362.maekawa.observer;

import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.common.LamportClock;
import br.ufop.bcc362.maekawa.common.Message;
import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.RequestId;
import br.ufop.bcc362.maekawa.common.Transport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observador de BEGIN/END. Conta acessos ativos a R e grava VIOLATION quando passa de 1.
 * Nao concede permissao, nao bloqueia ninguem, nao e um mutex: so registra.
 */
public final class Observer implements AutoCloseable {
    private final Transport transport;
    private final EventLog log;
    private final LamportClock clock = new LamportClock();
    private final Map<RequestId, String> active = new LinkedHashMap<>();
    private final AtomicInteger violations = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private int begins, ends;

    public Observer(Transport transport) {
        this(transport, new EventLog("observer", null));
    }

    public Observer(Transport transport, EventLog log) {
        this.transport = transport;
        this.log = log;
    }

    public void start() {
        transport.start(this::onMessage);
        log.info("observer pronto");
    }

    public int violations() {
        return violations.get();
    }

    public int maxActive() {
        return maxActive.get();
    }

    public synchronized int activeCount() {
        return active.size();
    }

    private synchronized void onMessage(Message m) {
        long lamport = clock.merge(m.lamport());
        switch (m.type()) {
            case BEGIN -> {
                begins++;
                active.put(m.requestId(), m.from());
                int n = active.size();
                maxActive.accumulateAndGet(n, Math::max);
                log.local("BEGIN", m.requestId(), lamport, null, Map.of("from", m.from(), "active", n));
                if (n > 1) {
                    violations.incrementAndGet();
                    log.local("VIOLATION", m.requestId(), lamport, null, Map.of(
                            "active", n, "holders", Map.copyOf(active)));
                }
                ack(MessageType.BEGIN_ACK, m);
            }
            case END -> {
                ends++;
                if (active.remove(m.requestId()) == null) {
                    log.local("END_WITHOUT_BEGIN", m.requestId(), lamport, null, Map.of("from", m.from()));
                } else {
                    log.local("END", m.requestId(), lamport, null, Map.of("from", m.from(), "active", active.size()));
                }
                ack(MessageType.END_ACK, m);
            }
            default -> log.info("mensagem inesperada no observador: " + m.type());
        }
    }

    /** Confirma o registro. O no espera por isso; o observador continua sem decidir nada. */
    private void ack(MessageType type, Message m) {
        long lamport = clock.tick();
        transport.send(Message.of(type, "observer", m.from(), lamport, m.requestId()));
    }

    public synchronized Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("begins", begins);
        s.put("ends", ends);
        s.put("max_active", maxActive.get());
        s.put("violations", violations.get());
        return s;
    }

    @Override
    public void close() {
        log.local("SUMMARY", null, clock.now(), null, summary());
        transport.close();
    }
}
