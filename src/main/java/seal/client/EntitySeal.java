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
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
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

    /** Ticks the owner has to stand still before the seal sits down / falls asleep. */
    private static final int SIT_AFTER = 120;
    private static final int SLEEP_AFTER = 600;
    private static final int SHAKE_DURATION = 16;

    private int jumpCooldown;
    private boolean loggedState;

    // Mood and pose. The *Amount values fade between 0 and 1 so the model can blend poses smoothly.
    private float sitAmount, prevSitAmount;
    private float sleepAmount, prevSleepAmount;
    private float begAmount, prevBegAmount;
    private boolean sleeping;
    private boolean begging;
    private int idleTicks;
    private double anchorX, anchorY, anchorZ;
    private boolean hasAnchor;
    private int blinkTicks;
    private int nextBlinkIn = 60;
    private int happyTicks;
    private int shakeTicks;
    private boolean wasFar;
    private boolean wasInLiquid;

    public EntitySeal(World world) {
        super(world);
        setSize(0.5F, 0.45F);
        isImmuneToFire = true;

        getNavigator().setAvoidsWater(false);
        getNavigator().setCanSwim(true);

        tasks.addTask(0, new EntityAISwimming(this));
        tasks.addTask(1, new SealAIFollowPlayer(this, 1.0D));
        tasks.addTask(2, new SealAIBegForFish(this));
        tasks.addTask(3, new SealAIPotter(this, 0.6D));
        tasks.addTask(4, new EntityAIWatchClosest(this, EntityPlayer.class, 6.0F));
        tasks.addTask(5, new EntityAILookIdle(this));
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
     * Squared distance to the player's feet. On the client the local player's posY is at eye level, so the plain
     * entity distance would be off by 1.6 blocks vertically.
     */
    public double getDistanceSqToFeet(EntityPlayer player) {
        double dx = posX - player.posX;
        double dy = posY - player.boundingBox.minY;
        double dz = posZ - player.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Distance at which the seal starts walking after the player. */
    public float getFollowStartDistance() {
        EntityPlayer owner = getOwner();
        if (isScared()) return 1.2F;
        return owner != null && isHoldingFish(owner) ? 2.5F : 4.0F;
    }

    /** Distance at which the seal is close enough and stops. In the Nether it hides right under the player. */
    public float getFollowStopDistance() {
        EntityPlayer owner = getOwner();
        if (isScared()) return 0.4F;
        return owner != null && isHoldingFish(owner) ? 1.5F : 2.0F;
    }

    public static boolean isHoldingFish(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        return held != null && (held.getItem() == Items.fish || held.getItem() == Items.cooked_fished);
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
        updateMood(inLiquid);

        if (sleeping) {
            // Sound asleep: no thinking, no walking. The owner moving wakes it up (see updateMood).
            getNavigator().clearPathEntity();
            moveForward = 0.0F;
            moveStrafing = 0.0F;
            isJumping = false;
        } else {
            // AI: decide where to go and where to look.
            tasks.onUpdateTasks();
            getNavigator().onUpdateNavigation();
            getMoveHelper().onUpdateMoveHelper();
            getLookHelper().onUpdateLook();
            getJumpHelper().doJump();
        }

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

    private void updateMood(boolean inLiquid) {
        prevSitAmount = sitAmount;
        prevSleepAmount = sleepAmount;
        prevBegAmount = begAmount;

        if (blinkTicks > 0) {
            blinkTicks--;
        } else if (--nextBlinkIn <= 0) {
            blinkTicks = 3;
            nextBlinkIn = 50 + rand.nextInt(100);
        }
        if (happyTicks > 0) happyTicks--;
        if (shakeTicks > 0) {
            shakeTicks--;
            if (shakeTicks % 2 == 0) spawnParticles("splash", 3, 0.0D);
        }

        EntityPlayer owner = getOwner();
        double distSq = owner != null ? getDistanceSqToFeet(owner) : Double.MAX_VALUE;
        if (owner != null) {
            // Is the owner standing still? Small fidgeting doesn't count.
            double dx = owner.posX - anchorX, dy = owner.boundingBox.minY - anchorY, dz = owner.posZ - anchorZ;
            if (!hasAnchor || dx * dx + dy * dy + dz * dz > 0.36D) {
                anchorX = owner.posX;
                anchorY = owner.boundingBox.minY;
                anchorZ = owner.posZ;
                hasAnchor = true;
                idleTicks = 0;
            } else {
                idleTicks++;
            }
            // Fish wakes anyone up.
            if (isHoldingFish(owner) && distSq < 64.0D) idleTicks = 0;

            // Reunited after being left behind (or teleported back): happy hop.
            if (distSq > 100.0D) {
                wasFar = true;
            } else if (wasFar && distSq < 9.0D) {
                wasFar = false;
                celebrate();
            }
        }

        boolean canRest = owner != null && !isScared()
            && !begging
            && !inLiquid
            && onGround
            && distSq < 49.0D
            && getNavigator().noPath();
        sleeping = canRest && idleTicks > SLEEP_AFTER;
        boolean sitting = begging || canRest && idleTicks > SIT_AFTER && !sleeping;

        sitAmount = approach(sitAmount, sitting ? 1.0F : 0.0F, 0.1F);
        sleepAmount = approach(sleepAmount, sleeping ? 1.0F : 0.0F, 0.05F);
        begAmount = approach(begAmount, begging ? 1.0F : 0.0F, 0.15F);

        // Seals love water: shake off after a swim, and enjoy the rain.
        if (wasInLiquid && !inLiquid) startShake();
        wasInLiquid = inLiquid;
        if (isInRain()) {
            happyTicks = Math.max(happyTicks, 10);
            if (rand.nextInt(3) == 0) spawnParticles("splash", 1, height);
            if (!sleeping && shakeTicks == 0 && rand.nextInt(160) == 0) startShake();
        }
    }

    private static float approach(float value, float target, float step) {
        return value < target ? Math.min(value + step, target) : Math.max(value - step, target);
    }

    private void celebrate() {
        happyTicks = 40;
        idleTicks = 0;
        if (onGround) motionY = 0.4D;
        spawnParticles("heart", 5, height);
    }

    private void startShake() {
        shakeTicks = SHAKE_DURATION;
    }

    private boolean isInRain() {
        int x = MathHelper.floor_double(posX);
        int y = MathHelper.floor_double(posY);
        int z = MathHelper.floor_double(posZ);
        return worldObj.canLightningStrikeAt(x, y, z)
            || worldObj.canLightningStrikeAt(x, MathHelper.floor_double(posY + height), z);
    }

    private void spawnParticles(String name, int count, double yOffset) {
        for (int i = 0; i < count; i++) {
            worldObj.spawnParticle(
                name,
                posX + (rand.nextDouble() - 0.5D) * width * 1.5D,
                posY + yOffset + rand.nextDouble() * 0.3D,
                posZ + (rand.nextDouble() - 0.5D) * width * 1.5D,
                rand.nextGaussian() * 0.05D,
                0.1D,
                rand.nextGaussian() * 0.05D);
        }
    }

    // --- State read by the AI, model and renderer ---

    /** Too scared to wander off or nap: the Nether is no place for a seal. */
    public boolean isScared() {
        return worldObj.provider.isHellWorld;
    }

    public boolean canWander() {
        return !isScared() && !begging && sitAmount == 0.0F && sleepAmount == 0.0F;
    }

    public void setBegging(boolean begging) {
        this.begging = begging;
        if (begging) idleTicks = 0;
    }

    public boolean isHappy() {
        return happyTicks > 0;
    }

    public boolean areEyesClosed() {
        return blinkTicks > 0 || sleepAmount > 0.6F;
    }

    public float getSitAmount(float partialTicks) {
        return prevSitAmount + (sitAmount - prevSitAmount) * partialTicks;
    }

    public float getSleepAmount(float partialTicks) {
        return prevSleepAmount + (sleepAmount - prevSleepAmount) * partialTicks;
    }

    public float getBegAmount(float partialTicks) {
        return prevBegAmount + (begAmount - prevBegAmount) * partialTicks;
    }

    /** 0..1 through the current shake, or 0 if not shaking. */
    public float getShakeProgress(float partialTicks) {
        if (shakeTicks <= 0) return 0.0F;
        return Math.min((SHAKE_DURATION - shakeTicks + partialTicks) / SHAKE_DURATION, 1.0F);
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
