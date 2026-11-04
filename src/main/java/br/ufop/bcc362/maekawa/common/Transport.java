package br.ufop.bcc362.maekawa.common;

import java.util.function.Consumer;

/**
 * Canal de mensagens de um processo. A implementacao real e TCP; a de teste e em memoria.
 * send() nunca bloqueia a thread do protocolo: enfileira e devolve.
 */
public interface Transport extends AutoCloseable {
    /** Nome deste processo na rede ("sync-3", "client-0", "observer"). */
    String self();

    /** Entrega assincrona; mensagens para o mesmo destino chegam na ordem de envio. */
    void send(Message m);

    /** Comeca a receber; o handler e chamado uma mensagem por vez para este processo. */
    void start(Consumer<Message> handler);

    @Override
    void close();
}
