package com.smartwater.dashboard;

import com.smartwater.Config;
import com.smartwater.blockchain.LedgerReplicator;
import com.smartwater.blockchain.RejectedBlock;
import com.smartwater.communication.Message;
import com.smartwater.snapshot.GlobalSnapshot;
import com.smartwater.snapshot.LocalSnapshot;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Tableau de bord JavaFX (Partie 7, Binôme 2) : observe tout le système en <b>lecture seule</b>.
 *
 * <p>Panneaux : agriculteurs (état de la pompe, humidité, quota, demandes), réservoir, pompe (qui la tient,
 * file d'attente dans l'ordre de Ricart-Agrawala), registre (blocs, signatures, validité, réplication, blocs
 * refusés avec la règle violée), snapshot Chandy-Lamport, journal des messages.</p>
 *
 * <p>Options : {@code --keys=<dossier>} (clés publiques, par défaut {@code keys}),
 * {@code --broker=<url>}, {@code --tab=<0|1|2>} (onglet affiché au départ), {@code --capture=<fichier.png>} et
 * {@code --capture-after=<ms>} : enregistre une
 * image de la fenêtre puis se ferme (captures pour le rapport).</p>
 *
 * <pre>mvn javafx:run                       (ou : DashboardLauncher depuis l'IDE)</pre>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public class DashboardApp extends Application {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Map<String, String> STATE_COLORS = Map.of(
            "RELEASED", "#6b7280", "WANTED", "#d97706", "HELD", "#16a34a");

    private DashboardModel model;
    private DashboardMqttClient mqtt;
    private Timeline refresh;

    // ---- widgets mis à jour
    private final Label status = new Label("connexion…");
    private final Map<String, FarmerCard> cards = new HashMap<>();
    private final ProgressBar reservoirBar = new ProgressBar(1);
    private final Label reservoirLabel = big("—");
    private final Label holderLabel = big("—");
    private final Label waitingLabel = new Label("file d'attente : —");
    private final Label exclusionLabel = new Label();
    private final Label chainStatus = new Label();
    private final Label replicationLabel = new Label();
    private final Label snapshotLabel = new Label("aucun snapshot reçu : en déclencher un depuis un nœud (commande « s »)");
    private final ObservableList<DashboardModel.BlockView> blocks = FXCollections.observableArrayList();
    private final ObservableList<RejectedBlock> rejected = FXCollections.observableArrayList();
    private final ObservableList<LocalSnapshot> snapshotRows = FXCollections.observableArrayList();
    private final ObservableList<String> inTransit = FXCollections.observableArrayList();
    private final ObservableList<String> events = FXCollections.observableArrayList();
    private long lastMessages = -1;
    private String lastSnapshotKey = "";

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Map<String, String> opts = options(getParameters().getRaw());
        Path keys = Path.of(opts.getOrDefault("keys", Config.KEYS_DIR));
        model = new DashboardModel(keys);

        // La fenêtre s'adapte à la zone utile de l'écran (hors barre des tâches) : 1280 x 820 fixes dépassaient
        // d'un portable 1280 x 720 et coupaient le bas (blocs refusés, barre d'état).
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        compact = screen.getHeight() < 780;

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f3f4f6; -fx-font-size: " + (compact ? 12 : 13) + "px;");
        root.setTop(header());
        VBox center = new VBox(compact ? 8 : 12, overview(), details());
        VBox.setVgrow(center.getChildren().get(1), Priority.ALWAYS);
        center.setPadding(new Insets(compact ? 8 : 12));
        root.setCenter(center);
        status.setPadding(new Insets(4, 12, 4, 12));
        status.setStyle("-fx-text-fill: #374151;");
        root.setBottom(status);

        double width = Math.min(1280, screen.getWidth() - 16);
        double height = Math.min(820, screen.getHeight() - 56);          // 56 : barre de titre + marge
        stage.setTitle("Smart Water Sharing : tableau de bord");
        stage.setScene(new Scene(root, width, height));
        stage.setMinWidth(Math.min(960, screen.getWidth()));
        stage.setMinHeight(Math.min(520, screen.getHeight()));
        stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2);
        stage.setY(screen.getMinY() + 6);
        stage.show();

        mqtt = new DashboardMqttClient(opts.getOrDefault("broker", Config.BROKER_URL), passwordOrNull(),
                model::accept);
        mqtt.setStatusListener(s -> Platform.runLater(() -> status.setText(s)));
        Thread connector = new Thread(() -> {
            try {
                mqtt.connect();
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("connexion au broker impossible : " + e.getMessage()));
            }
        }, "dashboard-connect");
        connector.setDaemon(true);
        connector.start();

        refresh = new Timeline(new KeyFrame(Duration.millis(400), e -> update()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();

        if (opts.containsKey("tab")) {                             // 0 registre, 1 snapshot, 2 journal
            tabs.getSelectionModel().select(Integer.parseInt(opts.get("tab")));
        }
        if (opts.containsKey("capture")) {
            long after = Long.parseLong(opts.getOrDefault("capture-after", "3000"));
            Timeline shot = new Timeline(new KeyFrame(Duration.millis(after), e -> {
                update();
                capture(stage.getScene(), new File(opts.get("capture")));
                Platform.exit();
            }));
            shot.play();
        }
    }

    @Override
    public void stop() {
        if (refresh != null) refresh.stop();
        if (mqtt != null) mqtt.disconnect();
    }

    private static String passwordOrNull() {
        try {
            return Config.mqttPassword(DashboardMqttClient.CLIENT_ID);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ construction

    private Node header() {
        Label title = new Label("Smart Water Sharing");
        title.setStyle("-fx-font-size: " + (compact ? 17 : 20) + "px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label sub = new Label("observation en lecture seule de water/from/+  ·  Ricart-Agrawala · blockchain · Chandy-Lamport");
        sub.setStyle("-fx-text-fill: #dbeafe;");
        VBox box = compact ? new VBox(2, title) : new VBox(2, title, sub);
        box.setPadding(compact ? new Insets(6, 14, 6, 14) : new Insets(10, 14, 10, 14));
        box.setStyle("-fx-background-color: #1e3a8a;");
        return box;
    }

    private Node overview() {
        HBox row = new HBox(12);
        for (String id : Config.NODE_IDS) {
            FarmerCard c = new FarmerCard(id);
            cards.put(id, c);
            row.getChildren().add(c.root);
            HBox.setHgrow(c.root, Priority.ALWAYS);
        }
        reservoirBar.setMaxWidth(Double.MAX_VALUE);
        reservoirBar.setPrefHeight(22);
        reservoirBar.setStyle("-fx-accent: #2563eb;");
        VBox reservoir = card("Réservoir commun", reservoirLabel, reservoirBar,
                muted("recalculé depuis le registre (la chaîne fait foi)"));
        VBox pump = card("Pompe (section critique)", holderLabel, waitingLabel, exclusionLabel);
        reservoir.setMinWidth(190);
        pump.setMinWidth(200);
        row.getChildren().addAll(reservoir, pump);
        return row;
    }

    private final TabPane tabs = new TabPane();
    /** Écran de faible hauteur (portable) : polices, marges et listes réduites. */
    private boolean compact;
    private TableView<DashboardModel.BlockView> ledgerTable;

    private Node details() {
        tabs.getTabs().addAll(
                new Tab("Registre (blockchain)", ledgerPane()),
                new Tab("Snapshot Chandy-Lamport", snapshotPane()),
                new Tab("Journal des messages", eventsPane()));
        tabs.getTabs().forEach(t -> t.setClosable(false));
        return tabs;
    }

    private Node ledgerPane() {
        TableView<DashboardModel.BlockView> table = new TableView<>(blocks);
        ledgerTable = table;
        table.getColumns().add(col("#", b -> b.index(), 45));
        table.getColumns().add(col("Heure", b -> b.index() == 0 ? "—" : TIME.format(Instant.ofEpochMilli(b.timestamp())), 80));
        table.getColumns().add(col("Agriculteur", b -> b.nodeId(), 90));
        table.getColumns().add(col("Litres", b -> b.liters(), 60));
        table.getColumns().add(col("Quota restant", b -> b.quotaRemaining(), 100));
        table.getColumns().add(col("Hash", b -> shortHash(b.hash()), 120));
        table.getColumns().add(col("Hash précédent", b -> shortHash(b.previousHash()), 120));
        table.getColumns().add(col("Signature", b -> b.signatureOk() == null ? "genesis"
                : b.signatureOk() ? "✓ valide" : "✗ INVALIDE", 90));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("en attente du premier bloc (le registre se complète dès la prochaine balise des nœuds)"));

        ListView<RejectedBlock> refused = new ListView<>(rejected);
        refused.setPrefHeight(compact ? 64 : 110);
        refused.setMinHeight(48);
        refused.setPlaceholder(new Label("aucun bloc refusé"));
        refused.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(RejectedBlock r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : TIME.format(Instant.ofEpochMilli(r.time())) + "  bloc #"
                        + r.block().getIndex() + " de " + r.block().getNodeId() + " (" + r.block().getLiters()
                        + " L) : " + r.reason());
                setStyle(empty ? "" : "-fx-text-fill: #b91c1c;");
            }
        });
        Label refusedTitle = new Label("Blocs refusés (règles V1 à V7) — tentatives de triche");
        refusedTitle.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(8, new HBox(16, chainStatus, replicationLabel), table, refusedTitle, refused);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private Node snapshotPane() {
        TableView<LocalSnapshot> table = new TableView<>(snapshotRows);
        table.getColumns().add(col("Nœud", s -> s.nodeId(), 60));
        table.getColumns().add(col("Pompe", s -> s.state().pumpState(), 90));
        table.getColumns().add(col("Humidité", s -> String.format(Locale.ROOT, "%.1f %%", s.state().humidity()), 80));
        table.getColumns().add(col("Quota", s -> s.state().quotaRemaining() + " L", 70));
        table.getColumns().add(col("REPLY différés", s -> String.join(", ", s.state().deferredReplies()), 110));
        table.getColumns().add(col("Registre", s -> s.ledgerLength() + " blocs, " + shortHash(s.ledgerLastHash()), 170));
        table.getColumns().add(col("Enregistré à", s -> TIME.format(Instant.ofEpochMilli(s.recordedAt())), 90));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("aucun état local reçu"));
        ListView<String> transit = new ListView<>(inTransit);
        transit.setPrefHeight(compact ? 64 : 110);
        transit.setMinHeight(48);
        transit.setPlaceholder(new Label("aucun message en transit"));
        Label transitTitle = new Label("Messages en transit au moment du snapshot (état des canaux)");
        transitTitle.setStyle("-fx-font-weight: bold;");
        snapshotLabel.setWrapText(true);
        VBox box = new VBox(8, snapshotLabel, table, transitTitle, transit);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private Node eventsPane() {
        ListView<String> list = new ListView<>(events);
        list.setStyle("-fx-font-family: 'Consolas', monospace;");
        list.setPlaceholder(new Label("aucun message reçu"));
        VBox box = new VBox(list);
        VBox.setVgrow(list, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    // ------------------------------------------------------------------ mise à jour

    private void update() {
        DashboardModel.View v = model.view();
        for (DashboardModel.FarmerView f : v.farmers()) {
            FarmerCard c = cards.get(f.nodeId());
            if (c != null) c.show(f);
        }

        reservoirBar.setProgress(v.capacity() == 0 ? 0 : (double) v.reservoir() / v.capacity());
        reservoirLabel.setText(v.reservoir() + " / " + v.capacity() + " L");

        holderLabel.setText(v.holders().isEmpty() ? "libre" : "tenue par " + String.join(" + ", v.holders()));
        holderLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: "
                + (v.holders().isEmpty() ? "#374151" : v.mutualExclusionViolated() ? "#b91c1c" : "#16a34a") + ";");
        waitingLabel.setText("file d'attente : " + (v.waiting().isEmpty() ? "vide" : String.join(" → ", v.waiting())));
        exclusionLabel.setText(v.mutualExclusionViolated() ? "⚠ exclusion mutuelle violée" : "exclusion mutuelle respectée");
        exclusionLabel.setStyle("-fx-text-fill: " + (v.mutualExclusionViolated() ? "#b91c1c" : "#15803d") + ";");

        if (v.chain().size() != blocks.size()) {
            blocks.setAll(v.chain());
            if (ledgerTable != null && !blocks.isEmpty()) {
                ledgerTable.scrollTo(blocks.size() - 1);          // toujours montrer le bloc le plus récent
            }
        }
        if (v.rejected().size() != rejected.size()) {
            rejected.setAll(v.rejected());
        }
        chainStatus.setText("Chaîne : " + v.chain().size() + " blocs, " + (v.chainValid() ? "✓ valide" : "✗ INVALIDE"));
        chainStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: " + (v.chainValid() ? "#15803d" : "#b91c1c") + ";");
        replicationLabel.setText(replication(v));

        showSnapshot(v.snapshot());

        if (v.messages() != lastMessages) {
            lastMessages = v.messages();
            List<String> lines = new ArrayList<>();
            for (DashboardModel.EventView e : v.events()) {
                lines.add(String.format("%s  H=%-4d %-16s %s → %-3s %s", TIME.format(Instant.ofEpochMilli(e.time())),
                        e.lamport(), e.type(), e.from(), Message.ALL.equals(e.to()) ? "*" : e.to(), e.text()));
            }
            events.setAll(lines);
        }
        boolean silent = v.messages() == 0 && v.beacons() == 0;
        status.setText((mqtt != null && mqtt.isConnected() ? "● connecté" : "○ déconnecté") + "  ·  "
                + v.messages() + " messages  ·  " + v.beacons() + " balises d'état  ·  usurpations détectées : "
                + (mqtt == null ? 0 : mqtt.getSpoofedCount())
                + (silent ? "  ·  aucun nœud détecté : lancez le scénario (scripts\\run-demo.ps1)" : ""));
    }

    private static String replication(DashboardModel.View v) {
        StringBuilder sb = new StringBuilder("Dernière copie annoncée : ");
        for (String id : Config.NODE_IDS) {
            LedgerReplicator.PeerHead h = v.peerHeads().get(id);
            // ⚠ seulement si l'écart dure : une balise a jusqu'à 1 s de retard, ce n'est pas une anomalie
            boolean lasting = h != null && System.currentTimeMillis() - h.time() > 3 * Config.STATUS_PERIOD_MS;
            sb.append(id).append(" = ").append(h == null ? "?"
                    : h.length() + " blocs " + (h.length() == v.chain().size() ? "✓" : lasting ? "⚠" : "…")).append("   ");
        }
        sb.append(v.divergences().isEmpty() ? " ·  aucune divergence" : " ·  ⚠ " + v.divergences().size()
                + " divergence(s) : " + v.divergences().get(v.divergences().size() - 1).reason());
        return sb.toString();
    }

    private void showSnapshot(GlobalSnapshot s) {
        if (s == null) return;
        String key = s.snapshotId() + "/" + s.nodes().size();
        if (key.equals(lastSnapshotKey)) return;
        lastSnapshotKey = key;
        snapshotLabel.setText("Snapshot " + s.snapshotId() + " initié par " + s.initiator() + " : "
                + (s.isComplete() ? "complet (" : "en cours (") + s.nodes().size() + "/" + s.expectedNodes().size()
                + " états locaux)  ·  registres " + (s.sameLedgerEverywhere() ? "identiques partout" : "DIFFÉRENTS")
                + "  ·  " + s.messagesInTransit().size() + " message(s) en transit");
        snapshotRows.setAll(s.nodes().values());
        List<String> transit = new ArrayList<>();
        for (LocalSnapshot ls : s.nodes().values()) {
            ls.channels().forEach((peer, msgs) -> msgs.forEach(m ->
                    transit.add("canal " + peer + " → " + ls.nodeId() + " : " + m.getType() + " (H=" + m.getLamport()
                            + ") " + DashboardModel.describe(m))));
        }
        inTransit.setAll(transit);
    }

    // ------------------------------------------------------------------ composants

    /** Carte d'un agriculteur. */
    private static final class FarmerCard {
        final VBox root;
        final Label state = new Label();
        final Label live = new Label("●");
        private String liveText = "";
        final ProgressBar humidity = new ProgressBar(0);
        final Label humidityText = new Label("humidité : en attente du nœud…");
        final ProgressBar quota = new ProgressBar(1);
        final Label quotaText = new Label();
        final Label stats = new Label();

        FarmerCard(String id) {
            Label name = new Label("Agriculteur " + id);
            name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            live.setStyle("-fx-font-size: 13px; -fx-text-fill: #9ca3af;");
            HBox top = new HBox(6, live, name, spacer, state);   // voyant de présence devant le nom
            top.setAlignment(Pos.CENTER_LEFT);
            for (ProgressBar bar : List.of(humidity, quota)) {
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.setMinHeight(14);
                bar.setPrefHeight(14);
            }
            humidity.setStyle("-fx-accent: #0891b2;");
            quota.setStyle("-fx-accent: #7c3aed;");
            root = card(null, top, humidityText, humidity, quotaText, quota, stats);
            root.setMinWidth(170);
        }

        void show(DashboardModel.FarmerView f) {
            state.setText(f.pumpState());
            state.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 2 8 2 8; -fx-background-radius: 10;"
                    + " -fx-background-color: " + STATE_COLORS.getOrDefault(f.pumpState(), "#6b7280") + ";");
            String text;
            switch (f.liveness()) {
                case "ONLINE" -> {
                    text = "en ligne";
                    live.setStyle("-fx-font-size: 13px; -fx-text-fill: #16a34a;");
                    root.setOpacity(1.0);
                }
                case "OFFLINE" -> {
                    text = "hors ligne : aucun signal du nœud depuis plus de "
                            + DashboardModel.OFFLINE_AFTER_MS / 1000 + " s";
                    live.setStyle("-fx-font-size: 13px; -fx-text-fill: #dc2626;");
                    root.setOpacity(0.6);
                }
                default -> {
                    text = "en attente d'un signal de ce nœud";
                    live.setStyle("-fx-font-size: 13px; -fx-text-fill: #9ca3af;");
                }
            }
            if (!text.equals(liveText)) {                        // une infobulle par changement, pas toutes les 400 ms
                liveText = text;
                live.setTooltip(new javafx.scene.control.Tooltip(text));
            }
            if (f.humidity() != null) {
                humidity.setProgress(f.humidity() / 100.0);
                humidityText.setText(String.format(Locale.ROOT, "humidité : %.1f %%", f.humidity()));
            }
            quota.setProgress((double) f.quota() / Config.QUOTA_INITIAL);
            quotaText.setText("quota : " + f.quota() + " / " + Config.QUOTA_INITIAL + " L");
            stats.setText((f.liveness().equals("OFFLINE") ? "hors ligne  ·  " : "") + "demandes " + f.requests()
                    + "  ·  accès " + f.entries()
                    + (f.lastRequestLamport() >= 0 ? "  ·  H=" + f.lastRequestLamport() : ""));
            stats.setStyle("-fx-text-fill: #4b5563;");
        }
    }

    private static VBox card(String title, Node... content) {
        VBox box = new VBox(6);
        if (title != null) {
            Label t = new Label(title);
            t.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
            box.getChildren().add(t);
        }
        box.getChildren().addAll(content);
        box.setPadding(new Insets(10, 12, 10, 12));
        box.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-border-color: #e5e7eb;"
                + " -fx-border-radius: 8;");
        return box;
    }

    private static Label big(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        return l;
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
        l.setWrapText(true);
        return l;
    }

    private static <S, T> TableColumn<S, T> col(String title, Function<S, T> value, double width) {
        TableColumn<S, T> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    // ------------------------------------------------------------------ utilitaires

    private static String shortHash(String h) {
        return h == null ? "—" : h.length() > 10 ? h.substring(0, 10) + "…" : h;
    }

    static Map<String, String> options(List<String> args) {
        Map<String, String> opts = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) {
                opts.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            }
        }
        return opts;
    }

    /** Image PNG de la fenêtre (captures pour le rapport). */
    private static void capture(Scene scene, File file) {
        WritableImage img = scene.snapshot(null);
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader px = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, px.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(out, "png", file);
            System.out.println("[DASHBOARD] capture enregistrée : " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[DASHBOARD] capture impossible : " + e.getMessage());
        }
    }
}
