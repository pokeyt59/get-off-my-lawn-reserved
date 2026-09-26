package draylar.goml.block.augment;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * Keeps an augment's effect on a player without sending an effect update every tick.
 * <p>
 * The effect lasts a bit over a second and is only refreshed when it's about to run out, so a player standing in the
 * claim gets about one update per second. Augments remove it when the player leaves, and only ever touch their own
 * effect (ambient, no particles, level I, short), never potions or beacons.
 */
public final class AugmentEffects {
    static final int DURATION = 30;
    static final int REFRESH_BELOW = 10;

    private AugmentEffects() {
    }

    public static void keep(LivingEntity entity, Holder<MobEffect> effect, boolean showIcon) {
        var current = entity.getEffect(effect);
        // Other effects (potions, beacons) are left alone, ours takes over once they run out
        if (current == null || (isOurs(current) && current.getDuration() < REFRESH_BELOW)) {
            entity.addEffect(new MobEffectInstance(effect, DURATION, 0, true, false, showIcon));
        }
    }

    public static void remove(LivingEntity entity, Holder<MobEffect> effect) {
        var current = entity.getEffect(effect);
        if (current != null && isOurs(current)) {
            entity.removeEffect(effect);
        }
    }

    static boolean isOurs(MobEffectInstance instance) {
        return instance.isAmbient() && !instance.isVisible() && instance.getAmplifier() == 0
                && !instance.isInfiniteDuration() && instance.getDuration() <= DURATION;
    }
}
