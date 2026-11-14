package com.smartwater.snapshot;

import com.smartwater.communication.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * État global cohérent produit par Chandy-Lamport : l'instantané local de chaque nœud.
 * Format proposé par Binôme 1 (contrat, section 7), affiché par le dashboard de Binôme 2.
 *
 * @param snapshotId    identifiant, ex. "A-1" (initiateur + numéro)
 * @param initiator     nœud qui a déclenché le snapshot
 * @param nodes         instantanés locaux reçus, par nœud
 * @param expectedNodes nœuds attendus (A, B, C)
 */
public record GlobalSnapshot(String snapshotId, String initiator, Map<String, LocalSnapshot> nodes,
                             List<String> expectedNodes) {

    public GlobalSnapshot {
        nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        expectedNodes = List.copyOf(expectedNodes);
    }

    /** true quand l'instantané local de chaque nœud attendu est arrivé. */
    public boolean isComplete() {
        return nodes.keySet().containsAll(expectedNodes);
    }

    /** Tous les messages en transit au moment du snapshot, tous canaux confondus. */
    public List<Message> messagesInTransit() {
        List<Message> all = new ArrayList<>();
        for (LocalSnapshot local : nodes.values()) {
            local.channels().values().forEach(all::addAll);
        }
        return all;
    }

    /** true si toutes les copies du registre avaient la même longueur et le même dernier hash. */
    public boolean sameLedgerEverywhere() {
        LocalSnapshot first = null;
        for (LocalSnapshot local : nodes.values()) {
            if (first == null) {
                first = local;
            } else if (first.ledgerLength() != local.ledgerLength()
                    || !Objects.equals(first.ledgerLastHash(), local.ledgerLastHash())) {
                return false;
            }
        }
        return true;
    }
}
