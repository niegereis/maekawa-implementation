package br.ufop.bcc362.maekawa.observer;

import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.common.LamportClock;
import br.ufop.bcc362.maekawa.common.Message;
import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.RequestId;
import br.ufop.bcc362.maekawa.common.Transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observador de BEGIN/END. Conta acessos ativos a R e grava VIOLATION quando passa de 1.
 * Nao concede permissao, nao bloqueia ninguem, nao e um mutex: so registra.
 *
 * Tambem guarda o modo do experimento (Maekawa ligado ou desligado) e o anuncia aos nos.
 * Continua sem decidir quem entra: o modo so diz se os nos pedem votos antes de entrar.
 * Cada troca abre uma epoca nova. Uma violacao conta para a epoca se, no momento dela,
 * os N nos ja confirmaram a epoca e todos os acessos ativos foram admitidos nela; senao
 * ela fica na conta da transicao (pedidos do modo antigo ainda na secao critica).
 *
 * A duracao da secao critica simulada tambem pode ser trocada daqui (vai no mesmo MODE).
 * Ela nao abre epoca nova: nao muda a corretude, so o ritmo. Enquanto ninguem a troca,
 * o MODE vai sem ela e cada no fica com o CS_MIN_MS/CS_MAX_MS com que subiu.
 */
public final class Observer implements AutoCloseable {
    public static final String NAME = "observer";

    private final Transport transport;
    private final EventLog log;
    private final LamportClock clock = new LamportClock();
    private final int nodeCount;
    /** pedido ativo -> no que o executa */
    private final Map<RequestId, String> active = new LinkedHashMap<>();
    /** pedido ativo -> epoca em que o no o admitiu */
    private final Map<RequestId, Long> activeEpoch = new LinkedHashMap<>();
    private final AtomicInteger violations = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private int begins, ends;

    private boolean syncEnabled;
    private long epoch;
    private long changedAtNs = System.nanoTime();
    /** no -> ultima epoca confirmada por MODE_ACK */
    private final Map<String, Long> nodeEpoch = new TreeMap<>();
    private final Map<Long, EpochStats> epochs = new TreeMap<>();
    /** secao critica anunciada; null ate alguem trocar pela API */
    private Long csMinMs, csMaxMs;
    /** no -> [min, max] confirmado no ultimo MODE_ACK */
    private final Map<String, long[]> nodeCs = new TreeMap<>();
    public static final long CS_LIMIT_MS = 60_000;
    private int transitionViolations;

    public Observer(Transport transport) {
        this(transport, new EventLog(NAME, null), 7, true);
    }

    public Observer(Transport transport, EventLog log) {
        this(transport, log, 7, true);
    }

    /**
     * @param nodeCount   quantos nos (sync-0..sync-N-1) recebem o modo
     * @param initialSync modo da epoca 0; deve ser o mesmo SYNC_ENABLED dos nos
     */
    public Observer(Transport transport, EventLog log, int nodeCount, boolean initialSync) {
        this.transport = transport;
        this.log = log;
        this.nodeCount = nodeCount;
        this.syncEnabled = initialSync;
        epochs.put(0L, new EpochStats(initialSync));
    }

    public void start() {
        transport.start(this::onMessage);
        log.local("MODE_INIT", null, clock.now(), null, Map.of("sync_enabled", syncEnabled, "epoch", epoch));
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

    // ------------------------------------------------------------------ modo

    /**
     * Troca o modo e anuncia aos N nos. Pedir o mesmo modo de novo nao abre epoca nova,
     * mas reenvia o anuncio (util se algum no nao confirmou).
     */
    public synchronized Map<String, Object> setSyncEnabled(boolean sync) {
        if (sync != syncEnabled) {
            boolean prev = syncEnabled;
            syncEnabled = sync;
            epoch++;
            changedAtNs = System.nanoTime();
            epochs.put(epoch, new EpochStats(sync));
            log.local("MODE_CHANGE", null, clock.tick(), null, Map.of(
                    "sync_enabled", sync, "epoch", epoch, "previous_sync_enabled", prev, "active", active.size()));
        }
        for (int i = 0; i < nodeCount; i++) {
            String node = "sync-" + i;
            if (!Long.valueOf(epoch).equals(nodeEpoch.get(node))) sendMode(node);
        }
        return modeStatus();
    }

    /**
     * Troca a duracao da secao critica simulada nos N nos, a partir do proximo acesso de cada um.
     * Nao abre epoca nova.
     */
    public synchronized Map<String, Object> setCriticalSection(long min, long max) {
        if (min < 0 || max < min || max > CS_LIMIT_MS) {
            throw new IllegalArgumentException("secao critica invalida: precisa 0 <= min <= max <= " + CS_LIMIT_MS);
        }
        Long prevMin = csMinMs, prevMax = csMaxMs;
        csMinMs = min;
        csMaxMs = max;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("cs_min_ms", min);
        ev.put("cs_max_ms", max);
        ev.put("previous_cs_min_ms", prevMin);
        ev.put("previous_cs_max_ms", prevMax);
        log.local("CS_CHANGE", null, clock.tick(), null, ev);
        for (int i = 0; i < nodeCount; i++) sendMode("sync-" + i);
        return modeStatus();
    }

    public synchronized boolean syncEnabled() {
        return syncEnabled;
    }

    public synchronized long epoch() {
        return epoch;
    }

    /** Quantos nos confirmaram a epoca atual. */
    public synchronized int acked() {
        int n = 0;
        for (Long e : nodeEpoch.values()) if (e == epoch) n++;
        return n;
    }

    public synchronized Map<String, Object> modeStatus() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sync_enabled", syncEnabled);
        s.put("epoch", epoch);
        s.put("nodes", nodeCount);
        s.put("acked", acked());
        s.put("settled", settled());
        s.put("since_ms", (System.nanoTime() - changedAtNs) / 1_000_000);
        Map<String, Object> perNode = new LinkedHashMap<>();
        for (int i = 0; i < nodeCount; i++) perNode.put("sync-" + i, nodeEpoch.get("sync-" + i));
        s.put("node_epochs", perNode);
        s.put("cs_min_ms", csMinMs);
        s.put("cs_max_ms", csMaxMs);
        Map<String, Object> perNodeCs = new LinkedHashMap<>();
        int csAcked = 0;
        for (int i = 0; i < nodeCount; i++) {
            long[] c = nodeCs.get("sync-" + i);
            perNodeCs.put("sync-" + i, c == null ? null : List.of(c[0], c[1]));
            if (c != null && csMinMs != null && c[0] == csMinMs && c[1] == csMaxMs) csAcked++;
        }
        s.put("node_cs", perNodeCs);
        s.put("cs_acked", csMinMs == null ? null : csAcked);
        return s;
    }

    private boolean settled() {
        return acked() == nodeCount;
    }

    private void sendMode(String node) {
        long lamport = clock.tick();
        Message m = Message.of(MessageType.MODE, NAME, node, lamport, null).withMode(syncEnabled, epoch)
                .withCriticalSection(csMinMs, csMaxMs);
        log.message("send", m, lamport, null);
        transport.send(m);
    }

    // ------------------------------------------------------------------ mensagens

    private synchronized void onMessage(Message m) {
        long lamport = clock.merge(m.lamport());
        switch (m.type()) {
            case BEGIN -> {
                begins++;
                long reqEpoch = m.epoch() == null ? epoch : m.epoch();
                active.put(m.requestId(), m.from());
                activeEpoch.put(m.requestId(), reqEpoch);
                int n = active.size();
                maxActive.accumulateAndGet(n, Math::max);
                epochs.computeIfAbsent(reqEpoch, e -> new EpochStats(syncEnabled)).begins++;
                // janela limpa: todos os nos na epoca atual e nenhum acesso do modo antigo ativo
                boolean inEpoch = settled() && activeEpoch.values().stream().allMatch(e -> e == epoch);
                if (inEpoch) {
                    EpochStats cur = epochs.get(epoch);
                    cur.maxActive = Math.max(cur.maxActive, n);
                }
                log.local("BEGIN", m.requestId(), lamport, null, Map.of(
                        "from", m.from(), "active", n, "epoch", reqEpoch));
                if (n > 1) {
                    violations.incrementAndGet();
                    if (inEpoch) epochs.get(epoch).violations++;
                    else transitionViolations++;
                    log.local("VIOLATION", m.requestId(), lamport, null, Map.of(
                            "active", n, "holders", Map.copyOf(active),
                            "epoch", epoch, "transition", !inEpoch));
                }
                ack(MessageType.BEGIN_ACK, m);
            }
            case END -> {
                ends++;
                activeEpoch.remove(m.requestId());
                if (active.remove(m.requestId()) == null) {
                    log.local("END_WITHOUT_BEGIN", m.requestId(), lamport, null, Map.of("from", m.from()));
                } else {
                    log.local("END", m.requestId(), lamport, null, Map.of("from", m.from(), "active", active.size()));
                }
                ack(MessageType.END_ACK, m);
            }
            case MODE_QUERY -> sendMode(m.from());
            case MODE_ACK -> {
                if (m.epoch() == null) break;
                boolean wasSettled = settled();
                nodeEpoch.put(m.from(), m.epoch());
                if (m.csMinMs() != null && m.csMaxMs() != null) nodeCs.put(m.from(), new long[]{m.csMinMs(), m.csMaxMs()});
                if (!wasSettled && settled()) {
                    log.local("MODE_SETTLED", null, lamport, null, Map.of(
                            "sync_enabled", syncEnabled, "epoch", epoch,
                            "after_ms", (System.nanoTime() - changedAtNs) / 1_000_000));
                }
            }
            default -> log.info("mensagem inesperada no observador: " + m.type());
        }
        // no que ainda nao confirmou a epoca atual (ex.: o observador reiniciou) recebe o modo
        if (m.from().startsWith("sync-") && m.type() != MessageType.MODE_ACK && m.type() != MessageType.MODE_QUERY
                && !Long.valueOf(epoch).equals(nodeEpoch.get(m.from()))) {
            nodeEpoch.put(m.from(), -1L); // pendente: evita reenviar a cada BEGIN/END ate o ACK chegar
            sendMode(m.from());
        }
    }

    /** Confirma o registro. O no espera por isso; o observador continua sem decidir nada. */
    private void ack(MessageType type, Message m) {
        long lamport = clock.tick();
        transport.send(Message.of(type, NAME, m.from(), lamport, m.requestId()));
    }

    /**
     * Zera os contadores (violacoes, BEGIN/END, maximo em R, estatisticas por epoca), para medir
     * um trecho da rodada do zero. Nao mexe no que e estado: quem esta em R agora, o modo, a
     * epoca e as confirmacoes dos nos. O log guarda COUNTERS_RESET com os valores anteriores.
     */
    public synchronized Map<String, Object> resetCounters() {
        Map<String, Object> before = summary();
        begins = 0;
        ends = 0;
        violations.set(0);
        transitionViolations = 0;
        maxActive.set(active.size());
        epochs.clear();
        epochs.put(epoch, new EpochStats(syncEnabled));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("epoch", epoch);
        ev.put("active", active.size());
        ev.put("before", before);
        log.local("COUNTERS_RESET", null, clock.tick(), null, ev);
        return summary();
    }

    public synchronized Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("begins", begins);
        s.put("ends", ends);
        s.put("max_active", maxActive.get());
        s.put("violations", violations.get());
        s.put("active", active.size());
        s.put("transition_violations", transitionViolations);
        List<Map<String, Object>> list = new ArrayList<>();
        epochs.forEach((e, st) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("epoch", e);
            x.put("sync_enabled", st.syncEnabled);
            x.put("begins", st.begins);
            x.put("max_active", st.maxActive);
            x.put("violations", st.violations);
            list.add(x);
        });
        s.put("epochs", list);
        return s;
    }

    /** Violacoes contadas numa epoca ja estavel (fora da transicao). */
    public synchronized int violationsInEpoch(long e) {
        EpochStats st = epochs.get(e);
        return st == null ? 0 : st.violations;
    }

    public synchronized int transitionViolations() {
        return transitionViolations;
    }

    @Override
    public void close() {
        log.local("SUMMARY", null, clock.now(), null, summary());
        transport.close();
    }

    private static final class EpochStats {
        final boolean syncEnabled;
        int begins, maxActive, violations;

        EpochStats(boolean syncEnabled) {
            this.syncEnabled = syncEnabled;
        }
    }
}
