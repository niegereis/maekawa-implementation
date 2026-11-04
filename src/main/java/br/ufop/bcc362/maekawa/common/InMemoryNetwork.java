package br.ufop.bcc362.maekawa.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Rede em memoria para testes: varios processos no mesmo JVM, cada um com a propria fila
 * de entrada FIFO e a propria thread de entrega. Nao ha estado compartilhado entre os
 * processos alem desta tabela de roteamento; eles so se falam por Message.
 */
public final class InMemoryNetwork implements AutoCloseable {
    private final Map<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final DeliveryGate gate;

    public InMemoryNetwork() {
        this(new DeliveryGate(java.util.List.of()));
    }

    public InMemoryNetwork(DeliveryGate gate) {
        this.gate = gate;
    }

    public Transport endpoint(String name) {
        return endpoints.computeIfAbsent(name, Endpoint::new);
    }

    public DeliveryGate gate() {
        return gate;
    }

    /** Abre o gate e entrega o que estava seguro. */
    public void releaseGate() {
        gate.release(this::route);
    }

    private void route(Message m) {
        Endpoint dst = endpoints.get(m.to());
        if (dst == null) throw new IllegalStateException("destino desconhecido: " + m.to());
        dst.inbox.add(m);
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    private final class Endpoint implements Transport {
        private final String name;
        private final LinkedBlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
        private volatile boolean running;

        Endpoint(String name) {
            this.name = name;
        }

        @Override
        public String self() {
            return name;
        }

        @Override
        public void send(Message m) {
            if (gate.hold(m)) return;
            route(m);
        }

        @Override
        public void start(Consumer<Message> handler) {
            running = true;
            pool.submit(() -> {
                while (running) {
                    try {
                        handler.accept(inbox.take());
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }

        @Override
        public void close() {
            running = false;
        }
    }
}
