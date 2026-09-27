package draylar.goml.test;

import net.fabricmc.api.ModInitializer;

/**
 * Only present on test servers: a test-only permission hook, and with -Dgoml.e2e=true the commands the Bedrock bot
 * uses to drive the end-to-end test.
 */
public class GomlTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        TestPermissions.init();

        if (Boolean.getBoolean("goml.e2e")) {
            BedrockE2E.init();
        }
    }
}
