package br.ufop.bcc362.maekawa.common;

public enum MessageType {
    // protocolo, versao basica
    REQUEST(Kind.PROTOCOL),
    LOCKED(Kind.PROTOCOL),
    RELEASE(Kind.PROTOCOL),
    // protocolo, variante de resolucao (Maekawa 1985)
    FAILED(Kind.PROTOCOL),
    INQUIRE(Kind.PROTOCOL),
    RELINQUISH(Kind.PROTOCOL),
    // cliente <-> no
    ACQUIRE_REQ(Kind.INSTRUMENTATION),
    COMMITTED(Kind.INSTRUMENTATION),
    // no -> observador, e a confirmacao de registro de volta.
    // O no espera o ACK antes de dormir (BEGIN) e antes de enviar RELEASE (END): assim o observador
    // ve o END de quem liberou antes do BEGIN de quem recebeu o voto, e nao acusa falso positivo.
    BEGIN(Kind.INSTRUMENTATION),
    END(Kind.INSTRUMENTATION),
    BEGIN_ACK(Kind.INSTRUMENTATION),
    END_ACK(Kind.INSTRUMENTATION),
    // controle do experimento: liga/desliga o Maekawa em tempo de execucao.
    // O observador e a fonte do modo; MODE vai para os nos, MODE_ACK volta, MODE_QUERY e o no
    // perguntando o modo atual ao subir.
    MODE(Kind.INSTRUMENTATION),
    MODE_ACK(Kind.INSTRUMENTATION),
    MODE_QUERY(Kind.INSTRUMENTATION);

    public enum Kind { PROTOCOL, INSTRUMENTATION }

    public final Kind kind;

    MessageType(Kind kind) {
        this.kind = kind;
    }

    public boolean isProtocol() {
        return kind == Kind.PROTOCOL;
    }
}
