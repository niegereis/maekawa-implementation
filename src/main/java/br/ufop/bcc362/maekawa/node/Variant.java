package br.ufop.bcc362.maekawa.node;

/** Qual versao do protocolo o no executa. */
public enum Variant {
    /** REQUEST, LOCKED, RELEASE. Entra em deadlock com espera circular de votos. */
    BASIC,
    /** Acrescenta FAILED, INQUIRE, RELINQUISH com prioridade por (Lamport, id). Maekawa 1985. */
    RESOLUTION;

    /** Le a variavel VARIANT ("basic"/"basica" ou "resolution"/"resolucao"); vazio = RESOLUTION. */
    public static Variant parse(String s) {
        if (s == null || s.isBlank()) return RESOLUTION;
        return switch (s.trim().toLowerCase()) {
            case "basic", "basica", "básica" -> BASIC;
            case "resolution", "resolucao", "resolução" -> RESOLUTION;
            default -> throw new IllegalArgumentException("VARIANT desconhecida: " + s);
        };
    }
}
