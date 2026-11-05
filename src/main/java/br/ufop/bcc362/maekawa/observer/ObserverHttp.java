package br.ufop.bcc362.maekawa.observer;

import br.ufop.bcc362.maekawa.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * API HTTP do observador, para o frontend de observabilidade. So JDK (com.sun.net.httpserver).
 *
 * GET  /mode     modo atual, epoca e quantos nos confirmaram
 * POST /mode     {"sync_enabled": false} (ou ?sync_enabled=false) liga/desliga o Maekawa nos N nos;
 *                {"cs_min_ms": 2000, "cs_max_ms": 4000} troca a duracao da secao critica simulada.
 *                Os dois podem ir juntos; pelo menos um e obrigatorio.
 * GET  /stats    contadores do observador (total e por epoca) mais o modo
 * POST /reset    zera os contadores (violacoes, BEGIN/END, maximo em R, por epoca); devolve /stats
 * GET  /healthz  "ok"
 *
 * Todas as respostas sao JSON e levam Access-Control-Allow-Origin (HTTP_CORS_ORIGIN, padrao "*"),
 * para o frontend poder chamar de outra origem.
 */
public final class ObserverHttp implements AutoCloseable {
    private final Observer observer;
    private final HttpServer server;
    private final String corsOrigin;

    public ObserverHttp(Observer observer, int port, String corsOrigin) throws IOException {
        this.observer = observer;
        this.corsOrigin = corsOrigin;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/mode", this::mode);
        server.createContext("/stats", this::stats);
        server.createContext("/reset", ex -> {
            if (preflight(ex)) return;
            if (!ex.getRequestMethod().equals("POST")) {
                send(ex, 405, Map.of("error", "use POST"));
                return;
            }
            observer.resetCounters();
            send(ex, 200, statsBody());
        });
        server.createContext("/healthz", ex -> {
            if (preflight(ex)) return;
            send(ex, 200, Map.of("status", "ok"));
        });
    }

    public void start() {
        server.start();
    }

    /** Porta efetiva (util com port 0 nos testes). */
    public int port() {
        return server.getAddress().getPort();
    }

    private void mode(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        switch (ex.getRequestMethod()) {
            case "GET" -> send(ex, 200, observer.modeStatus());
            case "POST", "PUT" -> {
                Change c;
                try {
                    c = requestedChange(ex);
                } catch (IllegalArgumentException e) {
                    send(ex, 400, Map.of("error", e.getMessage()));
                    return;
                }
                if (c.sync == null && c.csMin == null) {
                    send(ex, 400, Map.of("error", "informe sync_enabled (true|false) e/ou cs_min_ms + cs_max_ms"));
                    return;
                }
                try {
                    // valida a secao critica antes de trocar o modo, para nao aplicar meia mudanca
                    if (c.csMin != null && (c.csMin < 0 || c.csMax < c.csMin || c.csMax > Observer.CS_LIMIT_MS)) {
                        throw new IllegalArgumentException("secao critica invalida: precisa 0 <= cs_min_ms <= cs_max_ms <= "
                                + Observer.CS_LIMIT_MS);
                    }
                    Map<String, Object> status = null;
                    if (c.sync != null) status = observer.setSyncEnabled(c.sync);
                    if (c.csMin != null) status = observer.setCriticalSection(c.csMin, c.csMax);
                    send(ex, 200, status);
                } catch (IllegalArgumentException e) {
                    send(ex, 400, Map.of("error", e.getMessage()));
                }
            }
            default -> send(ex, 405, Map.of("error", "use GET ou POST"));
        }
    }

    private void stats(HttpExchange ex) throws IOException {
        if (preflight(ex)) return;
        if (!ex.getRequestMethod().equals("GET")) {
            send(ex, 405, Map.of("error", "use GET"));
            return;
        }
        send(ex, 200, statsBody());
    }

    private Map<String, Object> statsBody() {
        Map<String, Object> body = new LinkedHashMap<>(observer.summary());
        body.put("mode", observer.modeStatus());
        return body;
    }

    private record Change(Boolean sync, Long csMin, Long csMax) {
    }

    /**
     * Le a mudanca da query (?sync_enabled=false&cs_min_ms=..&cs_max_ms=..) ou do corpo JSON
     * ({"sync_enabled": false, "cs_min_ms": 2000, "cs_max_ms": 4000}). Query tem precedencia.
     */
    private static Change requestedChange(HttpExchange ex) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i < 0) continue;
                q.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        JsonNode body = null;
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            bytes = in.readNBytes(4096);
        }
        if (q.isEmpty() && bytes.length > 0) {
            try {
                body = Json.MAPPER.readTree(bytes);
            } catch (IOException e) {
                throw new IllegalArgumentException("corpo nao e JSON valido");
            }
            if (body != null && !body.isObject()) throw new IllegalArgumentException("corpo deve ser um objeto JSON");
        }
        String syncRaw = field(q, body, "sync_enabled", "sync");
        Boolean sync = syncRaw == null ? null : parseBool(syncRaw);
        String min = field(q, body, "cs_min_ms"), max = field(q, body, "cs_max_ms");
        if ((min == null) != (max == null)) throw new IllegalArgumentException("informe cs_min_ms e cs_max_ms juntos");
        Long csMin = min == null ? null : parseMs(min), csMax = max == null ? null : parseMs(max);
        return new Change(sync, csMin, csMax);
    }

    private static String field(Map<String, String> q, JsonNode body, String... names) {
        for (String n : names) {
            if (q.containsKey(n)) return q.get(n);
            if (body != null && body.has(n) && !body.get(n).isNull()) return body.get(n).asText();
        }
        return null;
    }

    private static long parseMs(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("milissegundos invalidos: " + s);
        }
    }

    private static boolean parseBool(String s) {
        return switch (s.trim().toLowerCase()) {
            case "true", "1", "on" -> true;
            case "false", "0", "off" -> false;
            default -> throw new IllegalArgumentException("sync_enabled invalido: " + s);
        };
    }

    private boolean preflight(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", corsOrigin);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (!ex.getRequestMethod().equals("OPTIONS")) return false;
        ex.sendResponseHeaders(204, -1);
        ex.close();
        return true;
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.line(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
