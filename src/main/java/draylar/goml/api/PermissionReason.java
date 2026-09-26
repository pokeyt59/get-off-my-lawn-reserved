package draylar.goml.api;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public enum PermissionReason {
    BLOCK_PROTECTED("text.goml.block_protected"),
    ENTITY_PROTECTED("text.goml.entity_protected"),
    AREA_PROTECTED("text.goml.area_protected");

    private final String key;
    private final Component reason;

    PermissionReason(String key) {
        this.key = key;
        this.reason = Component.translatable(key);
    }

    public Component getReason() {
        return reason;
    }

    /**
     * @param owner name of the owner of the claim that denied it, null if unknown
     */
    public Component getReason(@Nullable String owner) {
        return owner != null ? Component.translatable(this.key + ".owner", owner) : this.reason;
    }
}
