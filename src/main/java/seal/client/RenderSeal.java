package seal.client;

import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import seal.Seal;

public class RenderSeal extends RenderLiving {

    private static final ResourceLocation TEXTURE = new ResourceLocation(Seal.MODID, "textures/entity/seal.png");
    private static final float SCALE = 0.8F;

    public RenderSeal() {
        super(new ModelSeal(), 0.3F);
    }

    @Override
    protected void preRenderCallback(EntityLivingBase entity, float partialTicks) {
        GL11.glScalef(SCALE, SCALE, SCALE);
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return TEXTURE;
    }
}
