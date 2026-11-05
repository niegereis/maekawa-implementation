package br.ufop.bcc362.maekawa;

import br.ufop.bcc362.maekawa.client.Client;
import br.ufop.bcc362.maekawa.common.DeliveryGate;
import br.ufop.bcc362.maekawa.common.TcpTransport;
import br.ufop.bcc362.maekawa.node.Node;
import br.ufop.bcc362.maekawa.node.NodeConfig;
import br.ufop.bcc362.maekawa.node.Quorum;
import br.ufop.bcc362.maekawa.node.Variant;
import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.observer.Observer;
import br.ufop.bcc362.maekawa.observer.ObserverHttp;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * Ponto de entrada unico. ROLE=node|client|observer escolhe o papel; o resto vem de
 * variaveis de ambiente, para o mesmo jar rodar local, no kind e no k3s.
 *
 * Variaveis comuns:
 *   PEER_ADDR_PATTERN  endereco do no i, com %d  (padrao "sync-%d.sync:7000"; local: "localhost:70%02d")
 *   OBSERVER_ADDR      (padrao "observer:7100")
 *   N_NODES            7 (Fano, K=3) ou 13 (plano projetivo de ordem 3, K=4); nos e observador
 *   RANDOM_SEED        semente base; no i usa RANDOM_SEED + i, cliente i usa RANDOM_SEED + 1000 + i.
 *                      Vazia: sorteada na subida. Em todo caso vai no evento START de cada processo.
 *
 * node:     PROCESS_ID (ou HOSTNAME sync-i), LISTEN_PORT=7000, VARIANT=basic|resolution,
 *           SYNC_ENABLED=true|false (modo inicial; o observador pode trocar em tempo de execucao),
 *           CS_MIN_MS=200, CS_MAX_MS=1000,
 *           GATE_HOLD="REQUEST:sync-0>sync-1,...", GATE_RELEASE_AFTER_MS=3000
 * client:   CLIENT_ID (ou JOB_COMPLETION_INDEX), TARGET=sync-<id>, ACCESSES=20,
 *           CONTINUOUS=false (true = pede sem parar e ignora ACCESSES; o ConfigMap base liga),
 *           THINK_MIN_MS=1000, THINK_MAX_MS=5000, START_DELAY_MS=0, REQUEST_TIMEOUT_MS=60000.
 *           Qualquer um desses quatro aceita a forma X_BY_CLIENT="v0,v1,...,v6" para carga desigual
 *           (ex.: ACCESSES_BY_CLIENT="1,1,1,0,0,0,0" no cenario de deadlock).
 * observer: LISTEN_PORT=7100, SYNC_ENABLED=true|false (modo da epoca 0, o mesmo dos nos),
 *           HTTP_PORT=8080 (API /mode e /stats para o frontend; 0 desliga), HTTP_CORS_ORIGIN="*"
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String role = env("ROLE", args.length > 0 ? args[0] : "node").toLowerCase();
        switch (role) {
            case "node" -> runNode();
            case "client" -> runClient();
            case "observer" -> runObserver();
            default -> {
                System.err.println("ROLE invalido: " + role + " (node|client|observer)");
                System.exit(2);
            }
        }
    }

    // ------------------------------------------------------------------ papeis

    private static void runNode() throws Exception {
        int pid = envInt("PROCESS_ID", -1);
        if (pid < 0) pid = NodeConfig.pidOf(env("HOSTNAME", "sync-0"));
        NodeConfig cfg = NodeConfig.defaults(pid)
                .withVariant(Variant.parse(env("VARIANT", "resolution")))
                .withSyncEnabled(envBool("SYNC_ENABLED", true))
                .withCriticalSection(envLong("CS_MIN_MS", 200), envLong("CS_MAX_MS", 1000))
                .withSeed(baseSeed() + pid);

        // O gate (so no perfil deadlock) segura REQUESTs escolhidos e abre GATE_RELEASE_AFTER_MS depois
        // de segurar o primeiro; assim o ciclo se forma mesmo que os clientes cheguem com segundos de diferenca.
        DeliveryGate gate = DeliveryGate.parse(env("GATE_HOLD", ""));
        TcpTransport transport = new TcpTransport(NodeConfig.nodeName(pid), envInt("LISTEN_PORT", 7000), resolver(), gate,
                gate.isEmpty() ? 0 : envLong("GATE_RELEASE_AFTER_MS", 3000));
        Node node = new Node(cfg, Quorum.forSize(envInt("N_NODES", 7)), transport);
        node.start();

        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        new CountDownLatch(1).await(); // o no vive ate o pod ser encerrado
    }

    private static void runClient() throws Exception {
        int id = envInt("CLIENT_ID", envInt("JOB_COMPLETION_INDEX", 0));
        Client.Config cfg = new Client.Config(
                id,
                env("TARGET", "sync-" + id),
                (int) perClient("ACCESSES", id, 20),
                perClient("THINK_MIN_MS", id, 1000),
                perClient("THINK_MAX_MS", id, 5000),
                perClient("START_DELAY_MS", id, 0),
                envLong("REQUEST_TIMEOUT_MS", 60_000),
                envBool("CONTINUOUS", false),
                baseSeed() + 1000 + id);
        TcpTransport transport = new TcpTransport("client-" + id, 0, resolver(), null);
        try (Client client = new Client(cfg, transport)) {
            client.run();
            System.exit(client.timedOut() > 0 ? 1 : 0);
        }
    }

    private static void runObserver() throws Exception {
        TcpTransport transport = new TcpTransport("observer", envInt("LISTEN_PORT", 7100), resolver(), null);
        Observer observer = new Observer(transport, new EventLog(Observer.NAME, null),
                Quorum.forSize(envInt("N_NODES", 7)).size(), envBool("SYNC_ENABLED", true));
        observer.start();
        int httpPort = envInt("HTTP_PORT", 8080);
        ObserverHttp http = null;
        if (httpPort > 0) {
            http = new ObserverHttp(observer, httpPort, env("HTTP_CORS_ORIGIN", "*"));
            http.start();
        }
        ObserverHttp httpRef = http;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (httpRef != null) httpRef.close();
            observer.close();
        }));
        new CountDownLatch(1).await();
    }

    // ------------------------------------------------------------------ rede

    /** Resolve "sync-i" e "observer"; clientes nao tem endereco (resposta volta pela conexao de entrada). */
    private static Function<String, InetSocketAddress> resolver() {
        String peerPattern = env("PEER_ADDR_PATTERN", "sync-%d.sync:7000");
        String observerAddr = env("OBSERVER_ADDR", "observer:7100");
        return name -> {
            if (name.equals("observer")) return parse(observerAddr);
            if (name.startsWith("sync-")) return parse(String.format(peerPattern, NodeConfig.pidOf(name)));
            return null;
        };
    }

    private static InetSocketAddress parse(String hostPort) {
        int i = hostPort.lastIndexOf(':');
        return InetSocketAddress.createUnresolved(hostPort.substring(0, i), Integer.parseInt(hostPort.substring(i + 1)));
    }

    // ------------------------------------------------------------------ env

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int envInt(String k, int def) {
        return Integer.parseInt(env(k, Integer.toString(def)));
    }

    private static long envLong(String k, long def) {
        return Long.parseLong(env(k, Long.toString(def)));
    }

    /** RANDOM_SEED, ou uma semente sorteada na subida (que o START registra). */
    private static long baseSeed() {
        String s = env("RANDOM_SEED", "");
        return s.isEmpty() ? new java.security.SecureRandom().nextInt(1_000_000_000) : Long.parseLong(s);
    }

    private static boolean envBool(String k, boolean def) {
        return Boolean.parseBoolean(env(k, Boolean.toString(def)));
    }

    /**
     * Valor por cliente: se existir X_BY_CLIENT="a,b,c,..." e houver posicao para este id, usa-a;
     * senao usa X; senao o padrao. Permite carga desigual sem um Job por cliente.
     */
    private static long perClient(String k, int id, long def) {
        String list = env(k + "_BY_CLIENT", "");
        if (!list.isBlank()) {
            String[] parts = list.split(",");
            if (id < parts.length && !parts[id].isBlank()) return Long.parseLong(parts[id].trim());
        }
        return envLong(k, def);
    }
}
