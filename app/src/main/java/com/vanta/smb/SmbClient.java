package com.vanta.smb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

public final class SmbClient implements AutoCloseable {
    private final CIFSContext context;

    public SmbClient(String username, String password) throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.enableSMB2", "true");
        props.setProperty("jcifs.smb.client.responseTimeout", "10000");
        props.setProperty("jcifs.smb.client.soTimeout", "15000");
        props.setProperty("jcifs.netbios.retryTimeout", "3000");
        BaseContext base = new BaseContext(new PropertyConfiguration(props));
        context = base.withCredentials(new NtlmPasswordAuthenticator("", username, password));
    }

    public List<SmbEntry> list(String url) throws Exception {
        List<SmbEntry> result = new ArrayList<>();
        try (SmbFile directory = new SmbFile(ensureDirectoryUrl(url), context)) {
            for (SmbFile file : directory.listFiles()) {
                try (SmbFile child = file) {
                    boolean isDirectory = child.isDirectory();
                    result.add(new SmbEntry(cleanName(child.getName()), child.getCanonicalPath(),
                            isDirectory, isDirectory ? 0 : child.length(), child.lastModified()));
                }
            }
        }
        Collections.sort(result, Comparator
                .comparing((SmbEntry e) -> !e.directory)
                .thenComparing(e -> e.name.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    public void rename(SmbEntry source, String newName) throws Exception {
        String parent = parentUrl(source.url);
        try (SmbFile from = new SmbFile(source.url, context);
             SmbFile to = new SmbFile(parent + encodePathSegment(newName), context)) {
            if (to.exists()) throw new IllegalStateException("目标文件已存在：" + newName);
            from.renameTo(to);
        }
    }

    public static String buildRootUrl(String host, String remotePath) {
        String cleanHost = host == null ? "" : host.trim();
        cleanHost = cleanHost.replaceFirst("(?i)^smb://", "");
        int slash = cleanHost.indexOf('/');
        String hostPath = "";
        if (slash >= 0) {
            hostPath = cleanHost.substring(slash + 1);
            cleanHost = cleanHost.substring(0, slash);
        }
        if (cleanHost.isEmpty()) throw new IllegalArgumentException("请输入设备 IP 或主机名");
        String combined = joinPath(hostPath, remotePath == null ? "" : remotePath.trim());
        StringBuilder url = new StringBuilder("smb://").append(cleanHost).append('/');
        if (!combined.isEmpty()) {
            for (String segment : combined.split("/+")) {
                if (!segment.isEmpty()) url.append(encodePathSegment(segment)).append('/');
            }
        }
        return url.toString();
    }

    private static String joinPath(String left, String right) {
        String a = left.replaceAll("^/+|/+$", "");
        String b = right.replaceAll("^/+|/+$", "");
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "/" + b;
    }

    private static String encodePathSegment(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte value : bytes) {
            int b = value & 0xff;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') ||
                    (b >= '0' && b <= '9') || b == '-' || b == '_' || b == '.' || b == '~') {
                out.append((char) b);
            } else {
                out.append('%').append(hex[b >> 4]).append(hex[b & 15]);
            }
        }
        return out.toString();
    }

    private static String ensureDirectoryUrl(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static String parentUrl(String url) {
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int slash = trimmed.lastIndexOf('/');
        return trimmed.substring(0, slash + 1);
    }

    private static String cleanName(String name) {
        return name != null && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    @Override public void close() throws Exception {
        context.close();
    }
}
