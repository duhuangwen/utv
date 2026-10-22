package com.bjunicom.iptv.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.bjunicom.iptv.config.IptvSettings;
import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.parser.PlaylistNormalizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 频道数据仓库
 * 负责管理：
 * 1. 直播源：http(s) 订阅（默认 iptv-org 中国频道，缓存于 filesDir）、内置列表（安徽联通 IPTV）或本地导入
 * 2. 动态扫描与 check_iptv_m3u.sh 生成的 modified_iptv.m3u 合并及持久化
 * 3. 按频道实际分组动态生成分类索引（央视、卫视优先，收藏置底）
 */
public class ChannelRepository {
    private static final String ASSET_M3U_LATEST = "ah-unicom-iptv.m3u";
    private static final String ASSET_M3U_ALL = "ah-unicom-iptv.m3u";
    private static final String CUSTOM_SCANNED_M3U = "modified_iptv.m3u";
    private static final String ASSET_IPTV_ORG_SNAPSHOT = "iptv-org-cn.m3u";
    private static final String SOURCE_CACHE_FILE = "source_cache.m3u";
    private static final String SOURCE_CACHE_META_FILE = "source_cache.url";
    private static final int MAX_PLAYLIST_BYTES = 8 * 1024 * 1024;

    public static final String GROUP_ALL = "全部频道";
    public static final String GROUP_FAVORITES = "收藏频道";
    public static final String GROUP_4K = "4K";
    public static final String GROUP_HD_CCTV = "[高清]央视";
    public static final String GROUP_HD_SATELLITE = "[高清]卫视";
    public static final String GROUP_EDUCATION = "教育";
    public static final String GROUP_KIDS = "少儿";
    public static final String GROUP_SD_ALL = "标清频道";
    public static final String GROUP_DISCOVERED = "动态扫描发现";
    private static final String[] PREFERRED_GROUP_ORDER = {
            GROUP_HD_CCTV, PlaylistNormalizer.GROUP_CCTV, GROUP_HD_SATELLITE, PlaylistNormalizer.GROUP_SATELLITE,
            GROUP_4K, GROUP_KIDS, GROUP_EDUCATION
    };

    private static volatile ChannelRepository instance;

    private final Context appContext;
    private final IptvSettings settings;
    private final List<IptvChannel> allChannels = new ArrayList<>();
    private final Map<String, IptvChannel> knownMulticastMap = new LinkedHashMap<>();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ChannelRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.settings = new IptvSettings(appContext);
        reloadChannels();
    }

    public static ChannelRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (ChannelRepository.class) {
                if (instance == null) {
                    instance = new ChannelRepository(context);
                }
            }
        }
        return instance;
    }

    public synchronized void reloadChannels() {
        allChannels.clear();
        knownMulticastMap.clear();

        String assetName = settings.isIncludeAllRepoChannels() ? ASSET_M3U_ALL : ASSET_M3U_LATEST;
        List<IptvChannel> baseList = loadSourceChannels();
        Set<String> favorites = settings.getFavoriteUrls();

        int idx = 1;
        for (IptvChannel ch : baseList) {
            ch.setIndex(idx++);
            ch.setFavorite(favorites.contains(ch.getRawUrl()));
            allChannels.add(ch);
            if (!TextUtils.isEmpty(ch.getMulticastKey())) {
                knownMulticastMap.put(ch.getMulticastKey(), ch);
            }
        }

        // 同时加载全量字典以便扫描器识别历史频道名称
        if (!ASSET_M3U_ALL.equals(assetName)) {
            for (IptvChannel ch : loadFromAssets(ASSET_M3U_ALL)) {
                if (!TextUtils.isEmpty(ch.getMulticastKey()) && !knownMulticastMap.containsKey(ch.getMulticastKey())) {
                    knownMulticastMap.put(ch.getMulticastKey(), ch);
                }
            }
        }

        // 如果存在动态扫描保存的 modified_iptv.m3u，合并检测状态与新发现的组播地址
        File scannedFile = new File(getScanRootDir(), CUSTOM_SCANNED_M3U);
        if (scannedFile.exists()) {
            mergeFromModifiedM3u(scannedFile);
        }
    }

    private List<IptvChannel> loadSourceChannels() {
        String source = settings.getSourceUrl();
        List<IptvChannel> list = new ArrayList<>();
        try {
            if (source.startsWith(IptvSettings.SOURCE_ASSET_PREFIX)) {
                list = loadFromAssets(source.substring(IptvSettings.SOURCE_ASSET_PREFIX.length()));
            } else if (source.equals(readCachedSourceUrl())) {
                try (InputStream is = new FileInputStream(getSourceCacheFile())) {
                    list = M3uParser.parse(is);
                }
            }
        } catch (Throwable ignored) {
        }
        // 订阅从未成功拉取（首次启动/离线）时用随包快照，保证开机即有频道
        if (list.isEmpty() && IptvSettings.SOURCE_IPTV_ORG_CN.equals(source)) {
            list = loadFromAssets(ASSET_IPTV_ORG_SNAPSHOT);
        }
        PlaylistNormalizer.normalize(list);
        return list;
    }

    public interface SourceRefreshCallback {
        void onRefreshFinished(boolean success, int channelCount, String message);
    }

    /**
     * 后台拉取 http(s) 订阅源，成功后写入缓存并重载频道；回调在主线程执行。内置/本地导入源直接重载。
     */
    public void refreshSourceAsync(final SourceRefreshCallback callback) {
        final String source = settings.getSourceUrl();
        refreshExecutor.execute(() -> {
            boolean success;
            String message;
            int count = 0;
            if (source.startsWith("http://") || source.startsWith("https://")) {
                try {
                    byte[] body = downloadPlaylist(source);
                    List<IptvChannel> parsed = M3uParser.parse(new ByteArrayInputStream(body));
                    if (parsed.isEmpty()) {
                        throw new IOException("未解析到任何频道");
                    }
                    saveSourceCache(source, body);
                    success = true;
                    count = parsed.size();
                    message = "直播源已更新，共 " + count + " 个频道";
                } catch (Throwable t) {
                    success = false;
                    message = "直播源更新失败: " + t.getMessage();
                }
            } else {
                success = true;
                message = null;
            }
            if (success) {
                reloadChannels();
                count = getAllChannels().size();
            }
            final boolean ok = success;
            final int total = count;
            final String msg = message;
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onRefreshFinished(ok, total, msg);
                }
            });
        });
    }

    public synchronized void switchSource(String sourceUrl) {
        if (TextUtils.isEmpty(sourceUrl) || sourceUrl.trim().equals(settings.getSourceUrl())) {
            return;
        }
        settings.setSourceUrl(sourceUrl);
        deleteScanResults();
    }

    private void deleteScanResults() {
        File scanned = new File(getScanRootDir(), CUSTOM_SCANNED_M3U);
        if (scanned.exists()) {
            scanned.delete();
        }
    }

    /**
     * 导入本地 M3U 文件并切换为本地直播源
     */
    public synchronized int importPlaylist(InputStream inputStream) throws IOException {
        byte[] body = readFully(inputStream, MAX_PLAYLIST_BYTES);
        List<IptvChannel> parsed = M3uParser.parse(new ByteArrayInputStream(body));
        if (parsed.isEmpty()) {
            throw new IOException("文件中未找到频道");
        }
        switchSource(IptvSettings.SOURCE_LOCAL_FILE);
        saveSourceCache(IptvSettings.SOURCE_LOCAL_FILE, body);
        reloadChannels();
        return allChannels.size();
    }

    private byte[] downloadPlaylist(String source) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "uTV-Playlist/1.2");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream is = conn.getInputStream()) {
                return readFully(is, MAX_PLAYLIST_BYTES);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readFully(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) {
                throw new IOException("直播源文件过大");
            }
        }
        return out.toByteArray();
    }

    private synchronized void saveSourceCache(String source, byte[] body) throws IOException {
        byte[] previous = null;
        File cacheFile = getSourceCacheFile();
        if (source.equals(readCachedSourceUrl()) && cacheFile.exists()) {
            try (InputStream is = new FileInputStream(cacheFile)) {
                previous = readFully(is, MAX_PLAYLIST_BYTES);
            } catch (IOException ignored) {
            }
        }
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(body);
        }
        try (FileOutputStream fos = new FileOutputStream(getSourceMetaFile())) {
            fos.write(source.getBytes(StandardCharsets.UTF_8));
        }
        // 频道表变化后旧巡检结果的 URL 已失效，若保留会被 mergeScanResults 当作「新发现」频道并回灌
        if (!Arrays.equals(previous, body)) {
            deleteScanResults();
        }
    }

    private String readCachedSourceUrl() {
        File meta = getSourceMetaFile();
        if (!meta.exists()) {
            return null;
        }
        try (InputStream is = new FileInputStream(meta)) {
            return new String(readFully(is, 4096), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private File getSourceCacheFile() {
        return new File(appContext.getFilesDir(), SOURCE_CACHE_FILE);
    }

    private File getSourceMetaFile() {
        return new File(appContext.getFilesDir(), SOURCE_CACHE_META_FILE);
    }

    private List<IptvChannel> loadFromAssets(String fileName) {
        try (InputStream is = appContext.getAssets().open(fileName)) {
            return M3uParser.parse(is);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * 合并 check_iptv_m3u.sh 或动态扫描输出的 modified_iptv.m3u
     */
    public synchronized void mergeFromModifiedM3u(File m3uFile) {
        if (m3uFile == null || !m3uFile.exists()) {
            return;
        }
        try (InputStream is = new FileInputStream(m3uFile)) {
            List<IptvChannel> scannedChannels = M3uParser.parse(is);
            mergeScanResults(scannedChannels, false);
        } catch (IOException ignored) {
        }
    }

    /**
     * 合并扫描结果列表到当前频道仓库并持久化
     */
    public synchronized void mergeScanResults(List<IptvChannel> scannedList, boolean persistToDisk) {
        if (scannedList == null || scannedList.isEmpty()) {
            return;
        }
        Map<String, IptvChannel> currentByKey = new LinkedHashMap<>();
        for (IptvChannel ch : allChannels) {
            currentByKey.put(ch.getMulticastKey(), ch);
        }

        Set<String> favorites = settings.getFavoriteUrls();
        for (IptvChannel scanned : scannedList) {
            String key = scanned.getMulticastKey();
            IptvChannel existing = currentByKey.get(key);
            if (existing != null) {
                if (scanned.getConnected() != null) {
                    existing.setConnected(scanned.getConnected());
                } else {
                    existing.setConnected(true);
                }
                if (!TextUtils.isEmpty(scanned.getActualResolutionFlag())) {
                    existing.setActualResolutionFlag(scanned.getActualResolutionFlag());
                } else if (!TextUtils.isEmpty(scanned.getResolutionTag())) {
                    existing.setActualResolutionFlag(scanned.getResolutionTag());
                }
                if (!TextUtils.isEmpty(scanned.getVideoTrackInfo())) {
                    existing.setVideoTrackInfo(scanned.getVideoTrackInfo());
                }
                if (!TextUtils.isEmpty(scanned.getAudioTrackInfo())) {
                    existing.setAudioTrackInfo(scanned.getAudioTrackInfo());
                }
                if (scanned.getLatencyMs() > 0) {
                    existing.setLatencyMs(scanned.getLatencyMs());
                }
                existing.setResolutionMismatch(scanned.isResolutionMismatch());
            } else {
                // 新扫描发现的组播地址
                scanned.setIndex(allChannels.size() + 1);
                scanned.setDiscoveredByScan(true);
                if (scanned.getConnected() == null) {
                    scanned.setConnected(true);
                }
                scanned.setFavorite(favorites.contains(scanned.getRawUrl()));
                List<String> groups = new ArrayList<>(scanned.getGroups());
                if (!groups.contains(GROUP_DISCOVERED)) {
                    groups.add(GROUP_DISCOVERED);
                }
                scanned.setGroups(groups);
                allChannels.add(scanned);
                currentByKey.put(key, scanned);
                knownMulticastMap.put(key, scanned);
            }
        }

        if (persistToDisk) {
            saveModifiedM3u(allChannels);
        }
    }

    public synchronized void saveModifiedM3u(List<IptvChannel> channels) {
        try {
            File outFile = new File(getScanRootDir(), CUSTOM_SCANNED_M3U);
            String m3uContent = M3uParser.toM3uContent(channels, true, null);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(m3uContent.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    public File getScanRootDir() {
        File dir = appContext.getExternalFilesDir("iptv_scan");
        if (dir == null) {
            dir = new File(appContext.getFilesDir(), "iptv_scan");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public synchronized List<IptvChannel> getAllChannels() {
        return new ArrayList<>(allChannels);
    }

    public synchronized IptvChannel findKnownChannelByIpPort(String ip, int port) {
        return knownMulticastMap.get(ip + ":" + port);
    }

    public synchronized List<Integer> getKnownPortsForIp(String ip) {
        List<Integer> ports = new ArrayList<>();
        String prefix = ip + ":";
        for (Map.Entry<String, IptvChannel> entry : knownMulticastMap.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                int port = entry.getValue().getMulticastPort();
                if (port > 0 && !ports.contains(port)) {
                    ports.add(port);
                }
            }
        }
        return ports;
    }

    public synchronized List<String> getCategoryGroups() {
        Set<String> present = new LinkedHashSet<>();
        boolean anyDiscovered = false;
        for (IptvChannel ch : allChannels) {
            present.addAll(ch.getGroups());
            anyDiscovered |= ch.isDiscoveredByScan();
        }
        present.remove(GROUP_DISCOVERED);

        List<String> categories = new ArrayList<>();
        categories.add(GROUP_ALL);
        for (String preferred : PREFERRED_GROUP_ORDER) {
            if (present.remove(preferred)) {
                categories.add(preferred);
            }
        }
        boolean hasOther = present.remove(PlaylistNormalizer.GROUP_OTHER);
        categories.addAll(present);
        if (hasOther) {
            categories.add(PlaylistNormalizer.GROUP_OTHER);
        }
        if (anyDiscovered) {
            categories.add(GROUP_DISCOVERED);
        }
        categories.add(GROUP_FAVORITES);
        return categories;
    }

    public synchronized List<IptvChannel> filterChannels(String category, String searchQuery, boolean onlyAvailable) {
        List<IptvChannel> result = new ArrayList<>();
        String query = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";

        for (IptvChannel ch : allChannels) {
            if (onlyAvailable && Boolean.FALSE.equals(ch.getConnected())) {
                continue;
            }
            if (!matchesCategory(ch, category)) {
                continue;
            }
            if (!query.isEmpty()) {
                boolean matchName = ch.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
                        || (ch.getRawTitle() != null && ch.getRawTitle().toLowerCase(Locale.ROOT).contains(query));
                boolean matchNum = String.valueOf(ch.getChannelNumber()).equals(query)
                        || ch.getChannelNumberDisplay().equals(query);
                boolean matchIp = ch.getRawUrl() != null && ch.getRawUrl().toLowerCase(Locale.ROOT).contains(query);
                if (!matchName && !matchNum && !matchIp) {
                    continue;
                }
            }
            result.add(ch);
        }
        return result;
    }

    private boolean matchesCategory(IptvChannel ch, String category) {
        if (TextUtils.isEmpty(category) || GROUP_ALL.equals(category)) {
            return true;
        }
        if (GROUP_FAVORITES.equals(category)) {
            return ch.isFavorite();
        }
        if (GROUP_DISCOVERED.equals(category)) {
            return ch.isDiscoveredByScan() || Boolean.TRUE.equals(ch.getConnected());
        }
        if (GROUP_4K.equals(category)) {
            return "4K".equals(ch.getResolutionTag()) || ch.getGroups().contains("4K");
        }
        if (GROUP_SD_ALL.equals(category)) {
            if ("标清".equals(ch.getResolutionTag())) {
                return true;
            }
            for (String g : ch.getGroups()) {
                if (g.contains("标清") || (!g.contains("高清") && !g.contains("4K") && !g.contains("少儿"))) {
                    return true;
                }
            }
            return false;
        }
        for (String g : ch.getGroups()) {
            if (g.equalsIgnoreCase(category)) {
                return true;
            }
        }
        return false;
    }

    public synchronized IptvChannel findByChannelNumber(int number) {
        for (IptvChannel ch : allChannels) {
            if (ch.getChannelNumber() == number) {
                return ch;
            }
        }
        for (IptvChannel ch : allChannels) {
            if (ch.getIndex() == number) {
                return ch;
            }
        }
        return null;
    }

    public synchronized void toggleFavorite(IptvChannel channel) {
        if (channel == null) {
            return;
        }
        settings.toggleFavorite(channel.getRawUrl());
        boolean isFav = settings.getFavoriteUrls().contains(channel.getRawUrl());
        channel.setFavorite(isFav);
    }

    public synchronized List<Integer> getAllKnownPorts() {
        List<Integer> ports = new ArrayList<>();
        int[] defaultUnicomPorts = {
                8001, 4120, 8000, 4220, 2000, 1234,
                3001, 3002, 3003, 3004, 3005, 3006,
                8004, 8008, 8012, 8016, 8020, 8024, 8028, 8032, 8036, 8040, 8044, 8048,
                8052, 8056, 8060, 8064, 8072, 8076, 8084, 8092, 8104, 8108, 8112, 8116,
                8120, 8124, 8128, 8132, 8136, 8140, 8144, 8148, 8152, 8156, 8160, 8164,
                8172, 8176, 8180, 8184, 8188, 8192, 8196, 8200, 8212, 8216, 8228, 8232,
                8236, 8268, 8272, 8276, 8280, 8284, 8288, 8292, 8296, 8300, 8304,
                9000, 9012, 9020, 9024, 9136, 9244, 9252, 9268
        };
        for (int p : defaultUnicomPorts) {
            ports.add(p);
        }
        return Collections.unmodifiableList(ports);
    }
}
