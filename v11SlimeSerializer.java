package com.infernalsuite.aswm.serialization.slime.reader.impl.v11;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.ListTag;
import com.flowpowered.nbt.TagType;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.flowpowered.nbt.stream.NBTOutputStream;
import com.github.luben.zstd.Zstd;
import com.infernalsuite.aswm.api.utils.NibbleArray;
import com.infernalsuite.aswm.api.utils.SlimeFormat;
import com.infernalsuite.aswm.api.world.SlimeChunk;
import com.infernalsuite.aswm.api.world.SlimeChunkSection;
import com.infernalsuite.aswm.api.world.SlimeWorld;
import com.infernalsuite.aswm.api.world.properties.SlimePropertyMap;
import com.infernalsuite.aswm.serialization.slime.ChunkPruner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;

public class v11SlimeSerializer {

    static ExecutorService ioSerializers;

    public static byte[] serializeCustom(SlimeWorld world, int threads){
        if(ioSerializers == null){
        ioSerializers = Executors.newWorkStealingPool(threads);}

        CompoundTag extraData = world.getExtraData();
        SlimePropertyMap propertyMap = world.getPropertyMap();

        // Store world properties
        if (!extraData.getValue().containsKey("properties")) {
            extraData.getValue().putIfAbsent("properties", propertyMap.toCompound());
        } else {
            extraData.getValue().replace("properties", propertyMap.toCompound());
        }

        ByteArrayOutputStream outByteStream = new ByteArrayOutputStream(1024*64);
        DataOutputStream outStream = new DataOutputStream(outByteStream);

        try {
            // File Header and Slime version
            outStream.write(SlimeFormat.SLIME_HEADER);
            outStream.writeByte(SlimeFormat.SLIME_VERSION);

            // World version
            outStream.writeInt(world.getDataVersion());

            // Chunks
            byte[] chunkData = serializeChunksParallelism2(world, world.getChunkStorage());

            byte[] compressedChunkData = Zstd.compress(chunkData);

            outStream.writeInt(compressedChunkData.length);
            outStream.writeInt(chunkData.length);
            outStream.write(compressedChunkData);
            // Entities

            List<CompoundTag> entitiesList = new ArrayList<>();
            for (SlimeChunk chunk : world.getChunkStorage()) {
                entitiesList.addAll(chunk.getEntities());
            }

            ListTag<CompoundTag> entitiesNbtList = new ListTag<>("entities", TagType.TAG_COMPOUND, entitiesList);
            CompoundTag entitiesCompound = new CompoundTag("", new CompoundMap(Collections.singletonList(entitiesNbtList)));
            byte[] entitiesData = serializeCompoundTag(entitiesCompound);
            byte[] compressedEntitiesData = Zstd.compress(entitiesData);

            outStream.writeInt(compressedEntitiesData.length);
            outStream.writeInt(entitiesData.length);
            outStream.write(compressedEntitiesData);

            // Extra Tag
            {
                byte[] extra = serializeCompoundTag(extraData);
                byte[] compressedExtra = Zstd.compress(extra);

                outStream.writeInt(compressedExtra.length);
                outStream.writeInt(extra.length);
                outStream.write(compressedExtra);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return outByteStream.toByteArray();
    }

    private static final int CHUNK_DATA_INITIAL_CAPACITY = 8192;

    static byte[] serializeChunksParallelism2(SlimeWorld world, Collection<SlimeChunk> chunks) {
        try (ByteArrayOutputStream outByteStream = new ByteArrayOutputStream(16384 * 4);
             DataOutputStream outStream = new DataOutputStream(outByteStream)) {

            // Use a concurrent queue to store the empty chunks
            List<SlimeChunk> emptyChunks = new ArrayList<>();

            // Add the chunks that can be pruned to the emptyChunks queue
            for (SlimeChunk chunk : chunks) {
                if (!ChunkPruner.canBePruned(world, chunk)) {
                    emptyChunks.add(chunk);
                }
            }

            outStream.writeInt(chunks.size());

            // Use a CompletionService to submit and retrieve chunk serialization tasks asynchronously
            CompletionService<byte[]> completionService = new ExecutorCompletionService<>(ioSerializers);

            for (int i = 0; i < emptyChunks.size(); i++) {
                SlimeChunk chunk = emptyChunks.get(i);
                completionService.submit(() -> {
                    try (ByteArrayOutputStream stream = new ByteArrayOutputStream(CHUNK_DATA_INITIAL_CAPACITY);
                         DataOutputStream dataOut = new DataOutputStream(stream)){
                        serializeChunkData2(chunk, dataOut);
                        return Zstd.compress(stream.toByteArray());
                    } catch (Throwable throwable) {
                        throw new RuntimeException("Error serializing chunk: " + chunk, throwable);
                    }
                });
            }
            // Process the completed tasks and write the serialized data to the output stream
            for (int i = 0; i < emptyChunks.size(); i++) {
                try {
                    Future<byte[]> future = completionService.take();
                    byte[] data = future.get();

                    outStream.writeInt(data.length);
                    outStream.write(data);
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException("Error processing chunk data", e);
                }
            }

            return outByteStream.toByteArray();
        } catch (Exception exception){
            exception.printStackTrace();
        }
        return null;
    }

    private static void serializeChunkData2(SlimeChunk chunk, DataOutputStream out) throws IOException {
        // Write chunk coordinates
        out.writeInt(chunk.getX());
        out.writeInt(chunk.getZ());

        // Write height maps
        byte[] heightMaps = serializeCompoundTag(chunk.getHeightMaps());
        out.writeInt(heightMaps.length);
        out.write(heightMaps);

        // Write all sections (block data etc)
        SlimeChunkSection[] sections = chunk.getSections();
        out.writeInt(sections.length);

        CompletionService<V11SlimeWorldDeSerializer.ChunkContainer> completionService = new ExecutorCompletionService<>(ioSerializers);

        Map<Integer, V11SlimeWorldDeSerializer.ChunkContainer> paralellChunkContainerMap = new ConcurrentHashMap<>();
        int secId = 0;
        for (int i = 0; i < sections.length; i++){
            SlimeChunkSection section = sections[i];
            V11SlimeWorldDeSerializer.ChunkContainer chunkContainer = new V11SlimeWorldDeSerializer.ChunkContainer();
            final int seconded = secId;
            chunkContainer.sectionId = seconded;
            paralellChunkContainerMap.put(seconded, chunkContainer);
            // Block light
            NibbleArray blockLight = section.getBlockLight();
            boolean hasBlockLight = blockLight != null;
            out.writeBoolean(hasBlockLight);
            if (hasBlockLight){
                out.write(blockLight.getBacking());
            }

            // Sky light
            NibbleArray skyLight = section.getSkyLight();
            boolean hasSkyLight = skyLight != null;
            out.writeBoolean(hasSkyLight);
            if (hasSkyLight) {
                out.write(skyLight.getBacking());
            }

            completionService.submit(() -> {
                chunkContainer.blockStates = serializeCompoundTag(section.getBlockStatesTag());
                chunkContainer.biomes = serializeCompoundTag(section.getBiomeTag());
                return chunkContainer;
            });
            secId+=1;
        }
        V11SlimeWorldDeSerializer.ChunkContainer elseContainer = new V11SlimeWorldDeSerializer.ChunkContainer();
        ExecutorService service = Executors.newSingleThreadExecutor();
        Callable<Object> callable;
        service.submit(callable = () -> {
            try {
                // Write tile entities (per chunk)
                List<CompoundTag> tileEntitiesList = new ArrayList<>(chunk.getTileEntities());
                ListTag<CompoundTag> tileEntitiesNbtList = new ListTag<>("tiles", TagType.TAG_COMPOUND, tileEntitiesList);
                CompoundTag tileEntitiesCompound = new CompoundTag("", new CompoundMap(Collections.singletonList(tileEntitiesNbtList)));
                byte[] tileEntitiesData = serializeCompoundTag(tileEntitiesCompound);
                elseContainer.tileEntities = Zstd.compress(tileEntitiesData);
            } catch (Exception exception){
                exception.printStackTrace();
            }
            return elseContainer;
        });

        // Process the completed tasks and write the serialized data to the output stream
        for (int i = 0; i < sections.length ; i++) {//*2 because its 2 tasks submited.
            try {
                Future<V11SlimeWorldDeSerializer.ChunkContainer> future = completionService.take();
                V11SlimeWorldDeSerializer.ChunkContainer data = future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Error processing chunk data", e);
            }
        }
        for (Map.Entry<Integer, V11SlimeWorldDeSerializer.ChunkContainer> data : paralellChunkContainerMap.entrySet()){
            V11SlimeWorldDeSerializer.ChunkContainer chunkContainer = data.getValue();
            out.writeInt(chunkContainer.sectionId);
            out.writeInt(chunkContainer.blockStates.length);
            out.write(chunkContainer.blockStates);
            out.writeInt(chunkContainer.biomes.length);
            out.write(chunkContainer.biomes);
        }
        try {
            service.invokeAll(Collections.singletonList(callable));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        out.writeInt(elseContainer.tileEntities.length); // compressed length.
        out.write(elseContainer.tileEntities); // compressed data.

        // serialize compound.
    }

    private static final ThreadLocal<ByteArrayOutputStream> BUFFER_THREAD_LOCAL = ThreadLocal.withInitial(() -> new ByteArrayOutputStream(8192));
    private static final ThreadLocal<NBTOutputStream> STREAM_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return new NBTOutputStream(BUFFER_THREAD_LOCAL.get(), NBTInputStream.NO_COMPRESSION, ByteOrder.BIG_ENDIAN);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    });

    protected static byte[] serializeCompoundTag(CompoundTag tag) throws IOException {
        if (tag == null) {
            return new byte[0];
        }
        ByteArrayOutputStream outByteStream = BUFFER_THREAD_LOCAL.get();
        outByteStream.reset();
        try (NBTOutputStream outStream = STREAM_THREAD_LOCAL.get()) {
            outStream.writeTag(tag);
            return outByteStream.toByteArray();
        }
    }

}
