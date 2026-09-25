package draylar.goml.other;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import draylar.goml.GetOffMyLawn;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Checks this fork's GitHub releases for a newer build and tells the console and admins about it. Never downloads anything.
 * <p>
 * The "release" channel looks at normal releases (v1.2.3 tags), the "alpha" channel at the rolling "alpha" pre-release,
 * which the build workflow replaces with every pushed GitHub Actions build.
 */
@ApiStatus.Internal
public final class UpdateChecker {
    private static final String API_URL = "https://api.github.com/repos/" + GetOffMyLawn.REPOSITORY;
    private static final Identifier NOTIFY_PERMISSION = GetOffMyLawn.id("update_notify");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String ALPHA_TAG = "alpha";

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "GOML Update Checker");
        thread.setDaemon(true);
        return thread;
    });

    private static final @Nullable Build CURRENT = readCurrentBuild();

    private static @Nullable HttpClient client;
    private static @Nullable Session session;
    private static @Nullable ScheduledFuture<?> task;
    private static volatile @Nullable Update available;

    private UpdateChecker() {
        // NO-OP
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(UpdateChecker::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var update = available;
            if (update != null && canBeNotified(handler.player)) {
                handler.player.sendSystemMessage(message(update), false);
            }
        });
    }

    /**
     * (Re)starts the checks with the current config, first one right away.
     */
    public static synchronized void start(MinecraftServer server) {
        stop();

        var config = GetOffMyLawn.CONFIG;
        if (!config.checkForUpdates || CURRENT == null) {
            return;
        }

        var channel = Channel.byName(config.updateChannel);
        session = new Session(server, channel, CURRENT);
        task = config.updateCheckIntervalHours > 0
                ? EXECUTOR.scheduleWithFixedDelay(session, 0, config.updateCheckIntervalHours, TimeUnit.HOURS)
                : EXECUTOR.schedule(session, 0, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        session = null;
        available = null;
    }

    private static synchronized boolean publish(Session from, @Nullable Update update) {
        if (session != from) {
            // Stopped or restarted while this check was running
            return false;
        }

        available = update;
        return true;
    }

    private static final class Session implements Runnable {
        private final MinecraftServer server;
        private final Channel channel;
        private final Build current;
        private boolean firstCheck = true;
        private @Nullable Update lastAnnounced;

        private Session(MinecraftServer server, Channel channel, Build current) {
            this.server = server;
            this.channel = channel;
            this.current = current;
        }

        @Override
        public void run() {
            Update update;
            try {
                update = findUpdate(this.channel, this.current);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // No stack trace, a failed check isn't a problem with the mod
                GetOffMyLawn.LOGGER.warn("Couldn't check for Get Off My Lawn updates ({} channel): {}", this.channel.id, e.toString());
                return;
            }

            if (!publish(this, update)) {
                return;
            }

            if (update == null) {
                if (this.firstCheck) {
                    GetOffMyLawn.LOGGER.info("Get Off My Lawn {} is up to date ({} channel)", this.current.version.getFriendlyString(), this.channel.id);
                }
            } else if (!update.equals(this.lastAnnounced)) {
                GetOffMyLawn.LOGGER.info("Get Off My Lawn {} is available on the {} channel (running {}). Download: {}",
                        update.version, this.channel.id, this.current.version.getFriendlyString(), update.downloadUrl);

                // Admins joining later are told on join
                this.server.execute(() -> {
                    if (available != update) {
                        return;
                    }
                    for (var player : this.server.getPlayerList().getPlayers()) {
                        if (canBeNotified(player)) {
                            player.sendSystemMessage(message(update), false);
                        }
                    }
                });
                this.lastAnnounced = update;
            }

            this.firstCheck = false;
        }
    }

    private static @Nullable Update findUpdate(Channel channel, Build current) throws IOException, InterruptedException {
        Candidate best = null;

        if (channel == Channel.ALPHA) {
            var alpha = get("/releases/tags/" + ALPHA_TAG);
            if (alpha != null && alpha.isJsonObject()) {
                best = Candidate.of(alpha.getAsJsonObject(), current);
            }
        } else {
            var releases = get("/releases?per_page=30");
            if (releases != null && releases.isJsonArray()) {
                for (var element : releases.getAsJsonArray()) {
                    var release = element.getAsJsonObject();
                    if (getBoolean(release, "draft") || getBoolean(release, "prerelease") || !getString(release, "tag_name", "").startsWith("v")) {
                        continue;
                    }

                    var candidate = Candidate.of(release, current);
                    if (candidate != null && (best == null || compare(candidate.version, best.version) > 0)) {
                        best = candidate;
                    }
                }
            }
        }

        return best != null && best.isNewerThan(current, channel)
                ? new Update(best.version.getFriendlyString(), channel, best.downloadUrl)
                : null;
    }

    private static @Nullable JsonElement get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(API_URL + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "GetOffMyLawn-UpdateChecker/" + (CURRENT != null ? CURRENT.version.getFriendlyString() : "unknown"))
                .GET()
                .build();

        var response = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + request.uri());
        }

        return JsonParser.parseString(response.body());
    }

    private static synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return client;
    }

    private static boolean canBeNotified(ServerPlayer player) {
        return FabricPermissionBridge.checkPermission(player, NOTIFY_PERMISSION, PermissionLevel.ADMINS);
    }

    private static Component message(Update update) {
        var download = Component.translatable("text.goml.update.download").setStyle(Style.EMPTY
                .withColor(ChatFormatting.BLUE)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(update.downloadUrl)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(update.downloadUrl))));

        return GetOffMyLawn.CONFIG.prefix(Component.translatable("text.goml.update.available",
                        Component.literal(update.version).withStyle(ChatFormatting.GREEN),
                        update.channel.id,
                        CURRENT != null ? CURRENT.version.getFriendlyString() : "?"))
                .append(" ")
                .append(download);
    }

    private static @Nullable Build readCurrentBuild() {
        var mod = FabricLoader.getInstance().getModContainer(GetOffMyLawn.MOD_ID).orElse(null);
        if (mod == null) {
            return null;
        }

        var metadata = mod.getMetadata();
        SemanticVersion version;
        if (metadata.getVersion() instanceof SemanticVersion semanticVersion) {
            version = semanticVersion;
        } else {
            try {
                version = SemanticVersion.parse(metadata.getVersion().getFriendlyString());
            } catch (VersionParsingException e) {
                GetOffMyLawn.LOGGER.warn("Update checks disabled, can't read mod version {}", metadata.getVersion().getFriendlyString());
                return null;
            }
        }

        // Filled in by the GitHub Actions build, empty in local builds
        String commit = null;
        Instant time = null;
        var build = metadata.getCustomValue("goml:build");
        if (build != null && build.getType() == CustomValue.CvType.OBJECT) {
            commit = getString(build.getAsObject().get("commit"));
            time = parseInstant(getString(build.getAsObject().get("time")));
        }

        return new Build(version, version.getBuildKey().orElse(null), commit, time);
    }

    private static @Nullable String getString(@Nullable CustomValue value) {
        return value != null && value.getType() == CustomValue.CvType.STRING && !value.getAsString().isBlank() ? value.getAsString() : null;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static boolean getBoolean(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    // SemanticVersion#compareTo(SemanticVersion) is deprecated, Version#compareTo(Version) isn't
    private static int compare(Version a, Version b) {
        return a.compareTo(b);
    }

    private static @Nullable Instant parseInstant(@Nullable String value) {
        if (value == null) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public enum Channel {
        RELEASE("release"),
        ALPHA("alpha");

        public final String id;

        Channel(String id) {
            this.id = id;
        }

        public static Channel byName(@Nullable String name) {
            for (var channel : values()) {
                if (channel.id.equalsIgnoreCase(name)) {
                    return channel;
                }
            }

            GetOffMyLawn.LOGGER.warn("Unknown update channel '{}', using '{}'. Valid channels are 'release' and 'alpha'", name, RELEASE.id);
            return RELEASE;
        }
    }

    /**
     * @param minecraft the Minecraft version this jar is built for (the "+26.2" part of the version)
     * @param commit    git commit this jar was built from, null for local builds
     * @param time      when this jar was built, null for local builds
     */
    private record Build(SemanticVersion version, @Nullable String minecraft, @Nullable String commit, @Nullable Instant time) {
    }

    private record Update(String version, Channel channel, String downloadUrl) {
    }

    /**
     * A release's mod jar.
     */
    private record Candidate(SemanticVersion version, String downloadUrl, String commit, @Nullable Instant published) {
        private static @Nullable Candidate of(JsonObject release, Build current) {
            var assets = release.get("assets");
            if (assets == null || !assets.isJsonArray()) {
                return null;
            }

            for (var element : assets.getAsJsonArray()) {
                var asset = element.getAsJsonObject();
                var name = getString(asset, "name", "");
                var url = getString(asset, "browser_download_url", "");
                if (!name.startsWith("goml-") || !name.endsWith(".jar") || name.endsWith("-sources.jar") || name.endsWith("-dev.jar")
                        || !url.startsWith("https://")) {
                    continue;
                }

                SemanticVersion version;
                try {
                    version = SemanticVersion.parse(name.substring("goml-".length(), name.length() - ".jar".length()));
                } catch (VersionParsingException e) {
                    continue;
                }

                // Builds for other Minecraft versions won't run on this server
                if (current.minecraft != null && !current.minecraft.equals(version.getBuildKey().orElse(null))) {
                    return null;
                }

                return new Candidate(version, url, getString(release, "target_commitish", ""), parseInstant(getString(release, "published_at", "")));
            }

            return null;
        }

        private boolean isNewerThan(Build current, Channel channel) {
            if (current.commit != null && current.commit.toLowerCase(Locale.ROOT).equals(this.commit.toLowerCase(Locale.ROOT))) {
                // This exact build
                return false;
            }

            if (current.time != null && this.published != null) {
                if (!this.published.isAfter(current.time)) {
                    // Published before this jar was built, so it's older code even when its version number is higher
                    return false;
                }

                if (channel == Channel.ALPHA) {
                    // The alpha is always the latest Actions build, version numbers don't matter
                    return true;
                }
            }

            return compare(this.version, current.version) > 0;
        }
    }
}
