package br.ufop.bcc362.maekawa.observer;

import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.common.InMemoryNetwork;
import br.ufop.bcc362.maekawa.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** API HTTP do observador. Os nos aqui sao endpoints mudos: so recebem o MODE. */
class ObserverHttpTest {
    private static final PrintStream QUIET = new PrintStream(OutputStream.nullOutputStream());
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Timeout(20)
    void getPostEValidacao() throws Exception {
        try (InMemoryNetwork net = new InMemoryNetwork()) {
            for (int i = 0; i < 7; i++) net.endpoint("sync-" + i).start(m -> { });
            Observer obs = new Observer(net.endpoint("observer"), new EventLog("observer", null, QUIET), 7, true);
            obs.start();
            try (ObserverHttp api = new ObserverHttp(obs, 0, "*")) {
                api.start();
                String base = "http://localhost:" + api.port();

                JsonNode mode = get(base + "/mode", 200);
                assertTrue(mode.get("sync_enabled").asBoolean());
                assertEquals(0, mode.get("epoch").asLong());
                assertEquals(7, mode.get("nodes").asInt());

                JsonNode off = post(base + "/mode", "{\"sync_enabled\": false}", 200);
                assertFalse(off.get("sync_enabled").asBoolean());
                assertEquals(1, off.get("epoch").asLong());
                assertFalse(obs.syncEnabled());

                // mesmo modo de novo: sem epoca nova
                assertEquals(1, post(base + "/mode?sync_enabled=false", "", 200).get("epoch").asLong());
                assertEquals(2, post(base + "/mode?sync_enabled=true", "", 200).get("epoch").asLong());

                post(base + "/mode", "{\"sync_enabled\": \"talvez\"}", 400);
                post(base + "/mode", "{}", 400);
                post(base + "/mode", "nao e json", 400);

                JsonNode stats = get(base + "/stats", 200);
                assertEquals(0, stats.get("violations").asInt());
                assertEquals(3, stats.get("epochs").size());
                assertEquals(2, stats.get("mode").get("epoch").asLong());

                HttpResponse<String> pre = http.send(HttpRequest.newBuilder(URI.create(base + "/mode"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(204, pre.statusCode());
                assertEquals("*", pre.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            } finally {
                obs.close();
            }
        }
    }

    private JsonNode get(String url, int status) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, r.statusCode(), r.body());
        return Json.MAPPER.readTree(r.body());
    }

    private JsonNode post(String url, String body, int status) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, r.statusCode(), r.body());
        return Json.MAPPER.readTree(r.body());
    }
}
