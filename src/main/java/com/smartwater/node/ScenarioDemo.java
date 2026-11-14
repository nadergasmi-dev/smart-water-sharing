package com.smartwater.node;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.smartwater.Config;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageType;
import com.smartwater.security.KeyGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scénario complet en console (Parties 6 et 8) : les 3 agriculteurs dans la même JVM, chacun avec son propre
 * client MQTT, sa pompe Ricart-Agrawala et sa copie du registre. Le nœud A déclenche un snapshot
 * Chandy-Lamport à intervalle régulier, pour alimenter le dashboard (humidité, état global).
 *
 * <p>Options :</p>
 * <ul>
 *   <li>{@code --keys=<dossier>} : clés de signature (défaut {@code target/demo-keys}, générées si absentes ;
 *       le dashboard doit être lancé avec le même dossier) ;</li>
 *   <li>{@code --period=<ms>} : durée d'un cycle d'agriculteur (défaut 800) ;</li>
 *   <li>{@code --snapshot-every=<ms>} : intervalle des snapshots (défaut 4000, 0 = aucun) ;</li>
 *   <li>{@code --triche} : après quelques secondes, C diffuse deux blocs falsifiés (quota dépassé, puis
 *       contenu modifié après signature) : les nœuds et le dashboard les refusent (règles V6 et V1).</li>
 * </ul>
 * Le scénario s'arrête quand tous les quotas sont épuisés (ou sur Entrée).
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public final class ScenarioDemo {

    private ScenarioDemo() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = options(args);
        Path keys = Path.of(opts.getOrDefault("keys", "target/demo-keys"));
        long period = Long.parseLong(opts.getOrDefault("period", "800"));
        long snapshotEvery = Long.parseLong(opts.getOrDefault("snapshot-every", "4000"));
        boolean cheat = opts.containsKey("triche");

        Files.createDirectories(keys);
        KeyGenerator.generateAll(keys, false);

        List<FarmerNode> farmers = new ArrayList<>();
        long seed = System.currentTimeMillis();
        for (String id : Config.NODE_IDS) {
            farmers.add(new FarmerNode(id, keys, new SoilSimulator(seed + id.hashCode()), new FarmerState(id)));
        }
        for (FarmerNode f : farmers) f.start();
        Thread.sleep(500);
        for (FarmerNode f : farmers) f.startLoop(period);
        System.out.println("=== scénario lancé (clés : " + keys.toAbsolutePath() + ") : Entrée pour arrêter ===");

        Thread stdin = new Thread(() -> {
            try {
                System.in.read();
            } catch (Exception ignored) { }
        }, "stdin");
        stdin.setDaemon(true);
        stdin.start();

        long start = System.currentTimeMillis();
        long nextSnapshot = start + 1500;
        boolean cheated = false;
        while (stdin.isAlive() && !allExhausted(farmers)) {
            long now = System.currentTimeMillis();
            if (snapshotEvery > 0 && now >= nextSnapshot) {
                farmers.get(0).triggerSnapshot();
                nextSnapshot = now + snapshotEvery;
            }
            if (cheat && !cheated && now - start > 6000) {
                cheated = true;
                cheat(farmers.get(2), keys);
            }
            Thread.sleep(100);
        }
        // un dernier snapshot pour l'état final
        Thread.sleep(1000);
        if (snapshotEvery > 0) {
            farmers.get(0).triggerSnapshot();
            Thread.sleep(1500);
        }
        System.out.println("=== fin du scénario ===");
        for (FarmerNode f : farmers) {
            f.stopLoop();
            System.out.println(f);
        }
        for (FarmerNode f : farmers) f.stop();
    }

    private static boolean allExhausted(List<FarmerNode> farmers) {
        return farmers.stream().allMatch(f -> f.state().getQuotaRemaining() == 0);
    }

    /**
     * Le nœud C triche : il diffuse sur son propre topic deux blocs qui ne respectent pas les règles.
     * (Il signe avec sa vraie clé : la signature est valide, ce sont les règles métier qui le trahissent.)
     */
    public static void cheat(FarmerNode c, Path keys) {
        Blockchain ledger = c.runtime().ledger();
        Block last = ledger.getLastBlock();
        int quota = ledger.getQuota(c.getNodeId());
        PrivateKey key = KeyGenerator.loadPrivateKey(keys, c.getNodeId());

        // 1) Quota dépassé : 500 L alors qu'il en reste au plus 200 → V6
        Block greedy = Block.create(last.getIndex() + 1, System.currentTimeMillis(), c.getNodeId(), 500,
                quota - 500, last.getHash(), key);
        // 2) Bloc correct, puis litres modifiés APRÈS calcul du hash et signature → V1
        Block honest = Block.create(last.getIndex() + 1, System.currentTimeMillis(), c.getNodeId(), 5,
                quota - 5, last.getHash(), key);
        Block tampered = new Block(honest.getIndex(), honest.getTimestamp(), honest.getNodeId(), 150,
                honest.getQuotaRemaining(), honest.getPreviousHash(), honest.getHash(), honest.getSignature());

        System.out.println("[" + c.getNodeId() + "] TRICHE : diffusion de deux blocs falsifiés");
        for (Block b : List.of(greedy, tampered)) {
            JsonObject payload = new JsonObject();
            payload.add("block", new Gson().toJsonTree(b));
            c.runtime().sender().publish(new Message(MessageType.LEDGER_UPDATE, Message.ALL, payload));
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> opts = new java.util.HashMap<>();
        for (String a : args) {
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                opts.put(eq < 0 ? a.substring(2) : a.substring(2, eq), eq < 0 ? "" : a.substring(eq + 1));
            }
        }
        return opts;
    }
}
