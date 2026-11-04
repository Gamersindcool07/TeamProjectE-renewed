package cn.leomc.teamprojecte;

import com.mojang.authlib.GameProfile;
import java.math.BigInteger;
import java.util.UUID;
import moze_intel.projecte.api.capabilities.PECapabilities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/**
 * Regression gametest for PLAN-transmutation-emc: the transmutation table must
 * show the team EMC pool, not 0 or personal EMC.
 *
 * <p>Run with: {@code ./gradlew runGameTestServer}
 */
public class TeamEmcGameTests {

    @GameTest(template = "empty", timeoutTicks = 100, setupTicks = 0)
    public static void teamEmcPoolIsShown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // --- Team member: gains must land on the team pool and be visible via getEmc().
        ServerPlayer fake = FakePlayerFactory.getMinecraft(level);
        UUID uuid = fake.getUUID();
        if (uuid == null) throw new AssertionError("FakePlayer UUID is null");
        if (TPTeam.getTeamByMember(uuid) != null)
            throw new AssertionError("FakePlayer should not already be in a team");

        TPTeam team = TPTeam.createTeam(uuid, "EmcTestTeam");
        moze_intel.projecte.api.capabilities.IKnowledgeProvider provider =
                fake.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();

        provider.setEmc(new BigInteger("1234")); // gain path -> team pool
        if (!new BigInteger("1234").equals(provider.getEmc()))
            throw new AssertionError("getEmc expected 1234 (team pool), got " + provider.getEmc());
        if (!new BigInteger("1234").equals(team.getEmc(team.getOwner())))
            throw new AssertionError("Team pool expected 1234, got " + team.getEmc(team.getOwner()));

        // syncEmc must not throw and must broadcast to online team members.
        provider.syncEmc(fake);

        // --- Spend path: reduce the pool; new value must be visible immediately.
        provider.setEmc(new BigInteger("100"));
        if (!new BigInteger("100").equals(provider.getEmc()))
            throw new AssertionError("getEmc after spend expected 100, got " + provider.getEmc());
        if (!new BigInteger("100").equals(team.getEmc(team.getOwner())))
            throw new AssertionError("Team pool after spend expected 100, got " + team.getEmc(team.getOwner()));

        // --- Non-team player: vanilla ephemeral behaviour (never creates a team).
        ServerPlayer solo = FakePlayerFactory.get(
                level, new GameProfile(UUID.randomUUID(), "SoloTester"));
        moze_intel.projecte.api.capabilities.IKnowledgeProvider soloProvider =
                solo.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).resolve().orElseThrow();
        if (!BigInteger.ZERO.equals(soloProvider.getEmc()))
            throw new AssertionError("Non-team getEmc expected 0, got " + soloProvider.getEmc());
        if (TPTeam.getTeamByMember(solo.getUUID()) != null)
            throw new AssertionError("Non-team player must not gain a persisted team");
        soloProvider.setEmc(new BigInteger("50"));
        if (!new BigInteger("50").equals(soloProvider.getEmc()))
            throw new AssertionError("Non-team emc expected 50, got " + soloProvider.getEmc());
        soloProvider.syncEmc(solo);
        soloProvider.sync(solo);

        // --- Cleanup: remove the test team so repeated runs stay isolated.
        cn.leomc.teamprojecte.TPSavedData data = cn.leomc.teamprojecte.TPSavedData.getData();
        if (data != null) {
            data.teams.remove(team.getUUID());
            data.setDirty();
        }

        helper.succeed();
    }
}
