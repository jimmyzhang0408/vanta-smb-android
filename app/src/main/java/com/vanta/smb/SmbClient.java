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
        props.setProperty("jcifs.smb.client.minVersion", "SMB1");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        props.setProperty("jcifs.smb.client.connTimeout", "10000");
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
        if (!source.isJson()) throw new IllegalArgumentException("只能重命名 JSON 文件");
        try (SmbFile from = new SmbFile(source.url, context);
             SmbFile to = new SmbFile(renamedUrl(source.url, newName), context)) {
            if (!from.exists()) throw new IllegalStateException("源文件已不存在，请刷新目录");
            if (from.length() != source.length || from.lastModified() != source.modifiedAt) {
                throw new IllegalStateException("文件仍在变化，请刷新目录并在设备写入完成后重试");
            }
            if (source.name.equals(newName)) return;
            if (to.exists()) throw new IllegalStateException("目标文件已存在：" + newName);
            from.renameTo(to, false);
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
        if (!cleanHost.matches("([A-Za-z0-9._-]+|\\[[A-Fa-f0-9:]+\\])(:[0-9]{1,5})?")) {
            throw new IllegalArgumentException("设备地址只能包含 IP/主机名和可选端口，账号请填在下方");
        }
        String combined = joinPath(hostPath, remotePath == null ? "" : remotePath.trim());
        StringBuilder url = new StringBuilder("smb://").append(cleanHost).append('/');
        if (!combined.isEmpty()) {
            for (String segment : combined.split("/+")) {
                if (!segment.isEmpty()) {
                    validateSegment(segment);
                    // jCIFS uses URL.getPath() directly; percent escaping becomes part of the SMB filename.
                    url.append(segment).append('/');
                }
            }
        }
        return url.toString();
    }

    private static String joinPath(String left, String right) {
        String a = left.replace('\\', '/').replaceAll("^/+|/+$", "");
        String b = right.replace('\\', '/').replaceAll("^/+|/+$", "");
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "/" + b;
    }

    static String renamedUrl(String sourceUrl, String newName) {
        validateSegment(newName);
        if (!newName.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            throw new IllegalArgumentException("文件必须保留 .json 扩展名");
        }
        return parentUrl(sourceUrl) + newName;
    }

    private static void validateSegment(String text) {
        if (text.isEmpty() || text.equals(".") || text.equals("..")
                || text.matches(".*[\\\\/:*?\"<>|#\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("路径或文件名包含不支持的字符");
        }
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
