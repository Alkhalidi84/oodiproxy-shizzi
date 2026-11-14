package com.seloomwarp.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VpnEngineManagerTest {
    private static final class FakeEngine implements VpnEngineManager.Engine {
        final String name;
        boolean running;
        boolean failStart;
        boolean failStop;
        int starts;
        int stops;

        FakeEngine(String name) { this.name = name; }
        @Override public String id() { return name; }
        @Override public void start() {
            starts++;
            if (failStart) throw new IllegalStateException("start failed");
            running = true;
        }
        @Override public void stop() {
            stops++;
            if (failStop) throw new IllegalStateException("stop failed");
            running = false;
        }
        @Override public boolean isRunning() { return running; }
    }

    @Test public void startsRequestedEngineAndTracksOwner() throws Exception {
        VpnEngineManager manager = new VpnEngineManager();
        FakeEngine wireGuard = new FakeEngine("wireguard");
        manager.start(wireGuard);
        assertTrue(manager.isActive(wireGuard));
        assertEquals("wireguard", manager.activeEngineId());
    }

    @Test public void switchingStopsOldEngineBeforeStartingNewOne() throws Exception {
        VpnEngineManager manager = new VpnEngineManager();
        FakeEngine wireGuard = new FakeEngine("wireguard");
        FakeEngine singBox = new FakeEngine("sing-box");
        manager.start(wireGuard);
        manager.start(singBox);
        assertFalse(wireGuard.running);
        assertEquals(1, wireGuard.stops);
        assertTrue(singBox.running);
        assertEquals("sing-box", manager.activeEngineId());
    }

    @Test public void failedStopPreventsSecondEngineFromStarting() throws Exception {
        VpnEngineManager manager = new VpnEngineManager();
        FakeEngine wireGuard = new FakeEngine("wireguard");
        FakeEngine singBox = new FakeEngine("sing-box");
        manager.start(wireGuard);
        wireGuard.failStop = true;
        assertThrows(IllegalStateException.class, () -> manager.start(singBox));
        assertEquals(0, singBox.starts);
        assertEquals("wireguard", manager.activeEngineId());
    }

    @Test public void failedStartRollsBackRequestedEngine() {
        VpnEngineManager manager = new VpnEngineManager();
        FakeEngine singBox = new FakeEngine("sing-box");
        singBox.failStart = true;
        assertThrows(IllegalStateException.class, () -> manager.start(singBox));
        assertFalse(singBox.running);
        assertNull(manager.activeEngineId());
    }

    @Test public void repeatedStartOfActiveEngineDoesNotRestartIt() throws Exception {
        VpnEngineManager manager = new VpnEngineManager();
        FakeEngine wireGuard = new FakeEngine("wireguard");
        manager.start(wireGuard);
        manager.start(wireGuard);
        assertEquals(1, wireGuard.starts);
        assertEquals(0, wireGuard.stops);
    }

    @Test public void stopClearsOwner() throws Exception {
        VpnEngineManager manager = new VpnEngineManager();
        FakeEngine wireGuard = new FakeEngine("wireguard");
        manager.start(wireGuard);
        manager.stop();
        assertFalse(wireGuard.running);
        assertNull(manager.activeEngineId());
    }
}
