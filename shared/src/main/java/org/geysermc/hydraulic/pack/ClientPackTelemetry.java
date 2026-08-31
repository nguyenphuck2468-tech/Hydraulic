package org.geysermc.hydraulic.pack;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Coarse client lifecycle timings exposed by the public Geyser API. */
public final class ClientPackTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger("HydraulicClientPacks");
    private final Map<GeyserConnection, Trace> traces = new ConcurrentHashMap<>();

    @Subscribe
    public void onInitialize(SessionInitializeEvent event) {
        traces.put(event.connection(), new Trace(System.nanoTime()));
        LOGGER.info("Bedrock session initialized [{}]", client(event.connection()));
    }

    @Subscribe
    public void onLoadPacks(SessionLoadResourcePacksEvent event) {
        Trace trace = traces.computeIfAbsent(event.connection(), ignored -> new Trace(System.nanoTime()));
        trace.packOfferAt = System.nanoTime();
        trace.stage = "pack-offer";
        LOGGER.info("Bedrock pack offer [{}]: {} pack(s), {} ms after session initialization",
                client(event.connection()), event.resourcePacks().size(), millis(trace.startedAt, trace.packOfferAt));
    }

    @Subscribe
    public void onLogin(SessionLoginEvent event) {
        Trace trace = traces.computeIfAbsent(event.connection(), ignored -> new Trace(System.nanoTime()));
        long now = System.nanoTime();
        trace.stage = "login";
        LOGGER.info("Bedrock login [{}]: {} ms after pack offer, {} ms total",
                client(event.connection()), millis(trace.packOfferAt, now), millis(trace.startedAt, now));
    }

    @Subscribe
    public void onJoin(SessionJoinEvent event) {
        Trace trace = traces.computeIfAbsent(event.connection(), ignored -> new Trace(System.nanoTime()));
        long now = System.nanoTime();
        trace.stage = "world-join";
        LOGGER.info("Bedrock world join [{}]: {} ms total", client(event.connection()), millis(trace.startedAt, now));
    }

    @Subscribe
    public void onDisconnect(SessionDisconnectEvent event) {
        Trace trace = traces.remove(event.connection());
        if (trace == null) return;
        LOGGER.info("Bedrock disconnect [{}]: stage={}, {} ms total, reason={}", client(event.connection()), trace.stage,
                millis(trace.startedAt, System.nanoTime()), event.disconnectReason());
    }

    private static String client(GeyserConnection connection) {
        return connection.bedrockUsername() + "/" + connection.platform() + "/" + connection.version();
    }

    private static long millis(long start, long end) {
        return start == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(end - start);
    }

    private static final class Trace {
        private final long startedAt;
        private volatile long packOfferAt;
        private volatile String stage = "initialized";

        private Trace(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
