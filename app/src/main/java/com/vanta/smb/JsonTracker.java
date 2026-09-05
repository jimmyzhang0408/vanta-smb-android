package com.vanta.smb;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One directory/session. Existing files are manual; new files wait for two stable observations. */
final class JsonTracker {
    private boolean initialized;
    private final Set<String> handled = new HashSet<>();
    private final Map<String, SmbEntry> previous = new HashMap<>();
    private final ArrayDeque<SmbEntry> ready = new ArrayDeque<>();

    void reset() {
        initialized = false;
        handled.clear();
        previous.clear();
        ready.clear();
    }

    void observe(List<SmbEntry> entries) {
        Map<String, SmbEntry> latest = new HashMap<>();
        for (SmbEntry entry : entries) {
            if (!entry.isJson()) continue;
            latest.put(entry.url, entry);
            if (!initialized) handled.add(entry.url);
            SmbEntry old = previous.get(entry.url);
            if (!handled.contains(entry.url) && entry.length > 0 && old != null
                    && old.length == entry.length && old.modifiedAt == entry.modifiedAt) {
                ready.add(entry);
                handled.add(entry.url);
            }
        }
        ready.removeIf(e -> !latest.containsKey(e.url));
        handled.retainAll(latest.keySet());
        previous.clear();
        previous.putAll(latest);
        initialized = true;
    }

    void ignore(String url) {
        handled.add(url);
        ready.removeIf(e -> e.url.equals(url));
    }

    SmbEntry next() { return ready.poll(); }
}
