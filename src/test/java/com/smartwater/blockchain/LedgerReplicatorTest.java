package com.smartwater.blockchain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smartwater.Config;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;
import com.smartwater.node.LocalState;
import com.smartwater.node.NodeStatus;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Réplication sur un réseau simulé en mémoire (pas de broker) : il reproduit ce que fait NodeMqttClient
 * (aller-retour JSON, filtre sur « to », on ne reçoit pas ses propres messages) et permet de couper un nœud.
 */
class LedgerReplicatorTest {

    static Map<String, KeyPair> pairs;
    static Map<String, PublicKey> publicKeys;
    static final Gson GSON = new Gson();

    @BeforeAll
    static void keys() {
        pairs = Map.of("A", KeyGenerator.generateKeyPair(),
                       "B", KeyGenerator.generateKeyPair(),
                       "C", KeyGenerator.generateKeyPair());
        publicKeys = Map.of("A", pairs.get("A").getPublic(),
                            "B", pairs.get("B").getPublic(),
                            "C", pairs.get("C").getPublic());
    }

    // ---------------------------------------------------------------- réseau simulé

    /** File FIFO de messages livrés comme le ferait NodeMqttClient. */
    static class Net {
        final Map<String, LedgerReplicator> nodes = new LinkedHashMap<>();
        final Set<String> offline = new HashSet<>();
        final Set<String> seesEverything = new HashSet<>();   // le dashboard lit tout water/from/+, sans filtre « to »
        final Deque<Message> queue = new ArrayDeque<>();

        MessageSender senderFor(String id) {
            return m -> {
                m.setFrom(id);
                m.setMsgId(UUID.randomUUID().toString());
                queue.add(m);
                return 0;
            };
        }

        void flush() {
            while (!queue.isEmpty()) {
                Message wire = Message.fromJson(queue.poll().toJson());   // comme sur le réseau
                for (Map.Entry<String, LedgerReplicator> e : nodes.entrySet()) {
                    String id = e.getKey();
                    if (id.equals(wire.getFrom()) || offline.contains(id)) continue;
                    if (!seesEverything.contains(id) && !wire.isBroadcast() && !id.equals(wire.getTo())) continue;
                    e.getValue().onMessage(wire);
                }
            }
        }
    }

    Net net;
    Map<String, Blockchain> ledgers;

    @BeforeEach
    void network() {
        net = new Net();
        ledgers = new LinkedHashMap<>();
        for (String id : List.of("A", "B", "C")) {
            Blockchain ledger = new Blockchain(pairs.get(id).getPrivate(), publicKeys,
                    Config.initialQuotas(), Config.RESERVOIR_INITIAL);
            ledgers.put(id, ledger);
            net.nodes.put(id, new LedgerReplicator(id, ledger, net.senderFor(id)));
        }
    }

    /** Séquence d'irrigation côté registre : addBlock local puis LEDGER_UPDATE, puis livraison. */
    Block irrigate(String id, int liters) throws InvalidBlockException {
        Blockchain ledger = ledgers.get(id);
        Block b = ledger.addBlock(id, liters, ledger.getQuota(id) - liters);
        net.nodes.get(id).broadcast(b);
        net.flush();
        return b;
    }

    void assertIdentical(String... ids) {
        List<Block> reference = ledgers.get(ids[0]).getChain();
        for (String id : ids) {
            List<Block> chain = ledgers.get(id).getChain();
            assertEquals(reference.size(), chain.size(), "longueur de la copie de " + id);
            for (int i = 0; i < chain.size(); i++) {
                assertEquals(reference.get(i).getHash(), chain.get(i).getHash(), "bloc " + i + " de " + id);
            }
            assertTrue(ledgers.get(id).validate(), "copie de " + id + " valide");
        }
    }

    /** Message envoyé « à la main » par un nœud (pour simuler un tricheur). */
    void inject(String from, MessageType type, String to, JsonObject payload) {
        net.senderFor(from).publish(new Message(type, to, payload));
        net.flush();
    }

    static JsonObject blockPayload(Block b) {
        JsonObject p = new JsonObject();
        p.add("block", GSON.toJsonTree(b));
        return p;
    }

    // ---------------------------------------------------------------- copies identiques

    @Test
    void threeCopiesAreIdenticalAfterSeveralBlocks() throws Exception {
        irrigate("A", 40);
        irrigate("B", 50);
        irrigate("C", 30);
        irrigate("A", 20);

        assertEquals(5, ledgers.get("A").getChain().size());
        assertIdentical("A", "B", "C");
        for (String id : List.of("A", "B", "C")) {
            assertTrue(net.nodes.get(id).getDivergences().isEmpty(), "aucune divergence chez " + id);
        }
        assertEquals(140, ledgers.get("C").getQuota("A"), "les quotas sont recalculés pareil partout");
    }

    @Test
    void copiesAreComparedByLengthAndLastHash() throws Exception {
        irrigate("A", 40);
        irrigate("B", 50);

        // C ne sait pas encore que A a reçu le bloc de B : il compare à la demande
        LedgerReplicator c = net.nodes.get("C");
        c.requestSync();
        net.flush();

        assertTrue(c.isInSyncWith("A"));
        assertTrue(c.isInSyncWith("B"));
        LedgerReplicator.PeerHead headA = c.getPeerHeads().get("A");
        assertEquals(3, headA.length());
        assertEquals(ledgers.get("A").getLastBlock().getHash(), headA.lastHash());
    }

    @Test
    void nodeThatAnswersARequestDiscoversItIsBehindAndCatchesUp() throws Exception {
        net.offline.add("B");
        irrigate("A", 40);
        irrigate("C", 30);
        net.offline.remove("B");

        // A demande une comparaison ; B répond, voit que A est plus long, et se rattrape lui-même
        net.nodes.get("A").requestSync();
        net.flush();

        assertIdentical("A", "B", "C");
    }

    @Test
    void sameLengthButDifferentLastBlockIsReportedAsFork() throws Exception {
        net.offline.add("C");
        irrigate("A", 40);
        ledgers.get("C").addBlock("C", 30, 170);   // C, isolé, a son propre bloc 1
        net.offline.remove("C");

        net.nodes.get("C").requestSync();
        net.flush();

        assertTrue(net.nodes.get("A").getDivergences().stream().anyMatch(d -> d.reason().contains("fourche")),
                "A constate la fourche de C");
        assertFalse(net.nodes.get("C").isInSyncWith("A"));
    }

    @Test
    void duplicateUpdateIsIgnoredWithoutAlarm() throws Exception {
        Block b = irrigate("A", 40);
        net.nodes.get("A").broadcast(b);   // rediffusé une seconde fois
        net.flush();

        assertIdentical("A", "B", "C");
        assertTrue(net.nodes.get("B").getDivergences().isEmpty());
        assertTrue(ledgers.get("B").getRejected().isEmpty());
    }

    @Test
    void onlyASuccessfullyAddedBlockCanBeBroadcast() {
        Block notInChain = Block.create(1, 1L, "A", 10, 190, Block.genesis().getHash(), pairs.get("A").getPrivate());
        assertThrows(IllegalArgumentException.class, () -> net.nodes.get("A").broadcast(notInChain));
    }

    // ---------------------------------------------------------------- faux blocs : refus + divergence

    @Test
    void forgedLedgerUpdateIsRefusedByTheOtherTwo() throws Exception {
        irrigate("A", 40);
        Block last = ledgers.get("A").getLastBlock();
        // C fabrique un bloc au nom de B, signé avec SA clé
        Block forged = Block.create(2, System.currentTimeMillis(), "B", 50, 150, last.getHash(),
                pairs.get("C").getPrivate());
        inject("C", MessageType.LEDGER_UPDATE, Message.ALL, blockPayload(forged));

        for (String id : List.of("A", "B")) {
            assertEquals(2, ledgers.get(id).getChain().size(), "chaîne de " + id + " inchangée");
            List<LedgerReplicator.Divergence> d = net.nodes.get(id).getDivergences();
            assertEquals(1, d.size(), "divergence signalée chez " + id);
            assertEquals("C", d.get(0).peer());
            assertTrue(d.get(0).reason().startsWith("V4 : "), d.get(0).reason());
            assertEquals(1, ledgers.get(id).getRejected().size(), "bloc refusé visible par le dashboard");
        }
    }

    @Test
    void blockTamperedInTransitIsRefused() throws Exception {
        irrigate("A", 40);
        Block last = ledgers.get("B").getLastBlock();
        Block ok = Block.create(2, System.currentTimeMillis(), "B", 50, 150, last.getHash(), pairs.get("B").getPrivate());
        Block tampered = new Block(ok.getIndex(), ok.getTimestamp(), "B", 5, ok.getQuotaRemaining(),
                ok.getPreviousHash(), ok.getHash(), ok.getSignature());
        inject("B", MessageType.LEDGER_UPDATE, Message.ALL, blockPayload(tampered));

        assertTrue(net.nodes.get("A").getDivergences().get(0).reason().startsWith("V1 : "));
        assertTrue(net.nodes.get("C").getDivergences().get(0).reason().startsWith("V1 : "));
        assertEquals(2, ledgers.get("C").getChain().size());
    }

    @Test
    void malformedUpdateIsReportedNotCrashed() {
        JsonObject garbage = new JsonObject();
        garbage.addProperty("block", "pas un bloc");
        inject("B", MessageType.LEDGER_UPDATE, Message.ALL, garbage);

        assertEquals(1, net.nodes.get("A").getDivergences().size());
        assertEquals(1, ledgers.get("A").getChain().size());
    }

    // ---------------------------------------------------------------- rattrapage

    @Test
    void nodeBackOnlineCatchesUpOnTheNextBlock() throws Exception {
        net.offline.add("C");
        irrigate("A", 40);
        irrigate("B", 50);
        irrigate("A", 20);
        assertEquals(1, ledgers.get("C").getChain().size(), "C a tout raté");

        net.offline.remove("C");
        irrigate("B", 10);   // C voit un bloc d'index 4 alors qu'il s'arrête à 0 : trou → LEDGER_REQUEST → LEDGER_SYNC

        assertIdentical("A", "B", "C");
        assertTrue(net.nodes.get("C").getDivergences().isEmpty(), "un retard n'est pas une divergence");
    }

    @Test
    void lateNodeCatchesUpWithRequestSync() throws Exception {
        net.offline.add("C");
        irrigate("A", 40);
        irrigate("B", 50);
        net.offline.remove("C");

        net.nodes.get("C").requestSync();   // au démarrage du nœud
        net.flush();

        assertIdentical("A", "B", "C");
        assertTrue(net.nodes.get("C").isInSyncWith("A"));
    }

    @Test
    void syncCarryingATamperedBlockStopsAtThatBlock() throws Exception {
        net.offline.add("C");
        Block b1 = irrigate("A", 40);
        Block b2 = irrigate("B", 50);
        net.offline.remove("C");

        // B (malveillant) répond à C avec un 2e bloc modifié
        Block tampered = new Block(b2.getIndex(), b2.getTimestamp(), "B", 5, b2.getQuotaRemaining(),
                b2.getPreviousHash(), b2.getHash(), b2.getSignature());
        JsonArray blocks = new JsonArray();
        blocks.add(GSON.toJsonTree(b1));
        blocks.add(GSON.toJsonTree(tampered));
        JsonObject payload = new JsonObject();
        payload.add("blocks", blocks);
        inject("B", MessageType.LEDGER_SYNC, "C", payload);

        assertEquals(2, ledgers.get("C").getChain().size(), "le bloc valide est pris, pas le bloc falsifié");
        assertTrue(net.nodes.get("C").getDivergences().get(0).reason().startsWith("V1 : "));
    }

    @Test
    void forkIsReportedButNeverMerged() throws Exception {
        net.offline.add("C");
        Block a1 = irrigate("A", 40);
        // C, isolé, crée son propre bloc 1 : deux versions valides du bloc 1 existent
        ledgers.get("C").addBlock("C", 30, 170);
        net.offline.remove("C");

        net.nodes.get("A").broadcast(a1);
        net.flush();

        List<LedgerReplicator.Divergence> d = net.nodes.get("C").getDivergences();
        assertEquals(1, d.size());
        assertTrue(d.get(0).reason().contains("fourche"), d.get(0).reason());
        assertEquals("C", ledgers.get("C").getLastBlock().getNodeId(), "pas de consensus : C garde sa version");
        assertFalse(net.nodes.get("C").isInSyncWith("A"));
    }

    // ---------------------------------------------------------------- dashboard (passif)

    @Test
    void passiveObserverFollowsTheLedgerWithoutSendingAnything() throws Exception {
        Blockchain observer = new Blockchain(null, publicKeys, Config.initialQuotas(), Config.RESERVOIR_INITIAL);
        LedgerReplicator dashboard = new LedgerReplicator("DASHBOARD", observer, null);
        net.nodes.put("DASHBOARD", dashboard);
        net.seesEverything.add("DASHBOARD");

        irrigate("A", 40);
        irrigate("B", 50);

        assertEquals(ledgers.get("A").getChain().size(), observer.getChain().size());
        assertEquals(ledgers.get("A").getLastBlock().getHash(), observer.getLastBlock().getHash());
        assertThrows(IllegalStateException.class, dashboard::requestSync);
        assertThrows(IllegalStateException.class, () -> dashboard.broadcast(observer.getLastBlock()));
    }

    @Test
    void passiveObserverAlsoCatchesUpFromSyncsAddressedToOthers() throws Exception {
        Blockchain observer = new Blockchain(null, publicKeys, Config.initialQuotas(), Config.RESERVOIR_INITIAL);
        net.nodes.put("DASHBOARD", new LedgerReplicator("DASHBOARD", observer, null));
        net.seesEverything.add("DASHBOARD");

        net.offline.add("C");
        net.offline.add("DASHBOARD");
        irrigate("A", 40);
        irrigate("B", 50);
        net.offline.remove("C");
        net.offline.remove("DASHBOARD");

        net.nodes.get("C").requestSync();   // la réponse destinée à C passe aussi sous les yeux du dashboard
        net.flush();

        assertEquals(3, observer.getChain().size());
        assertIdentical("A", "B", "C");
    }

    // ---------------------------------------------------------------- balises d'état NODE_STATUS

    /** Balise de {@code id} avec la chaîne annoncée (sans genesis), livrée comme sur le réseau. */
    void injectBeacon(String id, List<Block> blocks) {
        NodeStatus st = new NodeStatus(new LocalState(id, 40.0, 200, "RELEASED", List.of()), null, blocks);
        inject(id, MessageType.NODE_STATUS, Message.ALL, st.toPayload());
    }

    List<Block> withoutGenesis(String id) {
        List<Block> chain = ledgers.get(id).getChain();
        return chain.subList(1, chain.size());
    }

    @Test
    void passiveObserverStartingLateCatchesUpFromASingleBeacon() throws Exception {
        irrigate("A", 40);
        irrigate("B", 50);
        irrigate("C", 30);                                   // 3 blocs déjà passés : l'observateur n'était pas là

        Blockchain observer = new Blockchain(null, publicKeys, Config.initialQuotas(), Config.RESERVOIR_INITIAL);
        LedgerReplicator dashboard = new LedgerReplicator("DASHBOARD", observer, null);
        net.nodes.put("DASHBOARD", dashboard);
        net.seesEverything.add("DASHBOARD");
        assertEquals(1, observer.getChain().size());

        injectBeacon("A", withoutGenesis("A"));

        assertEquals(4, observer.getChain().size(), "la chaîne complète arrive avec la balise");
        assertEquals(ledgers.get("A").getLastBlock().getHash(), observer.getLastBlock().getHash());
        assertTrue(observer.validate());
        assertTrue(dashboard.getDivergences().isEmpty());
        assertEquals(4, dashboard.getPeerHeads().get("A").length());
    }

    @Test
    void activeNodeLearnsPeerHeadsFromBeaconsAndCatchesUpWhenBehind() throws Exception {
        net.offline.add("C");
        irrigate("A", 40);
        irrigate("B", 50);
        net.offline.remove("C");
        assertEquals(1, ledgers.get("C").getChain().size(), "C a raté deux blocs");

        injectBeacon("A", withoutGenesis("A"));              // A annonce 3 blocs : C se rend compte qu'il est en retard

        assertEquals(3, net.nodes.get("C").getPeerHeads().get("A").length());
        assertIdentical("A", "B", "C");                       // rattrapage déclenché par la balise
    }

    @Test
    void beaconsNeverCopyBlocksIntoAnActiveNode() throws Exception {
        net.offline.add("C");
        irrigate("A", 40);
        net.offline.remove("C");
        // C reçoit seulement la balise : il apprend la longueur de A mais n'en copie pas les blocs lui-même
        net.queue.clear();
        NodeStatus st = new NodeStatus(new LocalState("A", 40.0, 160, "RELEASED", List.of()), null, withoutGenesis("A"));
        Message beacon = new Message(MessageType.NODE_STATUS, Message.ALL, st.toPayload());
        beacon.setFrom("A");
        net.nodes.get("C").onMessage(beacon);

        assertEquals(1, ledgers.get("C").getChain().size(), "le rattrapage passe par LEDGER_REQUEST / LEDGER_SYNC");
        assertTrue(net.queue.stream().anyMatch(m -> m.getType() == MessageType.LEDGER_REQUEST));
    }

    @Test
    void malformedBeaconIsReportedOnlyOnce() {
        JsonObject garbage = new JsonObject();
        garbage.addProperty("local", "pas un objet");
        for (int i = 0; i < 3; i++) {
            inject("B", MessageType.NODE_STATUS, Message.ALL, garbage);   // une balise par seconde, toujours la même
        }
        assertEquals(1, net.nodes.get("A").getDivergences().size());
        assertEquals(1, net.nodes.get("C").getDivergences().size());
    }
}
