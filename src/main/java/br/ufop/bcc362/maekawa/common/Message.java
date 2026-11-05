package br.ufop.bcc362.maekawa.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mensagem trocada entre processos. Uma por linha JSON no fio.
 *
 * @param type      tipo da mensagem
 * @param from      nome do remetente ("sync-3", "client-0", "observer")
 * @param to        nome do destinatario
 * @param lamport   relogio de Lamport do remetente no envio
 * @param requestId pedido a que a mensagem se refere
 * @param priority  prioridade do pedido (so em REQUEST)
 * @param processId id numerico do processo remetente, quando for um no do cluster
 * @param syncEnabled modo do protocolo (so em MODE / MODE_ACK)
 * @param epoch     epoca do modo: em MODE / MODE_ACK e a epoca anunciada; em BEGIN / END e a
 *                  epoca em que o pedido foi admitido no no
 * @param csMinMs   duracao minima da secao critica (so em MODE / MODE_ACK; null = nao mexe)
 * @param csMaxMs   duracao maxima da secao critica (idem)
 * @param thinkMinMs pausa minima entre pedidos do cliente (MODE, MODE_ACK e COMMITTED; null = nao mexe)
 * @param thinkMaxMs pausa maxima entre pedidos do cliente (idem)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(@JsonProperty("type") MessageType type,
                      @JsonProperty("from") String from,
                      @JsonProperty("to") String to,
                      @JsonProperty("lamport") long lamport,
                      @JsonProperty("request_id") RequestId requestId,
                      @JsonProperty("priority") Priority priority,
                      @JsonProperty("process_id") Integer processId,
                      @JsonProperty("sync_enabled") Boolean syncEnabled,
                      @JsonProperty("epoch") Long epoch,
                      @JsonProperty("cs_min_ms") Long csMinMs,
                      @JsonProperty("cs_max_ms") Long csMaxMs,
                      @JsonProperty("think_min_ms") Long thinkMinMs,
                      @JsonProperty("think_max_ms") Long thinkMaxMs) {

    @JsonCreator
    public Message {
    }

    public static Message of(MessageType type, String from, String to, long lamport, RequestId requestId) {
        return new Message(type, from, to, lamport, requestId, null, null, null, null, null, null, null, null);
    }

    public Message withPriority(Priority p) {
        return new Message(type, from, to, lamport, requestId, p, processId, syncEnabled, epoch, csMinMs, csMaxMs, thinkMinMs, thinkMaxMs);
    }

    public Message withProcessId(int pid) {
        return new Message(type, from, to, lamport, requestId, priority, pid, syncEnabled, epoch, csMinMs, csMaxMs, thinkMinMs, thinkMaxMs);
    }

    public Message withMode(boolean sync, long ep) {
        return new Message(type, from, to, lamport, requestId, priority, processId, sync, ep, csMinMs, csMaxMs, thinkMinMs, thinkMaxMs);
    }

    public Message withEpoch(long ep) {
        return new Message(type, from, to, lamport, requestId, priority, processId, syncEnabled, ep, csMinMs, csMaxMs, thinkMinMs, thinkMaxMs);
    }

    public Message withThink(Long min, Long max) {
        return new Message(type, from, to, lamport, requestId, priority, processId, syncEnabled, epoch, csMinMs, csMaxMs, min, max);
    }

    public Message withCriticalSection(Long min, Long max) {
        return new Message(type, from, to, lamport, requestId, priority, processId, syncEnabled, epoch, min, max, thinkMinMs, thinkMaxMs);
    }
}
