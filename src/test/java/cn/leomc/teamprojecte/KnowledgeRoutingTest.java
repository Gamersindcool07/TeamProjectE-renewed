package cn.leomc.teamprojecte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.UUID;
import moze_intel.projecte.api.ItemInfo;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Regression tests for knowledge routing / extraction (plan:
 * 2026-11-04-knowledge-routing-extraction-bug).
 *
 * <p>Extraction eligibility mirrors the server-side gate in
 * {@link TeamKnowledgeProvider#hasKnowledge(ItemInfo)}: {@code hasFullKnowledge(player)
 * || getKnowledge(player).contains(info)} — a per-viewer check that must never
 * fall back to team aggregates ({@code getTeamKnowledge()} /
 * {@code getKnowledge(NIL_UUID)}), which are stats/UI only.
 *
 * <p>{@link ItemInfo} fixtures are allocated without constructors because the
 * Forge/Minecraft bootstrap (needed for {@code ItemInfo.fromItem}) is not
 * available in a plain JUnit run; equality in {@link ItemInfo} is item
 * identity plus NBT, so distinct allocated infos behave like distinct items.
 */
public class KnowledgeRoutingTest {

    private static final Unsafe UNSAFE = unsafe();
    private static final long ITEM_OFFSET = offset(ItemInfo.class, "item");
    private static final long NBT_OFFSET = offset(ItemInfo.class, "nbt");

    private static boolean canExtract(KnowledgeData data, UUID player, ItemInfo info) {
        return data.hasFullKnowledge(player) || data.getKnowledge(player).contains(info);
    }

    @Test
    void nonSharingEachMemberSeesAndExtractsOnlyOwn() {
        KnowledgeData data = new KnowledgeData.NotSharing();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemInfo itemA = info();
        ItemInfo itemB = info();

        assertTrue(data.addKnowledge(itemA, a));
        assertTrue(data.addKnowledge(itemB, b));

        // Per-player views are disjoint.
        assertTrue(data.getKnowledge(a).contains(itemA));
        assertFalse(data.getKnowledge(a).contains(itemB));
        assertTrue(data.getKnowledge(b).contains(itemB));
        assertFalse(data.getKnowledge(b).contains(itemA));

        // Extraction eligibility follows the same per-player view.
        assertTrue(canExtract(data, a, itemA));
        assertFalse(canExtract(data, a, itemB));
        assertTrue(canExtract(data, b, itemB));
        assertFalse(canExtract(data, b, itemA));

        // Team aggregate is the union (stats/UI only, not extraction).
        assertTrue(data.getTeamKnowledge().contains(itemA));
        assertTrue(data.getTeamKnowledge().contains(itemB));
    }

    @Test
    void nonSharingFullKnowledgeIsPerPlayer() {
        KnowledgeData data = new KnowledgeData.NotSharing();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemInfo anyItem = info();

        data.setFullKnowledge(true, a);

        assertTrue(data.hasFullKnowledge(a));
        assertFalse(data.hasFullKnowledge(b));
        assertTrue(canExtract(data, a, anyItem));
        assertFalse(canExtract(data, b, anyItem));
    }

    @Test
    void sharingBothMembersSeeUnionAndExtractBoth() {
        KnowledgeData data = new KnowledgeData.Sharing();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemInfo itemA = info();
        ItemInfo itemB = info();

        assertTrue(data.addKnowledge(itemA, a));
        assertTrue(data.addKnowledge(itemB, b));

        // Both members see the union.
        assertTrue(data.getKnowledge(a).contains(itemA));
        assertTrue(data.getKnowledge(a).contains(itemB));
        assertTrue(data.getKnowledge(b).contains(itemA));
        assertTrue(data.getKnowledge(b).contains(itemB));

        // And either member can extract either item.
        assertTrue(canExtract(data, a, itemA));
        assertTrue(canExtract(data, a, itemB));
        assertTrue(canExtract(data, b, itemA));
        assertTrue(canExtract(data, b, itemB));
    }

    @Test
    void sharingUnlearnSemantics() {
        // Plan 2026-11-04-team-knowledge-data-semantics-rework:
        // - sole learner unlearns -> item leaves team-knowledge-data, both lose it
        // - both learned -> one unlearns: private set loses it, team set keeps
        //   it, unlearner still sees it via the shared view; last contributor
        //   unlearning then removes it for everyone
        // - a member who never personally learned it cannot unlearn it
        KnowledgeData data = new KnowledgeData.Sharing();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        ItemInfo item = info();

        // A learns X alone: both A and B (and C) see it together.
        assertTrue(data.addKnowledge(item, a));
        assertEquals(1, data.getMemberCount(item));
        assertTrue(data.getTeamKnowledge().contains(item));
        assertTrue(data.getKnowledge(a).contains(item));
        assertTrue(data.getKnowledge(b).contains(item));
        assertTrue(data.getKnowledge(c).contains(item));

        // C never learned it: unlearn is a no-op, team set unaffected.
        assertFalse(data.removeKnowledge(item, c));
        assertTrue(data.getTeamKnowledge().contains(item));
        assertEquals(1, data.getMemberCount(item));

        // B learns the same item too.
        assertTrue(data.addKnowledge(item, b));
        assertEquals(2, data.getMemberCount(item));

        // A unlearns: A's private set loses X, count drops to 1,
        // team-knowledge-data keeps X, and A still sees X via shared view.
        assertTrue(data.removeKnowledge(item, a));
        assertFalse(data.getPrivateKnowledge(a).contains(item));
        assertTrue(data.getPrivateKnowledge(b).contains(item));
        assertEquals(1, data.getMemberCount(item));
        assertTrue(data.getTeamKnowledge().contains(item));
        assertTrue(data.getKnowledge(a).contains(item));
        assertTrue(canExtract(data, a, item));
        assertTrue(data.getKnowledge(b).contains(item));

        // B (last contributor) unlearns: X leaves team-knowledge-data,
        // and neither A nor B sees it anymore.
        assertTrue(data.removeKnowledge(item, b));
        assertEquals(0, data.getMemberCount(item));
        assertFalse(data.getTeamKnowledge().contains(item));
        assertFalse(data.getKnowledge(a).contains(item));
        assertFalse(data.getKnowledge(b).contains(item));
        assertFalse(canExtract(data, a, item));
        assertFalse(canExtract(data, b, item));
    }

    @Test
    void sharingSoleLearnerUnlearnRemovesImmediately() {
        KnowledgeData data = new KnowledgeData.Sharing();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemInfo item = info();

        assertTrue(data.addKnowledge(item, a));
        assertTrue(data.removeKnowledge(item, a));

        assertEquals(0, data.getMemberCount(item));
        assertFalse(data.getTeamKnowledge().contains(item));
        assertFalse(data.getKnowledge(a).contains(item));
        assertFalse(data.getKnowledge(b).contains(item));
        assertFalse(data.getPrivateKnowledge(a).contains(item));
    }

    @Test
    void addIfMissingIsIdempotent() {
        // Plan 2026-11-04-put-learns-item-check-player-knowledge:
        // check-if-in-player-knowledge then add-if-missing — adding an item
        // that is already known is a no-op (returns false, counts unchanged),
        // so no events/sync churn fire for duplicate learns.
        KnowledgeData data = new KnowledgeData.NotSharing();
        UUID a = UUID.randomUUID();
        ItemInfo item = info();

        // First add: missing -> added.
        assertFalse(data.getKnowledge(a).contains(item));
        assertTrue(data.addKnowledge(item, a));
        assertTrue(data.getKnowledge(a).contains(item));
        assertEquals(1, data.getMemberCount(item));

        // Second add: already in player-knowledge-data -> no-op.
        assertTrue(data.getKnowledge(a).contains(item));
        assertFalse(data.addKnowledge(item, a));
        assertEquals(1, data.getMemberCount(item));
    }

    @Test
    void addIfMissingIsIdempotentSharing() {
        KnowledgeData data = new KnowledgeData.Sharing();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ItemInfo item = info();

        assertFalse(data.getKnowledge(b).contains(item));
        assertTrue(data.addKnowledge(item, a));
        // Shared view already contains it for everyone: re-add is a no-op
        // only for the contributor (private set). B adding is a new
        // contribution, but a second add by A must no-op.
        assertFalse(data.addKnowledge(item, a));
        assertEquals(1, data.getMemberCount(item));
        assertTrue(data.addKnowledge(item, b));
        assertEquals(2, data.getMemberCount(item));
        assertFalse(data.addKnowledge(item, b));
        assertEquals(2, data.getMemberCount(item));
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsafe unavailable", e);
        }
    }

    private static long offset(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return UNSAFE.objectFieldOffset(field);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Field unavailable: " + type + "." + name, e);
        }
    }

    private static ItemInfo info() {
        try {
            ItemInfo info = (ItemInfo) UNSAFE.allocateInstance(ItemInfo.class);
            // The item slot is a plain identity token; ItemInfo.equals/hashCode
            // use it only by identity, so a plain Object stands in for the item
            // without loading Minecraft item classes in a plain JUnit run.
            UNSAFE.putObject(info, ITEM_OFFSET, new Object());
            UNSAFE.putObject(info, NBT_OFFSET, null);
            return info;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Cannot allocate ItemInfo fixture", e);
        }
    }
}
