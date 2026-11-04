package br.ufop.bcc362.maekawa.common;

import java.util.concurrent.atomic.AtomicLong;

/** Relogio logico de Lamport. tick() antes de enviar; merge() ao receber. */
public final class LamportClock {
    private final AtomicLong value = new AtomicLong(0);

    public long now() {
        return value.get();
    }

    /** Incrementa e devolve o novo valor. Usado antes de todo envio. */
    public long tick() {
        return value.incrementAndGet();
    }

    /** max(local, remoto) + 1. Usado em todo recebimento. */
    public long merge(long remote) {
        return value.updateAndGet(local -> Math.max(local, remote) + 1);
    }
}
