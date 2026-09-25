package draylar.goml.compat.webmap.player;

import draylar.goml.GetOffMyLawn;
import draylar.goml.compat.webmap.WebmapCompat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs web requests needed by web map markers (Mojang profiles and skin textures) on a separate thread,
 * so the server never waits on them. Markers are built with whatever is already cached,
 * and markers missing data are rebuilt once all queued lookups finish.
 */
public final class PlayerLookups {
    private static final long RETRY_AFTER_FAILURE_MS = 5 * 60 * 1000;

    // Single thread, so requests are spread out instead of hitting Mojang's rate limits at once
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "GOML Web Map Lookups");
        thread.setDaemon(true);
        return thread;
    });

    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final AtomicInteger PENDING = new AtomicInteger();

    private PlayerLookups() {}

    /**
     * Queues a lookup, unless the same one is already queued or failed recently.
     * Lookups store their results in caches, so they should do nothing if the result is already cached.
     *
     * @param key unique key of the lookup, used to skip duplicates
     * @return true if the lookup is queued, so its result will be available later
     */
    static boolean request(String key, Lookup lookup) {
        var failedAt = FAILED.get(key);
        if (failedAt != null) {
            if (System.currentTimeMillis() - failedAt < RETRY_AFTER_FAILURE_MS) {
                return false;
            }
            FAILED.remove(key);
        }

        if (!QUEUED.add(key)) {
            return true;
        }

        PENDING.incrementAndGet();
        EXECUTOR.execute(() -> {
            try {
                lookup.run();
            } catch (Exception exception) {
                FAILED.put(key, System.currentTimeMillis());
                GetOffMyLawn.LOGGER.warn("Web map lookup {} failed: {}", key, exception.toString());
            } finally {
                QUEUED.remove(key);
                if (PENDING.decrementAndGet() == 0) {
                    WebmapCompat.refreshPendingMarkers();
                }
            }
        });
        return true;
    }

    @FunctionalInterface
    interface Lookup {
        void run() throws Exception;
    }
}
