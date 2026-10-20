package seal.client;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAIWatchClosest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import seal.Seal;

/**
 * A purely client-side pet, ticked and rendered by {@link SealController} rather than the world's entity list. It is
 * never sent to the server, never saved and can't be hit or pushed, but it still walks, jumps, swims and pathfinds
 * around blocks using the vanilla AI helpers.
 */
public class EntitySeal extends EntityCreature {

    private int jumpCooldown;
    private boolean loggedState;

    public EntitySeal(World world) {
        super(world);
        setSize(0.5F, 0.45F);
        isImmuneToFire = true;

        getNavigator().setAvoidsWater(false);
        getNavigator().setCanSwim(true);

        tasks.addTask(0, new EntityAISwimming(this));
        tasks.addTask(1, new SealAIFollowPlayer(this, 1.0D, 4.0F, 2.0F));
        tasks.addTask(2, new SealAIPotter(this, 0.6D));
        tasks.addTask(3, new EntityAIWatchClosest(this, EntityPlayer.class, 6.0F));
        tasks.addTask(4, new EntityAILookIdle(this));
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(10.0D);
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0.3D);
        getEntityAttribute(SharedMonsterAttributes.followRange).setBaseValue(32.0D);
    }

    /** The player the seal belongs to: always the local player, as long as they are in the same world. */
    public EntityPlayer getOwner() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        return player != null && player.worldObj == worldObj && player.isEntityAlive() ? player : null;
    }

    /**
     * Moves the seal onto a free spot next to the player (1-2 blocks away). Returns false if there is no safe ground
     * nearby, e.g. while the player is flying or swimming in open water.
     */
    public boolean moveNextTo(EntityPlayer player) {
        int baseX = MathHelper.floor_double(player.posX);
        int baseY = MathHelper.floor_double(player.boundingBox.minY);
        int baseZ = MathHelper.floor_double(player.posZ);

        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int x = baseX + dx, y = baseY + dy, z = baseZ + dz;
                    if (isStandable(worldObj, x, y, z)) {
                        setLocationAndAngles(x + 0.5D, y, z + 0.5D, rotationYaw, rotationPitch);
                        getNavigator().clearPathEntity();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True if the seal could stand at the given block: solid ground below and two free blocks of space. */
    public static boolean isStandable(World world, int x, int y, int z) {
        if (!World.doesBlockHaveSolidTopSurface(world, x, y - 1, z)) return false;
        Block feet = world.getBlock(x, y, z);
        Block head = world.getBlock(x, y + 1, z);
        return !feet.isNormalCube() && !head.isNormalCube()
            && !feet.getMaterial()
                .isLiquid()
            && feet.getCollisionBoundingBoxFromPool(world, x, y, z) == null;
    }

    // --- Client-side simulation ---

    /**
     * The whole tick of the seal, called by {@link SealController}. Vanilla's living entity update does not move an
     * entity that only exists on the client, so the AI helpers are driven here and the physics is done by hand, on
     * top of the same {@link #moveEntity} the player uses (block collisions, step-up, gravity).
     */
    @Override
    public void onUpdate() {
        prevPosX = posX;
        prevPosY = posY;
        prevPosZ = posZ;
        prevRotationYaw = rotationYaw;
        prevRotationPitch = rotationPitch;
        prevRenderYawOffset = renderYawOffset;
        prevRotationYawHead = rotationYawHead;
        prevLimbSwingAmount = limbSwingAmount;

        if (posY < -64.0D) {
            setDead();
            return;
        }

        boolean inLiquid = handleWaterMovement() || handleLavaMovement();

        // AI: decide where to go and where to look.
        tasks.onUpdateTasks();
        getNavigator().onUpdateNavigation();
        getMoveHelper().onUpdateMoveHelper();
        getLookHelper().onUpdateLook();
        getJumpHelper().doJump();

        if (jumpCooldown > 0) jumpCooldown--;
        if (isJumping) {
            if (inLiquid) {
                motionY += 0.04D;
            } else if (onGround && jumpCooldown == 0) {
                jump();
                jumpCooldown = 10;
            }
        }

        moveStrafing *= 0.98F;
        moveForward *= 0.98F;
        travel(moveStrafing, moveForward, inLiquid);
        updateBodyRotation();
        updateLimbSwing();

        if (!loggedState && ticksExisted == 40) {
            loggedState = true;
            Seal.LOG.info(
                "Seal state after 2s: pos=({}, {}, {}) onGround={} inLiquid={} motion=({}, {}, {}) forward={} path={}",
                posX,
                posY,
                posZ,
                onGround,
                inLiquid,
                motionX,
                motionY,
                motionZ,
                moveForward,
                !getNavigator().noPath());
        }
    }

    private void travel(float strafe, float forward, boolean inLiquid) {
        if (inLiquid) {
            double startY = posY;
            moveFlying(strafe, forward, 0.04F);
            moveEntity(motionX, motionY, motionZ);
            motionX *= 0.8D;
            motionY *= 0.8D;
            motionZ *= 0.8D;
            motionY -= 0.02D;
            if (isCollidedHorizontally && isOffsetPositionInLiquid(motionX, motionY + 0.6D - posY + startY, motionZ)) {
                motionY = 0.3D;
            }
            return;
        }

        float friction = 0.91F;
        if (onGround) {
            Block below = worldObj.getBlock(
                MathHelper.floor_double(posX),
                MathHelper.floor_double(boundingBox.minY) - 1,
                MathHelper.floor_double(posZ));
            friction = below.slipperiness * 0.91F;
        }
        float accel = onGround ? getAIMoveSpeed() * (0.16277136F / (friction * friction * friction)) : 0.02F;

        moveFlying(strafe, forward, accel);
        moveEntity(motionX, motionY, motionZ);

        motionY -= 0.08D;
        motionY *= 0.98D;
        motionX *= friction;
        motionZ *= friction;
    }

    /** Body turns towards where the seal walks; the head may look up to 75 degrees away from it. */
    private void updateBodyRotation() {
        double dx = posX - prevPosX;
        double dz = posZ - prevPosZ;
        if (dx * dx + dz * dz > 0.0025D) {
            renderYawOffset += MathHelper.wrapAngleTo180_float(rotationYaw - renderYawOffset) * 0.3F;
        }

        float headOffset = MathHelper.wrapAngleTo180_float(rotationYawHead - renderYawOffset);
        if (headOffset > 75.0F) renderYawOffset += headOffset - 75.0F;
        if (headOffset < -75.0F) renderYawOffset += headOffset + 75.0F;
    }

    private void updateLimbSwing() {
        double dx = posX - prevPosX;
        double dz = posZ - prevPosZ;
        float speed = Math.min(MathHelper.sqrt_double(dx * dx + dz * dz) * 4.0F, 1.0F);
        limbSwingAmount += (speed - limbSwingAmount) * 0.4F;
        limbSwing += limbSwingAmount;
    }

    // Keep vanilla code paths that check these consistent with the seal simulating itself.

    @Override
    public boolean isClientWorld() {
        return true;
    }

    @Override
    protected boolean isAIEnabled() {
        return true;
    }

    @Override
    protected boolean canDespawn() {
        return false;
    }

    @Override
    public boolean allowLeashing() {
        return false;
    }

    // --- Ghost behaviour: nobody can hit, push or ride it, and it doesn't push anything ---

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public void applyEntityCollision(Entity entity) {}

    @Override
    protected void collideWithNearbyEntities() {}

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isEntityInvulnerable() {
        return true;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public boolean writeToNBTOptional(NBTTagCompound tag) {
        return false;
    }

    @Override
    public String getCommandSenderName() {
        return StatCollector.translateToLocal("entity.seal.name");
    }
}
