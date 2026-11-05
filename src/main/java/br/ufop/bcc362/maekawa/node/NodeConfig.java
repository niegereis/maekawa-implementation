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
    public static NodeConfig defaults(int pid) {
        return new NodeConfig(pid, Variant.RESOLUTION, true, 200, 1000, "observer", pid);
    }

    public NodeConfig withVariant(Variant v) {
        return new NodeConfig(pid, v, syncEnabled, csMinMs, csMaxMs, observerName, seed);
    }

    public NodeConfig withSyncEnabled(boolean e) {
        return new NodeConfig(pid, variant, e, csMinMs, csMaxMs, observerName, seed);
    }

    public NodeConfig withCriticalSection(long min, long max) {
        return new NodeConfig(pid, variant, syncEnabled, min, max, observerName, seed);
    }

    public NodeConfig withSeed(long s) {
        return new NodeConfig(pid, variant, syncEnabled, csMinMs, csMaxMs, observerName, s);
    }

    public static String nodeName(int pid) {
        return "sync-" + pid;
    }

    public static int pidOf(String nodeName) {
        return Integer.parseInt(nodeName.substring(nodeName.lastIndexOf('-') + 1));
    }
}
