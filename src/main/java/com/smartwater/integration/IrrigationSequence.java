package com.smartwater.integration;

import com.google.gson.JsonObject;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.InvalidBlockException;
import com.smartwater.blockchain.LedgerReplicator;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;
import com.smartwater.irrigation.PumpAccess;

import java.util.Objects;

// Séquence imposée lors d'une irrigation, dans la section critique
public final class IrrigationSequence {

    // Calcul de l'eau attribuée (Binôme 2) : min(besoin, quotaRestant, réservoirDisponible)
    @FunctionalInterface
    public interface Allocation {
        int liters(int quotaRemaining, int reservoirRemaining);
    }

    // Réactions de Binôme 2 (compteurs, affichage)
    public interface Listener {
        // Étape 5 : mettre à jour les compteurs (quota, réservoir) — appelé seulement si le bloc est accepté
        void onIrrigated(Block block);

        default void onNothingAllocated() { }

        default void onRefused(String reason) { }
    }

    public enum Outcome { IRRIGATED, NOTHING_ALLOCATED, REFUSED, LEDGER_NOT_UP_TO_DATE }

    // Résultat d'une tentative d'irrigation. block n'est renseigné que si IRRIGATED
    public record Result(Outcome outcome, int liters, Block block, String reason) { }

    private final String nodeId;
    private final PumpAccess pump;
    private final Blockchain ledger;
    private final LedgerReplicator replicator;
    private final MessageSender sender;
    private final long syncTimeoutMs;

    public IrrigationSequence(String nodeId, PumpAccess pump, Blockchain ledger, LedgerReplicator replicator,
                              MessageSender sender, long syncTimeoutMs) {
        this.nodeId = Objects.requireNonNull(nodeId);
        this.pump = Objects.requireNonNull(pump);
        this.ledger = Objects.requireNonNull(ledger);
        this.replicator = Objects.requireNonNull(replicator);
        this.sender = Objects.requireNonNull(sender);
        this.syncTimeoutMs = syncTimeoutMs;
    }

    // Exécute la séquence complète ; la pompe est toujours libérée, même si une exception est levée
    public Result irrigate(Allocation allocation, Listener listener) {
        pump.requestPump();
        try {
            if (!ensureLedgerUpToDate()) {
                log("registre en retard sur un autre nœud et rattrapage non abouti : pas de bloc");
                return new Result(Outcome.LEDGER_NOT_UP_TO_DATE, 0, null, "registre pas à jour");
            }
            int quota = ledger.getQuota(nodeId);
            int reservoir = ledger.getReservoirRemaining();
            int liters = allocation.liters(quota, reservoir);
            if (liters <= 0) {
                listener.onNothingAllocated();
                log("attribué = 0 : pas d'irrigation, pas de bloc");
                return new Result(Outcome.NOTHING_ALLOCATED, 0, null, null);
            }

            Block block;
            // Verrou de cohérence du snapshot : bloc + compteurs + événements d'un seul bloc, sous le MÊME verrou
            // que Chandy-Lamport quand il enregistre l'état et envoie son MARKER (le client MQTT). Un snapshot
            // tombe donc avant ou après l'irrigation, jamais au milieu (registre à jour mais compteurs pas encore).
            synchronized (sender) {
                try {
                    block = ledger.addBlock(nodeId, liters, quota - liters);
                } catch (InvalidBlockException e) {
                    listener.onRefused(e.getMessage());
                    log("bloc REFUSÉ (" + e.getMessage() + ") : pas d'irrigation, compteurs inchangés");
                    return new Result(Outcome.REFUSED, liters, null, e.getMessage());
                }

                listener.onIrrigated(block);                              // compteurs, après le succès

                JsonObject event = new JsonObject();                      // IRRIGATION_EVENT puis LEDGER_UPDATE
                event.addProperty("txId", "T-" + nodeId + "-" + block.getIndex());
                event.addProperty("liters", block.getLiters());
                event.addProperty("quotaRemaining", block.getQuotaRemaining());
                event.addProperty("reservoirLevel", ledger.getReservoirRemaining());
                sender.publish(new Message(MessageType.IRRIGATION_EVENT, Message.ALL, event));
                replicator.broadcast(block);
            }

            log("irrigation de " + liters + " L (bloc " + block.getIndex() + ", quota restant "
                    + block.getQuotaRemaining() + " L)");
            return new Result(Outcome.IRRIGATED, liters, block, null);
        } finally {
            releaseAndAnnounce();                                         // toujours
        }
    }

    // Annonce la sortie au dashboard (PUMP_RELEASED
    private void releaseAndAnnounce() {
        synchronized (sender) {
            try {
                sender.publish(new Message(MessageType.PUMP_RELEASED, Message.ALL, null));
            } catch (RuntimeException e) {
                log("annonce PUMP_RELEASED non envoyée (" + e.getMessage() + ") : la pompe est libérée quand même");
            }
            pump.releasePump();
        }
    }

    // Vérifie que la copie locale est au moins aussi longue que la dernière copie connue des autres nœuds
    private boolean ensureLedgerUpToDate() {
        int known = replicator.getPeerHeads().values().stream()
                .mapToInt(LedgerReplicator.PeerHead::length).max().orElse(0);
        if (ledger.getChain().size() >= known) {
            return true;
        }
        log("copie locale plus courte (" + ledger.getChain().size() + " < " + known + ") : rattrapage avant le bloc");
        replicator.requestSync();
        long end = System.currentTimeMillis() + syncTimeoutMs;
        while (ledger.getChain().size() < known && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ledger.getChain().size() >= known;
    }

    private void log(String text) {
        System.out.println("[" + nodeId + "] irrigation : " + text);
    }
}
