package com.smartwater.blockchain;

import com.smartwater.Config;
import com.smartwater.security.BlockSigner;
import com.smartwater.security.KeyGenerator;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registre privé avec validation AVANT ajout : un bloc invalide n'entre jamais dans la chaîne
 * (contrat, section 6.3).
 *
 * <ul>
 *   <li>V1 hash recalculé = hash du bloc</li>
 *   <li>V2 index = dernier + 1 et previousHash = hash du dernier</li>
 *   <li>V3 nœud connu (A, B ou C)</li>
 *   <li>V4 signature valide avec la clé publique du nœud</li>
 *   <li>V5 liters &gt; 0</li>
 *   <li>V6 quotaRemaining = quota actuel du nœud − liters, et ≥ 0</li>
 *   <li>V7 liters ≤ réservoir restant</li>
 * </ul>
 *
 * Le quota de chaque nœud et le réservoir restant sont <b>recalculés à partir de la chaîne</b>
 * (valeurs initiales de {@link Config} moins les blocs déjà acceptés) : chaque nœud vérifie ainsi
 * les blocs des autres sans dépendre de leur état local.
 *
 * Les méthodes publiques sont {@code synchronized} : le thread MQTT et le thread du nœud y accèdent.
 */
public class Blockchain implements Ledger {

    /** Au-delà, les refus les plus anciens sont oubliés (évite qu'un flot de faux blocs sature la mémoire). */
    private static final int MAX_REJECTED = 1000;

    private final PrivateKey privateKey;                 // null pour l'observateur
    private final Map<String, PublicKey> publicKeys;     // A, B, C -> clé publique
    private final Map<String, Integer> initialQuotas;
    private final int reservoirInitial;

    private final List<Block> chain = new ArrayList<>();
    private final List<RejectedBlock> rejected = new ArrayList<>();

    /**
     * Constructeur général (tests, démos). En production, utiliser {@link #forNode} ou {@link #observer}.
     *
     * @param privateKey clé du nœud local qui signe ses blocs ; {@code null} = registre en lecture seule
     */
    public Blockchain(PrivateKey privateKey, Map<String, PublicKey> publicKeys,
                      Map<String, Integer> initialQuotas, int reservoirInitial) {
        this.privateKey = privateKey;
        this.publicKeys = Collections.unmodifiableMap(new HashMap<>(publicKeys));
        this.initialQuotas = Collections.unmodifiableMap(new HashMap<>(initialQuotas));
        this.reservoirInitial = reservoirInitial;
        this.chain.add(Block.genesis());
    }

    // ---------------------------------------------------------------- obtention (contrat, section 7)

    /** Registre d'un nœud : charge sa clé privée et les 3 clés publiques ({@code keys/}), valeurs de {@link Config}. */
    public static Blockchain forNode(String nodeId) {
        return forNode(nodeId, Path.of(Config.KEYS_DIR));
    }

    public static Blockchain forNode(String nodeId, Path keysDir) {
        PrivateKey key = KeyGenerator.loadPrivateKey(keysDir, nodeId);   // refuse aussi un nodeId inconnu
        return new Blockchain(key, KeyGenerator.loadAllPublicKeys(keysDir),
                Config.initialQuotas(), Config.RESERVOIR_INITIAL);
    }

    /**
     * Registre du dashboard : clés publiques seulement, ne crée jamais de bloc.
     * Il reçoit les blocs répliqués ({@link #acceptBlock}), les valide et enregistre les refus.
     */
    public static Blockchain observer() {
        return observer(Path.of(Config.KEYS_DIR));
    }

    public static Blockchain observer(Path keysDir) {
        return new Blockchain(null, KeyGenerator.loadAllPublicKeys(keysDir),
                Config.initialQuotas(), Config.RESERVOIR_INITIAL);
    }

    // ---------------------------------------------------------------- ajout

    /**
     * Crée le bloc, le signe avec la clé du nœud local, le valide (V1-V7) puis l'ajoute.
     * Le bloc est toujours signé avec la clé du nœud local : demander un bloc au nom d'un autre
     * nœud est donc refusé par V4.
     *
     * @throws InvalidBlockException si une règle échoue ; la chaîne n'est alors pas modifiée
     * @throws IllegalStateException si ce registre est un observateur (aucune clé privée)
     */
    @Override
    public synchronized Block addBlock(String nodeId, int liters, int quotaRemaining) throws InvalidBlockException {
        if (privateKey == null) {
            throw new IllegalStateException("Registre en lecture seule (observateur) : création de bloc impossible");
        }
        Block last = lastBlock();
        Block block = Block.create(last.getIndex() + 1, System.currentTimeMillis(), nodeId, liters,
                quotaRemaining, last.getHash(), privateKey);
        accept(block);
        return block;
    }

    /**
     * Ajoute un bloc reçu d'un autre nœud (LEDGER_UPDATE) s'il passe les 7 règles.
     * Usage interne (réplication).
     *
     * @throws InvalidBlockException si une règle échoue ; la chaîne n'est alors pas modifiée
     */
    public synchronized void acceptBlock(Block b) throws InvalidBlockException {
        accept(b);
    }

    private void accept(Block b) throws InvalidBlockException {
        try {
            check(b);
        } catch (InvalidBlockException e) {
            recordRejection(b, e.getMessage());
            throw e;
        }
        chain.add(b);
    }

    private void recordRejection(Block b, String reason) {
        if (b == null) {
            return;
        }
        rejected.add(new RejectedBlock(b, reason, System.currentTimeMillis()));
        if (rejected.size() > MAX_REJECTED) {
            rejected.remove(0);
        }
    }

    /** Applique V1 à V7 dans l'ordre du contrat ; ne modifie rien. */
    private void check(Block b) throws InvalidBlockException {
        if (b == null) {
            throw new InvalidBlockException("V1", "bloc absent");
        }
        Block last = lastBlock();

        // V1 : le contenu correspond au hash
        if (b.getHash() == null || !HashValidator.hasValidHash(b)) {
            throw new InvalidBlockException("V1", "hash invalide, contenu modifié (" + b + ")");
        }

        // V2 : bonne position et bon previousHash (bloque aussi les blocs rejoués ou en double)
        if (!HashValidator.isLinked(last, b)) {
            throw new InvalidBlockException("V2", "bloc hors chaîne : attendu index " + (last.getIndex() + 1)
                    + " et previousHash " + Block.shortHash(last.getHash())
                    + ", reçu index " + b.getIndex() + " et previousHash " + Block.shortHash(b.getPreviousHash()));
        }

        // V3 : nœud connu
        PublicKey key = publicKeys.get(b.getNodeId());
        if (key == null) {
            throw new InvalidBlockException("V3", "nœud inconnu : " + b.getNodeId());
        }

        // V4 : signature valide avec la clé publique du nœud
        if (!BlockSigner.verify(b, key)) {
            throw new InvalidBlockException("V4", "signature invalide : le bloc n'a pas été signé par "
                    + b.getNodeId());
        }

        // V5 : quantité strictement positive
        if (b.getLiters() <= 0) {
            throw new InvalidBlockException("V5", "quantité invalide : " + b.getLiters() + " L");
        }

        // V6 : quota restant = quota actuel - liters, et jamais négatif
        int currentQuota = quotaOf(b.getNodeId());
        int expected = currentQuota - b.getLiters();
        if (expected < 0) {
            throw new InvalidBlockException("V6", "quota dépassé pour " + b.getNodeId() + " : "
                    + b.getLiters() + " L demandés, " + currentQuota + " L restants");
        }
        if (b.getQuotaRemaining() != expected) {
            throw new InvalidBlockException("V6", "quota falsifié pour " + b.getNodeId() + " : annoncé "
                    + b.getQuotaRemaining() + ", attendu " + expected);
        }

        // V7 : pas plus que l'eau disponible dans le réservoir
        int reservoir = reservoirOf();
        if (b.getLiters() > reservoir) {
            throw new InvalidBlockException("V7", "réservoir insuffisant : " + b.getLiters()
                    + " L demandés, " + reservoir + " L disponibles");
        }
    }

    // ---------------------------------------------------------------- validation de la chaîne

    /** Vérification complète V1 à V4 (au démarrage ou après une synchronisation). */
    @Override
    public synchronized boolean validate() {
        return firstInvalidIndex(chain, publicKeys) == -1;
    }

    /**
     * Index du premier bloc invalide d'une chaîne (V1 à V4), ou -1 si elle est valide.
     * Le genesis doit être le genesis fixe du contrat. Visible du package pour les tests de falsification.
     */
    static int firstInvalidIndex(List<Block> blocks, Map<String, PublicKey> keys) {
        Block genesis = Block.genesis();
        if (blocks.isEmpty() || blocks.get(0) == null
                || !genesis.getHash().equals(blocks.get(0).getHash())
                || !HashValidator.hasValidHash(blocks.get(0))) {
            return 0;
        }
        for (int i = 1; i < blocks.size(); i++) {
            Block prev = blocks.get(i - 1);
            Block curr = blocks.get(i);
            if (curr == null || curr.getHash() == null
                    || !HashValidator.hasValidHash(curr)                       // V1
                    || !HashValidator.isLinked(prev, curr)                     // V2
                    || !keys.containsKey(curr.getNodeId())                     // V3
                    || !BlockSigner.verify(curr, keys.get(curr.getNodeId()))) { // V4
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- lecture

    @Override
    public synchronized List<Block> getChain() {
        return Collections.unmodifiableList(new ArrayList<>(chain));
    }

    @Override
    public synchronized List<RejectedBlock> getRejected() {
        return Collections.unmodifiableList(new ArrayList<>(rejected));
    }

    public synchronized Block getLastBlock() {
        return lastBlock();
    }

    /** Quota restant d'un nœud, recalculé à partir de la chaîne. */
    public synchronized int getQuota(String nodeId) {
        return quotaOf(nodeId);
    }

    /** Litres restant dans le réservoir, recalculés à partir de la chaîne. */
    public synchronized int getReservoirRemaining() {
        return reservoirOf();
    }

    // ---------------------------------------------------------------- calculs internes (verrou déjà pris)

    private Block lastBlock() {
        return chain.get(chain.size() - 1);
    }

    private int quotaOf(String nodeId) {
        int quota = initialQuotas.getOrDefault(nodeId, 0);
        for (Block b : chain) {
            if (nodeId.equals(b.getNodeId())) {
                quota -= b.getLiters();
            }
        }
        return quota;
    }

    private int reservoirOf() {
        int reservoir = reservoirInitial;
        for (Block b : chain) {
            reservoir -= b.getLiters();
        }
        return reservoir;
    }
}
