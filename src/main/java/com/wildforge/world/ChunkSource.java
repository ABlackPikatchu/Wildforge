package com.wildforge.world;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Owns chunk residency and asynchronous raw terrain generation. Never builds
 * meshes or touches OpenGL. All public methods are called on the game thread;
 * the worker executes only the supplied column generator.
 *
 * Cancellation is best effort, but removed requests cannot be installed even
 * if their worker ignores interruption. Reset invalidates all in-flight jobs.
 */
final class ChunkSource implements AutoCloseable {
    @FunctionalInterface
    interface Generator { ChunkColumn generate(int chunkX, int chunkZ); }

    private final Map<Long, ChunkColumn> resident = new LinkedHashMap<>();
    private final Map<Long, Future<ChunkColumn>> pending = new LinkedHashMap<>();
    private ExecutorService generatorWorker = createWorker();
    private boolean closed;

    private static ExecutorService createWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Wildforge-Chunk-Generation");
            thread.setDaemon(true);
            return thread;
        });
    }

    ChunkColumn get(long key) { return resident.get(key); }
    boolean containsKey(long key) { return resident.containsKey(key); }
    Collection<ChunkColumn> columns() { return java.util.Collections.unmodifiableCollection(resident.values()); }
    int size() { return resident.size(); }
    int pendingCount() { return pending.size(); }

    void install(ChunkColumn chunk) {
        Objects.requireNonNull(chunk, "chunk");
        long key = ChunkCoordinates.pack(chunk.cx, chunk.cz);
        if (resident.putIfAbsent(key, chunk) != null)
            throw new IllegalStateException("Column already resident: " + chunk.cx + ", " + chunk.cz);
    }

    /** Evict resident columns and cancel requests outside the square render radius. */
    int evictOutside(int centerX, int centerZ, int radius, Consumer<ChunkColumn> onEvicted) {
        Objects.requireNonNull(onEvicted, "onEvicted");
        int removed = 0;
        Iterator<Map.Entry<Long, ChunkColumn>> loaded = resident.entrySet().iterator();
        while (loaded.hasNext()) {
            ChunkColumn c = loaded.next().getValue();
            if (!outside(c.cx, c.cz, centerX, centerZ, radius)) continue;
            loaded.remove();
            onEvicted.accept(c);
            removed++;
        }
        Iterator<Map.Entry<Long, Future<ChunkColumn>>> queued = pending.entrySet().iterator();
        while (queued.hasNext()) {
            var entry = queued.next();
            if (!outside(ChunkCoordinates.chunkX(entry.getKey()), ChunkCoordinates.chunkZ(entry.getKey()),
                    centerX, centerZ, radius)) continue;
            entry.getValue().cancel(true);
            queued.remove();
        }
        return removed;
    }

    private static boolean outside(int x, int z, int cx, int cz, int radius) {
        return Math.abs((long)x - cx) > radius || Math.abs((long)z - cz) > radius;
    }

    /** Poll at most one finished column (including canceled tasks) per call. */
    ChunkColumn pollCompleted() {
        Iterator<Map.Entry<Long, Future<ChunkColumn>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Future<ChunkColumn> future = entry.getValue();
            if (!future.isDone()) continue;
            it.remove();
            try {
                ChunkColumn column = future.get();
                if (column == null || ChunkCoordinates.pack(column.cx, column.cz) != entry.getKey())
                    throw new IllegalStateException("Chunk generator returned incorrect coordinates");
                if (!resident.containsKey(entry.getKey())) return column;
            } catch (CancellationException ignored) {
                // Cancelled requests never enter the resident cache.
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Asynchronous terrain generation failed", ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Queue near-to-far in Chebyshev rings, with a bounded total request count. */
    void requestNearest(int centerX, int centerZ, int radius, int maxPending, Generator generator) {
        Objects.requireNonNull(generator, "generator");
        if (closed) throw new IllegalStateException("Chunk source already closed");
        if (maxPending <= 0 || pending.size() >= maxPending) return;
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                long x = (long)centerX + dx, z = (long)centerZ + dz;
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE ||
                    z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                int chunkX = (int)x, chunkZ = (int)z;
                long key = ChunkCoordinates.pack(chunkX, chunkZ);
                if (resident.containsKey(key) || pending.containsKey(key)) continue;
                pending.put(key, generatorWorker.submit(() -> generator.generate(chunkX, chunkZ)));
                if (pending.size() >= maxPending) return;
            }
        }
    }

    /** Clear resident state and invalidate stale work on every world load/reset. */
    void reset() {
        if (closed) throw new IllegalStateException("Chunk source already closed");
        for (Future<ChunkColumn> task : pending.values()) task.cancel(true);
        pending.clear();
        generatorWorker.shutdownNow();
        generatorWorker = createWorker();
        resident.clear();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Future<ChunkColumn> task : pending.values()) task.cancel(true);
        pending.clear();
        generatorWorker.shutdownNow();
        resident.clear();
    }
}
