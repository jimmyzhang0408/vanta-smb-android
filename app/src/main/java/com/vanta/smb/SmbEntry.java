package com.vanta.smb;

import java.util.Objects;

public final class SmbEntry {
    public final String name;
    public final String url;
    public final boolean directory;
    public final long length;
    public final long modifiedAt;

    public SmbEntry(String name, String url, boolean directory, long length, long modifiedAt) {
        this.name = name;
        this.url = url;
        this.directory = directory;
        this.length = length;
        this.modifiedAt = modifiedAt;
    }

    public boolean isJson() {
        return !directory && name.toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    @Override public boolean equals(Object other) {
        return other instanceof SmbEntry && Objects.equals(url, ((SmbEntry) other).url);
    }

    @Override public int hashCode() {
        return Objects.hash(url);
    }
}
