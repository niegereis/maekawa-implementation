package br.ufop.bcc362.maekawa.common;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Segura mensagens escolhidas no lado do remetente ate release(). Serve so para o cenario
 * de deadlock reproduzivel (secao 7 do protocolo): sem ele a ordem de chegada dependeria
 * de latencia. Nos perfis normais nao ha gate.
 *
 * Formato da configuracao: "REQUEST:sync-0>sync-1,REQUEST:sync-1>sync-3,REQUEST:sync-2>sync-2"
 */
public final class DeliveryGate {
    public record Rule(MessageType type, String from, String to) {
        boolean matches(Message m) {
            return m.type() == type && m.from().equals(from) && m.to().equals(to);
        }
    }

    private final List<Rule> rules;
    private final List<Message> held = new ArrayList<>();
    private boolean open = false;

    public DeliveryGate(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static DeliveryGate parse(String spec) {
        List<Rule> rules = new ArrayList<>();
        if (spec == null || spec.isBlank()) return new DeliveryGate(rules);
        for (String part : spec.split(",")) {
            String s = part.trim();
            int colon = s.indexOf(':');
            int gt = s.indexOf('>');
            if (colon < 0 || gt < 0) throw new IllegalArgumentException("gate rule invalida: " + s);
            rules.add(new Rule(MessageType.valueOf(s.substring(0, colon)),
                    s.substring(colon + 1, gt), s.substring(gt + 1)));
        }
        return new DeliveryGate(rules);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** true se segurou a mensagem; false se ela deve seguir normalmente. */
    public synchronized boolean hold(Message m) {
        if (open) return false;
        for (Rule r : rules) {
            if (r.matches(m)) {
                held.add(m);
                return true;
            }
        }
        return false;
    }

    public synchronized int heldCount() {
        return held.size();
    }

    /** Abre o gate: entrega tudo que estava seguro, na ordem em que foi seguro, e deixa de segurar. */
    public void release(Consumer<Message> deliver) {
        List<Message> toDeliver;
        synchronized (this) {
            open = true;
            toDeliver = new ArrayList<>(held);
            held.clear();
        }
        toDeliver.forEach(deliver);
    }
}
