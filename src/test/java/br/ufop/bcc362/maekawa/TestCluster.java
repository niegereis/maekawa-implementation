package br.ufop.bcc362.maekawa;

import br.ufop.bcc362.maekawa.common.DeliveryGate;
import br.ufop.bcc362.maekawa.common.EventLog;
import br.ufop.bcc362.maekawa.common.InMemoryNetwork;
import br.ufop.bcc362.maekawa.node.Node;
import br.ufop.bcc362.maekawa.node.NodeConfig;
import br.ufop.bcc362.maekawa.node.Quorum;
import br.ufop.bcc362.maekawa.node.Variant;
import br.ufop.bcc362.maekawa.observer.Observer;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Sete nos e um observador no mesmo JVM, ligados por InMemoryNetwork. Sem estado compartilhado entre eles. */
final class TestCluster implements AutoCloseable {
    static final PrintStream QUIET = new PrintStream(OutputStream.nullOutputStream());

    final InMemoryNetwork net;
    final List<Node> nodes = new ArrayList<>();
    final Observer observer;

    TestCluster(Variant variant, long csMinMs, long csMaxMs, DeliveryGate gate) {
        this(variant, csMinMs, csMaxMs, gate, true);
    }

    TestCluster(Variant variant, long csMinMs, long csMaxMs, DeliveryGate gate, boolean syncEnabled) {
        net = gate == null ? new InMemoryNetwork() : new InMemoryNetwork(gate);
        Quorum q = Quorum.fano();
        for (int i = 0; i < q.size(); i++) {
            NodeConfig cfg = NodeConfig.defaults(i).withVariant(variant).withCriticalSection(csMinMs, csMaxMs)
                    .withSyncEnabled(syncEnabled);
            Node n = new Node(cfg, q, net.endpoint(NodeConfig.nodeName(i)), new EventLog(NodeConfig.nodeName(i), i, QUIET));
            nodes.add(n);
        }
        observer = new Observer(net.endpoint("observer"), new EventLog("observer", null, QUIET), q.size(), syncEnabled);
        observer.start();
        nodes.forEach(Node::start);
    }

    Node node(int i) {
        return nodes.get(i);
    }

    /** Troca o modo pelo observador e espera os N nos confirmarem a nova epoca. */
    void switchMode(boolean sync) throws InterruptedException {
        observer.setSyncEnabled(sync);
        awaitSettled();
    }

    void awaitSettled() throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (observer.acked() < nodes.size()) {
            if (System.nanoTime() > deadline) throw new AssertionError("nos nao confirmaram o modo: " + observer.modeStatus());
            Thread.sleep(5);
        }
    }

    @Override
    public void close() {
        nodes.forEach(Node::close);
        observer.close();
        net.close();
    }
}
