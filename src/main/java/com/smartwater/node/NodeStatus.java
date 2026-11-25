package com.smartwater.node;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.RejectedBlock;

import java.util.ArrayList;
import java.util.List;

// Contenu d'une balise d'état NODE_STATUS
public record NodeStatus(LocalState local, JsonObject stats, List<Block> blocks, List<RejectedBlock> rejected) {

    private static final Gson GSON = new Gson();

    public NodeStatus {
        stats = stats == null ? new JsonObject() : stats;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    // Balise sans blocs refusés
    public NodeStatus(LocalState local, JsonObject stats, List<Block> blocks) {
        this(local, stats, blocks, List.of());
    }

    // Charge utile du message NODE_STATUS
    public JsonObject toPayload() {
        JsonObject payload = new JsonObject();
        payload.add("local", GSON.toJsonTree(local));
        payload.add("stats", stats);
        JsonArray array = new JsonArray();
        for (Block b : blocks) {
            array.add(GSON.toJsonTree(b));
        }
        payload.add("blocks", array);
        JsonArray refused = new JsonArray();
        for (RejectedBlock r : rejected) {
            refused.add(GSON.toJsonTree(r));
        }
        payload.add("rejected", refused);
        return payload;
    }

    // Relit une charge utile reçue du réseau
    public static NodeStatus fromPayload(JsonObject p) {
        try {
            if (p == null || !p.has("local") || !p.get("local").isJsonObject()) {
                throw new IllegalArgumentException("NODE_STATUS sans état local");
            }
            LocalState local = GSON.fromJson(p.get("local"), LocalState.class);
            if (local == null || local.nodeId() == null) {
                throw new IllegalArgumentException("NODE_STATUS : état local illisible");
            }
            JsonObject stats = p.has("stats") && p.get("stats").isJsonObject() ? p.getAsJsonObject("stats") : new JsonObject();
            List<Block> blocks = new ArrayList<>();
            if (p.has("blocks") && p.get("blocks").isJsonArray()) {
                for (JsonElement e : p.getAsJsonArray("blocks")) {
                    Block b = GSON.fromJson(e, Block.class);
                    if (b == null) {
                        throw new IllegalArgumentException("NODE_STATUS : bloc illisible");
                    }
                    blocks.add(b);
                }
            }
            List<RejectedBlock> rejected = new ArrayList<>();
            if (p.has("rejected") && p.get("rejected").isJsonArray()) {
                for (JsonElement e : p.getAsJsonArray("rejected")) {
                    RejectedBlock r = GSON.fromJson(e, RejectedBlock.class);
                    if (r == null || r.block() == null || r.reason() == null) {
                        throw new IllegalArgumentException("NODE_STATUS : bloc refusé illisible");
                    }
                    rejected.add(r);
                }
            }
            return new NodeStatus(local, stats, blocks, rejected);
        } catch (com.google.gson.JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new IllegalArgumentException("NODE_STATUS mal formé : " + e.getMessage(), e);
        }
    }

    // lecture pratique des compteurs

    public long stat(String key, long fallback) {
        try {
            return stats.has(key) && stats.get(key).isJsonPrimitive() ? stats.get(key).getAsLong() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // Nœuds dont ce nœud attend encore un REPLY
    public List<String> awaiting() {
        List<String> out = new ArrayList<>();
        if (stats.has("awaiting") && stats.get("awaiting").isJsonArray()) {
            for (JsonElement e : stats.getAsJsonArray("awaiting")) {
                if (e.isJsonPrimitive()) out.add(e.getAsString());
            }
        }
        return out;
    }
}
