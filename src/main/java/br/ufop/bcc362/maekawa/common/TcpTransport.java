package br.ufop.bcc362.maekawa.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Transporte TCP com uma mensagem JSON por linha.
 *
 * - Uma conexao de saida persistente por destino conhecido (resolvido por nome).
 * - Para destinos sem endereco (clientes efemeros de um Job), a resposta volta pela
 *   conexao de entrada em que o destino falou pela ultima vez.
 * - Toda leitura e escrita acontece em threads virtuais; o handler do protocolo roda em
 *   uma unica thread de entrega, uma mensagem por vez.
 * - Reconecta com espera fixa: pods do StatefulSet sobem em ordem e os pares podem ainda
 *   nao estar ouvindo.
 */
public final class TcpTransport implements Transport {
    private static final long RECONNECT_MS = 500;

    private final String self;
    private final int listenPort;
    private final Function<String, InetSocketAddress> resolver;
    private final DeliveryGate gate;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final LinkedBlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    private final Map<String, Outbound> outbound = new ConcurrentHashMap<>();
    private final Map<String, BufferedWriter> replyChannels = new ConcurrentHashMap<>();
    private final long gateReleaseAfterMs;
    private final AtomicBoolean gateTimerStarted = new AtomicBoolean();
    private volatile boolean running;
    private ServerSocket server;

    public TcpTransport(String self, int listenPort, Function<String, InetSocketAddress> resolver, DeliveryGate gate) {
        this(self, listenPort, resolver, gate, 0);
    }

    /**
     * @param gateReleaseAfterMs se > 0, o gate abre sozinho esse tempo depois de segurar a PRIMEIRA
     *                           mensagem (nao depois da subida do processo: os clientes podem demorar a chegar)
     */
    public TcpTransport(String self, int listenPort, Function<String, InetSocketAddress> resolver, DeliveryGate gate,
                        long gateReleaseAfterMs) {
        this.self = self;
        this.listenPort = listenPort;
        this.resolver = resolver;
        this.gate = gate == null ? new DeliveryGate(java.util.List.of()) : gate;
        this.gateReleaseAfterMs = gateReleaseAfterMs;
    }

    @Override
    public String self() {
        return self;
    }

    @Override
    public void send(Message m) {
        if (gate.hold(m)) {
            if (gateReleaseAfterMs > 0 && gateTimerStarted.compareAndSet(false, true)) {
                pool.submit(() -> {
                    try {
                        Thread.sleep(gateReleaseAfterMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                    System.err.println(self + ": gate aberto apos " + gateReleaseAfterMs + " ms; liberando "
                            + gate.heldCount() + " mensagem(ns)");
                    releaseGate();
                });
            }
            return;
        }
        route(m);
    }

    /** Abre o gate e envia o que estava seguro. */
    public void releaseGate() {
        gate.release(this::route);
    }

    private void route(Message m) {
        InetSocketAddress addr = resolver.apply(m.to());
        if (addr != null) {
            outbound.computeIfAbsent(m.to(), to -> new Outbound(to, addr)).queue.add(m);
            return;
        }
        BufferedWriter reply = replyChannels.get(m.to());
        if (reply == null) throw new IllegalStateException(self + ": sem rota para " + m.to());
        pool.submit(() -> {
            try {
                writeLine(reply, m);
            } catch (IOException e) {
                System.err.println(self + ": falha ao responder a " + m.to() + ": " + e.getMessage());
            }
        });
    }

    @Override
    public void start(Consumer<Message> handler) {
        running = true;
        if (listenPort > 0) {
            try {
                server = new ServerSocket(listenPort);
            } catch (IOException e) {
                throw new IllegalStateException("nao consegui ouvir em " + listenPort, e);
            }
            pool.submit(this::acceptLoop);
        }
        pool.submit(() -> {
            while (running) {
                try {
                    handler.accept(inbox.take());
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    System.err.println(self + ": erro no handler: " + e);
                    e.printStackTrace(System.err);
                }
            }
        });
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                pool.submit(() -> readLoop(s));
            } catch (IOException e) {
                if (running) System.err.println(self + ": accept falhou: " + e.getMessage());
            }
        }
    }

    private void readLoop(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                Message m = Json.parse(line);
                replyChannels.put(m.from(), out);
                inbox.add(m);
            }
        } catch (IOException e) {
            // par fechou; nada a fazer alem de encerrar esta conexao
        }
    }

    private static void writeLine(BufferedWriter out, Message m) throws IOException {
        synchronized (out) {
            out.write(Json.line(m));
            out.write('\n');
            out.flush();
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        outbound.values().forEach(Outbound::close);
        pool.shutdownNow();
    }

    /** Conexao de saida persistente para um destino, com fila propria e reconexao. */
    private final class Outbound {
        final String to;
        final InetSocketAddress addr;
        final LinkedBlockingQueue<Message> queue = new LinkedBlockingQueue<>();
        volatile Socket socket;

        Outbound(String to, InetSocketAddress addr) {
            this.to = to;
            this.addr = addr;
            pool.submit(this::writeLoop);
        }

        void writeLoop() {
            BufferedWriter out = null;
            Message pending = null;
            while (running) {
                try {
                    if (out == null) {
                        socket = new Socket();
                        // resolve o DNS a cada tentativa: o par pode ainda nao existir no servico headless
                        socket.connect(new InetSocketAddress(addr.getHostString(), addr.getPort()), 2000);
                        socket.setTcpNoDelay(true);
                        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                        // mantem a conexao viva lendo (e descartando) o que o par mandar por ela
                        Socket s = socket;
                        pool.submit(() -> readLoop(s));
                    }
                    if (pending == null) pending = queue.take();
                    writeLine(out, pending);
                    pending = null;
                } catch (InterruptedException e) {
                    return;
                } catch (IOException e) {
                    out = null;
                    closeSocket();
                    try {
                        Thread.sleep(RECONNECT_MS);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }

        void closeSocket() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
        }

        void close() {
            closeSocket();
        }
    }
}
