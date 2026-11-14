package com.seloomwarp.vpn;

/**
 * Serializes VPN engine ownership. Exactly one adapter may own the app VPN
 * interface at a time. This class is platform-neutral so lifecycle behavior
 * can be unit-tested without Android framework or a device.
 */
final class VpnEngineManager {
    interface Engine {
        String id();
        void start() throws Exception;
        void stop() throws Exception;
        boolean isRunning();
    }

    private final Object lock = new Object();
    private Engine activeEngine;

    String activeEngineId() {
        synchronized (lock) {
            return activeEngine == null ? null : activeEngine.id();
        }
    }

    boolean isActive(Engine engine) {
        synchronized (lock) {
            return activeEngine == engine && engine.isRunning();
        }
    }

    void start(Engine requested) throws Exception {
        if (requested == null) throw new IllegalArgumentException("VPN engine is required");
        synchronized (lock) {
            if (activeEngine == requested && requested.isRunning()) return;

            // Do not start a second engine if the previous owner cannot stop
            // or reports a stale running state after stop() returned.
            stopActiveLocked();

            try {
                requested.start();
                if (!requested.isRunning()) {
                    throw new IllegalStateException("VPN engine did not enter the running state: " + requested.id());
                }
                activeEngine = requested;
            } catch (Exception startFailure) {
                try {
                    requested.stop();
                    if (requested.isRunning()) {
                        throw new IllegalStateException("VPN engine remained running after rollback: " + requested.id());
                    }
                    activeEngine = null;
                } catch (Exception stopFailure) {
                    // Keep the uncertain engine as owner. Allowing another
                    // engine to start could create competing TUN owners.
                    activeEngine = requested;
                    startFailure.addSuppressed(stopFailure);
                }
                throw startFailure;
            }
        }
    }

    void stop() throws Exception {
        synchronized (lock) {
            stopActiveLocked();
        }
    }

    private void stopActiveLocked() throws Exception {
        if (activeEngine == null) return;
        Engine previous = activeEngine;
        // Retain ownership if stop fails or the engine still reports running.
        previous.stop();
        if (previous.isRunning()) {
            throw new IllegalStateException("VPN engine remained running after stop: " + previous.id());
        }
        activeEngine = null;
    }
}
