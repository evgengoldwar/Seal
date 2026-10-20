package seal;

import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import seal.client.EntitySeal;
import seal.client.RenderSeal;
import seal.client.SealController;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        RenderingRegistry.registerEntityRenderingHandler(EntitySeal.class, new RenderSeal());
        ClientRegistry.registerKeyBinding(SealController.KEY_TOGGLE);
        SealController controller = new SealController();
        FMLCommonHandler.instance()
            .bus()
            .register(controller);
        MinecraftForge.EVENT_BUS.register(controller);

        Seal.LOG.info("Seal " + Tags.VERSION + " is ready to waddle");
    }
}
