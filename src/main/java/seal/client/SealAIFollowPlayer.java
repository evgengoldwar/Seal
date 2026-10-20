package seal.client;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.PathNavigate;

/**
 * Dog-like following, modelled after the vanilla wolf AI: walk (or swim) to the player along a real path, hurry up when
 * falling behind, and hop over next to them when they get too far away.
 */
public class SealAIFollowPlayer extends EntityAIBase {

    /** Distance at which the seal gives up walking and teleports next to the player. */
    private static final double TELEPORT_DIST_SQ = 12.0D * 12.0D;
    /** Teleport even if a (partial) path exists - the player is clearly out of reach. */
    private static final double FORCE_TELEPORT_DIST_SQ = 24.0D * 24.0D;
    /** Beyond this distance the seal speeds up to catch up. */
    private static final double HURRY_DIST_SQ = 7.0D * 7.0D;

    private final EntitySeal seal;
    private final double speed;

    private EntityPlayer owner;
    private int repathTimer;

    public SealAIFollowPlayer(EntitySeal seal, double speed) {
        this.seal = seal;
        this.speed = speed;
        setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        EntityPlayer player = seal.getOwner();
        if (player == null) return false;
        float startDist = seal.getFollowStartDistance();
        if (seal.getDistanceSqToFeet(player) < startDist * startDist) return false;
        owner = player;
        return true;
    }

    @Override
    public boolean continueExecuting() {
        return owner != null && owner == seal.getOwner()
            && !seal.getNavigator()
                .noPath()
            && seal.getDistanceSqToFeet(owner) > stopDistSq();
    }

    private double stopDistSq() {
        float stopDist = seal.getFollowStopDistance();
        return stopDist * stopDist;
    }

    @Override
    public void startExecuting() {
        repathTimer = 0;
    }

    @Override
    public void resetTask() {
        owner = null;
        seal.getNavigator()
            .clearPathEntity();
    }

    @Override
    public void updateTask() {
        seal.getLookHelper()
            .setLookPositionWithEntity(owner, 10.0F, seal.getVerticalFaceSpeed());

        if (--repathTimer > 0) return;
        repathTimer = 10;

        double distSq = seal.getDistanceSqToFeet(owner);
        if (distSq >= FORCE_TELEPORT_DIST_SQ && seal.moveNextTo(owner)) return;

        PathNavigate navigator = seal.getNavigator();
        double moveSpeed = distSq > HURRY_DIST_SQ ? speed * 1.5D : speed;
        if (!navigator.tryMoveToEntityLiving(owner, moveSpeed) && distSq >= TELEPORT_DIST_SQ) {
            seal.moveNextTo(owner);
        }
    }
}
