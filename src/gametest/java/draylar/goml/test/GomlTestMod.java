package draylar.goml.test;

import net.fabricmc.api.ModInitializer;

/**
 * Only present on test servers: a test-only permission hook, and with -Dgoml.e2e=true the commands the end-to-end
 * tests (the Bedrock bot and the full server test) use to drive the server.
 */
public class GomlTestMod implements ModInitializer {
    @Override
    public void onInitialize() {
        TestPermissions.init();

        if (Boolean.getBoolean("goml.e2e")) {
            E2ECommands.init();
        }
    }
}
