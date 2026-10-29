package com.wildforge.world;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Residency, nearest-first requests, cancellation, and worker reset regression. */
public final class ChunkSourceSmokeTest {
    public static void main(String[] args) throws Exception {
        try (ChunkSource source = new ChunkSource()) {
            source.install(new ChunkColumn(-8, 9));
            require(source.size() == 1, "install resident column");
            List<String> removed = new ArrayList<>();
            int evicted = source.evictOutside(0, 0, 3,
                    c -> removed.add(c.cx + "," + c.cz));
            require(evicted == 1 && removed.equals(List.of("-8,9")), "eviction callback");

            CountDownLatch generation = new CountDownLatch(1);
            source.requestNearest(-3, 5, 2, 1, (x, z) -> {
                generation.countDown();
                return new ChunkColumn(x, z);
            });
            require(generation.await(3, TimeUnit.SECONDS), "generation started");
            ChunkColumn completed = waitForCompletion(source);
            require(completed != null && completed.cx == -3 && completed.cz == 5,
                "nearest chunk requested first, negative coordinates preserved");
            source.install(completed);
            require(source.containsKey(ChunkCoordinates.pack(-3, 5)), "finished result installed");
            // Reset while an old-world generator is actively running. The old
            // result must not be allowed into a new world's residency map.
            CountDownLatch oldStarted = new CountDownLatch(1);
            CountDownLatch releaseOld = new CountDownLatch(1);
            source.requestNearest(100, 100, 0, 1, (x, z) -> {
                oldStarted.countDown();
                try { releaseOld.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return new ChunkColumn(x, z);
            });
            require(oldStarted.await(3, TimeUnit.SECONDS), "old-world worker started");
            source.reset();
            releaseOld.countDown();
            require(source.size() == 0 && source.pendingCount() == 0, "world swap clears residency/jobs");
            source.requestNearest(1, 1, 0, 1, ChunkColumn::new);
            ChunkColumn newWorld = waitForCompletion(source);
            require(newWorld.cx == 1 && newWorld.cz == 1, "late old-world result never installed");
        }
        System.out.println("ChunkSource passed: streaming lifecycle, nearest-first generation and reset");
    }

    private static ChunkColumn waitForCompletion(ChunkSource source) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            ChunkColumn chunk = source.pollCompleted();
            if (chunk != null) return chunk;
            Thread.sleep(2);
        }
        throw new AssertionError("Timed out waiting for background column");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
