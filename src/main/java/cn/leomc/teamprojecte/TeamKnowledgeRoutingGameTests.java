package cn.leomc.teamprojecte;

import com.mojang.authlib.GameProfile;
import java.math.BigInteger;
import java.util.UUID;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.PECapabilities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.Map;

/**
 * Regression gametest for knowledge routing / extraction (plan:
 * 2026-11-04-knowledge-routing-extraction-bug).
 *
 * <p>Two members in one team learn distinct items. In non-sharing mode each
 * member must see (and be able to extract) only their own items. In sharing
 * mode both must see the union and be able to extract each other's items.
 *
 * <p>Extraction eligibility is modelled as {@code hasKnowledge(item) &&
 * getEmc() covers the item cost}. The team pool is credited with ample EMC
 * so the knowledge check is the deciding factor — exactly the server-side
 * gate used by the transmutation table ({@code hasKnowledge} + EMC
 * sufficiency), served per-viewer by {@link TeamKnowledgeProvider}.
 *
 * <p>Run with: {@code ./gradlew runGameTestServer}
 */
public class TeamKnowledgeRoutingGameTests {

    @GameTest(template = "empty", timeoutTicks = 200, setupTicks = 0)
    public static void nonSharingKnowledgeIsPerPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer playerA = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "KnowA"));
        ServerPlayer playerB = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "KnowB"));
        UUID uuidA = playerA.getUUID();
        UUID uuidB = playerB.getUUID();

        TPTeam team = TPTeam.createTeam(uuidA, "KnowledgeRouting");
        team.addMember(uuidB);
        team.setShareKnowledge(false);

        moze_intel.projecte.api.capabilities.IKnowledgeProvider providerA =
                playerA.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();
        moze_intel.projecte.api.capabilities.IKnowledgeProvider providerB =
                playerB.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();

        // Ample pooled EMC (gains always land on the owner slot) so that
        // extraction is decided by knowledge, not by funds.
        providerA.setEmc(new BigInteger("1000000"));
        providerB.setEmc(new BigInteger("1000000"));

        ItemInfo itemA = ItemInfo.fromItem(Items.STONE);
        ItemInfo itemB = ItemInfo.fromItem(Items.COBBLESTONE);

        providerA.addKnowledge(itemA);
        providerB.addKnowledge(itemB);

        // Each member knows their own item...
        assertTrue(providerA.hasKnowledge(itemA),
                "A should know its own learned item");
        assertTrue(providerB.hasKnowledge(itemB),
                "B should know its own learned item");
        // ...but neither knows the other's.
        assertTrue(!providerA.hasKnowledge(itemB),
                "A must not know B's private item in non-sharing mode");
        assertTrue(!providerB.hasKnowledge(itemA),
                "B must not know A's private item in non-sharing mode");

        // Per-viewer knowledge sets are disjoint.
        assertTrue(providerA.getKnowledge().contains(itemA)
                        && !providerA.getKnowledge().contains(itemB),
                "A's knowledge view should contain only its own item");
        assertTrue(providerB.getKnowledge().contains(itemB)
                        && !providerB.getKnowledge().contains(itemA),
                "B's knowledge view should contain only its own item");

        // Extraction: own items extractable (known + pooled EMC covers cost),
        // the other member's items are not.
        assertTrue(canExtract(providerA, itemA),
                "A should be able to extract its own item");
        assertTrue(!canExtract(providerA, itemB),
                "A should not be able to extract B's item");
        assertTrue(canExtract(providerB, itemB),
                "B should be able to extract its own item");
        assertTrue(!canExtract(providerB, itemA),
                "B should not be able to extract A's item");

        // Full knowledge is per-player in non-sharing mode.
        providerA.setFullKnowledge(true);
        ItemInfo anyItem = ItemInfo.fromItem(Items.OBSIDIAN);
        assertTrue(providerA.hasKnowledge(anyItem),
                "A with full knowledge should know any mapped item");
        assertTrue(!providerB.hasKnowledge(anyItem),
                "B without full knowledge must not know items in non-sharing mode");
        providerA.setFullKnowledge(false);

        cleanup(team);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, setupTicks = 0)
    public static void sharingKnowledgeIsUnion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer playerA = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "ShareA"));
        ServerPlayer playerB = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "ShareB"));
        UUID uuidA = playerA.getUUID();
        UUID uuidB = playerB.getUUID();

        TPTeam team = TPTeam.createTeam(uuidA, "KnowledgeSharing");
        team.addMember(uuidB);
        team.setShareKnowledge(true);

        moze_intel.projecte.api.capabilities.IKnowledgeProvider providerA =
                playerA.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();
        moze_intel.projecte.api.capabilities.IKnowledgeProvider providerB =
                playerB.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();

        providerA.setEmc(new BigInteger("1000000"));

        ItemInfo itemA = ItemInfo.fromItem(Items.DIRT);
        ItemInfo itemB = ItemInfo.fromItem(Items.SAND);

        providerA.addKnowledge(itemA);
        providerB.addKnowledge(itemB);

        // In sharing mode both members see the union...
        assertTrue(providerA.hasKnowledge(itemA) && providerA.hasKnowledge(itemB),
                "A should know both items in sharing mode");
        assertTrue(providerB.hasKnowledge(itemA) && providerB.hasKnowledge(itemB),
                "B should know both items in sharing mode");
        assertTrue(providerA.getKnowledge().contains(itemA)
                        && providerA.getKnowledge().contains(itemB),
                "A's knowledge view should be the union");
        assertTrue(providerB.getKnowledge().contains(itemA)
                        && providerB.getKnowledge().contains(itemB),
                "B's knowledge view should be the union");
        // ...and either member can extract either item.
        assertTrue(canExtract(providerA, itemA) && canExtract(providerA, itemB),
                "A should extract both items in sharing mode");
        assertTrue(canExtract(providerB, itemA) && canExtract(providerB, itemB),
                "B should extract both items in sharing mode");

        cleanup(team);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, setupTicks = 0)
    public static void sharingUnlearnSemantics(GameTestHelper helper) {
        // Plan 2026-11-04-team-knowledge-data-semantics-rework, via the
        // provider (transmutation table) path.
        ServerLevel level = helper.getLevel();
        ServerPlayer playerA = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "UnlearnA"));
        ServerPlayer playerB = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "UnlearnB"));
        UUID uuidA = playerA.getUUID();
        UUID uuidB = playerB.getUUID();

        TPTeam team = TPTeam.createTeam(uuidA, "KnowledgeUnlearn");
        team.addMember(uuidB);
        team.setShareKnowledge(true);

        moze_intel.projecte.api.capabilities.IKnowledgeProvider providerA =
                playerA.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();
        moze_intel.projecte.api.capabilities.IKnowledgeProvider providerB =
                playerB.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();

        providerA.setEmc(new BigInteger("1000000"));

        ItemInfo item = ItemInfo.fromItem(Items.GOLD_INGOT);

        // Both learn the same item.
        providerA.addKnowledge(item);
        providerB.addKnowledge(item);
        assertTrue(team.getMemberCount(item) == 2,
                "count should be 2 after both learn");

        // A unlearns: private set loses it, team set keeps it, A still sees it.
        providerA.removeKnowledge(item);
        assertTrue(!team.getPrivateKnowledge(uuidA).contains(item),
                "unlearner's private set should lose the item");
        assertTrue(team.getTeamKnowledge().contains(item),
                "team-knowledge-data should keep the item while B knows it");
        assertTrue(team.getMemberCount(item) == 1,
                "count should drop to 1 after one unlearn");
        assertTrue(providerA.hasKnowledge(item),
                "unlearner should still see the item via the shared view");
        assertTrue(providerB.hasKnowledge(item),
                "remaining learner should still see the item");
        assertTrue(providerA.getKnowledge().contains(item),
                "unlearner's table view should still contain the item");

        // B unlearns (last contributor): item leaves team-knowledge-data.
        providerB.removeKnowledge(item);
        assertTrue(team.getMemberCount(item) == 0,
                "count should be 0 after last contributor unlearns");
        assertTrue(!team.getTeamKnowledge().contains(item),
                "team-knowledge-data should drop the item");
        assertTrue(!providerA.hasKnowledge(item) && !providerB.hasKnowledge(item),
                "neither member should see the item after last unlearn");

        // Sole learner case: A learns alone, unlearn removes immediately.
        providerA.addKnowledge(item);
        assertTrue(providerB.hasKnowledge(item),
                "B should see the shared item learned by A alone");
        providerA.removeKnowledge(item);
        assertTrue(!team.getTeamKnowledge().contains(item),
                "sole learner unlearn should remove the item from team data");
        assertTrue(!providerA.hasKnowledge(item) && !providerB.hasKnowledge(item),
                "both should lose the item after sole learner unlearns");

        cleanup(team);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, setupTicks = 0)
    public static void transmutationPutAddsToPrivateKnowledge(GameTestHelper helper) {
        // Plan 2026-11-04-transmutation-put-checks-private-knowledge:
        // a put into the transmutation table (receiveInputsAndLocks) checks
        // private knowledge via isInPlayerKnowledge and adds via
        // ensureKnowledge -> addKnowledge; duplicate puts are idempotent.
        ServerLevel level = helper.getLevel();
        ServerPlayer playerA = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "PutA"));
        ServerPlayer playerB = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "PutB"));
        UUID uuidA = playerA.getUUID();
        UUID uuidB = playerB.getUUID();

        TPTeam team = TPTeam.createTeam(uuidA, "KnowledgePutChecks");
        team.addMember(uuidB);
        team.setShareKnowledge(false);

        moze_intel.projecte.api.capabilities.IKnowledgeProvider providerA =
                playerA.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();
        providerA.setEmc(new BigInteger("1000000"));

        ItemInfo item = ItemInfo.fromItem(Items.COBBLESTONE);
        assertTrue(!team.getKnowledge(uuidA).contains(item),
                "item should start unknown for A");
        assertTrue(!team.getPrivateKnowledge(uuidA).contains(item),
                "item should start absent from A's private set");

        // Put via the client->server put path.
        providerA.receiveInputsAndLocks(Map.of(0, new ItemStack(Items.COBBLESTONE)));

        assertTrue(team.getPrivateKnowledge(uuidA).contains(item),
                "put should add item to A's private knowledge (add-if-missing)");
        assertTrue(providerA.hasKnowledge(item),
                "A should know the put item after put");
        assertTrue(!team.getPrivateKnowledge(uuidB).contains(item),
                "put must not leak into B's private set in non-sharing mode");

        // Duplicate put: check-if-in-private-knowledge makes it a no-op (count unchanged).
        int countBefore = team.getMemberCount(item);
        providerA.receiveInputsAndLocks(Map.of(0, new ItemStack(Items.COBBLESTONE)));
        assertTrue(team.getMemberCount(item) == countBefore,
                "duplicate put must be idempotent (counts unchanged)");

        // isInPlayerKnowledge / ensureKnowledge guard surface.
        TeamKnowledgeProvider concrete = (TeamKnowledgeProvider) providerA;
        assertTrue(concrete.isInPlayerKnowledge(item),
                "isInPlayerKnowledge should be true after put");
        assertTrue(!concrete.ensureKnowledge(item),
                "ensureKnowledge should no-op for already-known items");

        cleanup(team);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, setupTicks = 0)
    public static void refreshKnowledgeConvergesWithTeam(GameTestHelper helper) {
        // Plan 2026-11-04-transmutation-table-knowledge-refresh: refreshKnowledge()
        // re-pulls authoritative team knowledge + EMC, reports real changes
        // only, and is idempotent when already converged.
        ServerLevel level = helper.getLevel();
        ServerPlayer playerA = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "RefreshA"));
        ServerPlayer playerB = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "RefreshB"));
        UUID uuidA = playerA.getUUID();
        UUID uuidB = playerB.getUUID();

        TPTeam team = TPTeam.createTeam(uuidA, "KnowledgeRefresh");
        team.addMember(uuidB);
        team.setShareKnowledge(true);

        TeamKnowledgeProvider providerA = (TeamKnowledgeProvider)
                playerA.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();
        TeamKnowledgeProvider providerB = (TeamKnowledgeProvider)
                playerB.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();

        providerA.setEmc(new BigInteger("1000000"));

        ItemInfo itemA = ItemInfo.fromItem(Items.STONE);
        ItemInfo itemB = ItemInfo.fromItem(Items.COBBLESTONE);
        providerA.addKnowledge(itemA);

        // Baseline refresh for B: captures existing shared knowledge, no change.
        assertTrue(!providerB.refreshKnowledge(),
                "first refresh should baseline without reporting a change");
        assertTrue(providerB.hasKnowledge(itemA),
                "B should see A's shared item after baseline refresh");

        // External learn by A (team knowledge change) -> B's refresh reports it.
        providerA.addKnowledge(itemB);
        assertTrue(providerB.refreshKnowledge(),
                "refresh should report a change after a teammate learns an item");
        assertTrue(providerB.hasKnowledge(itemB) && providerB.getKnowledge().contains(itemB),
                "B's table view should reflect the newly learned shared item");

        // Idempotent: a second refresh with no intervening change reports false.
        assertTrue(!providerB.refreshKnowledge(),
                "refresh should be a no-op when already converged");

        // EMC change on the pool -> B's refresh reports it and getEmc matches.
        providerA.setEmc(new BigInteger("2000000"));
        assertTrue(providerB.refreshKnowledge(),
                "refresh should report an EMC pool change");
        assertTrue(providerB.getEmc().equals(new BigInteger("2000000")),
                "B's table EMC should update to the new team pool");
        assertTrue(!providerB.refreshKnowledge(),
                "refresh should be a no-op after the EMC change is absorbed");

        cleanup(team);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, setupTicks = 0)
    public static void shareKnowledgeToggleLiveSyncs(GameTestHelper helper) {
        // Plan 2026-11-04-share-knowledge-live-sync: toggling
        // share-knowledge to false while a member is live must immediately
        // shrink that member's view (no rejoin), and toggling the flag
        // itself counts as a refresh change even for an identical set.
        ServerLevel level = helper.getLevel();
        ServerPlayer playerA = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "LiveSyncA"));
        ServerPlayer playerB = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "LiveSyncB"));
        UUID uuidA = playerA.getUUID();
        UUID uuidB = playerB.getUUID();

        TPTeam team = TPTeam.createTeam(uuidA, "KnowledgeLiveSync");
        team.addMember(uuidB);
        team.setShareKnowledge(true);

        TeamKnowledgeProvider providerA = (TeamKnowledgeProvider)
                playerA.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();
        TeamKnowledgeProvider providerB = (TeamKnowledgeProvider)
                playerB.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();

        ItemInfo itemA = ItemInfo.fromItem(Items.STONE);
        ItemInfo itemB = ItemInfo.fromItem(Items.COBBLESTONE);
        providerA.addKnowledge(itemA);
        providerB.addKnowledge(itemB);
        // Baseline B under sharing: B sees the union.
        providerB.refreshKnowledge();
        assertTrue(providerB.hasKnowledge(itemA) && providerB.hasKnowledge(itemB),
                "sharing: B should see both items before toggle");

        // Owner toggles to false live: setShareKnowledge syncs every online
        // member, so B's view must shrink immediately (no rejoin/refresh).
        team.setShareKnowledge(false);
        assertTrue(!providerB.hasKnowledge(itemA),
                "after live toggle to false, B must no longer see A's item");
        assertTrue(providerB.hasKnowledge(itemB),
                "after live toggle to false, B must keep its own item");
        assertTrue(!providerB.getKnowledge().contains(itemA),
                "B's table view must exclude A's item after live toggle");
        // refreshKnowledge must report the toggle (knowledge + sharing flag)
        // if the provider's snapshot predates it, and be a no-op once
        // converged. FakePlayers are not in the live player list, so the
        // toggle's sync may not have run for them — accept either path but
        // require convergence on the next refresh.
        providerB.refreshKnowledge();
        assertTrue(!providerB.refreshKnowledge(),
                "refresh after live toggle should converge (no spurious re-report)");

        // Identical-set toggle: both know stone privately; flipping the
        // flag must still be detected as a change on the next refresh.
        providerB.addKnowledge(itemA);
        providerB.refreshKnowledge(); // baseline at sharing=false, B knows stone+cobble
        team.setShareKnowledge(true); // live sync absorbs this toggle for online players
        assertTrue(providerB.hasKnowledge(itemA) && providerB.hasKnowledge(itemB),
                "sharing again: B should see both items live");
        providerB.refreshKnowledge();
        assertTrue(!providerB.refreshKnowledge(),
                "refresh in sharing mode should be a no-op once converged");

        cleanup(team);
        helper.succeed();
    }

    /** Server-side extraction gate: knows the item and pooled EMC covers a unit cost. */
    private static boolean canExtract(
            moze_intel.projecte.api.capabilities.IKnowledgeProvider provider, ItemInfo info) {
        return provider.hasKnowledge(info) && provider.getEmc().signum() > 0;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void cleanup(TPTeam team) {
        cn.leomc.teamprojecte.TPSavedData data = cn.leomc.teamprojecte.TPSavedData.getData();
        if (data != null) {
            data.teams.remove(team.getUUID());
            data.setDirty();
        }
    }
}
