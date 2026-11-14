package com.smartwater;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Constantes partagées par les deux binômes (contrat, section 7).
 *
 * Les valeurs initiales du réservoir et des quotas sont ici car les règles de validation V6 et V7
 * en dépendent : elles doivent être identiques sur les 3 nœuds.
 */
public final class Config {

    public static final String BROKER_URL = "tcp://localhost:1883";
    /** Réservoir commun au départ, en litres. */
    public static final int RESERVOIR_INITIAL = 1000;
    /** Quota initial de chaque agriculteur, en litres. */
    public static final int QUOTA_INITIAL = 200;
    /** Dossier des clés de signature, relatif à la racine du projet. */
    public static final String KEYS_DIR = "keys";

    /** Nœuds agriculteurs (contrat, section 1). Chacun possède une paire de clés. */
    public static final List<String> NODE_IDS = List.of("A", "B", "C");
    /** Clients MQTT : les nœuds + le dashboard (contrat, section 2.1). */
    public static final List<String> MQTT_CLIENT_IDS = List.of("A", "B", "C", "DASHBOARD");

    /** Variable d'environnement lue en priorité : SMARTWATER_MQTT_PASSWORD_A, _B, _C, _DASHBOARD. */
    static final String PASSWORD_ENV_PREFIX = "SMARTWATER_MQTT_PASSWORD_";
    /** Fichier local non commité (voir .gitignore), lignes de la forme « A=motdepasse ». */
    static final Path PASSWORDS_FILE = Path.of("config", "mqtt-passwords.properties");

    private Config() { }

    /** Quota initial de chaque nœud : {A=200, B=200, C=200}. */
    public static Map<String, Integer> initialQuotas() {
        Map<String, Integer> quotas = new LinkedHashMap<>();
        for (String id : NODE_IDS) {
            quotas.put(id, QUOTA_INITIAL);
        }
        return quotas;
    }

    /**
     * Mot de passe MQTT d'un client (A, B, C, DASHBOARD) : variable d'environnement
     * {@code SMARTWATER_MQTT_PASSWORD_<ID>}, sinon fichier {@code config/mqtt-passwords.properties}.
     *
     * @throws IllegalStateException si aucun mot de passe n'est configuré
     */
    public static String mqttPassword(String clientId) {
        return resolvePassword(clientId, System::getenv, PASSWORDS_FILE);
    }

    /** Version testable : l'environnement et le fichier sont injectés. */
    static String resolvePassword(String clientId, Function<String, String> env, Path file) {
        if (!MQTT_CLIENT_IDS.contains(clientId)) {
            throw new IllegalArgumentException("Client MQTT inconnu : " + clientId);
        }
        String fromEnv = env.apply(PASSWORD_ENV_PREFIX + clientId);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        if (Files.isRegularFile(file)) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Lecture impossible de " + file + " : " + e.getMessage(), e);
            }
            String fromFile = props.getProperty(clientId);
            if (fromFile != null && !fromFile.isEmpty()) {
                return fromFile;
            }
        }
        throw new IllegalStateException("Mot de passe MQTT introuvable pour " + clientId
                + " : définir la variable d'environnement " + PASSWORD_ENV_PREFIX + clientId
                + " ou ajouter la ligne « " + clientId + "=… » dans " + file);
    }
}
