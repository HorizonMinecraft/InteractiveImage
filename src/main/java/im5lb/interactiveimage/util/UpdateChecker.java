package im5lb.interactiveimage.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import im5lb.interactiveimage.InteractiveImage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker {

    private final InteractiveImage plugin;
    private final String currentVersion;
    private final String githubApiUrl;
    private final String githubReleasesUrl;
    private String latestVersion;
    private String downloadUrl;
    private boolean updateAvailable = false;
    private boolean checkCompleted = false;
    private long lastNotificationTime = 0;
    private static final long NOTIFICATION_COOLDOWN = 86400000L;

    public UpdateChecker(InteractiveImage plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();

        this.githubApiUrl = "https://api.github.com/repos/iM5LB/InteractiveImage/releases/latest";
        this.githubReleasesUrl = "https://modrinth.com/plugin/interactiveimage";
    }

    public CompletableFuture<Boolean> checkForUpdates() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return checkGitHubReleases();
            } catch (Exception e) {
                if (plugin.getConfig().getBoolean("debug-mode", false)) {
                    plugin.getLogger().warning("Update check failed: " + e.getMessage());
                }
                return false;
            } finally {
                checkCompleted = true;
            }
        });
    }

    private boolean checkGitHubReleases() {
        try {
            URL url = new URL(githubApiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "InteractiveImage-UpdateChecker");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                return false;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            String jsonResponse = response.toString();

            String tagNameKey = "\"tag_name\":\"";
            int tagStart = jsonResponse.indexOf(tagNameKey);
            if (tagStart == -1) return false;

            tagStart += tagNameKey.length();
            int tagEnd = jsonResponse.indexOf("\"", tagStart);
            if (tagEnd == -1) return false;

            latestVersion = jsonResponse.substring(tagStart, tagEnd);

            String downloadKey = "\"browser_download_url\":\"";
            int downloadStart = jsonResponse.indexOf(downloadKey);
            if (downloadStart != -1) {
                downloadStart += downloadKey.length();
                int downloadEnd = jsonResponse.indexOf("\"", downloadStart);
                if (downloadEnd != -1) {
                    downloadUrl = jsonResponse.substring(downloadStart, downloadEnd);
                }
            }

            updateAvailable = isNewerVersion(latestVersion, currentVersion);
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private boolean isNewerVersion(String latest, String current) {
        try {
            latest = latest.startsWith("v") ? latest.substring(1) : latest;
            current = current.startsWith("v") ? current.substring(1) : current;

            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");

            int maxLength = Math.max(latestParts.length, currentParts.length);

            for (int i = 0; i < maxLength; i++) {
                int latestPart = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;
                int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;

                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }
            return false;

        } catch (Exception e) {
            return false;
        }
    }

    private int parseVersionPart(String part) {
        try {
            if (part.contains("-")) {
                String[] subParts = part.split("-");
                int mainVersion = Integer.parseInt(subParts[0]);
                String suffix = subParts[1].toLowerCase();

                if (suffix.contains("beta")) {
                    return mainVersion * 1000 - 100;
                } else if (suffix.contains("alpha")) {
                    return mainVersion * 1000 - 200;
                } else if (suffix.contains("rc")) {
                    return mainVersion * 1000 - 50;
                } else {
                    return mainVersion * 1000 - 150;
                }
            }
            return Integer.parseInt(part) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void notifyAdmins() {
        if (!updateAvailable || !checkCompleted) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastNotificationTime < NOTIFICATION_COOLDOWN) {
            return;
        }

        lastNotificationTime = currentTime;

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("interactiveimage.admin")) {
                        sendUpdateNotification(player);
                    }
                }
            }
        }.runTask(plugin);
    }

    public void sendUpdateNotification(Player player) {
        if (!updateAvailable) return;

        player.sendMessage(" ");
        player.sendMessage("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage("<#fdc43f><bold>📦 UPDATE AVAILABLE");
        player.sendMessage("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage("<gray>New Version: <green><bold>" + latestVersion + " <gray>(Current: " + currentVersion + ")");
        player.sendMessage(" ");

        String updateUrl = getProjectPageUrl();
        net.kyori.adventure.text.Component message = net.kyori.adventure.text.Component.text("")
            .append(net.kyori.adventure.text.Component.text("➡ ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
            .append(net.kyori.adventure.text.Component.text("Open Modrinth Page", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(updateUrl))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    net.kyori.adventure.text.Component.text("➡ " + updateUrl, net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                )));

        player.sendMessage(message);

        player.sendMessage("<yellow>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage(" ");
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getDownloadUrl() {
        return getProjectPageUrl();
    }

    public String getProjectPageUrl() {
        return (githubReleasesUrl != null && !githubReleasesUrl.isEmpty())
                ? githubReleasesUrl
                : "https://modrinth.com/plugin/interactiveimage";
    }

    public boolean isCheckCompleted() {
        return checkCompleted;
    }

    public boolean shouldNotify() {
        if (!updateAvailable || !checkCompleted) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        return (currentTime - lastNotificationTime) >= NOTIFICATION_COOLDOWN;
    }
}