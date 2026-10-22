package com.bjunicom.iptv.parser;

import com.bjunicom.iptv.model.IptvChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 iptv-org 等通用 M3U 订阅源规整为本应用的频道约定：中文分组、遥控器台号与清晰度标识。
 * 已带 "[台号]" 标题的列表（如内置安徽联通表）视为已规整，原样保留。
 */
public final class PlaylistNormalizer {
    public static final String GROUP_CCTV = "央视";
    public static final String GROUP_SATELLITE = "卫视";
    public static final String GROUP_OTHER = "其它";

    static final int FIRST_FREE_NUMBER = 21;
    private static final int CCTV_5_PLUS_NUMBER = 18;

    private static final Pattern BRACKET_NUMBER = Pattern.compile("\\[\\d{1,4}\\]");
    private static final Pattern RESOLUTION = Pattern.compile("\\((\\d{3,4})[pi]\\)");
    private static final Pattern TITLE_NOISE = Pattern.compile("\\s*(\\(\\d{3,4}[pi]\\)|\\[[^\\]]*\\])");
    private static final Pattern CCTV_NUMBER = Pattern.compile("^CCTV-(\\d{1,2})(\\+?)(\\s+HD)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROVINCE_MAIN_CHANNEL =
            Pattern.compile("^(.+?)\\s+(?:Satellite\\s+(?:TV|Channel)|TV)(\\s+HD)?$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> PROVINCES = new HashMap<>();
    private static final Map<String, String> SPECIAL_NAMES = new HashMap<>();
    private static final Map<String, String> GROUP_NAMES = new HashMap<>();

    static {
        String[][] provinces = {
                {"anhui", "安徽"}, {"beijing", "北京"}, {"tianjin", "天津"}, {"hebei", "河北"}, {"shanxi", "山西"},
                {"nei monggol", "内蒙古"}, {"inner mongolia", "内蒙古"}, {"liaoning", "辽宁"}, {"jilin", "吉林"},
                {"heilongjiang", "黑龙江"}, {"shanghai", "东方"}, {"dragon", "东方"}, {"jiangsu", "江苏"},
                {"zhejiang", "浙江"}, {"fujian", "东南"}, {"southeast", "东南"}, {"jiangxi", "江西"},
                {"shandong", "山东"}, {"henan", "河南"}, {"hubei", "湖北"}, {"hunan", "湖南"},
                {"guangdong", "广东"}, {"guangxi", "广西"}, {"hainan", "海南"}, {"chongqing", "重庆"},
                {"sichuan", "四川"}, {"guizhou", "贵州"}, {"yunnan", "云南"}, {"xizang", "西藏"},
                {"tibet", "西藏"}, {"shaanxi", "陕西"}, {"gansu", "甘肃"}, {"qinghai", "青海"},
                {"ningxia", "宁夏"}, {"xinjiang", "新疆"}, {"shenzhen", "深圳"}, {"xiamen", "厦门"},
                {"bingtuan", "兵团"}, {"yanbian", "延边"}, {"sansha", "三沙"}
        };
        for (String[] p : provinces) {
            PROVINCES.put(p[0], p[1]);
        }

        SPECIAL_NAMES.put("golden eagle cartoon", "金鹰卡通");
        SPECIAL_NAMES.put("brtv kaku childrens channel", "卡酷少儿");
        SPECIAL_NAMES.put("china weather channel", "中国气象频道");
        SPECIAL_NAMES.put("dragon tv international", "东方卫视国际频道");
        SPECIAL_NAMES.put("zhejiang international channel", "浙江国际频道");

        String[][] groups = {
                {"general", "综合"}, {"news", "新闻"}, {"movies", "电影"}, {"sports", "体育"},
                {"lifestyle", "生活"}, {"education", "教育"}, {"business", "财经"}, {"religious", "宗教"},
                {"music", "音乐"}, {"kids", "少儿"}, {"animation", "少儿"}, {"entertainment", "娱乐"},
                {"comedy", "娱乐"}, {"documentary", "纪录"}, {"shop", "购物"}, {"culture", "文化"},
                {"series", "电视剧"}, {"science", "科教"}, {"travel", "旅游"}, {"weather", "气象"},
                {"family", "家庭"}, {"classic", "经典"}, {"cooking", "美食"}, {"auto", "汽车"},
                {"outdoor", "户外"}, {"legislative", GROUP_OTHER}, {"undefined", GROUP_OTHER}
        };
        for (String[] g : groups) {
            GROUP_NAMES.put(g[0], g[1]);
        }
    }

    private PlaylistNormalizer() {
    }

    public static boolean isAlreadyNormalized(List<IptvChannel> channels) {
        for (IptvChannel ch : channels) {
            if (ch.getRawTitle() != null && BRACKET_NUMBER.matcher(ch.getRawTitle()).find()) {
                return true;
            }
        }
        return false;
    }

    public static void normalize(List<IptvChannel> channels) {
        if (channels == null || channels.isEmpty() || isAlreadyNormalized(channels)) {
            return;
        }
        Set<Integer> usedNumbers = new HashSet<>();
        List<IptvChannel> pendingNumber = new ArrayList<>();

        for (IptvChannel ch : channels) {
            String rawTitle = ch.getRawTitle() != null ? ch.getRawTitle() : "";
            String baseName = TITLE_NOISE.matcher(rawTitle).replaceAll("").trim();
            if (baseName.isEmpty()) {
                baseName = rawTitle;
            }

            String resolution = resolutionTagOf(rawTitle);
            if (resolution != null) {
                ch.setResolutionTag(resolution);
            }

            Set<String> groups = new LinkedHashSet<>();
            Matcher cctv = CCTV_NUMBER.matcher(baseName);
            if (cctv.matches()) {
                groups.add(GROUP_CCTV);
                int number = cctv.group(2).isEmpty() ? Integer.parseInt(cctv.group(1)) : CCTV_5_PLUS_NUMBER;
                if (usedNumbers.add(number)) {
                    ch.setChannelNumber(number);
                } else {
                    pendingNumber.add(ch);
                }
                ch.setDisplayName(baseName);
            } else {
                String localized = localizeName(baseName);
                String upper = baseName.toUpperCase(Locale.ROOT);
                if (upper.startsWith("CCTV") || upper.startsWith("CETV") || upper.startsWith("CGTN")) {
                    groups.add(GROUP_CCTV);
                } else if (localized.endsWith("卫视")) {
                    groups.add(GROUP_SATELLITE);
                } else {
                    groups.addAll(translateGroups(ch.getGroupTitleRaw()));
                }
                pendingNumber.add(ch);
                ch.setDisplayName(localized);
            }
            if ("4K".equals(ch.getResolutionTag())) {
                groups.add("4K");
            }
            ch.setGroups(new ArrayList<>(groups));
        }

        Collections.sort(pendingNumber, new Comparator<IptvChannel>() {
            @Override
            public int compare(IptvChannel a, IptvChannel b) {
                return Integer.compare(numberingPriority(a), numberingPriority(b));
            }
        });
        int next = FIRST_FREE_NUMBER;
        for (IptvChannel ch : pendingNumber) {
            while (usedNumbers.contains(next)) {
                next++;
            }
            ch.setChannelNumber(next);
            usedNumbers.add(next);
        }
        // 按台号排序使 CCTV-1 位于首位（无历史频道时开机默认播放列表第一个）
        Collections.sort(channels, new Comparator<IptvChannel>() {
            @Override
            public int compare(IptvChannel a, IptvChannel b) {
                return Integer.compare(a.getChannelNumber(), b.getChannelNumber());
            }
        });
    }

    private static int numberingPriority(IptvChannel ch) {
        if (ch.getGroups().contains(GROUP_CCTV)) {
            return 0;
        }
        return ch.getGroups().contains(GROUP_SATELLITE) ? 1 : 2;
    }

    static String resolutionTagOf(String rawTitle) {
        Matcher m = RESOLUTION.matcher(rawTitle);
        if (!m.find()) {
            return null;
        }
        int lines = Integer.parseInt(m.group(1));
        if (lines >= 2160) {
            return "4K";
        }
        return lines >= 720 ? "高清" : "标清";
    }

    static String localizeName(String baseName) {
        String key = baseName.toLowerCase(Locale.ROOT);
        String special = SPECIAL_NAMES.get(key);
        if (special != null) {
            return special;
        }
        Matcher m = PROVINCE_MAIN_CHANNEL.matcher(baseName);
        if (m.matches()) {
            String province = PROVINCES.get(m.group(1).toLowerCase(Locale.ROOT));
            if (province != null) {
                return province + "卫视" + (m.group(2) != null ? " HD" : "");
            }
        }
        return baseName;
    }

    private static List<String> translateGroups(String groupTitleRaw) {
        Set<String> result = new LinkedHashSet<>();
        if (groupTitleRaw != null) {
            for (String part : groupTitleRaw.split("[;,]")) {
                String g = part.trim();
                if (g.isEmpty()) {
                    continue;
                }
                String zh = GROUP_NAMES.get(g.toLowerCase(Locale.ROOT));
                result.add(zh != null ? zh : g);
            }
        }
        if (result.size() > 1) {
            result.remove(GROUP_OTHER);
        }
        if (result.isEmpty()) {
            result.add(GROUP_OTHER);
        }
        return new ArrayList<>(result);
    }
}
