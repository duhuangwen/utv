package com.bjunicom.iptv;

import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;
import com.bjunicom.iptv.parser.PlaylistNormalizer;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaylistNormalizerTest {

    @Test
    public void testNormalizeIptvOrgStyleEntries() {
        String sample = "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"CCTV1.cn@SD\" group-title=\"General\",CCTV-1 (720p)\n" +
                "https://example.com/cctv1.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"CCTV1.cn@HD\" group-title=\"General\",CCTV-1 HD (1080p)\n" +
                "https://example.com/cctv1hd.m3u8\n" +
                "#EXTINF:-1 group-title=\"Sports\",CCTV-5+\n" +
                "https://example.com/cctv5p.m3u8\n" +
                "#EXTINF:-1 group-title=\"General\",Beijing Satellite TV [Not 24/7]\n" +
                "https://example.com/btv.m3u8\n" +
                "#EXTINF:-1 group-title=\"General\",Hunan TV (2160p)\n" +
                "https://example.com/hunan.m3u8\n" +
                "#EXTINF:-1 group-title=\"News\",Guangzhou TV\n" +
                "https://example.com/gz.m3u8\n" +
                "#EXTINF:-1 group-title=\"Undefined\",Siping TV\n" +
                "https://example.com/siping.m3u8\n";

        List<IptvChannel> list = M3uParser.parseString(sample);
        PlaylistNormalizer.normalize(list);

        IptvChannel cctv1 = list.get(0);
        assertEquals(1, cctv1.getChannelNumber());
        assertEquals("CCTV-1", cctv1.getDisplayName());
        assertEquals("高清", cctv1.getResolutionTag());
        assertEquals(PlaylistNormalizer.GROUP_CCTV, cctv1.getGroups().get(0));

        assertTrue(find(list, "CCTV-1 HD").getChannelNumber() >= 21);
        assertEquals(18, find(list, "CCTV-5+").getChannelNumber());

        IptvChannel btv = find(list, "北京卫视");
        assertTrue(btv.getGroups().contains(PlaylistNormalizer.GROUP_SATELLITE));

        IptvChannel hunan = find(list, "湖南卫视");
        assertEquals("4K", hunan.getResolutionTag());
        assertTrue(hunan.getGroups().contains("4K"));

        assertEquals("新闻", find(list, "Guangzhou TV").getGroups().get(0));
        assertEquals(PlaylistNormalizer.GROUP_OTHER, find(list, "Siping TV").getGroups().get(0));

        Set<Integer> numbers = new HashSet<>();
        int previous = 0;
        for (IptvChannel ch : list) {
            assertTrue(numbers.add(ch.getChannelNumber()));
            assertTrue(ch.getChannelNumber() > previous);
            previous = ch.getChannelNumber();
        }
    }

    private static IptvChannel find(List<IptvChannel> list, String displayName) {
        for (IptvChannel ch : list) {
            if (displayName.equals(ch.getDisplayName())) {
                return ch;
            }
        }
        throw new AssertionError("channel not found: " + displayName);
    }

    @Test
    public void testAlreadyNormalizedPlaylistIsUntouched() throws Exception {
        List<IptvChannel> list;
        try (InputStream is = new FileInputStream("src/main/assets/ah-unicom-iptv.m3u")) {
            list = M3uParser.parse(is);
        }
        assertTrue(PlaylistNormalizer.isAlreadyNormalized(list));
        PlaylistNormalizer.normalize(list);
        assertEquals("CCTV-1综合", list.get(0).getDisplayName());
        assertTrue(list.get(0).getGroups().contains("[高清]央视"));
    }

    @Test
    public void testBundledIptvOrgSnapshotNormalizes() throws Exception {
        List<IptvChannel> list;
        try (InputStream is = new FileInputStream("src/main/assets/iptv-org-cn.m3u")) {
            list = M3uParser.parse(is);
        }
        assertTrue(list.size() > 50);
        assertFalse(PlaylistNormalizer.isAlreadyNormalized(list));
        PlaylistNormalizer.normalize(list);

        int cctvCount = 0;
        Set<Integer> numbers = new HashSet<>();
        for (IptvChannel ch : list) {
            assertFalse(ch.getDisplayName().contains("[Not 24/7]"));
            assertFalse(ch.getGroups().isEmpty());
            assertTrue(numbers.add(ch.getChannelNumber()));
            if (ch.getGroups().contains(PlaylistNormalizer.GROUP_CCTV)) {
                cctvCount++;
            }
        }
        assertTrue(cctvCount >= 17);
    }
}
