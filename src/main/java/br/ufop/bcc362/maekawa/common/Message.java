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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(@JsonProperty("type") MessageType type,
                      @JsonProperty("from") String from,
                      @JsonProperty("to") String to,
                      @JsonProperty("lamport") long lamport,
                      @JsonProperty("request_id") RequestId requestId,
                      @JsonProperty("priority") Priority priority,
                      @JsonProperty("process_id") Integer processId) {

    @JsonCreator
    public Message {
    }

    public static Message of(MessageType type, String from, String to, long lamport, RequestId requestId) {
        return new Message(type, from, to, lamport, requestId, null, null);
    }

    public Message withPriority(Priority p) {
        return new Message(type, from, to, lamport, requestId, p, processId);
    }

    public Message withProcessId(int pid) {
        return new Message(type, from, to, lamport, requestId, priority, pid);
    }
}
