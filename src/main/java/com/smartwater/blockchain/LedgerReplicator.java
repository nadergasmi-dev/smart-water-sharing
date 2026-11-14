package com.smartwater.blockchain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageListener;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Réplication du registre entre les nœuds (contrat, section 4.2).
 *
 * <ul>
 *   <li>{@link #broadcast(Block)} : diffuse un bloc ajouté localement (LEDGER_UPDATE).</li>
 *   <li>Réception d'un LEDGER_UPDATE : le bloc repasse par les 7 règles ({@link Blockchain#acceptBlock}) ;
 *       refusé → <b>divergence signalée avec la raison</b>.</li>
 *   <li>Rattrapage : un nœud en retard (trou dans les index) envoie LEDGER_REQUEST ; les autres répondent
 *       par LEDGER_SYNC avec les blocs manquants, qui sont validés un par un avant d'être ajoutés.</li>
 * </ul>
 *
 * <b>Ce n'est pas un consensus</b> : les blocs invalides sont refusés, mais deux versions valides
 * concurrentes (fourche) sont seulement signalées, jamais départagées.
 *
 * Sans {@link MessageSender} (null), le réplicateur est <b>passif</b> (dashboard) : il suit le registre
 * à partir des messages qu'il voit passer, sans jamais rien envoyer.
 */
public class LedgerReplicator implements MessageListener {

    /** Types à inscrire auprès du MessageHandler : {@code handler.register(replicator, LedgerReplicator.TYPES)}. */
    public static final MessageType[] TYPES = {
            MessageType.LEDGER_UPDATE, MessageType.LEDGER_REQUEST, MessageType.LEDGER_SYNC };

    /** Dernier état connu de la copie d'un autre nœud : longueur et hash du dernier bloc. */
    public record PeerHead(String nodeId, int length, String lastHash, long time) { }

    /** Un bloc reçu qui n'a pas pu être ajouté, avec la raison (pour les logs et le dashboard). */
    public record Divergence(String peer, int index, String reason, long time) { }

    private static final Gson GSON = new Gson();
    /** Pas plus d'une demande de rattrapage identique toutes les 2 s (plusieurs blocs peuvent signaler le même trou). */
    private static final long SYNC_RETRY_MS = 2000;
    private static final int MAX_DIVERGENCES = 1000;

    private enum Outcome { ADDED, ALREADY_PRESENT, GAP, REFUSED }

    private final String nodeId;
    private final Blockchain ledger;
    private final MessageSender sender;   // null = passif

    private final Map<String, PeerHead> peerHeads = new ConcurrentHashMap<>();
    private final List<Divergence> divergences = new ArrayList<>();
    private int lastSyncRequestFrom = -1;
    private long lastSyncRequestTime;

    /**
     * @param nodeId nom du nœud (pour les logs) ; « DASHBOARD » pour l'observateur
     * @param sender transport ; {@code null} = réplicateur passif (n'envoie jamais rien)
     */
    public LedgerReplicator(String nodeId, Blockchain ledger, MessageSender sender) {
        this.nodeId = nodeId;
        this.ledger = Objects.requireNonNull(ledger);
        this.sender = sender;
    }

    // ---------------------------------------------------------------- envoi

    /**
     * Diffuse un bloc créé localement (séquence d'irrigation, étape 5, après le succès de {@code addBlock}).
     *
     * @throws IllegalArgumentException si le bloc n'est pas dans le registre local (seul un ajout réussi est diffusé)
     */
    public void broadcast(Block block) {
        requireSender();
        List<Block> chain = ledger.getChain();
        if (block == null || block.getIndex() < 1 || block.getIndex() >= chain.size()
                || !Objects.equals(chain.get(block.getIndex()).getHash(), block.getHash())) {
            throw new IllegalArgumentException("Bloc absent du registre local : seul un bloc ajouté avec succès est diffusé");
        }
        JsonObject payload = new JsonObject();
        payload.add("block", GSON.toJsonTree(block));
        sender.publish(new Message(MessageType.LEDGER_UPDATE, Message.ALL, payload));
    }

    /** Demande aux autres les blocs qui manquent (au démarrage d'un nœud, ou après une coupure). */
    public void requestSync() {
        requireSender();
        List<Block> chain = ledger.getChain();
        synchronized (this) {
            lastSyncRequestFrom = chain.size();
            lastSyncRequestTime = System.currentTimeMillis();
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("fromIndex", chain.size());
        payload.addProperty("lastHash", chain.get(chain.size() - 1).getHash());
        sender.publish(new Message(MessageType.LEDGER_REQUEST, Message.ALL, payload));
        log("demande de rattrapage à partir de l'index " + chain.size());
    }

    /** Demande de rattrapage automatique sur un trou, sans répéter la même demande en rafale. */
    private void requestSyncIfUseful() {
        if (sender == null) {
            log("en retard, attente d'un LEDGER_SYNC (réplicateur passif)");
            return;
        }
        int size = ledger.getChain().size();
        synchronized (this) {
            if (size == lastSyncRequestFrom && System.currentTimeMillis() - lastSyncRequestTime < SYNC_RETRY_MS) {
                return;
            }
        }
        requestSync();
    }

    // ---------------------------------------------------------------- réception

    @Override
    public void onMessage(Message m) {
        switch (m.getType()) {
            case LEDGER_UPDATE -> onUpdate(m);
            case LEDGER_REQUEST -> onRequest(m);
            case LEDGER_SYNC -> onSync(m);
            default -> { }   // types non concernés
        }
    }

    private void onUpdate(Message m) {
        Block b = parseBlock(m.getPayload() == null ? null : m.getPayload().get("block"));
        if (b == null) {
            report(m.getFrom(), -1, "LEDGER_UPDATE mal formé : bloc illisible");
            return;
        }
        switch (integrate(m.getFrom(), b)) {
            case ADDED, ALREADY_PRESENT -> rememberHead(m.getFrom(), b);
            case GAP -> {
                log("trou détecté : bloc " + b.getIndex() + " reçu de " + m.getFrom()
                        + ", ma chaîne s'arrête à l'index " + (ledger.getChain().size() - 1));
                rememberHead(m.getFrom(), b);
                requestSyncIfUseful();
            }
            case REFUSED -> { }   // déjà signalé
        }
    }

    private void onRequest(Message m) {
        JsonObject p = m.getPayload();
        int fromIndex = intOr(p, "fromIndex", 1);
        String lastHash = p != null && p.has("lastHash") && !p.get("lastHash").isJsonNull()
                ? p.get("lastHash").getAsString() : null;
        peerHeads.put(m.getFrom(), new PeerHead(m.getFrom(), fromIndex, lastHash, System.currentTimeMillis()));

        if (sender == null) {
            return;   // passif : ne répond jamais
        }
        List<Block> chain = ledger.getChain();
        String myLastHash = chain.get(chain.size() - 1).getHash();

        // Comparaison des copies vue de MON côté : le demandeur est-il en avance, ou sur une autre branche ?
        if (fromIndex > chain.size()) {
            log(m.getFrom() + " a une copie plus longue (" + fromIndex + " blocs contre " + chain.size() + ")");
            requestSyncIfUseful();
        } else if (fromIndex == chain.size() && lastHash != null && !lastHash.equals(myLastHash)) {
            report(m.getFrom(), chain.size() - 1, "fourche : même longueur mais dernier bloc différent (le mien "
                    + Block.shortHash(myLastHash) + ", le sien " + Block.shortHash(lastHash) + ")");
        }

        // Réponse TOUJOURS envoyée : elle donne mon état (longueur + dernier hash) et les blocs qui lui manquent
        int start = Math.max(1, fromIndex);   // le genesis n'est jamais envoyé
        JsonArray blocks = new JsonArray();
        if (start < chain.size()) {
            for (Block b : chain.subList(start, chain.size())) {
                blocks.add(GSON.toJsonTree(b));
            }
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("length", chain.size());
        payload.addProperty("lastHash", myLastHash);
        payload.add("blocks", blocks);
        sender.publish(new Message(MessageType.LEDGER_SYNC, m.getFrom(), payload));
        if (blocks.size() > 0) {
            log("rattrapage envoyé à " + m.getFrom() + " : " + blocks.size() + " bloc(s) à partir de l'index " + start);
        }
    }

    private void onSync(Message m) {
        JsonElement raw = m.getPayload() == null ? null : m.getPayload().get("blocks");
        if (raw == null || !raw.isJsonArray()) {
            report(m.getFrom(), -1, "LEDGER_SYNC mal formé : liste de blocs absente");
            return;
        }
        int added = 0;
        Block last = null;
        for (JsonElement e : raw.getAsJsonArray()) {
            Block b = parseBlock(e);
            if (b == null) {
                report(m.getFrom(), -1, "LEDGER_SYNC mal formé : bloc illisible");
                return;
            }
            Outcome outcome = integrate(m.getFrom(), b);
            if (outcome == Outcome.GAP) {
                report(m.getFrom(), b.getIndex(), "LEDGER_SYNC incomplet : il manque des blocs avant l'index " + b.getIndex());
                return;
            }
            if (outcome == Outcome.REFUSED) {
                return;   // on s'arrête au premier bloc refusé : les suivants ne pourraient pas s'enchaîner
            }
            if (outcome == Outcome.ADDED) added++;
            last = b;
        }
        // État annoncé par celui qui répond (longueur + dernier hash) : c'est la base de la comparaison des copies
        JsonObject p = m.getPayload();
        if (p.has("length") && p.has("lastHash") && !p.get("lastHash").isJsonNull()) {
            peerHeads.put(m.getFrom(), new PeerHead(m.getFrom(), intOr(p, "length", 0),
                    p.get("lastHash").getAsString(), System.currentTimeMillis()));
        } else if (last != null) {
            rememberHead(m.getFrom(), last);
        }
        if (added > 0) {
            log("rattrapage reçu de " + m.getFrom() + " : " + added + " bloc(s) ajouté(s)");
        }
    }

    /** Tente d'ajouter un bloc reçu ; ne lève jamais d'exception. */
    private Outcome integrate(String from, Block b) {
        List<Block> chain = ledger.getChain();
        int size = chain.size();
        if (b.getIndex() >= 0 && b.getIndex() < size) {
            Block mine = chain.get(b.getIndex());
            if (Objects.equals(mine.getHash(), b.getHash())) {
                return Outcome.ALREADY_PRESENT;   // doublon (ex. reçu par un UPDATE et par un SYNC)
            }
            report(from, b.getIndex(), "fourche : bloc différent du mien à l'index " + b.getIndex()
                    + " (le mien " + Block.shortHash(mine.getHash()) + ", reçu " + Block.shortHash(b.getHash()) + ")");
            return Outcome.REFUSED;
        }
        if (b.getIndex() > size) {
            return Outcome.GAP;
        }
        try {
            ledger.acceptBlock(b);   // V1 à V7
            return Outcome.ADDED;
        } catch (InvalidBlockException e) {
            report(from, b.getIndex(), e.getMessage());
            return Outcome.REFUSED;
        }
    }

    // ---------------------------------------------------------------- état de la réplication

    /** Divergences constatées (blocs refusés, fourches, messages mal formés), les plus anciennes d'abord. */
    public synchronized List<Divergence> getDivergences() {
        return Collections.unmodifiableList(new ArrayList<>(divergences));
    }

    /** Dernier état connu (longueur, hash du dernier bloc) de la copie de chaque autre nœud. */
    public Map<String, PeerHead> getPeerHeads() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(peerHeads));
    }

    /** true si la dernière copie connue de {@code peer} a la même longueur et le même dernier hash que la nôtre. */
    public boolean isInSyncWith(String peer) {
        PeerHead head = peerHeads.get(peer);
        if (head == null) return false;
        List<Block> chain = ledger.getChain();
        return head.length() == chain.size()
                && Objects.equals(head.lastHash(), chain.get(chain.size() - 1).getHash());
    }

    public Blockchain getLedger() {
        return ledger;
    }

    // ---------------------------------------------------------------- outils

    private void rememberHead(String peer, Block b) {
        peerHeads.merge(peer, new PeerHead(peer, b.getIndex() + 1, b.getHash(), System.currentTimeMillis()),
                (old, neu) -> neu.length() >= old.length() ? neu : old);
    }

    private synchronized void report(String peer, int index, String reason) {
        divergences.add(new Divergence(peer, index, reason, System.currentTimeMillis()));
        if (divergences.size() > MAX_DIVERGENCES) {
            divergences.remove(0);
        }
        System.err.println("[" + nodeId + "] DIVERGENCE avec " + peer
                + (index >= 0 ? " (bloc " + index + ")" : "") + " : " + reason);
    }

    private static Block parseBlock(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        try {
            return GSON.fromJson(e, Block.class);
        } catch (JsonParseException | IllegalStateException | NumberFormatException ex) {
            return null;
        }
    }

    private static int intOr(JsonObject p, String key, int fallback) {
        try {
            return p != null && p.has(key) ? p.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void requireSender() {
        if (sender == null) {
            throw new IllegalStateException("Réplicateur passif (dashboard) : il n'envoie jamais de message");
        }
    }

    private void log(String text) {
        System.out.println("[" + nodeId + "] registre : " + text);
    }
}
