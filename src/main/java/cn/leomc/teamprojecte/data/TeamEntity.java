package cn.leomc.teamprojecte.data;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Plain Java team entity (no Minecraft/NBT imports). The data-access layer
 * maps between this entity and SQL/JSON; runtime behaviour lives in
 * {@code cn.leomc.teamprojecte.TPTeam}, hydrated via {@code fromEntity}.
 */
public class TeamEntity {

    public static final int CURRENT_VERSION = 2;

    private final UUID uuid;
    private UUID owner;
    private String name;
    private TeamSettings settings;
    private final Set<UUID> members = new HashSet<>();
    private final Set<UUID> vanished = new HashSet<>();
    private KnowledgeSnapshot knowledge;
    private EmcSnapshot emc;
    private int version = CURRENT_VERSION;

    public TeamEntity(UUID uuid, UUID owner, String name) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.name = (name == null || name.isBlank()) ? "Team " + uuid.toString().substring(0, 8) : name.trim();
        this.settings = TeamSettings.defaults();
        this.knowledge = KnowledgeSnapshot.emptySharing();
        this.emc = EmcSnapshot.emptySharing();
    }

    public UUID getUuid() { return uuid; }
    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public TeamSettings getSettings() { return settings; }
    public void setSettings(TeamSettings settings) { this.settings = settings; }
    public Set<UUID> getMembers() { return Set.copyOf(members); }
    public void addMember(UUID uuid) { members.add(uuid); }
    public void clearMembers() { members.clear(); }
    public void addAllMembers(java.util.Collection<UUID> uuids) { members.addAll(uuids); }
    public Set<UUID> getVanished() { return Set.copyOf(vanished); }
    public void addVanished(UUID uuid) { vanished.add(uuid); }
    public void clearVanished() { vanished.clear(); }
    public void addAllVanished(java.util.Collection<UUID> uuids) { vanished.addAll(uuids); }
    public KnowledgeSnapshot getKnowledge() { return knowledge; }
    public void setKnowledge(KnowledgeSnapshot knowledge) { this.knowledge = knowledge; }
    public EmcSnapshot getEmc() { return emc; }
    public void setEmc(EmcSnapshot emc) { this.emc = emc; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamEntity other)) return false;
        return uuid.equals(other.uuid)
                && owner.equals(other.owner)
                && name.equals(other.name)
                && settings.equals(other.settings)
                && members.equals(other.members)
                && vanished.equals(other.vanished)
                && knowledge.equals(other.knowledge)
                && emc.equals(other.emc);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, owner, name, settings, members, vanished, knowledge, emc);
    }

    @Override
    public String toString() {
        return "TeamEntity[" + uuid + " owner=" + owner + " name=" + name
                + " members=" + members.size() + " settings=" + settings + "]";
    }
}
