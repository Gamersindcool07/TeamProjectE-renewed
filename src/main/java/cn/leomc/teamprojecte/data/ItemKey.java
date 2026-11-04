package cn.leomc.teamprojecte.data;

import moze_intel.projecte.api.ItemInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Stable string identity for a known item: registry id plus optional NBT (SNBT).
 * Replaces {@link ItemInfo} NBT round-trips for persistence identity (plan:
 * 2026-11-04-stop-nbt-persistence-dal). Stored as {@code itemId} or
 * {@code itemId|nbtSnbt} in SQL rows.
 */
public record ItemKey(String itemId, String nbt) {

    public ItemKey {
        if (itemId == null) itemId = "";
        if (nbt == null) nbt = "";
    }

    public static ItemKey from(ItemInfo info) {
        if (info == null) return null;
        try {
            ResourceLocation loc = ForgeRegistries.ITEMS.getKey(info.getItem());
            if (loc == null) return null;
            var tag = info.getNBT();
            return new ItemKey(loc.toString(), tag == null ? "" : tag.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public ItemInfo toItemInfo() {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return null;
            var item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null) return null;
            if (nbt == null || nbt.isBlank()) return ItemInfo.fromItem(item);
            var tag = net.minecraft.nbt.TagParser.parseTag(nbt);
            return ItemInfo.fromItem(item, tag);
        } catch (Exception e) {
            return null;
        }
    }

    public static ItemKey parse(String s) {
        if (s == null || s.isBlank()) return null;
        int sep = s.indexOf('|');
        if (sep < 0) return new ItemKey(s.trim(), "");
        return new ItemKey(s.substring(0, sep).trim(), s.substring(sep + 1));
    }

    @Override
    public String toString() {
        return nbt.isEmpty() ? itemId : itemId + "|" + nbt;
    }
}
