package com.infernalsuite.aswm.serialization.slime.reader.impl.v11;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.DoubleTag;
import com.flowpowered.nbt.ListTag;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.github.luben.zstd.Zstd;
import com.infernalsuite.aswm.ChunkPos;
import com.infernalsuite.aswm.api.exceptions.CorruptedWorldException;
import com.infernalsuite.aswm.api.loaders.SlimeLoader;
import com.infernalsuite.aswm.api.utils.NibbleArray;
import com.infernalsuite.aswm.api.world.SlimeChunk;
import com.infernalsuite.aswm.api.world.SlimeChunkSection;
import com.infernalsuite.aswm.api.world.SlimeWorld;
import com.infernalsuite.aswm.api.world.properties.SlimeProperties;
import com.infernalsuite.aswm.api.world.properties.SlimePropertyMap;
import com.infernalsuite.aswm.serialization.slime.reader.VersionedByteSlimeWorldReader;
import com.infernalsuite.aswm.skeleton.SkeletonSlimeWorld;
import com.infernalsuite.aswm.skeleton.SlimeChunkSectionSkeleton;
import com.infernalsuite.aswm.skeleton.SlimeChunkSkeleton;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;

public class V11SlimeWorldDeSerializer implements VersionedByteSlimeWorldReader<SlimeWorld> {

    public static final int ARRAY_SIZE = 16 * 16 * 16 / (8 / 4); // blocks / bytes per block
    public static final ExecutorService ioDeserializer = Executors.newWorkStealingPool(16);

    @SuppressWarnings("unchecked")
    @Override
    public SlimeWorld deserializeWorld(byte version, SlimeLoader loader, String worldName, DataInputStream dataStream, SlimePropertyMap propertyMap, boolean readOnly)
            throws IOException {
        // World version
        int worldVersion = dataStream.readInt();
        // Chunk Data

        // serialized chunks in here.
        byte[] chunkBytes = readCompressed(dataStream);

        Map<ChunkPos, SlimeChunk> chunks = readChunks(propertyMap, chunkBytes);
        byte[] entities = readCompressed(dataStream);
        byte[] extra = readCompressed(dataStream);
        // Entity deserialization
        CompoundTag entitiesCompound = readCompound(entities);

        {
            List<CompoundTag> serializedEntities = ((ListTag<CompoundTag>) entitiesCompound.getValue().get("entities")).getValue();
            for (CompoundTag entityCompound : serializedEntities) {
                ListTag<DoubleTag> listTag = (ListTag<DoubleTag>) entityCompound.getAsListTag("Pos").get();

                int chunkX = listTag.getValue().get(0).getValue().intValue() >> 4;
                int chunkZ = listTag.getValue().get(2).getValue().intValue() >> 4;
                ChunkPos chunkKey = new ChunkPos(chunkX, chunkZ);
                SlimeChunk chunk = chunks.get(chunkKey);
                if (chunk != null) {
                    chunk.getEntities().add(entityCompound);
                }
            }
        }

        // Extra Data

        CompoundTag extraCompound = readCompound(extra);

        // World properties
        SlimePropertyMap worldPropertyMap = propertyMap;
        Optional<CompoundMap> propertiesMap = extraCompound
                .getAsCompoundTag("properties")
                .map(CompoundTag::getValue);

        if (propertiesMap.isPresent()) {
            worldPropertyMap = new SlimePropertyMap(propertiesMap.get());
            worldPropertyMap.merge(propertyMap); // Override world properties
        }

        return new SkeletonSlimeWorld(worldName, loader, readOnly, chunks,
                extraCompound,
                worldPropertyMap,
                worldVersion
        );
    }

    private static Map<ChunkPos, SlimeChunk> readChunks(SlimePropertyMap slimePropertyMap, byte[] bytes) throws IOException {
        Map<ChunkPos, SlimeChunk> chunkMap = new HashMap<>();
        DataInputStream chunkData = new DataInputStream(new ByteArrayInputStream(bytes));
        List<CompletableFuture<Map.Entry<ChunkPos, SlimeChunk>>> asyncProviders = new LinkedList<>();
        int chunks = chunkData.readInt();
        for (int i = 0; i < chunks; i++){
            int length = chunkData.readInt();
            // length of the chunkn bytearray
            final byte[] data = new byte[length];
            chunkData.read(data);
            CompletableFuture<Map.Entry<ChunkPos, SlimeChunk>> entryCompletableFuture = new CompletableFuture<>();
            asyncProviders.add(entryCompletableFuture);
            ioDeserializer.submit(() -> {

                byte[] dC = Zstd.decompress(data, (int) Zstd.decompressedSize(data));
                try (DataInputStream reader = new DataInputStream(new ByteArrayInputStream(dC))) {
                    // coords
                    int x = reader.readInt();
                    int z = reader.readInt();

                    // Height Maps
                    byte[] heightMapData = new byte[reader.readInt()];
                    reader.read(heightMapData);
                    CompoundTag heightMaps = readCompound(heightMapData);

                    // Chunk Sections
                    {
                        // See WorldUtils
                        int sectionAmount = slimePropertyMap.getValue(SlimeProperties.CHUNK_SECTION_MAX) - slimePropertyMap.getValue(SlimeProperties.CHUNK_SECTION_MIN) + 1;
                        SlimeChunkSection[] chunkSectionArray = new SlimeChunkSection[sectionAmount];
                        int sectionCount = reader.readInt();
                        for (int sectionId = 0; sectionId < sectionCount; sectionId++) {
                            // Block Light Nibble Array
                            NibbleArray blockLightArray;
                            if (reader.readBoolean()) {
                                byte[] blockLightByteArray = new byte[ARRAY_SIZE];
                                reader.read(blockLightByteArray);
                                blockLightArray = new NibbleArray(blockLightByteArray);
                            } else {
                                blockLightArray = null;
                            }

                            // Sky Light Nibble Array
                            NibbleArray skyLightArray;
                            if (reader.readBoolean()) {
                                byte[] skyLightByteArray = new byte[ARRAY_SIZE];
                                reader.read(skyLightByteArray);
                                skyLightArray = new NibbleArray(skyLightByteArray);
                            } else {
                                skyLightArray = null;
                            }
                            // these 2 are read after this ->
//                            // Block data
//                            byte[] blockStateData = new byte[reader.readInt()];
//                            reader.read(blockStateData);
//                            CompoundTag blockStateTag = readCompound(blockStateData);
//
//                            // Biome Data
//                            byte[] biomeData = new byte[reader.readInt()];
//                            reader.read(biomeData);
//                            CompoundTag biomeTag = readCompound(biomeData);
                            chunkSectionArray[sectionId] = new SlimeChunkSectionSkeleton(
                                    null,
                                    null,
                                    blockLightArray,
                                    skyLightArray);
                        }
                        List<Callable<Object>> callables = new ArrayList<>();
                        for (int i1 = 0; i1 < sectionAmount; i1++){
                            Callable<Object> callback;
                            int secId = reader.readInt();
                            byte[] blockStates = new byte[reader.readInt()];
                            reader.read(blockStates);
                            byte[] biomes = new byte[reader.readInt()];
                            reader.read(biomes);
                            ioDeserializer.submit(callback = () -> {
                                SlimeChunkSectionSkeleton chunkSectionSkeleton = (SlimeChunkSectionSkeleton) chunkSectionArray[secId];
                                CompoundTag bs = readCompound(blockStates);
                                CompoundTag ts = readCompound(biomes);
                                chunkSectionSkeleton.setBiome(ts);
                                chunkSectionSkeleton.setBlockStates(bs);
                                return null;
                            });
                            callables.add(callback);
                        }

                        SlimeChunk slimeChunk = new SlimeChunkSkeleton(x, z, chunkSectionArray, heightMaps, new ArrayList<>(), new ArrayList<>());

                        // Tile Entity deserialization
                        final byte[][] tileEntities = {new byte[reader.readInt()]};
                        reader.read(tileEntities[0]);
                        Callable<Object> callable;
                        ioDeserializer.submit(callable = () -> {
                            tileEntities[0] = Zstd.decompress(tileEntities[0], (int) Zstd.decompressedSize(tileEntities[0]));
                            CompoundTag tileEntitiesCompound = readCompound(tileEntities[0]);
                            for (CompoundTag tileEntityCompound : ((ListTag<CompoundTag>) tileEntitiesCompound.getValue().get("tiles")).getValue()){
                                slimeChunk.getTileEntities().add(tileEntityCompound);
                            }
                            return slimeChunk;
                        });
                        ioDeserializer.invokeAll(callables);
                        ioDeserializer.invokeAll(Collections.singletonList(callable));

                        entryCompletableFuture.complete(Map.entry(new ChunkPos(x, z), slimeChunk));
                    }
                } catch (Exception exception) {
                    entryCompletableFuture.completeExceptionally(exception);
                }
            });
            // process it all async.
        }
        for (CompletableFuture<Map.Entry<ChunkPos, SlimeChunk>> entryCompletableFuture : asyncProviders){
            try {
                Map.Entry<ChunkPos, SlimeChunk> entry = entryCompletableFuture.get();
                chunkMap.put(entry.getKey(), entry.getValue());
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        return chunkMap;
    }


    public static class ChunkContainer {

        public byte[] blockStates;
        public byte[] biomes;
        public byte[] tileEntities;
        public int sectionId;
    }

    private static byte[] readCompressed(DataInputStream stream) throws IOException {
        int compressedLength = stream.readInt();
        int normalLength = stream.readInt();
        byte[] compressed = new byte[compressedLength];
        byte[] normal = new byte[normalLength];

        stream.read(compressed);
        Zstd.decompress(normal, compressed);
        return normal;
    }

    private static CompoundTag readCompound(byte[] bytes) throws IOException {
        if (bytes.length == 0) {
            return null;
        }

        NBTInputStream stream = new NBTInputStream(new ByteArrayInputStream(bytes), NBTInputStream.NO_COMPRESSION, ByteOrder.BIG_ENDIAN);
        return (CompoundTag) stream.readTag();
    }


}
