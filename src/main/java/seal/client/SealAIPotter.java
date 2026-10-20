package seal.client;

import java.util.Random;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/** When the player stands still, the seal occasionally waddles to a random spot close to them. */
public class SealAIPotter extends EntityAIBase {

    private static final int RADIUS = 3;

    private final EntitySeal seal;
    private final double speed;

    private int targetX, targetY, targetZ;

    public SealAIPotter(EntitySeal seal, double speed) {
        this.seal = seal;
        this.speed = speed;
        setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        Random rand = seal.getRNG();
        if (rand.nextInt(100) != 0 || !seal.canWander()) return false;

        EntityPlayer owner = seal.getOwner();
        if (owner == null || EntitySeal.isHoldingFish(owner) || seal.getDistanceSqToFeet(owner) > 36.0D) return false;

        int baseX = MathHelper.floor_double(owner.posX);
        int baseY = MathHelper.floor_double(owner.boundingBox.minY);
        int baseZ = MathHelper.floor_double(owner.posZ);

        for (int attempt = 0; attempt < 10; attempt++) {
            int x = baseX + rand.nextInt(RADIUS * 2 + 1) - RADIUS;
            int z = baseZ + rand.nextInt(RADIUS * 2 + 1) - RADIUS;
            for (int y = baseY + 1; y >= baseY - 2; y--) {
                if (EntitySeal.isStandable(seal.worldObj, x, y, z)) {
                    targetX = x;
                    targetY = y;
                    targetZ = z;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void startExecuting() {
        seal.getNavigator()
            .tryMoveToXYZ(targetX + 0.5D, targetY, targetZ + 0.5D, speed);
    }

    @Override
    public boolean continueExecuting() {
        return seal.canWander() && !seal.getNavigator()
            .noPath();
    }
}
