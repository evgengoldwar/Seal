package seal.client;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

/**
 * A small chubby seal in a simple blocky style: a round body with a big head, a little snout, two front flippers and a
 * split tail. All parts hang off the body so the whole seal can bob and wiggle as it waddles. Texture is 64x32.
 */
public class ModelSeal extends ModelBase {

    private static final float DEG_TO_RAD = (float) Math.PI / 180.0F;

    private final ModelRenderer body;
    private final ModelRenderer head;
    private final ModelRenderer snout;
    private final ModelRenderer hips;
    private final ModelRenderer tailLeft;
    private final ModelRenderer tailRight;
    private final ModelRenderer flipperLeft;
    private final ModelRenderer flipperRight;

    // Pose of the seal currently being rendered, read from the entity in setLivingAnimations.
    private float sit, sleep, beg, shake;
    private boolean happy, scared;

    public ModelSeal() {
        textureWidth = 64;
        textureHeight = 32;

        body = new ModelRenderer(this, 0, 0);
        body.addBox(-3.5F, -5.0F, -4.0F, 7, 5, 8);
        body.setRotationPoint(0.0F, 24.0F, 0.0F);

        head = new ModelRenderer(this, 32, 0);
        head.addBox(-3.0F, -5.0F, -5.0F, 6, 5, 5);
        head.setRotationPoint(0.0F, -3.0F, -1.0F);
        body.addChild(head);

        snout = new ModelRenderer(this, 32, 10);
        snout.addBox(-1.5F, -2.0F, -1.0F, 3, 2, 1);
        snout.setRotationPoint(0.0F, 0.0F, -5.0F);
        head.addChild(snout);

        hips = new ModelRenderer(this, 0, 13);
        hips.addBox(-2.5F, -4.0F, 0.0F, 5, 4, 4);
        hips.setRotationPoint(0.0F, 0.0F, 4.0F);
        body.addChild(hips);

        tailLeft = new ModelRenderer(this, 18, 13);
        tailLeft.addBox(0.0F, 0.0F, 0.0F, 3, 1, 3);
        tailLeft.setRotationPoint(0.5F, -1.0F, 3.5F);
        hips.addChild(tailLeft);

        tailRight = new ModelRenderer(this, 18, 13);
        tailRight.mirror = true;
        tailRight.addBox(-3.0F, 0.0F, 0.0F, 3, 1, 3);
        tailRight.setRotationPoint(-0.5F, -1.0F, 3.5F);
        hips.addChild(tailRight);

        flipperLeft = new ModelRenderer(this, 40, 10);
        flipperLeft.addBox(0.0F, 0.0F, -1.0F, 4, 1, 2);
        flipperLeft.setRotationPoint(3.5F, -2.0F, -2.0F);
        body.addChild(flipperLeft);

        flipperRight = new ModelRenderer(this, 40, 10);
        flipperRight.mirror = true;
        flipperRight.addBox(-4.0F, 0.0F, -1.0F, 4, 1, 2);
        flipperRight.setRotationPoint(-3.5F, -2.0F, -2.0F);
        body.addChild(flipperRight);
    }

    @Override
    public void render(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
        float headPitch, float scale) {
        setRotationAngles(limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch, scale, entity);
        body.render(scale);
    }

    @Override
    public void setLivingAnimations(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
        float partialTicks) {
        EntitySeal seal = (EntitySeal) entity;
        sit = seal.getSitAmount(partialTicks);
        sleep = seal.getSleepAmount(partialTicks);
        beg = seal.getBegAmount(partialTicks);
        shake = seal.getShakeProgress(partialTicks);
        happy = seal.isHappy();
        scared = seal.isScared();
    }

    @Override
    public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
        float headPitch, float scale, Entity entity) {
        float awake = 1.0F - sleep;
        float move = limbSwingAmount * awake;
        float phase = limbSwing * 0.9F;
        float hump = MathHelper.sin(phase) * move;
        float idle = MathHelper.sin(ageInTicks * 0.08F);

        // Galumphing: the body rocks back and forth and hops a little, the hips flex the other way.
        // Sitting lifts the front up like a seal on a rock.
        body.rotationPointY = 24.0F - Math.abs(MathHelper.cos(phase)) * move * 1.2F - sit * 1.0F;
        body.rotateAngleX = hump * 0.15F - sit * 0.35F;
        hips.rotateAngleX = -hump * 0.35F + sit * 0.3F;

        // Shaking off water: a quick side-to-side wiggle that fades in and out. Scared seals shiver.
        body.rotateAngleZ = 0.0F;
        if (shake > 0.0F) {
            body.rotateAngleZ = MathHelper.sin(shake * (float) Math.PI * 6.0F) * 0.3F
                * MathHelper.sin(shake * (float) Math.PI);
        }
        if (scared) body.rotateAngleZ += MathHelper.sin(ageInTicks * 2.5F) * 0.03F;

        head.rotateAngleY = netHeadYaw * DEG_TO_RAD * awake;
        head.rotateAngleX = headPitch * DEG_TO_RAD * awake - body.rotateAngleX
            - sit * 0.15F
            + sleep * 0.2F
            + idle * 0.03F;
        head.rotateAngleZ = MathHelper.sin(ageInTicks * 0.05F) * 0.06F * awake;
        if (happy) head.rotateAngleZ += MathHelper.sin(ageInTicks * 0.4F) * 0.1F;

        // Flippers: paddle while moving, tuck against the sides when resting or scared, clap when begging.
        float paddle = MathHelper.cos(phase) * move * 0.7F + idle * 0.06F * awake;
        float tuck = Math.max(Math.max(sit, sleep), scared ? 0.6F : 0.0F);
        float flipperYaw = lerp(-0.45F + paddle, -1.2F, tuck);
        float flipperRoll = lerp(0.25F, 1.1F, tuck);

        float clap = (MathHelper.sin(ageInTicks * 1.2F) * 0.5F + 0.5F) * 0.5F;
        flipperYaw = lerp(flipperYaw, 0.9F + clap, beg);
        flipperRoll = lerp(flipperRoll, -0.4F, beg);

        flipperLeft.rotateAngleY = flipperYaw;
        flipperLeft.rotateAngleZ = flipperRoll;
        flipperRight.rotateAngleY = -flipperYaw;
        flipperRight.rotateAngleZ = -flipperRoll;

        // The tail wags all the time - fast when happy, barely while asleep, tucked in when scared.
        float wag;
        if (happy) {
            wag = MathHelper.sin(ageInTicks * 0.6F) * 0.3F;
        } else {
            wag = MathHelper.sin(ageInTicks * 0.15F) * 0.12F * (1.0F - sleep * 0.8F);
        }
        float spread = scared ? 0.15F : 0.35F;
        tailLeft.rotateAngleY = spread + wag;
        tailRight.rotateAngleY = -spread + wag;
        tailLeft.rotateAngleX = tailRight.rotateAngleX = -hump * 0.4F + 0.1F;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
