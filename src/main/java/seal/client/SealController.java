package seal.client;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import seal.Seal;

/**
 * Owns the seal. The seal is deliberately NOT added to the world's entity list: the world would not reliably tick a
 * client-made entity (and other mods may skip or cull it), so this class ticks it every client tick and renders it
 * itself. The entity still uses the world for collisions, gravity and pathfinding.
 */
public class SealController {

    public static final KeyBinding KEY_TOGGLE = new KeyBinding("key.seal.toggle", Keyboard.KEY_K, "key.categories.seal");

    private static final double RESCUE_DIST_SQ = 48.0D * 48.0D;
    private static final double RENDER_DIST_SQ = 96.0D * 96.0D;

    private final Random rand = new Random();
    private boolean active;
    private EntitySeal seal;
    private boolean loggedTickError;

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!KEY_TOGGLE.isPressed()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;

        active = !active;
        if (active) {
            spawn(mc.theWorld, mc.thePlayer, true);
        } else {
            despawn(true);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        EntityPlayer player = mc.thePlayer;
        if (world == null || player == null) {
            // Left the game; a new seal is created once we are back in a world.
            seal = null;
            return;
        }
        if (!active || mc.isGamePaused()) return;

        if (seal == null || seal.isDead || seal.worldObj != world) {
            spawn(world, player, false);
        } else if (seal.getDistanceSqToEntity(player) > RESCUE_DIST_SQ) {
            // Teleported, rode a minecart, flew away... come back.
            placeNear(seal, player);
        }

        tickSeal();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (seal == null || mc.theWorld == null || seal.worldObj != mc.theWorld || mc.renderViewEntity == null) return;
        if (seal.getDistanceSqToEntity(mc.renderViewEntity) > RENDER_DIST_SQ) return;

        float partialTicks = event.partialTicks;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        RenderHelper.enableStandardItemLighting();
        mc.entityRenderer.enableLightmap(partialTicks);

        RenderManager.instance.renderEntitySimple(seal, partialTicks);

        mc.entityRenderer.disableLightmap(partialTicks);
        RenderHelper.disableStandardItemLighting();
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** Does what World.updateEntities would do for a regular entity. */
    private void tickSeal() {
        seal.lastTickPosX = seal.posX;
        seal.lastTickPosY = seal.posY;
        seal.lastTickPosZ = seal.posZ;
        seal.prevRotationYaw = seal.rotationYaw;
        seal.prevRotationPitch = seal.rotationPitch;
        seal.ticksExisted++;

        try {
            seal.onUpdate();
        } catch (RuntimeException e) {
            if (!loggedTickError) {
                loggedTickError = true;
                Seal.LOG.error("Seal failed to tick, it will be dismissed", e);
            }
            active = false;
            seal = null;
        }
    }

    private void spawn(WorldClient world, EntityPlayer player, boolean effects) {
        EntitySeal newSeal = new EntitySeal(world);
        placeNear(newSeal, player);
        newSeal.rotationYaw = newSeal.prevRotationYaw = player.rotationYaw;
        newSeal.rotationYawHead = newSeal.prevRotationYawHead = player.rotationYaw;
        newSeal.renderYawOffset = newSeal.prevRenderYawOffset = player.rotationYaw;
        seal = newSeal;

        if (effects) {
            puff(world, newSeal, "heart", 4);
            puff(world, newSeal, "cloud", 10);
            world.playSound(newSeal.posX, newSeal.posY, newSeal.posZ, "random.pop", 0.6F, 1.6F, false);
        }
    }

    private void despawn(boolean effects) {
        if (seal == null) return;

        if (effects && seal.worldObj instanceof WorldClient) {
            puff((WorldClient) seal.worldObj, seal, "cloud", 12);
            seal.worldObj.playSound(seal.posX, seal.posY, seal.posZ, "random.pop", 0.6F, 1.0F, false);
        }
        seal.setDead();
        seal = null;
    }

    private static void placeNear(EntitySeal seal, EntityPlayer player) {
        if (!seal.moveNextTo(player)) {
            seal.setLocationAndAngles(player.posX, player.boundingBox.minY, player.posZ, seal.rotationYaw, 0.0F);
            seal.getNavigator()
                .clearPathEntity();
        }
        seal.motionX = seal.motionY = seal.motionZ = 0.0D;
        seal.fallDistance = 0.0F;
        // Snap interpolation so it doesn't visibly slide from the old spot.
        seal.prevPosX = seal.lastTickPosX = seal.posX;
        seal.prevPosY = seal.lastTickPosY = seal.posY;
        seal.prevPosZ = seal.lastTickPosZ = seal.posZ;
    }

    private void puff(WorldClient world, EntitySeal at, String particle, int count) {
        for (int i = 0; i < count; i++) {
            world.spawnParticle(
                particle,
                at.posX + (rand.nextDouble() - 0.5D) * at.width * 2.0D,
                at.posY + rand.nextDouble() * at.height + 0.2D,
                at.posZ + (rand.nextDouble() - 0.5D) * at.width * 2.0D,
                rand.nextGaussian() * 0.02D,
                0.05D,
                rand.nextGaussian() * 0.02D);
        }
    }
}
