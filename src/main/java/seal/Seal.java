package seal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Client-side companion mod: press a key to summon a little seal that follows you around. The seal only exists in
 * your own client world, so other players never see it and servers don't need the mod.
 */
@Mod(
    modid = Seal.MODID,
    version = Tags.VERSION,
    name = "Seal",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class Seal {

    public static final String MODID = "seal";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "seal.ClientProxy", serverSide = "seal.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
