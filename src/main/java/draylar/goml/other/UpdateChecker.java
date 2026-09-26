package draylar.goml.other;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import draylar.goml.GetOffMyLawn;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/**
 * Checks this fork's GitHub releases for a newer build and tells the console and admins about it.
 * <p>
 * The "release" channel looks at normal releases (v1.2.3 tags), the "alpha" channel at the rolling "alpha" pre-release,
 * which the build workflow replaces with every build of the main branch.
 * <p>
 * With autoUpdate on, a dedicated server also downloads the new jar next to the current one (verified against the
 * checksum GitHub lists for it) and swaps the jars once the server has stopped, so the update is used from the next start.
 */
@ApiStatus.Internal
public final class UpdateChecker {
    private static final String API_URL = "https://api.github.com/repos/" + GetOffMyLawn.REPOSITORY;
    private static final String DOWNLOAD_URL_PREFIX = GetOffMyLawn.SOURCE_URL + "/releases/download/";
    private static final Identifier NOTIFY_PERMISSION = GetOffMyLawn.id("update_notify");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final long MAX_JAR_SIZE = 64L * 1024 * 1024;
    private static final String ALPHA_TAG = "alpha";
    // Suffixes Fabric ignores in the mods folder, it only loads *.jar
    private static final String DOWNLOAD_SUFFIX = ".download";
    private static final String PART_SUFFIX = ".part";
    private static final String BACKUP_SUFFIX = ".old";

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "GOML Update Checker");
        thread.setDaemon(true);
        return thread;
    });

    private static final @Nullable Build CURRENT = readCurrentBuild();
    // The jar GOML was loaded from, null when it isn't a plain jar (dev environment, nested in another mod)
    private static final @Nullable Path CURRENT_JAR = findCurrentJar();

    private static @Nullable HttpClient client;
    private static @Nullable Session session;
    private static @Nullable ScheduledFuture<?> task;
    private static volatile @Nullable Update available;
    // Downloaded and verified, installed when the server stops
    private static volatile @Nullable Pending pending;

    private UpdateChecker() {
        // NO-OP
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(UpdateChecker::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> installPending());
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
        var autoUpdate = config.checkForUpdates && config.autoUpdate;
        if (!autoUpdate) {
            // Turned off by a config reload after something was downloaded
            discardPending(null);
        }

        if (!config.checkForUpdates || CURRENT == null) {
            return;
        }

        if (autoUpdate && (CURRENT_JAR == null || FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER)) {
            GetOffMyLawn.LOGGER.warn("autoUpdate only works on dedicated servers with Get Off My Lawn's jar in the mods folder, only checking for updates");
            autoUpdate = false;
        }

        var channel = Channel.byName(config.updateChannel);
        session = new Session(server, channel, CURRENT, autoUpdate);
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
        private final boolean autoUpdate;
        private boolean firstCheck = true;
        private @Nullable Update lastAnnounced;
        private boolean lastAnnouncedDownloaded;

        private Session(MinecraftServer server, Channel channel, Build current, boolean autoUpdate) {
            this.server = server;
            this.channel = channel;
            this.current = current;
            this.autoUpdate = autoUpdate;
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
                // E.g. the release it was downloaded from got deleted
                discardPending(this);
                if (this.firstCheck) {
                    GetOffMyLawn.LOGGER.info("Get Off My Lawn {} is up to date ({} channel)", this.current.version.getFriendlyString(), this.channel.id);
                }
                this.firstCheck = false;
                return;
            }

            var downloaded = false;
            if (this.autoUpdate) {
                try {
                    downloaded = prepareInstall(this, update);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    GetOffMyLawn.LOGGER.warn("Couldn't download Get Off My Lawn {}: {}", update.version, e.toString());
                }
            }

            if (!update.equals(this.lastAnnounced) || downloaded != this.lastAnnouncedDownloaded) {
                if (downloaded) {
                    GetOffMyLawn.LOGGER.info("Downloaded Get Off My Lawn {} from the {} channel (running {}), it will be installed when the server stops",
                            update.version, this.channel.id, this.current.version.getFriendlyString());
                } else {
                    GetOffMyLawn.LOGGER.info("Get Off My Lawn {} is available on the {} channel (running {}). Download: {}",
                            update.version, this.channel.id, this.current.version.getFriendlyString(), update.downloadUrl);
                }

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
                this.lastAnnouncedDownloaded = downloaded;
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
                ? new Update(best.version.getFriendlyString(), channel, best.downloadUrl, best.fileName, best.size, best.sha256)
                : null;
    }

    private static @Nullable JsonElement get(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(API_URL + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent())
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

    private static String userAgent() {
        return "GetOffMyLawn-UpdateChecker/" + (CURRENT != null ? CURRENT.version.getFriendlyString() : "unknown");
    }

    // Auto update

    /**
     * Downloads the update next to the current jar, unless that's already done.
     *
     * @return true if the update will be installed when the server stops
     */
    private static boolean prepareInstall(Session from, Update update) throws IOException, InterruptedException {
        var existing = pending;
        if (existing != null && existing.update.equals(update) && Files.isRegularFile(existing.file)) {
            return true;
        }

        var file = download(update, CURRENT_JAR.getParent());

        synchronized (UpdateChecker.class) {
            if (session != from) {
                Files.deleteIfExists(file);
                return false;
            }

            var previous = pending;
            pending = new Pending(update, file);
            if (previous != null && !previous.file.equals(file)) {
                Files.deleteIfExists(previous.file);
            }
        }
        return true;
    }

    private static Path download(Update update, Path directory) throws IOException, InterruptedException {
        if (update.sha256 == null) {
            throw new IOException("the release doesn't list a checksum for " + update.fileName);
        }
        if (update.size <= 0 || update.size > MAX_JAR_SIZE) {
            throw new IOException("unexpected size of " + update.fileName + ": " + update.size + " bytes");
        }

        var file = directory.resolve(update.fileName + DOWNLOAD_SUFFIX);
        if (Files.isRegularFile(file)) {
            try {
                // Left over from a run that didn't stop cleanly
                verify(file, update);
                return file;
            } catch (Exception e) {
                Files.delete(file);
            }
        }

        deleteLeftoverDownloads(directory);

        var part = directory.resolve(update.fileName + PART_SUFFIX);
        var request = HttpRequest.newBuilder(URI.create(update.downloadUrl))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", userAgent())
                .GET()
                .build();
        try {
            var response = client().send(request, HttpResponse.BodyHandlers.ofFile(part,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + request.uri());
            }

            verify(part, update);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        return file;
    }

    /**
     * Makes sure the file is exactly the jar GitHub lists for the release, and that it's this mod in the expected version.
     */
    private static void verify(Path file, Update update) throws IOException {
        var size = Files.size(file);
        if (size != update.size) {
            throw new IOException("downloaded " + size + " bytes instead of " + update.size);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (var in = Files.newInputStream(file); var out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            in.transferTo(out);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(update.sha256)) {
            throw new IOException("checksum of " + update.fileName + " doesn't match the release");
        }

        var json = readFabricModJson(file);
        if (json == null) {
            throw new IOException(update.fileName + " isn't a Fabric mod");
        }

        var id = getString(json, "id", "");
        var version = getString(json, "version", "");
        if (!GetOffMyLawn.MOD_ID.equals(id) || !update.version.equals(version)) {
            throw new IOException(update.fileName + " contains " + id + " " + version + " instead of " + GetOffMyLawn.MOD_ID + " " + update.version);
        }
    }

    private static @Nullable JsonObject readFabricModJson(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return null;
            }

            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader);
                return json.isJsonObject() ? json.getAsJsonObject() : null;
            }
        }
    }

    private static boolean isGomlJar(Path file) {
        try {
            var json = readFabricModJson(file);
            return json != null && GetOffMyLawn.MOD_ID.equals(getString(json, "id", ""));
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteLeftoverDownloads(Path directory) throws IOException {
        var keep = pending;
        try (var files = Files.newDirectoryStream(directory, "goml-*.jar{" + DOWNLOAD_SUFFIX + "," + PART_SUFFIX + "}")) {
            for (var file : files) {
                if (keep == null || !keep.file.equals(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static synchronized void discardPending(@Nullable Session from) {
        if (from != null && session != from) {
            return;
        }

        var previous = pending;
        pending = null;
        if (previous != null) {
            try {
                Files.deleteIfExists(previous.file);
            } catch (IOException e) {
                // Harmless, Fabric doesn't load it
            }
        }
    }

    /**
     * Replaces the current jar with the downloaded one, keeping the current one as a backup.
     * Runs after the server stopped, so nothing loads classes from the jar anymore.
     */
    private static void installPending() {
        Pending install;
        synchronized (UpdateChecker.class) {
            install = pending;
            pending = null;
        }
        if (install == null || CURRENT_JAR == null || !Files.isRegularFile(install.file)) {
            return;
        }

        var backup = CURRENT_JAR.resolveSibling(CURRENT_JAR.getFileName() + BACKUP_SUFFIX);
        var target = CURRENT_JAR.resolveSibling(install.update.fileName);
        try {
            Files.move(CURRENT_JAR, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(install.file, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(backup, CURRENT_JAR, StandardCopyOption.REPLACE_EXISTING);
                throw e;
            }

            deleteOlderBackups(backup);

            GetOffMyLawn.LOGGER.info("Installed Get Off My Lawn {} as {}, it will be used from the next start. The previous jar was kept as {}",
                    install.update.version, target.getFileName(), backup.getFileName());
        } catch (IOException e) {
            GetOffMyLawn.LOGGER.warn("Couldn't install Get Off My Lawn {}: {}. To update by hand, replace {} with {} (without the {} ending)",
                    install.update.version, e.toString(), CURRENT_JAR, install.file, DOWNLOAD_SUFFIX);
        }
    }

    /**
     * Keeps only one older version: removes other GOML backups, found by their content so renamed jars count too.
     */
    private static void deleteOlderBackups(Path keep) {
        try (var files = Files.newDirectoryStream(keep.getParent(), "*.jar" + BACKUP_SUFFIX)) {
            for (var file : files) {
                if (!file.equals(keep) && isGomlJar(file)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            GetOffMyLawn.LOGGER.warn("Couldn't remove older Get Off My Lawn backups: {}", e.toString());
        }
    }

    // Messages

    private static boolean canBeNotified(ServerPlayer player) {
        return FabricPermissionBridge.checkPermission(player, NOTIFY_PERMISSION, PermissionLevel.ADMINS);
    }

    private static Component message(Update update) {
        var currentVersion = CURRENT != null ? CURRENT.version.getFriendlyString() : "?";
        var version = Component.literal(update.version).withStyle(ChatFormatting.GREEN);

        var install = pending;
        if (install != null && install.update.equals(update)) {
            return GetOffMyLawn.CONFIG.prefix(Component.translatable("text.goml.update.downloaded", version, update.channel.id, currentVersion));
        }

        MutableComponent download = Component.translatable("text.goml.update.download").setStyle(Style.EMPTY
                .withColor(ChatFormatting.BLUE)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(update.downloadUrl)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(update.downloadUrl))));

        return GetOffMyLawn.CONFIG.prefix(Component.translatable("text.goml.update.available", version, update.channel.id, currentVersion))
                .append(" ")
                .append(download);
    }

    // Current build

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

    private static @Nullable Path findCurrentJar() {
        try {
            var mod = FabricLoader.getInstance().getModContainer(GetOffMyLawn.MOD_ID).orElse(null);
            if (mod == null || mod.getOrigin().getKind() != ModOrigin.Kind.PATH || mod.getOrigin().getPaths().size() != 1) {
                return null;
            }

            var path = mod.getOrigin().getPaths().get(0).toAbsolutePath();
            return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Helpers

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

    /**
     * @param sha256 checksum GitHub lists for the jar, null if it doesn't
     */
    private record Update(String version, Channel channel, String downloadUrl, String fileName, long size, @Nullable String sha256) {
    }

    private record Pending(Update update, Path file) {
    }

    /**
     * A release's mod jar.
     */
    private record Candidate(SemanticVersion version, String downloadUrl, String fileName, long size, @Nullable String sha256,
                             String commit, @Nullable Instant published) {
        private static @Nullable Candidate of(JsonObject release, Build current) {
            var assets = release.get("assets");
            if (assets == null || !assets.isJsonArray()) {
                return null;
            }

            for (var element : assets.getAsJsonArray()) {
                var asset = element.getAsJsonObject();
                var name = getString(asset, "name", "");
                var url = getString(asset, "browser_download_url", "");
                if (!name.matches("goml-[0-9A-Za-z.+_-]+\\.jar") || name.endsWith("-sources.jar") || name.endsWith("-dev.jar")
                        || !url.startsWith(DOWNLOAD_URL_PREFIX)) {
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

                var digest = getString(asset, "digest", "");
                var size = asset.get("size") != null && asset.get("size").isJsonPrimitive() ? asset.get("size").getAsLong() : -1;
                return new Candidate(version, url, name, size, digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null,
                        getString(release, "target_commitish", ""), parseInstant(getString(release, "published_at", "")));
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
                    // The alpha is always the latest main build, version numbers don't matter
                    return true;
                }
            }

            return compare(this.version, current.version) > 0;
        }
    }
}
