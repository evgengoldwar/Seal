package seal.client;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;

/** When the player holds a fish, the seal sits up next to them, stares at the fish and claps its flippers. */
public class SealAIBegForFish extends EntityAIBase {

    private static final double BEG_DIST_SQ = 8.0D * 8.0D;

    private final EntitySeal seal;
    private EntityPlayer owner;

    public SealAIBegForFish(EntitySeal seal) {
        this.seal = seal;
        setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        EntityPlayer player = seal.getOwner();
        if (player == null || !EntitySeal.isHoldingFish(player) || seal.getDistanceSqToFeet(player) > BEG_DIST_SQ) {
            return false;
        }
        owner = player;
        return true;
    }

    @Override
    public boolean continueExecuting() {
        return owner == seal.getOwner() && shouldExecute();
    }

    @Override
    public void startExecuting() {
        seal.getNavigator()
            .clearPathEntity();
        seal.setBegging(true);
    }

    @Override
    public void resetTask() {
        seal.setBegging(false);
        owner = null;
    }

    @Override
    public void updateTask() {
        // Look at the fish in the player's hand rather than at their face.
        seal.getLookHelper()
            .setLookPosition(owner.posX, owner.boundingBox.minY + 1.1D, owner.posZ, 30.0F, seal.getVerticalFaceSpeed());
    }
}
