package com.vanta.smb;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SmbClientTest {
    @Test public void buildsDefaultRoot() {
        assertEquals("smb://192.168.10.1/", SmbClient.buildRootUrl("192.168.10.1", ""));
    }

    @Test public void acceptsHostWithSchemeAndPath() {
        assertEquals("smb://192.168.10.1/share/result/", SmbClient.buildRootUrl("smb://192.168.10.1/share", "result"));
    }

    @Test public void encodesChinesePathAsUtf8() {
        assertEquals("smb://host/%E6%B5%8B%E8%AF%95/", SmbClient.buildRootUrl("host", "测试"));
    }
}
