package draylar.goml.block.augment;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * Keeps an augment's effect on a player without sending an effect update every tick.
 * <p>
 * The effect lasts 5 seconds and is refreshed once it's down to 4, so a player standing in the claim gets about one
 * update per second. The spare seconds keep it from running out during lag spikes, which mods like TT20 make up for by
 * ticking effects several times at once. Augments remove it when the player leaves, and only ever touch their own
 * effect (ambient, no particles, level I, short), never potions or beacons.
 */
public final class AugmentEffects {
    static final int DURATION = 100;
    static final int REFRESH_BELOW = 80;

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
