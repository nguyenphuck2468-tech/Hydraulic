package org.geysermc.hydraulic.pack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackListenerTest {
    @Test
    void returnsFinishedWorkWithoutWaitingForAStalledConversion() {
        CompletableFuture<String> stalled = new CompletableFuture<>();
        CompletableFuture<String> completed = CompletableFuture.completedFuture("ready");

        List<String> results = PackListener.awaitCompleted(List.of(stalled, completed), 1);

        assertEquals(List.of("ready"), results);
    }
}
