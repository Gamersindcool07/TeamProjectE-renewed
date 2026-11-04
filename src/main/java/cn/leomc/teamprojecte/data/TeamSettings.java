package cn.leomc.teamprojecte.data;

/** Team sharing toggles + free-join flag. Plain data, no Minecraft types. */
public record TeamSettings(boolean shareKnowledge, boolean shareEmc, boolean freeJoin) {
    public static TeamSettings defaults() {
        return new TeamSettings(true, true, true);
    }
}
