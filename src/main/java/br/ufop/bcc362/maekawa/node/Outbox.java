package br.ufop.bcc362.maekawa.node;

import br.ufop.bcc362.maekawa.common.MessageType;
import br.ufop.bcc362.maekawa.common.RequestId;

/** Como Requester e Voter pedem ao Node para enviar uma mensagem de protocolo a outro no. */
@FunctionalInterface
interface Outbox {
    void send(MessageType type, int toPid, RequestId requestId);
}
