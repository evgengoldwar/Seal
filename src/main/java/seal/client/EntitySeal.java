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
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.StatCollector;
import net.minecraft.util.Vec3;
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

    /** Walking speed in blocks per tick at path speed 1.0 (about 1.7 blocks per second). */
    private static final float WALK_SPEED = 0.085F;
    /** Speed change per tick when speeding up / slowing down: it takes about a second to get going. */
    private static final float ACCELERATION = 0.004F;
    private static final float DECELERATION = 0.012F;
    /** Fastest the seal may turn, in degrees per tick. */
    private static final float TURN_RATE = 15.0F;
    /** How far the head may turn away from the body. */
    private static final float MAX_HEAD_TURN = 75.0F;

    private int jumpCooldown;
    private float currentSpeed;
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
    private float happyAmount, prevHappyAmount;
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
        // 1.0 so that the AI's moveForward is simply the path speed multiplier, see steer().
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(1.0D);
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

        steer();
        travel(inLiquid);
        updateBodyRotation();
        updateLimbSwing();

        if (!loggedState && ticksExisted == 40) {
            loggedState = true;
            Seal.LOG.info(
                "Seal state after 2s: pos=({}, {}, {}) onGround={} inLiquid={} motion=({}, {}, {}) speed={} path={}",
                posX,
                posY,
                posZ,
                onGround,
                inLiquid,
                motionX,
                motionY,
                motionZ,
                currentSpeed,
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
        prevHappyAmount = happyAmount;
        happyAmount = approach(happyAmount, happyTicks > 0 ? 1.0F : 0.0F, 0.08F);
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
        if (onGround) motionY = 0.3D;
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

    public float getHappyAmount(float partialTicks) {
        return prevHappyAmount + (happyAmount - prevHappyAmount) * partialTicks;
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

    /**
     * Turns the AI's wishes into a smooth heading and speed. The move helper snaps rotationYaw towards the next path
     * point and asks for full speed at once; here the turn is rate-limited and the speed ramps up and down gradually,
     * slowing down for sharp turns instead of drifting sideways.
     */
    private void steer() {
        float turn = MathHelper.wrapAngleTo180_float(rotationYaw - prevRotationYaw);
        rotationYaw = prevRotationYaw + MathHelper.clamp_float(turn, -TURN_RATE, TURN_RATE);

        float targetSpeed = Math.max(moveForward, 0.0F) * WALK_SPEED;
        PathEntity path = getNavigator().getPath();
        if (targetSpeed > 0.0F && path != null && !path.isFinished()) {
            Vec3 next = path.getPosition(this);
            float wantedYaw = (float) (Math.atan2(next.zCoord - posZ, next.xCoord - posX) * 180.0D / Math.PI) - 90.0F;
            float misalignment = Math.abs(MathHelper.wrapAngleTo180_float(wantedYaw - rotationYaw));
            if (misalignment > 30.0F) {
                float rad = Math.min(misalignment, 90.0F) * (float) Math.PI / 180.0F;
                targetSpeed *= Math.max(0.2F, MathHelper.cos(rad));
            }
        }

        if (currentSpeed < targetSpeed) {
            currentSpeed = Math.min(currentSpeed + ACCELERATION, targetSpeed);
        } else {
            currentSpeed = Math.max(currentSpeed - DECELERATION, targetSpeed);
        }
        moveForward = 0.0F;
        moveStrafing = 0.0F;
    }

    private void travel(boolean inLiquid) {
        float yawRad = rotationYaw * (float) Math.PI / 180.0F;
        double wantX = -MathHelper.sin(yawRad) * currentSpeed;
        double wantZ = MathHelper.cos(yawRad) * currentSpeed;

        if (inLiquid) {
            double startY = posY;
            motionX += (wantX * 0.9D - motionX) * 0.12D;
            motionZ += (wantZ * 0.9D - motionZ) * 0.12D;
            moveEntity(motionX, motionY, motionZ);
            motionY *= 0.8D;
            motionY -= 0.02D;
            if (isCollidedHorizontally && isOffsetPositionInLiquid(motionX, motionY + 0.6D - posY + startY, motionZ)) {
                motionY = 0.3D;
            }
            return;
        }

        // Grip: how quickly the actual velocity follows the wanted one. Less on ice, very little in the air.
        double grip = 0.04D;
        if (onGround) {
            Block below = worldObj.getBlock(
                MathHelper.floor_double(posX),
                MathHelper.floor_double(boundingBox.minY) - 1,
                MathHelper.floor_double(posZ));
            grip = 0.35D * 0.6D / below.slipperiness;
        }
        motionX += (wantX - motionX) * grip;
        motionZ += (wantZ - motionZ) * grip;

        moveEntity(motionX, motionY, motionZ);

        motionY -= 0.08D;
        motionY *= 0.98D;
    }

    /**
     * The body faces where the seal is heading and turns smoothly; the head follows the body and may look at most
     * {@link #MAX_HEAD_TURN} degrees to the side. Standing still, the body slowly turns towards where the head looks.
     */
    private void updateBodyRotation() {
        float target;
        float maxStep;
        if (currentSpeed > 0.005F) {
            target = rotationYaw;
            maxStep = 12.0F;
        } else {
            float headOffset = MathHelper.wrapAngleTo180_float(rotationYawHead - renderYawOffset);
            target = Math.abs(headOffset) > 50.0F ? rotationYawHead : renderYawOffset;
            maxStep = 3.0F;
        }
        float step = MathHelper.wrapAngleTo180_float(target - renderYawOffset) * 0.25F;
        renderYawOffset += MathHelper.clamp_float(step, -maxStep, maxStep);

        float headOffset = MathHelper.wrapAngleTo180_float(rotationYawHead - renderYawOffset);
        if (headOffset > MAX_HEAD_TURN) rotationYawHead = renderYawOffset + MAX_HEAD_TURN;
        if (headOffset < -MAX_HEAD_TURN) rotationYawHead = renderYawOffset - MAX_HEAD_TURN;
    }

    private void updateLimbSwing() {
        double dx = posX - prevPosX;
        double dz = posZ - prevPosZ;
        float speed = Math.min(MathHelper.sqrt_double(dx * dx + dz * dz) * 4.0F, 1.0F);
        limbSwingAmount += (speed - limbSwingAmount) * 0.25F;
        limbSwing += limbSwingAmount;
    }

    // Keep vanilla code paths that check these consistent with the seal simulating itself.

    /** Slower head nods than vanilla's default 40 degrees per tick. */
    @Override
    public int getVerticalFaceSpeed() {
        return 15;
    }

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
