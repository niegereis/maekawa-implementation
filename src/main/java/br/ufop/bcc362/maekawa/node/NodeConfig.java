package br.ufop.bcc362.maekawa.node;

/**
 * Parametros de um no. Tudo vem de variavel de ambiente no Main; aqui e so um record.
 *
 * @param pid            id do processo (0..N-1)
 * @param variant        BASIC ou RESOLUTION
 * @param syncEnabled    false = acquire/release viram no-op (rodada de instrumentacao)
 * @param csMinMs        duracao minima da secao critica simulada
 * @param csMaxMs        duracao maxima
 * @param observerName   nome do observador na rede
 * @param seed           semente do sorteio da duracao da secao critica (registrada no START)
 */
public record NodeConfig(int pid, Variant variant, boolean syncEnabled, long csMinMs, long csMaxMs, String observerName,
                         long seed) {
    /** Padrao do enunciado: variante com resolucao, Maekawa ligado, secao critica de 200 a 1000 ms, semente = pid. */
    public static NodeConfig defaults(int pid) {
        return new NodeConfig(pid, Variant.RESOLUTION, true, 200, 1000, "observer", pid);
    }

    /** Copia com outra variante do protocolo. */
    public NodeConfig withVariant(Variant v) {
        return new NodeConfig(pid, v, syncEnabled, csMinMs, csMaxMs, observerName, seed);
    }

    /** Copia com o Maekawa ligado ou desligado. */
    public NodeConfig withSyncEnabled(boolean e) {
        return new NodeConfig(pid, variant, e, csMinMs, csMaxMs, observerName, seed);
    }

    /** Copia com outra duracao da secao critica simulada. */
    public NodeConfig withCriticalSection(long min, long max) {
        return new NodeConfig(pid, variant, syncEnabled, min, max, observerName, seed);
    }

    /** Copia com outra semente do sorteio da secao critica. */
    public NodeConfig withSeed(long s) {
        return new NodeConfig(pid, variant, syncEnabled, csMinMs, csMaxMs, observerName, s);
    }

    /** pid -> nome do no na rede: 3 -> "sync-3". */
    public static String nodeName(int pid) {
        return "sync-" + pid;
    }

    /** Nome do no -> pid: "sync-3" -> 3. */
    public static int pidOf(String nodeName) {
        return Integer.parseInt(nodeName.substring(nodeName.lastIndexOf('-') + 1));
    }
}
