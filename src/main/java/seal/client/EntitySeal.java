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

/**
 * A purely client-side pet. It lives only in the local {@link net.minecraft.client.multiplayer.WorldClient}: it is never
 * sent to the server, never saved and can't be hit or pushed, but it still walks, jumps, swims and pathfinds around
 * blocks with the regular vanilla AI.
 */
public class EntitySeal extends EntityCreature {

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

    // --- Client-side simulation: make the entity run its AI and physics locally like a server would ---

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
