package seal.client;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Handles the toggle key and keeps the seal alive: it is re-created after dimension changes or reconnecting, and
 * brought back if the player somehow ends up far away from it.
 */
public class SealController {

    public static final KeyBinding KEY_TOGGLE = new KeyBinding("key.seal.toggle", Keyboard.KEY_K, "key.categories.seal");

    /** Seals get negative ids so they can never clash with entities sent by the server. */
    private static int nextEntityId = -0x5EA1;

    private static final double RESCUE_DIST_SQ = 48.0D * 48.0D;

    private final Random rand = new Random();
    private boolean active;
    private EntitySeal seal;

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
            // The old world is gone together with the seal; a new one is spawned once we are back in game.
            seal = null;
            return;
        }
        if (!active || mc.isGamePaused()) return;

        if (seal == null || seal.isDead || seal.worldObj != world) {
            spawn(world, player, false);
        } else if (seal.getDistanceSqToEntity(player) > RESCUE_DIST_SQ) {
            // Teleported, rode a minecart, flew away... come back.
            despawn(false);
            spawn(world, player, false);
        }
    }

    private void spawn(WorldClient world, EntityPlayer player, boolean effects) {
        EntitySeal newSeal = new EntitySeal(world);
        newSeal.setEntityId(nextEntityId--);
        newSeal.setLocationAndAngles(player.posX, player.boundingBox.minY, player.posZ, player.rotationYaw, 0.0F);
        newSeal.moveNextTo(player);
        newSeal.rotationYawHead = newSeal.renderYawOffset = newSeal.rotationYaw;

        world.spawnEntityInWorld(newSeal);
        seal = newSeal;

        if (effects) {
            puff(world, newSeal, "heart", 4);
            puff(world, newSeal, "cloud", 10);
            world.playSound(newSeal.posX, newSeal.posY, newSeal.posZ, "random.pop", 0.6F, 1.6F, false);
        }
    }

    private void despawn(boolean effects) {
        if (seal == null) return;

        if (effects && !seal.isDead) {
            puff((WorldClient) seal.worldObj, seal, "cloud", 12);
            seal.worldObj.playSound(seal.posX, seal.posY, seal.posZ, "random.pop", 0.6F, 1.0F, false);
        }
        seal.worldObj.removeEntity(seal);
        seal = null;
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
