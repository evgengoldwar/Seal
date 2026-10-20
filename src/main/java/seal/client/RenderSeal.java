package seal.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import seal.Seal;

public class RenderSeal extends RenderLiving {

    private static final ResourceLocation TEXTURE = new ResourceLocation(Seal.MODID, "textures/entity/seal.png");
    private static final ResourceLocation TEXTURE_EYES_CLOSED = new ResourceLocation(
        Seal.MODID,
        "textures/entity/seal_eyes_closed.png");
    private static final float SCALE = 0.8F;

    public RenderSeal() {
        super(new ModelSeal(), 0.3F);
    }

    @Override
    protected void preRenderCallback(EntityLivingBase entity, float partialTicks) {
        EntitySeal seal = (EntitySeal) entity;
        float sleep = seal.getSleepAmount(partialTicks);

        // Breathing: the seal gently swells and shrinks, slower and deeper while asleep.
        float time = seal.ticksExisted + partialTicks;
        float breath = MathHelper.sin(time * (0.1F - 0.04F * sleep)) * (0.025F + 0.025F * sleep);
        GL11.glScalef(SCALE * (1.0F + breath), SCALE * (1.0F + breath * 0.7F), SCALE * (1.0F + breath * 0.3F));
    }

    @Override
    protected void rotateCorpse(EntityLivingBase entity, float ageInTicks, float yaw, float partialTicks) {
        super.rotateCorpse(entity, ageInTicks, yaw, partialTicks);

        // Asleep: roll over onto its side, lifted so it rests on the ground instead of sinking into it.
        float sleep = ModelSeal.ease(((EntitySeal) entity).getSleepAmount(partialTicks));
        if (sleep > 0.0F) {
            GL11.glTranslatef(0.0F, 0.17F * sleep, 0.0F);
            GL11.glRotatef(80.0F * sleep, 0.0F, 0.0F, 1.0F);
        }
    }

    @Override
    public void doRender(EntityLiving entity, double x, double y, double z, float yaw, float partialTicks) {
        super.doRender(entity, x, y, z, yaw, partialTicks);

        EntitySeal seal = (EntitySeal) entity;
        if (seal.getSleepAmount(partialTicks) > 0.8F) {
            renderSnore(seal, x, y, z, partialTicks);
        }
    }

    /** Little "Z"s floating up from the sleeping seal, facing the camera. */
    private void renderSnore(EntitySeal seal, double x, double y, double z, float partialTicks) {
        FontRenderer font = getFontRendererFromRenderManager();
        float time = seal.ticksExisted + partialTicks;

        GL11.glPushMatrix();
        GL11.glTranslated(x, y + 0.4D, z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        for (int i = 0; i < 3; i++) {
            float progress = ((time + i * 20.0F) % 60.0F) / 60.0F;
            int alpha = (int) (MathHelper.sin(progress * (float) Math.PI) * 230.0F);
            if (alpha < 8) continue;

            String letter = i == 1 ? "z" : "Z";
            float size = 0.012F + progress * 0.012F;
            GL11.glPushMatrix();
            GL11.glTranslatef(0.15F + progress * 0.25F + MathHelper.sin(progress * 6.0F) * 0.05F, progress * 0.6F, 0.0F);
            GL11.glScalef(-size, -size, size);
            font.drawString(letter, -font.getStringWidth(letter) / 2, 0, alpha << 24 | 0xD8E8FF);
            GL11.glPopMatrix();
        }

        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return ((EntitySeal) entity).areEyesClosed() ? TEXTURE_EYES_CLOSED : TEXTURE;
    }
}
