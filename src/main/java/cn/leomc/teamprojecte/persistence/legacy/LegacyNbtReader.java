package cn.leomc.teamprojecte.persistence.legacy;

import cn.leomc.teamprojecte.TeamProjectE;
import cn.leomc.teamprojecte.data.EmcSnapshot;
import cn.leomc.teamprojecte.data.ItemKey;
import cn.leomc.teamprojecte.data.KnowledgeSnapshot;
import cn.leomc.teamprojecte.data.TeamEntity;
import cn.leomc.teamprojecte.data.TeamSettings;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One-shot legacy adapter: reads pre-LiteSQL saves (NBT
 * {@code world/data/teamprojecte.dat} and legacy SQLite
 * {@code world/teamprojecte.db} with {@code tp_teams} SNBT blobs) into
 * {@link TeamEntity} objects for migration. This is the only place that
 * still understands NBT/SNBT; the DAL itself is NBT-free. Legacy files are
 * copied to {@code .bak} backups and otherwise left untouched.
 */
public final class LegacyNbtReader {

    private LegacyNbtReader() {
    }

    /** Read all legacy team entities from world dir (NBT + legacy SQL). */
    public static List<TeamEntity> readAll(Path worldDir) {
        List<TeamEntity> out = new ArrayList<>();
        Map<UUID, TeamEntity> byUuid = new HashMap<>();
        // 1. Legacy NBT SavedData file.
        Path nbtFile = worldDir.resolve("data").resolve("teamprojecte.dat");
        if (Files.exists(nbtFile)) {
            try {
                backup(nbtFile);
                CompoundTag tag = NbtIo.readCompressed(nbtFile.toFile());
                String version = tag.getString("version");
                for (Tag t : tag.getList("teams", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) t;
                    CompoundTag teamTag = entry.getCompound("team");
                    try {
                        TeamEntity e = entityFromLegacy(teamTag, version.isEmpty() ? "2" : version);
                        if (e != null) byUuid.put(e.getUuid(), e);
                    } catch (Exception ex) {
                        TeamProjectE.LOGGER.warn("Skipping unreadable legacy NBT team entry", ex);
                    }
                }
                TeamProjectE.LOGGER.info("Legacy NBT read: {} team(s)", byUuid.size());
            } catch (Exception e) {
                TeamProjectE.LOGGER.warn("Failed to read legacy NBT teamprojecte.dat", e);
            }
        }
        // 2. Legacy SQLite file (tp_teams with SNBT knowledge/emc columns).
        Path legacyDb = worldDir.resolve("teamprojecte.db");
        if (Files.exists(legacyDb)) {
            try {
                List<TeamEntity> fromSql = readLegacySql(legacyDb);
                for (TeamEntity e : fromSql) byUuid.putIfAbsent(e.getUuid(), e);
                if (!fromSql.isEmpty()) TeamProjectE.LOGGER.info("Legacy SQL read: {} team(s)", fromSql.size());
            } catch (Exception e) {
                TeamProjectE.LOGGER.warn("Failed to read legacy SQL teamprojecte.db", e);
            }
        }
        out.addAll(byUuid.values());
        return out;
    }

    static void backup(Path file) {
        try {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            if (!Files.exists(bak)) {
                Files.copy(file, bak, StandardCopyOption.COPY_ATTRIBUTES);
                TeamProjectE.LOGGER.info("Legacy save backed up: {}", bak);
            }
        } catch (Exception e) {
            TeamProjectE.LOGGER.warn("Failed to back up legacy save {}", file, e);
        }
    }

    static List<TeamEntity> readLegacySql(Path legacyDb) throws Exception {
        backup(legacyDb);
        List<TeamEntity> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + legacyDb.toAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM tp_teams")) {
            // Old tp_teams schemas may predate the vanished column; probe first.
            java.util.Set<String> columns = new java.util.HashSet<>();
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnName(i).toLowerCase());
            if (!columns.contains("uuid") || !columns.contains("owner")) {
                TeamProjectE.LOGGER.warn("Legacy tp_teams table missing required columns, skipping");
                return out;
            }
            while (rs.next()) {
                try {
                    CompoundTag tag = new CompoundTag();
                    tag.putUUID("uuid", UUID.fromString(rs.getString("uuid")));
                    tag.putUUID("owner", UUID.fromString(rs.getString("owner")));
                    tag.putString("name", rs.getString("name") == null ? "" : rs.getString("name"));
                    ListTag members = new ListTag();
                    String rawMembers = rs.getString("members");
                    if (rawMembers != null && !rawMembers.isBlank()) {
                        for (String p : rawMembers.split(",")) {
                            String s = p.trim();
                            if (s.isEmpty()) continue;
                            CompoundTag m = new CompoundTag();
                            m.putUUID("uuid", UUID.fromString(s));
                            members.add(m);
                        }
                    }
                    tag.put("members", members);
                    tag.put("knowledge", parseSnbt(columns.contains("knowledge") ? rs.getString("knowledge") : null));
                    tag.put("emc", parseSnbt(columns.contains("emc") ? rs.getString("emc") : null));
                    tag.putBoolean("freeJoin", !columns.contains("free_join") || rs.getInt("free_join") != 0);
                    ListTag vanishedList = new ListTag();
                    String rawVanished = columns.contains("vanished") ? rs.getString("vanished") : null;
                    if (rawVanished != null && !rawVanished.isBlank()) {
                        for (String part : rawVanished.split(",")) {
                            String s = part.trim();
                            if (s.isEmpty()) continue;
                            CompoundTag v = new CompoundTag();
                            v.putUUID("uuid", UUID.fromString(s));
                            vanishedList.add(v);
                        }
                    }
                    tag.put("vanished", vanishedList);
                    TeamEntity e = entityFromLegacy(tag, "2");
                    if (e != null) out.add(e);
                } catch (Exception ex) {
                    TeamProjectE.LOGGER.warn("Skipping unreadable legacy SQL team row", ex);
                }
            }
        }
        return out;
    }

    static CompoundTag parseSnbt(String snbt) {
        if (snbt == null || snbt.isBlank()) return new CompoundTag();
        try {
            return TagParser.parseTag(snbt);
        } catch (Exception e) {
            TeamProjectE.LOGGER.warn("Failed to parse legacy SNBT, using empty tag", e);
            return new CompoundTag();
        }
    }

    /** Convert one legacy team CompoundTag (v1/v2 layout) into an entity. */
    static TeamEntity entityFromLegacy(CompoundTag tag, String version) {
        UUID uuid = tag.getUUID("uuid");
        UUID owner = tag.getUUID("owner");
        String name = tag.contains("name") ? tag.getString("name") : "Team " + uuid.toString().substring(0, 8);
        TeamEntity e = new TeamEntity(uuid, owner, name);
        List<UUID> members = tag.getList("members", Tag.TAG_COMPOUND).stream()
                .map(t -> ((CompoundTag) t).getUUID("uuid")).toList();
        e.addAllMembers(members);
        boolean freeJoin = tag.contains("freeJoin") ? tag.getBoolean("freeJoin") : true;
        Set<UUID> vanished = new HashSet<>();
        if (tag.contains("vanished")) {
            tag.getList("vanished", Tag.TAG_COMPOUND).forEach(v -> vanished.add(((CompoundTag) v).getUUID("uuid")));
        }
        KnowledgeSnapshot knowledge;
        EmcSnapshot emc;
        if (version == null || version.isEmpty() || version.equals("0")) {
            // v0: flat knowledge list + emc string on the team tag itself.
            Set<ItemKey> shared = new HashSet<>();
            tag.getList("knowledge", Tag.TAG_COMPOUND).stream()
                    .map(t -> ItemInfo.read((CompoundTag) t))
                    .filter(Objects::nonNull)
                    .forEach(info -> {
                        ItemKey key = ItemKey.from(info);
                        if (key != null) shared.add(key);
                    });
            knowledge = new KnowledgeSnapshot(true, false, shared, Map.of(), Set.of());
            BigInteger pool = BigInteger.ZERO;
            try { pool = new BigInteger(tag.getString("emc")); } catch (Exception ignored) { }
            emc = new EmcSnapshot(true, pool, Map.of());
        } else {
            knowledge = knowledgeFromLegacy(tag.getCompound("knowledge"));
            emc = emcFromLegacy(tag.getCompound("emc"));
        }
        e.setSettings(new TeamSettings(knowledge.sharing(), emc.sharing(), freeJoin));
        e.setKnowledge(knowledge);
        e.setEmc(emc);
        e.addAllVanished(vanished);
        return e;
    }

    static KnowledgeSnapshot knowledgeFromLegacy(CompoundTag tag) {
        String type = tag.getString("type");
        boolean sharing = !"not_sharing".equals(type);
        if (sharing) {
            Set<ItemKey> shared = new HashSet<>();
            tag.getList("knowledge", Tag.TAG_COMPOUND).stream()
                    .map(t -> ItemInfo.read((CompoundTag) t))
                    .filter(Objects::nonNull)
                    .forEach(info -> {
                        ItemKey key = ItemKey.from(info);
                        if (key != null) shared.add(key);
                    });
            boolean sharedFull = tag.getBoolean("fullKnowledge");
            Map<UUID, Set<ItemKey>> privateKnowledge = new HashMap<>();
            if (tag.contains("privateKnowledge")) {
                tag.getList("privateKnowledge", Tag.TAG_COMPOUND).forEach(t -> {
                    CompoundTag ct = (CompoundTag) t;
                    Set<ItemKey> set = new HashSet<>();
                    ct.getList("knowledge", Tag.TAG_COMPOUND).stream()
                            .map(i -> ItemInfo.read((CompoundTag) i))
                            .filter(Objects::nonNull)
                            .forEach(info -> {
                                ItemKey key = ItemKey.from(info);
                                if (key != null) set.add(key);
                            });
                    privateKnowledge.put(ct.getUUID("player"), set);
                });
            }
            Set<UUID> privateFull = new HashSet<>();
            if (tag.contains("privateFullKnowledge")) {
                tag.getList("privateFullKnowledge", Tag.TAG_INT_ARRAY)
                        .forEach(t -> privateFull.add(NbtUtils.loadUUID(t)));
            }
            // Legacy "counts" fallback: counts-only saves with no private sets.
            if (privateKnowledge.isEmpty() && tag.contains("counts")) {
                tag.getList("counts", Tag.TAG_COMPOUND).forEach(t -> {
                    CompoundTag ct = (CompoundTag) t;
                    ItemInfo info = ItemInfo.read(ct.getCompound("item"));
                    if (info != null) {
                        int count = ct.contains("memberCount") ? ct.getInt("memberCount") : 1;
                        if (count > 0) {
                            ItemKey key = ItemKey.from(info);
                            if (key != null) shared.add(key);
                        }
                    }
                });
            }
            return new KnowledgeSnapshot(true, sharedFull, shared, privateKnowledge, privateFull);
        } else {
            Map<UUID, Set<ItemKey>> privateKnowledge = new HashMap<>();
            tag.getList("knowledge", Tag.TAG_COMPOUND).forEach(t -> {
                CompoundTag ct = (CompoundTag) t;
                Set<ItemKey> set = new HashSet<>();
                ct.getList("knowledge", Tag.TAG_COMPOUND).stream()
                        .map(i -> ItemInfo.read((CompoundTag) i))
                        .filter(Objects::nonNull)
                        .forEach(info -> {
                            ItemKey key = ItemKey.from(info);
                            if (key != null) set.add(key);
                        });
                privateKnowledge.put(ct.getUUID("player"), set);
            });
            Set<ItemKey> shared = new HashSet<>();
            if (privateKnowledge.isEmpty() && tag.contains("counts")) {
                tag.getList("counts", Tag.TAG_COMPOUND).forEach(t -> {
                    CompoundTag ct = (CompoundTag) t;
                    ItemInfo info = ItemInfo.read(ct.getCompound("item"));
                    if (info != null) {
                        int count = ct.contains("memberCount") ? ct.getInt("memberCount") : 1;
                        if (count > 0) {
                            ItemKey key = ItemKey.from(info);
                            if (key != null) shared.add(key);
                        }
                    }
                });
            }
            Set<UUID> privateFull = new HashSet<>();
            tag.getList("fullKnowledge", Tag.TAG_INT_ARRAY).forEach(t -> privateFull.add(NbtUtils.loadUUID(t)));
            return new KnowledgeSnapshot(false, false, shared, privateKnowledge, privateFull);
        }
    }

    static EmcSnapshot emcFromLegacy(CompoundTag tag) {
        String type = tag.getString("type");
        if (!"not_sharing".equals(type)) {
            BigInteger pool = BigInteger.ZERO;
            try { pool = new BigInteger(tag.getString("emc")); } catch (Exception ignored) { }
            return new EmcSnapshot(true, pool, Map.of());
        }
        Map<UUID, BigInteger> byPlayer = new HashMap<>();
        tag.getList("emc", Tag.TAG_COMPOUND).forEach(t -> {
            CompoundTag ct = (CompoundTag) t;
            try {
                byPlayer.put(ct.getUUID("player"), new BigInteger(ct.getString("emc")));
            } catch (Exception ignored) { }
        });
        return new EmcSnapshot(false, BigInteger.ZERO, byPlayer);
    }
}
