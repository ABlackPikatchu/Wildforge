package com.wildforge.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered chunk construction. Each stage must be deterministic for the seed and
 * chunk coordinates; cross-boundary features must use world-coordinate anchors.
 * The pipeline itself never schedules workers or reads mutable neighboring chunks.
 */
public final class ChunkGenerationPipeline<T> {
    @FunctionalInterface public interface Stage<T> { void apply(T chunk); }
    public record Step<T>(String name,Stage<T> stage) {
        public Step { Objects.requireNonNull(name);Objects.requireNonNull(stage); }
    }
    private final List<Step<T>> steps;
    public ChunkGenerationPipeline(List<Step<T>> steps) {
        if(steps==null||steps.isEmpty())throw new IllegalArgumentException("Empty generation pipeline");
        this.steps=List.copyOf(steps);
    }
    public List<String> stageNames() {return steps.stream().map(Step::name).toList();}
    public T run(T chunk) {for(Step<T> step:steps)step.stage().apply(chunk);return chunk;}
    public static final class Builder<T> {
        private final List<Step<T>> steps=new ArrayList<>();
        public Builder<T> add(String name,Stage<T> stage){steps.add(new Step<>(name,stage));return this;}
        public ChunkGenerationPipeline<T> build(){return new ChunkGenerationPipeline<>(steps);}
    }
}
