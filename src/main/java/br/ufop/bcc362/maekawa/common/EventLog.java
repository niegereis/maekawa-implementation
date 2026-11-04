package br.ufop.bcc362.maekawa.common;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uma linha JSON por evento em stdout. O Kubernetes recolhe via `kubectl logs`.
 * Campos minimos da secao 6.1 do plano. Thread-safe pelo lock do PrintStream.
 */
public final class EventLog {
    private final String processName;
    private final Integer processId;
    private final PrintStream out;

    public EventLog(String processName, Integer processId) {
        this(processName, processId, System.out);
    }

    public EventLog(String processName, Integer processId, PrintStream out) {
        this.processName = processName;
        this.processId = processId;
        this.out = out;
    }

    /** Evento de protocolo ou instrumentacao, enviado ou recebido. */
    public void message(String direction, Message m, long lamport, Collection<Integer> quorumGranted) {
        Map<String, Object> e = base(m.type().name(), lamport);
        e.put("kind", m.type().isProtocol() ? "protocol" : "instrumentation");
        e.put("direction", direction);
        e.put("peer", direction.equals("send") ? m.to() : m.from());
        e.put("request_id", m.requestId());
        if (m.priority() != null) e.put("priority", m.priority());
        if (quorumGranted != null) e.put("quorum_granted", quorumGranted);
        write(e);
    }

    /** Evento local: ACQUIRE, ENTER, EXIT, COMMITTED, VIOLATION, INFO... */
    public void local(String event, RequestId requestId, long lamport, Collection<Integer> quorumGranted, Map<String, ?> extra) {
        Map<String, Object> e = base(event, lamport);
        e.put("kind", "local");
        if (requestId != null) e.put("request_id", requestId);
        if (quorumGranted != null) e.put("quorum_granted", quorumGranted);
        if (extra != null) e.putAll(extra);
        write(e);
    }

    public void local(String event, RequestId requestId, long lamport) {
        local(event, requestId, lamport, null, null);
    }

    public void info(String text) {
        Map<String, Object> e = base("INFO", 0);
        e.put("kind", "local");
        e.put("text", text);
        write(e);
    }

    private Map<String, Object> base(String event, long lamport) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event", event);
        e.put("process", processName);
        if (processId != null) e.put("process_id", processId);
        e.put("lamport", lamport);
        e.put("ts_monotonic_ns", System.nanoTime());
        e.put("wall_clock", Instant.now().toString());
        return e;
    }

    private void write(Map<String, Object> e) {
        out.println(Json.line(e));
    }
}
