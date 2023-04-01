# BeeFormat
This is a custom world format that is the next generation of the SlimeFormat, this implements new techniques such as parallel deserialization, all to improve even more the format.

# Why a new format?

Slime world format is really good, it helps with saving up data volume storage with its usage on compression and faster world serialize and deserialize, a big problem of it becomes when you have a very high byte volume world, this makes the world take a few hundred ms to deserialize completely, mainly due to tile entities.

With this new format the idea is to split the data between chunks of byte arrays, instead of processing the chunks and tile entities all single-threaded, we will do so by doing it with parallel computation, this way we reduce the loading time of a world.

This helps with also database data handling, if you have a database like Redis, you're likely not to experience many improvements, but if you run your own database that works with parallel requests, you are likely to see this as a benefitial format, since it will store all the chunks into small portions of bytes in different key-values.

# How does it work?

Basically the serialization of everything in the world will be splitted between chunks, each chunk bytearray will contain the tile entities of that chunk serialized, as well as the chunk data, etc.
This allows parallel processing, making it really really fast, technically nx by the number of threads compared to the number of chunks.

# Serialization format & code example:

Currently the slime format serializes all tile entities and chunks on 1 single chunk byte array, here is a sample of the code for tile entities:

![image](https://user-images.githubusercontent.com/56891617/229285988-f937ecc8-d91f-45f5-9c96-74c8603dd397.png)

We do the same, but in different threads, parallel work, with the Executors.workingSteal executor, example of code:

![image](https://user-images.githubusercontent.com/56891617/229286030-ea854f37-0ea6-4414-b8fa-6410428dc86e.png)
![image](https://user-images.githubusercontent.com/56891617/229286055-ba5a2d70-cd79-4ff5-b981-d82b4284254a.png)

This way, when we deserialize, we will do almost the same for deserialization, except for the fact that the actual reading of the chunks of bytearrays written at the end (on following picture), will be read syncronously, like the rest of the data, except for chunk deserialization and tile entity processing).

![image](https://user-images.githubusercontent.com/56891617/229286115-ac5ffda6-2a5e-43f0-8154-5fb3b44a7be1.png)


