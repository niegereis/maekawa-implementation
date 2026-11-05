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
 * POST /mode     {"sync_enabled": false} (ou ?sync_enabled=false) liga/desliga o Maekawa nos N nos
 * GET  /stats    contadores do observador (total e por epoca) mais o modo
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
                Boolean sync;
                try {
                    sync = requestedSync(ex);
                } catch (IllegalArgumentException e) {
                    send(ex, 400, Map.of("error", e.getMessage()));
                    return;
                }
                if (sync == null) {
                    send(ex, 400, Map.of("error", "informe sync_enabled (true|false) no corpo JSON ou na query"));
                    return;
                }
                send(ex, 200, observer.setSyncEnabled(sync));
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
        Map<String, Object> body = new LinkedHashMap<>(observer.summary());
        body.put("mode", observer.modeStatus());
        send(ex, 200, body);
    }

    /** Le sync_enabled da query (?sync_enabled=false) ou do corpo JSON ({"sync_enabled": false}). */
    private static Boolean requestedSync(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                if (i < 0) continue;
                String k = URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8);
                if (k.equals("sync_enabled") || k.equals("sync")) {
                    return parseBool(URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readNBytes(4096);
        }
        if (raw.length == 0) return null;
        JsonNode n;
        try {
            n = Json.MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("corpo nao e JSON valido");
        }
        JsonNode v = n == null ? null : n.has("sync_enabled") ? n.get("sync_enabled") : n.get("sync");
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.booleanValue();
        return parseBool(v.asText());
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
