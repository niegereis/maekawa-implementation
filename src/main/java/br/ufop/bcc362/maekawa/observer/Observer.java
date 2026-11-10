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
import java.util.Optional;
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
    /** pausa entre pedidos anunciada; null ate alguem trocar pela API */
    private Long thinkMinMs, thinkMaxMs;
    /**
     * Carga desigual: um no (e portanto o cliente que fala com ele) com pausa propria.
     * null = sem excecao, todos usam a pausa geral.
     */
    private String thinkNode;
    private long thinkNodeMinMs, thinkNodeMaxMs;
    /** no -> [min, max] da pausa confirmada no ultimo MODE_ACK */
    private final Map<String, long[]> nodeThink = new TreeMap<>();
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

    /**
     * Troca a pausa entre pedidos dos clientes (a frequencia de pedidos). Vai aos nos no MODE e
     * deles aos clientes no proximo COMMITTED. Nao abre epoca nova.
     */
    public synchronized Map<String, Object> setThink(long min, long max) {
        return setThink(min, max, null);
    }

    /**
     * Pausa de um no especifico, para a carga desigual. O cliente i fala so com sync-i, entao
     * trocar a pausa do no troca a frequencia de pedidos daquele cliente.
     *
     * @param node  "sync-i"
     */
    public record ThinkException(String node, long minMs, long maxMs) {
    }

    /**
     * Troca a pausa geral e, junto, a excecao da carga desigual.
     *
     * @param exception {@code null} mantem a excecao atual; {@link Optional#empty()} remove;
     *                  um valor coloca (ou troca) o no com pausa propria
     */
    public synchronized Map<String, Object> setThink(long min, long max, Optional<ThinkException> exception) {
        checkThink(min, max);
        if (exception != null && exception.isPresent()) {
            ThinkException x = exception.get();
            checkThink(x.minMs(), x.maxMs());
            if (nodeIndex(x.node()) < 0) {
                throw new IllegalArgumentException("no invalido para a carga desigual: " + x.node()
                        + " (use sync-0 .. sync-" + (nodeCount - 1) + ")");
            }
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("think_min_ms", min);
        ev.put("think_max_ms", max);
        ev.put("previous_think_min_ms", thinkMinMs);
        ev.put("previous_think_max_ms", thinkMaxMs);
        thinkMinMs = min;
        thinkMaxMs = max;
        if (exception != null) {
            ev.put("previous_think_node", thinkNode);
            if (exception.isPresent()) {
                ThinkException x = exception.get();
                thinkNode = nodeName(x.node());
                thinkNodeMinMs = x.minMs();
                thinkNodeMaxMs = x.maxMs();
            } else {
                thinkNode = null;
            }
        }
        ev.put("think_unequal", thinkNode != null);
        if (thinkNode != null) {
            ev.put("think_node", thinkNode);
            ev.put("think_node_min_ms", thinkNodeMinMs);
            ev.put("think_node_max_ms", thinkNodeMaxMs);
        }
        log.local("THINK_CHANGE", null, clock.tick(), null, ev);
        for (int i = 0; i < nodeCount; i++) sendMode("sync-" + i);
        return modeStatus();
    }

    private static void checkThink(long min, long max) {
        if (min < 0 || max < min || max > CS_LIMIT_MS) {
            throw new IllegalArgumentException("pausa invalida: precisa 0 <= min <= max <= " + CS_LIMIT_MS);
        }
    }

    /** "sync-3" ou "3" -> 3; -1 se nao for um no deste cluster. */
    private int nodeIndex(String node) {
        if (node == null) return -1;
        String s = node.startsWith("sync-") ? node.substring(5) : node;
        try {
            int i = Integer.parseInt(s.trim());
            return i >= 0 && i < nodeCount ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Normaliza "3" ou "sync-3" para "sync-3"; null se invalido. */
    public String nodeName(String node) {
        int i = nodeIndex(node);
        return i < 0 ? null : "sync-" + i;
    }

    /** Pausa que este no deve repassar ao seu cliente: a excecao, se for ele, senao a geral. */
    private long[] thinkFor(String node) {
        if (node.equals(thinkNode)) return new long[]{thinkNodeMinMs, thinkNodeMaxMs};
        return thinkMinMs == null ? null : new long[]{thinkMinMs, thinkMaxMs};
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
        s.put("think_min_ms", thinkMinMs);
        s.put("think_max_ms", thinkMaxMs);
        s.put("think_unequal", thinkNode != null);
        s.put("think_node", thinkNode);
        s.put("think_node_min_ms", thinkNode == null ? null : thinkNodeMinMs);
        s.put("think_node_max_ms", thinkNode == null ? null : thinkNodeMaxMs);
        // confirmado = o no repassa exatamente a pausa que deveria (a geral ou a da excecao)
        int thinkAcked = 0, thinkTargets = 0;
        Map<String, Object> perNodeThink = new LinkedHashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            String node = "sync-" + i;
            long[] t = nodeThink.get(node);
            long[] want = thinkFor(node);
            perNodeThink.put(node, t == null ? null : List.of(t[0], t[1]));
            if (want == null) continue;
            thinkTargets++;
            if (t != null && t[0] == want[0] && t[1] == want[1]) thinkAcked++;
        }
        s.put("node_think", perNodeThink);
        s.put("think_acked", thinkTargets == 0 ? null : thinkAcked);
        s.put("think_targets", thinkTargets);
        return s;
    }

    private boolean settled() {
        return acked() == nodeCount;
    }

    private void sendMode(String node) {
        long lamport = clock.tick();
        long[] th = thinkFor(node);
        Message m = Message.of(MessageType.MODE, NAME, node, lamport, null).withMode(syncEnabled, epoch)
                .withCriticalSection(csMinMs, csMaxMs)
                .withThink(th == null ? null : th[0], th == null ? null : th[1]);
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
                if (m.thinkMinMs() != null && m.thinkMaxMs() != null) nodeThink.put(m.from(), new long[]{m.thinkMinMs(), m.thinkMaxMs()});
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
