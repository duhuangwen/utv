package com.bjunicom.iptv;

import com.bjunicom.iptv.model.IptvChannel;
import com.bjunicom.iptv.parser.M3uParser;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class M3uParserTest {

    @Test
    public void testParseBeijingUnicomM3uAndExport() {
        String sample = "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"\n" +
                "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" tvg-logo=\"http://epg.51zmt.top:8000/tb1/CCTV/CCTV1.png\" group-title=\"[高清]央视\",CCTV-1综合[1][高清]\n" +
                "rtp://239.3.1.129:8008\n" +
                "#EXTINF:-1 tvg-id=\"106\" tvg-name=\"CCTV4K\" tvg-logo=\"http://epg.51zmt.top:8000/tb1/CCTV/CCTV4k.png\" group-title=\"[高清]央视;[高清]北京IPTV;4K\",CCTV 4K[85][4K]\n" +
                "rtp://239.3.1.244:2000\n";

        List<IptvChannel> list = M3uParser.parseString(sample);
        assertEquals(2, list.size());

        IptvChannel cctv1 = list.get(0);
        assertEquals(1, cctv1.getChannelNumber());
        assertEquals("CCTV-1综合", cctv1.getDisplayName());
        assertEquals("高清", cctv1.getResolutionTag());
        assertEquals("239.3.1.129", cctv1.getMulticastIp());
        assertEquals(8008, cctv1.getMulticastPort());

        IptvChannel cctv4k = list.get(1);
        assertEquals(85, cctv4k.getChannelNumber());
        assertEquals("4K", cctv4k.getResolutionTag());
        assertTrue(cctv4k.getGroups().contains("4K"));

        cctv1.setConnected(true);
        cctv1.setActualResolutionFlag("高清");
        cctv1.setVideoTrackInfo("#1 h264, 1920x1080,25fps");
        cctv1.setAudioTrackInfo("#1 mp2,2ch,48khz");

        cctv4k.setConnected(false);
        cctv4k.setActualResolutionFlag("错误");

        String modifiedM3u = M3uParser.toM3uContent(list, true, "192.168.2.1:8012");
        assertTrue(modifiedM3u.contains("http://192.168.2.1:8012/rtp/239.3.1.129:8008"));
        assertFalse(modifiedM3u.contains("239.3.1.244"));

        String csv = M3uParser.toResultsCsvContent(list);
        assertTrue(csv.contains("编号,频道名称,video track,audio track,screenshot_name,连接flag,分辨率flag"));
        assertTrue(csv.contains("#1 h264, 1920x1080,25fps"));
    }

    @Test
    public void testParseBundledAnhuiUnicomPlaylist() throws Exception {
        List<IptvChannel> list;
        try (InputStream is = new FileInputStream("src/main/assets/ah-unicom-iptv.m3u")) {
            list = M3uParser.parse(is);
        }
        assertEquals(63, list.size());

        IptvChannel cctv1 = list.get(0);
        assertEquals(1, cctv1.getChannelNumber());
        assertEquals("CCTV-1综合", cctv1.getDisplayName());
        assertEquals("高清", cctv1.getResolutionTag());
        assertTrue(cctv1.getGroups().contains("[高清]央视"));
        assertEquals(cctv1.getRawUrl(), cctv1.getMulticastKey());

        Set<String> keys = new HashSet<>();
        for (IptvChannel ch : list) {
            assertTrue(ch.getRawUrl().startsWith("http://10.255."));
            keys.add(ch.getMulticastKey());
        }
        assertEquals(list.size(), keys.size());
    }

    @Test
    public void testParseBundledAnhuiTelecomPlaylist() throws Exception {
        List<IptvChannel> list;
        try (InputStream is = new FileInputStream("src/main/assets/ah-telecom-iptv.m3u")) {
            list = M3uParser.parse(is);
        }
        assertEquals(131, list.size());

        IptvChannel cctv1 = list.get(0);
        assertEquals(1, cctv1.getChannelNumber());
        assertEquals("CCTV-1综合", cctv1.getDisplayName());
        assertEquals("高清", cctv1.getResolutionTag());
        assertTrue(cctv1.getGroups().contains("央视"));

        Set<Integer> numbers = new HashSet<>();
        for (IptvChannel ch : list) {
            assertTrue(ch.getRawUrl().endsWith(".m3u8"));
            assertNull(ch.getMulticastIp());
            numbers.add(ch.getChannelNumber());
        }
        assertEquals(list.size(), numbers.size());
    }
}
