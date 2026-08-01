package com.andre.speedtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppUpdateChecker {
    private static final String TAG = "AppUpdateChecker";
    private static final String PREFS = "app_update_checker";
    private static final String LAST_CHECK = "last_check_millis";
    private static final long ONE_WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final String RELEASES_API_URL = "https://api.github.com/repos/efremandrei/runForest/releases/latest";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private AppUpdateChecker() {
    }

    public static void checkDaily(Activity activity) {
        Context appContext = activity.getApplicationContext();
        AppContextHolder.context = appContext;
        if (!shouldCheck(appContext)) {
            return;
        }
        markChecked(appContext);
        EXECUTOR.execute(() -> {
            UpdateInfo update = findUpdate();
            if (update == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    showDialog(activity, update);
                }
            });
        });
    }

    public static void checkNow(Activity activity) {
        Context appContext = activity.getApplicationContext();
        AppContextHolder.context = appContext;
        markChecked(appContext);
        EXECUTOR.execute(() -> {
            UpdateInfo update = findUpdate();
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (update != null) {
                    showDialog(activity, update);
                } else {
                    Toast.makeText(activity, "No updates found", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
    public static void checkFromBoot(Context context) {
        Context appContext = context.getApplicationContext();
        AppContextHolder.context = appContext;
        if (!shouldCheck(appContext)) {
            return;
        }
        markChecked(appContext);
        EXECUTOR.execute(() -> {
            UpdateInfo update = findUpdate();
            if (update != null) {
                showNotification(appContext, update);
            }
        });
    }

    private static boolean shouldCheck(Context context) {
        long lastCheck = prefs(context).getLong(LAST_CHECK, 0L);
        return System.currentTimeMillis() - lastCheck >= ONE_WEEK_MILLIS;
    }

    private static void markChecked(Context context) {
        prefs(context).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
    }


    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static UpdateInfo findUpdate() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES_API_URL).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "AndroidAppUpdateChecker");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JSONObject release = new JSONObject(body.toString());
            String tag = release.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                return null;
            }
            String releaseUrl = release.optString("html_url", "");
            String downloadUrl = releaseUrl;
            String assetName = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int index = 0; index < assets.length(); index++) {
                    JSONObject asset = assets.optJSONObject(index);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "");
                    if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                        assetName = name;
                        downloadUrl = asset.optString("browser_download_url", releaseUrl);
                        break;
                    }
                }
            }
            if (downloadUrl.isEmpty() || !isNewer(tag, assetName)) {
                return null;
            }
            return new UpdateInfo(tag, assetName, downloadUrl);
        } catch (Exception error) {
            Log.w(TAG, "Update check failed", error);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isNewer(String tag, String assetName) {
        int latestBuild = Math.max(extractBuildNumber(tag), extractBuildNumber(assetName));
        if (latestBuild > currentVersionCode()) {
            return true;
        }
        String latestVersion = normalizeVersion(tag);
        String currentVersion = normalizeVersion(currentVersionName());
        return compareVersions(latestVersion, currentVersion) > 0;
    }

    private static int currentVersionCode() {
        try {
            PackageInfo info = appContext().getPackageManager().getPackageInfo(appContext().getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception error) {
            return 0;
        }
    }

    private static String currentVersionName() {
        try {
            PackageInfo info = appContext().getPackageManager().getPackageInfo(appContext().getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception error) {
            return "";
        }
    }

    private static Context appContext() {
        return AppContextHolder.context;
    }
    private static int extractBuildNumber(String text) {
        Matcher matcher = Pattern.compile("(?i)(?:build|versionCode|code)[-_ ]?(\\d+)").matcher(text == null ? "" : text);
        int value = -1;
        while (matcher.find()) {
            value = Math.max(value, parseInt(matcher.group(1), -1));
        }
        return value;
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "";
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[^0-9]+");
        String[] rightParts = right.split("[^0-9]+");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            int leftValue = index < leftParts.length ? parseInt(leftParts[index], 0) : 0;
            int rightValue = index < rightParts.length ? parseInt(rightParts[index], 0) : 0;
            if (leftValue != rightValue) {
                return leftValue < rightValue ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static void showDialog(Activity activity, UpdateInfo update) {
        new AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("A newer build is available in the origin repo: " + update.displayName() + ".")
            .setPositiveButton("Download", (dialog, which) -> openDownload(activity, update.downloadUrl))
            .setNegativeButton("Later", null)
            .show();
    }

    private static void showNotification(Context context, UpdateInfo update) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String channelId = "app_updates";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                channelId,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ));
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl));
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            7021,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        int icon = context.getApplicationInfo().icon != 0
            ? context.getApplicationInfo().icon
            : android.R.drawable.stat_sys_download_done;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(context, channelId)
            : new Notification.Builder(context);
        builder.setSmallIcon(icon)
            .setContentTitle("Update available")
            .setContentText("Tap to download " + update.displayName())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true);
        manager.notify(7021, builder.build());
    }

    private static void openDownload(Context context, String downloadUrl) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
        context.startActivity(intent);
    }

    private static final class AppContextHolder {
        static Context context;
    }
    private static final class UpdateInfo {
        final String tag;
        final String assetName;
        final String downloadUrl;

        UpdateInfo(String tag, String assetName, String downloadUrl) {
            this.tag = tag;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
        }

        String displayName() {
            return assetName == null || assetName.isEmpty() ? tag : assetName;
        }
    }
}