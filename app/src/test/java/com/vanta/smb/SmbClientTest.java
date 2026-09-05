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

    @Test public void retainsChinesePathForJcifs() throws Exception {
        String url = SmbClient.buildRootUrl("host", "share/测试");
        try (jcifs.smb.SmbFile file = new jcifs.smb.SmbFile(url,
                new jcifs.context.BaseContext(new jcifs.config.PropertyConfiguration(new java.util.Properties())))) {
            assertEquals("\\测试\\", file.getLocator().getUNCPath());
        }
    }

    @Test public void retainsChineseRenameTargetForJcifs() throws Exception {
        String url = SmbClient.renamedUrl("smb://host/share/old.json", "样品一号.json");
        try (jcifs.smb.SmbFile file = new jcifs.smb.SmbFile(url,
                new jcifs.context.BaseContext(new jcifs.config.PropertyConfiguration(new java.util.Properties())))) {
            assertEquals("\\样品一号.json", file.getLocator().getUNCPath());
        }
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsDirectoryTraversal() {
        SmbClient.buildRootUrl("host", "share/../outside");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsRenameToAnotherDirectory() {
        SmbClient.renamedUrl("smb://host/share/a.json", "../b.json");
    }

    @Test(expected = IllegalArgumentException.class) public void credentialsAreNotAcceptedInHost() {
        SmbClient.buildRootUrl("vanta:secret@host", "");
    }
}
