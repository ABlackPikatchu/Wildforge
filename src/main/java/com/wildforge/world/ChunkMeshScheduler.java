package com.wildforge.world;

import java.util.*;
import java.util.concurrent.*;

/**
 * Owns all asynchronous section-mesh and distant-LOD work. Builders are handed
 * immutable snapshots assembled by the game thread; workers never access live
 * mutable columns. Installations and invalidations run on the game thread only.
 */
public final class ChunkMeshScheduler implements AutoCloseable {
    public record LodMeshes(float[] terrain, float[] fluids) { }
    @FunctionalInterface public interface MeshBuilder {
        Callable<float[][]> snapshot(ChunkColumn column,BitSet sections);
    }
    private record MeshJob(Future<float[][]> future,BitSet sections,int epoch,int generation) { }
    private record LodJob(Future<LodMeshes> future,int revision,int generation) { }
    private final LinkedHashMap<Long,BitSet> dirty=new LinkedHashMap<>();
    private final HashMap<Long,MeshJob> meshes=new HashMap<>();
    private final HashMap<Long,Integer> epochs=new HashMap<>();
    private final HashMap<Long,LodJob> lods=new HashMap<>();
    private ExecutorService mesher = newMesher();
    private ExecutorService lodWorker = newLodWorker();
    private int generation;
    private boolean lodPending;

    private static ExecutorService newMesher(){
        return Executors.newFixedThreadPool(2,r->{ Thread t=new Thread(r,"Wildforge-Section-Mesher");t.setDaemon(true);return t; });
    }
    private static ExecutorService newLodWorker(){
        return Executors.newSingleThreadExecutor(r->{ Thread t=new Thread(r,"Wildforge-Distant-LOD");t.setDaemon(true);return t; });
    }
    public void reset() {
        generation++;
        mesher.shutdownNow();lodWorker.shutdownNow();
        dirty.clear();meshes.clear();epochs.clear();lods.clear();lodPending=false;
        mesher=newMesher();lodWorker=newLodWorker();
    }
    @Override public void close(){
        mesher.shutdownNow();lodWorker.shutdownNow();
        dirty.clear();meshes.clear();epochs.clear();lods.clear();lodPending=false;
    }
    public void evict(long key) {
        dirty.remove(key);epochs.remove(key);
        MeshJob job=meshes.remove(key);if(job!=null)job.future.cancel(true);
        LodJob lod=lods.remove(key);if(lod!=null)lod.future.cancel(true);
    }
    public void markDirty(long key,BitSet sections,ChunkColumn column) {
        if(column==null||sections.isEmpty())return;
        epochs.merge(key,1,Integer::sum);
        BitSet requested=dirty.computeIfAbsent(key,ignore->new BitSet(TerrainMesh.SECTION_COUNT));
        requested.or(sections);
        MeshJob old=meshes.remove(key);
        if(old!=null){requested.or(old.sections);old.future.cancel(true);}
        LodJob distant=lods.remove(key);
        if(distant!=null)distant.future.cancel(true);
        column.render.invalidateDistant();
    }
    /** Consume at most maxResults completed section jobs; stale results never install. */
    public int installSections(ChunkSource source,int maxResults) {
        int installed=0;
        for(Iterator<Map.Entry<Long,MeshJob>> it=meshes.entrySet().iterator();it.hasNext()&&installed<maxResults;) {
            var entry=it.next();MeshJob job=entry.getValue();
            if(!job.future.isDone())continue;
            it.remove();
            try {
                float[][] results=job.future.get();
                ChunkColumn chunk=source.get(entry.getKey());
                if(chunk==null||job.generation!=generation)continue;
                if(job.epoch!=epochs.getOrDefault(entry.getKey(),0)){
                    dirty.computeIfAbsent(entry.getKey(),ignore->new BitSet(TerrainMesh.SECTION_COUNT)).or(job.sections);
                    continue;
                }
                for(int section=job.sections.nextSetBit(0);section>=0;section=job.sections.nextSetBit(section+1))
                    chunk.render.installSection(section,results[section],results[section+TerrainMesh.SECTION_COUNT]);
                installed++;
            }catch(CancellationException ignored){
            }catch(ExecutionException e){throw new IllegalStateException("Asynchronous section meshing failed",e.getCause());
            }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        return installed;
    }
    /** Choose nearest dirty columns first; take snapshots synchronously. */
    public void scheduleSections(ChunkSource source,int cameraCx,int cameraCz,MeshBuilder builder) {
        while(meshes.size()<2&&!dirty.isEmpty()){
            long nearest=0,distance=Long.MAX_VALUE;
            for(long candidate:dirty.keySet()){
                long dx=(long)(int)(candidate>>32)-cameraCx,dz=(long)(int)candidate-cameraCz;
                long d=dx*dx+dz*dz;
                if(d<distance){distance=d;nearest=candidate;}
            }
            BitSet selected=dirty.remove(nearest);
            ChunkColumn chunk=source.get(nearest);
            if(chunk==null||selected==null||selected.isEmpty()||meshes.containsKey(nearest))continue;
            BitSet immutable=(BitSet)selected.clone();
            Callable<float[][]> work=builder.snapshot(chunk,immutable); // snapshots while on game thread
            meshes.put(nearest,new MeshJob(mesher.submit(work),immutable,
                    epochs.getOrDefault(nearest,0),generation));
        }
    }
    /** Consume a bounded number of LOD results and discard canceled/stale jobs. */
    public int installDistant(ChunkSource source){
        int installed=0;
        for(Iterator<Map.Entry<Long,LodJob>> it=lods.entrySet().iterator();it.hasNext();){
            var entry=it.next();LodJob job=entry.getValue();
            if(!job.future.isDone())continue;
            it.remove();
            ChunkColumn chunk=source.get(entry.getKey());
            if(chunk==null||job.generation!=generation||job.revision!=chunk.render.distantRevision())continue;
            try {
                LodMeshes meshes=job.future.get();
                chunk.render.installDistant(meshes.terrain(),meshes.fluids());
                installed++;
            }catch(CancellationException ignored){
            }catch(ExecutionException e){throw new IllegalStateException("Background distant LOD generation failed",e.getCause());
            }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        return installed;
    }
    public boolean requestDistant(long key,ChunkColumn chunk,Callable<LodMeshes> builder){
        if(lods.containsKey(key)||lods.size()>=2)return false;
        lods.put(key,new LodJob(lodWorker.submit(builder),chunk.render.distantRevision(),generation));
        return true;
    }
    public boolean hasDistantJob(long key){return lods.containsKey(key);}
    public int pendingMeshCount(){return dirty.size()+meshes.size();}
    public int pendingMeshSectionCount(){
        int n=0;for(BitSet value:dirty.values())n+=value.cardinality();
        for(MeshJob value:meshes.values())n+=value.sections.cardinality();
        return n;
    }
    public int pendingLodCount(){return lods.size();}
    public void setLodBuildPending(boolean value){lodPending=value;}
    public boolean lodBuildPending(){return lodPending;}
}
