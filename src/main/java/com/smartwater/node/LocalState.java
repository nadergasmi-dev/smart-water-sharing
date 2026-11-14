package com.smartwater.node;

import java.util.List;

/**
 * État local d'un nœud, enregistré par Chandy-Lamport (contrat, section 7).
 *
 * PROPOSITION de Binôme 1, à valider par Binôme 2 (point ouvert du contrat, section 11) :
 * Binôme 2 peut ajouter des champs, à condition de garder une copie immuable et cohérente.
 *
 * @param nodeId          A, B ou C
 * @param humidity        humidité simulée du sol (en %)
 * @param quotaRemaining  quota restant du nœud, en litres
 * @param pumpState       état Ricart-Agrawala : "RELEASED", "WANTED" ou "HELD"
 * @param deferredReplies nœuds dont la demande a été différée (REPLY en attente)
 */
public record LocalState(String nodeId, double humidity, int quotaRemaining, String pumpState,
                         List<String> deferredReplies) {

    public LocalState {
        deferredReplies = deferredReplies == null ? List.of() : List.copyOf(deferredReplies);
    }
}
