package seal.client;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
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
    public void setRotationAngles(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
        float headPitch, float scale, Entity entity) {
        float phase = limbSwing * 0.9F;
        float hump = MathHelper.sin(phase) * limbSwingAmount;
        float idle = MathHelper.sin(ageInTicks * 0.08F);

        // Galumphing: the body rocks back and forth and hops a little, the hips flex the other way.
        body.rotationPointY = 24.0F - Math.abs(MathHelper.cos(phase)) * limbSwingAmount * 1.2F;
        body.rotateAngleX = hump * 0.15F;
        hips.rotateAngleX = -hump * 0.35F;

        head.rotateAngleY = netHeadYaw * DEG_TO_RAD;
        head.rotateAngleX = headPitch * DEG_TO_RAD - body.rotateAngleX + idle * 0.03F;
        head.rotateAngleZ = MathHelper.sin(ageInTicks * 0.05F) * 0.06F;

        // Flippers push the seal forward while moving and gently flap when idle.
        float paddle = MathHelper.cos(phase) * limbSwingAmount * 0.7F + idle * 0.06F;
        flipperLeft.rotateAngleY = -0.45F + paddle;
        flipperLeft.rotateAngleZ = 0.25F;
        flipperRight.rotateAngleY = 0.45F - paddle;
        flipperRight.rotateAngleZ = -0.25F;

        // The tail wags happily all the time.
        float wag = MathHelper.sin(ageInTicks * 0.15F) * 0.12F;
        tailLeft.rotateAngleY = 0.35F + wag;
        tailRight.rotateAngleY = -0.35F + wag;
        tailLeft.rotateAngleX = tailRight.rotateAngleX = -hump * 0.4F + 0.1F;
    }
}
