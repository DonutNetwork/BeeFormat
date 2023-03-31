# BeeFormat
This is a custom world format that is the next generation of the SlimeFormat, this implements new techniques such as parallel deserialization, all to improve even more the format.

# Why a new format?

Slime world format is really good, it helps with saving up data volume storage with its usage on compression and faster world serialize and deserialize, a big problem of it becomes when you have a very high byte volume world, this makes the world take a few hundred ms to deserialize completely, mainly due to tile entities.

With this new format the idea is to split the data between chunks of byte arrays, instead of processing the chunks and tile entities all single-threaded, we will do so by doing it with parallel computation, this way we reduce the loading time of a world.

This helps with also database data handling, if you have a database like Redis, you're likely not to experience many improvements, but if you run your own database that works with parallel requests, you are likely to see this as a benefitial format, since it will store all the chunks into small portions of bytes in different key-values.

# How does it work?

Basically the serialization of everything in the world will be splitted between chunks, each chunk bytearray will contain the tile entities of that chunk serialized, as well as the chunk data, etc.
This allows parallel processing, here is an example:

Let's say that you have many tile entities in your world, and they are "spread" over the map, this way you can split the load of deserializing it completely on multiple threads, example:

# Performancec & Benchmark

5 benchmarks were ran in a row with the same data, total of 64 chunks & each chunk with random data generated, around 8KiB of data on each pack.

```yml

5512.262 SINGLE
133.0859 MULTI (2 threads)
65.334 MULTI (4 threads)
54.0457 MULTI (8 threads)
48.3198 MULTI (16 threads)
49.9157 MULTI (32 threads)
48.4659 MULTI (64 threads)

244.537 SINGLE
124.0473 MULTI (2 threads)
71.4215 MULTI (4 threads)
46.585 MULTI (8 threads)
49.5414 MULTI (16 threads)
50.0162 MULTI (32 threads)
49.0651 MULTI (64 threads)

244.9949 SINGLE
125.0672 MULTI (2 threads)
65.3293 MULTI (4 threads)
51.2231 MULTI (8 threads)
51.4698 MULTI (16 threads)
49.5102 MULTI (32 threads)
49.6802 MULTI (64 threads)

244.8143 SINGLE
122.5404 MULTI (2 threads)
63.8949 MULTI (4 threads)
52.0172 MULTI (8 threads)
48.3287 MULTI (16 threads)
48.0633 MULTI (32 threads)
47.1268 MULTI (64 threads)
```

Its a good improvement on speed, this is specifically going to target improvement of speed on tile entities, as deserialization of the normal block side is pretty damn fast already, the issue is with tile entities (such as chests, shulker boxes, etc).

Now, for some proof of concept, we will show with 256 chunks, quick look:
```yml
979.8095 SINGLE
493.9498 MULTI (2 threads)
272.3524 MULTI (4 threads)
191.9357 MULTI (8 threads)
193.2013 MULTI (16 threads)
184.8947 MULTI (32 threads)
189.2698 MULTI (64 threads)
```
As you can see, the most benefitial thread number should be around 16.
