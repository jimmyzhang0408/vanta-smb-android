package com.vanta.smb;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class JsonTrackerTest {
    private SmbEntry json(String name, long size) {
        return new SmbEntry(name, "smb://host/share/" + name, false, size, size);
    }
    @Test public void existingFilesDoNotPromptAndTwoNewFilesAreQueued() {
        JsonTracker tracker = new JsonTracker();
        SmbEntry a = json("a.json", 10), b = json("b.json", 10), c = json("c.json", 10);
        tracker.observe(Collections.singletonList(a));
        assertNull(tracker.next());
        tracker.observe(Arrays.asList(a, b, c));
        assertNull(tracker.next());
        tracker.observe(Arrays.asList(a, b, c));
        assertEquals(b, tracker.next());
        assertEquals(c, tracker.next());
        assertNull(tracker.next());
    }
    @Test public void waitsForNonEmptyStableFileAndDoesNotRequeueRenamedFile() {
        JsonTracker tracker = new JsonTracker();
        tracker.observe(Collections.emptyList());
        tracker.observe(Collections.singletonList(json("a.json", 0)));
        tracker.observe(Collections.singletonList(json("a.json", 0)));
        assertNull(tracker.next());
        tracker.observe(Collections.singletonList(json("a.json", 10)));
        assertNull(tracker.next());
        tracker.observe(Collections.singletonList(json("a.json", 20)));
        assertNull(tracker.next());
        tracker.observe(Collections.singletonList(json("a.json", 20)));
        assertNotNull(tracker.next());
        tracker.ignore("smb://host/share/SUP001.json");
        tracker.observe(Collections.singletonList(json("SUP001.json", 20)));
        tracker.observe(Collections.singletonList(json("SUP001.json", 20)));
        assertNull(tracker.next());
    }
    @Test public void ignoresFilesDeletedBeforeTheirTurn() {
        JsonTracker tracker = new JsonTracker();
        tracker.observe(Collections.emptyList());
        tracker.observe(Collections.singletonList(json("a.json", 10)));
        tracker.observe(Collections.singletonList(json("a.json", 10)));
        tracker.observe(Collections.emptyList());
        assertNull(tracker.next());
    }
}
