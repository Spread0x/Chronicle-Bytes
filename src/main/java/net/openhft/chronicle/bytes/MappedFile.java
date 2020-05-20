/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.bytes;

import net.openhft.chronicle.core.*;
import net.openhft.chronicle.core.io.IORuntimeException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.nio.ch.Interruptible;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.FileLock;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * A memory mapped files which can be randomly accessed in chunks. It has overlapping regions to
 * avoid wasting bytes at the end of chunks.
 */
@SuppressWarnings({"rawtypes", "unchecked", "restriction"})
public class MappedFile implements ReferenceCounted {
    private static final long DEFAULT_CAPACITY = 128L << 40;
    // A single JVM cannot lock a file more than once.
    private static final Object GLOBAL_FILE_LOCK = FileChannel.class;
    @NotNull
    private final RandomAccessFile raf;
    private final FileChannel fileChannel;
    private final long chunkSize;
    private final long overlapSize;
    private final List<WeakReference<MappedBytesStore>> stores = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReferenceCounted refCount = ReferenceCounter.onReleased(this::performRelease);
    private final long capacity;
    @NotNull
    private final File file;
    private final boolean readOnly;
    private NewChunkListener newChunkListener = MappedFile::logNewChunk;

    protected MappedFile(@NotNull final File file,
                         @NotNull final RandomAccessFile raf,
                         final long chunkSize,
                         final long overlapSize,
                         final long capacity,
                         final boolean readOnly) {
        this.file = file;
        this.raf = raf;
        this.fileChannel = raf.getChannel();
        this.chunkSize = OS.mapAlign(chunkSize);
        this.overlapSize = overlapSize > 0 && overlapSize < 64 << 10 ? chunkSize : OS.mapAlign(overlapSize);
        this.capacity = capacity;
        this.readOnly = readOnly;

        if (Jvm.isJava9Plus())
            doNotCloseOnInterrupt9(this.fileChannel);
        else
            doNotCloseOnInterrupt(this.fileChannel);

        assert registerMappedFile(this);
    }

    private static void logNewChunk(final String filename,
                                    final int chunk,
                                    final long delayMicros) {
        if (!Jvm.isDebugEnabled(MappedFile.class))
            return;

        // avoid a GC while trying to memory map.
        final String message = BytesInternal.acquireStringBuilder()
                .append("Allocation of ").append(chunk)
                .append(" chunk in ").append(filename)
                .append(" took ").append(delayMicros / 1e3).append(" ms.")
                .toString();
        Jvm.debug().on(MappedFile.class, message);
    }

    private static boolean registerMappedFile(final MappedFile mappedFile) {
//        MAPPED_FILE_THROWABLE_MAP.put(mappedFile, new Throwable("Created here"));
        return true;
    }

    public static void checkMappedFiles() {

/*
        int[] count = {0};
        for (Map.Entry<MappedFile, Throwable> entry : MAPPED_FILE_THROWABLE_MAP.entrySet()) {
            entry.getKey().check(entry.getValue(), count);
        }
        MAPPED_FILE_THROWABLE_MAP.clear();
        if (count[0] > 0)
            throw new AssertionError("Count: " + count[0]);
*/
    }

//    static final Map<MappedFile, Throwable> MAPPED_FILE_THROWABLE_MAP = Collections.synchronizedMap(new IdentityHashMap<>());

    @NotNull
    public static MappedFile of(@NotNull final File file,
                                final long chunkSize,
                                final long overlapSize,
                                final boolean readOnly) throws FileNotFoundException {
//        if (readOnly && OS.isWindows()) {
//            Jvm.warn().on(MappedFile.class, "Read only mode not supported on Windows, defaulting to read/write");
//            readOnly = false;
//        }

        @NotNull RandomAccessFile raf = new CleaningRandomAccessFile(file, readOnly ? "r" : "rw");
//        try {
        final long capacity = /*readOnly ? raf.length() : */DEFAULT_CAPACITY;
        return new MappedFile(file, raf, chunkSize, overlapSize, capacity, readOnly);
/*
        } catch (IOException e) {
            Closeable.closeQuietly(raf);
            @NotNull FileNotFoundException fnfe = new FileNotFoundException("Unable to open " + file);
            fnfe.initCause(e);
            throw fnfe;
        }
*/
    }

    @NotNull
    public static MappedFile mappedFile(@NotNull final File file, final long chunkSize) throws FileNotFoundException {
        return mappedFile(file, chunkSize, OS.pageSize());
    }

/*
    private void check(Throwable throwable, int[] count) {
        for (int i = 0; i < stores.size(); i++) {
            WeakReference<MappedBytesStore> storeRef = stores.get(i);
            if (storeRef == null)
                continue;
            @Nullable MappedBytesStore mbs = storeRef.get();
            if (mbs != null && mbs.refCount() > 0) {
                mbs.release();
                throwable.printStackTrace();
                count[0]++;
            }
        }
    }
*/

    @NotNull
    public static MappedFile mappedFile(@NotNull final String filename, final long chunkSize) throws FileNotFoundException {
        return mappedFile(filename, chunkSize, OS.pageSize());
    }

    @NotNull
    public static MappedFile mappedFile(@NotNull final String filename,
                                        final long chunkSize,
                                        final long overlapSize) throws FileNotFoundException {
        return mappedFile(new File(filename), chunkSize, overlapSize);
    }

    @NotNull
    public static MappedFile mappedFile(@NotNull final File file,
                                        final long chunkSize,
                                        final long overlapSize) throws FileNotFoundException {
        return mappedFile(file, chunkSize, overlapSize, false);
    }

    @NotNull
    public static MappedFile mappedFile(@NotNull final File file,
                                        final long chunkSize,
                                        final long overlapSize,
                                        final boolean readOnly) throws FileNotFoundException {
        return MappedFile.of(file, chunkSize, overlapSize, readOnly);
    }

    @NotNull
    public static MappedFile readOnly(@NotNull final File file) throws FileNotFoundException {
        long chunkSize = file.length();
        long overlapSize = 0;
        // Chunks of 4 GB+ not supported on Windows.
        if (OS.isWindows() && chunkSize > 2L << 30) {
            chunkSize = 2L << 30;
            overlapSize = OS.pageSize();
        }
        return MappedFile.of(file, chunkSize, overlapSize, true);
    }

    @NotNull
    public static MappedFile mappedFile(@NotNull final File file,
                                        final long capacity,
                                        final long chunkSize,
                                        final long overlapSize,
                                        final boolean readOnly) throws IOException {
        final RandomAccessFile raf = new CleaningRandomAccessFile(file, readOnly ? "r" : "rw");
        // Windows throws an exception when setting the length when you re-open
        if (raf.length() < capacity)
            raf.setLength(capacity);
        return new MappedFile(file, raf, chunkSize, overlapSize, capacity, readOnly);
    }

    public static void warmup() {
        try {
            Jvm.disableDebugHandler();

            @NotNull final File file = File.createTempFile("delete_warming_up", "me");
            file.deleteOnExit();
            final long mapAlignment = OS.mapAlignment();
            final int chunks = 64;
            final int compileThreshold = Jvm.compileThreshold();
            for (int j = 0; j <= compileThreshold; j += chunks) {
                try {
                    try (@NotNull RandomAccessFile raf = new CleaningRandomAccessFile(file, "rw")) {
                        @NotNull final MappedFile mappedFile = new MappedFile(file, raf, mapAlignment, 0, mapAlignment * chunks, false);
                        warmup0(mapAlignment, chunks, mappedFile);
                        mappedFile.releaseLast();
                    }
                    Thread.yield();
                    Files.delete(file.toPath());
                } catch (IOException e) {
                    Jvm.debug().on(MappedFile.class, "Error during warmup", e);
                }
            }
        } catch (IOException e) {
            Jvm.warn().on(MappedFile.class, "Error during warmup", e);
        }

        Jvm.resetExceptionHandlers();
    }

    private static void warmup0(final long mapAlignment,
                                final int chunks,
                                @NotNull final MappedFile mappedFile) throws IOException {
        for (int i = 0; i < chunks; i++) {
            mappedFile.acquireBytesForRead(i * mapAlignment).releaseLast();
            mappedFile.acquireBytesForWrite(i * mapAlignment).releaseLast();
        }
    }

    private void doNotCloseOnInterrupt(final FileChannel fc) {
        try {
            final Field field = AbstractInterruptibleChannel.class
                    .getDeclaredField("interruptor");
            Jvm.setAccessible(field);
            field.set(fc, (Interruptible) thread
                    -> System.err.println(getClass().getName() + " - " + fc + " not closed on interrupt"));
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), "Couldn't disable close on interrupt", e);
        }
    }

    // based on a solution by https://stackoverflow.com/users/9199167/max-vollmer
    // https://stackoverflow.com/a/52262779/57695
    private void doNotCloseOnInterrupt9(final FileChannel fc) {
        try {
            final Field field = AbstractInterruptibleChannel.class.getDeclaredField("interruptor");
            final Class<?> interruptibleClass = field.getType();
            Jvm.setAccessible(field);
            field.set(fc, Proxy.newProxyInstance(
                    interruptibleClass.getClassLoader(),
                    new Class[]{interruptibleClass},
                    (p, m, a) -> {
                        System.err.println(getClass().getName() + " - " + fc + " not closed on interrupt");
                        return null;
                    }));
        } catch (Throwable e) {
            Jvm.warn().on(getClass(), "Couldn't disable close on interrupt", e);
        }
    }

    @NotNull
    public File file() {
        return file;
    }

    @NotNull
    public MappedBytesStore acquireByteStore(final long position)
            throws IOException, IllegalArgumentException, IllegalStateException {
        return acquireByteStore(position, readOnly ? ReadOnlyMappedBytesStore::new : MappedBytesStore::new);
    }

    @NotNull
    public synchronized <T extends MappedBytesStore> T acquireByteStore(final long position,
                                                                        @NotNull final MappedBytesStoreFactory<T> mappedBytesStoreFactory)
            throws IOException, IllegalArgumentException, IllegalStateException {
        if (closed.get())
            throw new IOException("Closed");
        if (position < 0)
            throw new IOException("Attempt to access a negative position: " + position);
        final int chunk = (int) (position / chunkSize);


        while (stores.size() <= chunk) {
            stores.add(null);
        }
        final WeakReference<MappedBytesStore> mbsRef = stores.get(chunk);
        if (mbsRef != null) {
            @NotNull final T mbs = (T) mbsRef.get();
            if (mbs != null && mbs.refCount() > 0) {
                return mbs;
            }
        }
        final long start = System.nanoTime();
        final long minSize = (chunk + 1L) * chunkSize + overlapSize;
        long size = fileChannel.size();
        if (size < minSize && !readOnly) {
            // handle a possible race condition between processes.
            try {
                synchronized (GLOBAL_FILE_LOCK) {
                    size = fileChannel.size();
                    if (size < minSize) {
                        final long time0 = System.nanoTime();
                        try (FileLock ignore = fileChannel.lock()) {
                            size = fileChannel.size();
                            if (size < minSize) {
                                raf.setLength(minSize);
                            }
                        }
                        final long time1 = System.nanoTime() - time0;
                        if (time1 >= 1_000_000L) {
                            Jvm.warn().on(getClass(), "Took " + time1 / 1000L + " us to grow file " + file());
                        }
                    }
                }
            } catch (IOException ioe) {
                throw new IOException("Failed to resize to " + minSize, ioe);
            }
        }
        final long mappedSize = chunkSize + overlapSize;
        final MapMode mode = readOnly ? MapMode.READ_ONLY : MapMode.READ_WRITE;
        final long startOfMap = chunk * chunkSize;
        final long address = OS.map(fileChannel, mode, startOfMap, mappedSize);

        final T mbs2 = mappedBytesStoreFactory.create(this, chunk * this.chunkSize, address, mappedSize, this.chunkSize);
        stores.set(chunk, new WeakReference<>(mbs2));

        final long time2 = System.nanoTime() - start;
        if (newChunkListener != null) {
            newChunkListener.onNewChunk(file.getPath(), chunk, time2 / 1000);
        }
        if (time2 > 5_000_000L)
            Jvm.warn().on(getClass(), "Took " + time2 / 1000L + " us to add mapping for " + file());

        return mbs2;

    }

    /**
     * Convenience method so you don't need to release the BytesStore
     */
    @NotNull
    public Bytes acquireBytesForRead(final long position)
            throws IOException, IllegalStateException, IllegalArgumentException {
        @Nullable final MappedBytesStore mbs = acquireByteStore(position);
        final Bytes bytes = mbs.bytesForRead();
        bytes.readPositionUnlimited(position);
        return bytes;
    }

    public void acquireBytesForRead(final long position, @NotNull final VanillaBytes bytes)
            throws IOException, IllegalStateException, IllegalArgumentException {
        @Nullable final MappedBytesStore mbs = acquireByteStore(position);
        bytes.bytesStore(mbs, position, mbs.capacity() - position);
    }

    @NotNull
    public Bytes acquireBytesForWrite(final long position)
            throws IOException, IllegalStateException, IllegalArgumentException {
        @Nullable MappedBytesStore mbs = acquireByteStore(position);
        @NotNull Bytes bytes = mbs.bytesForWrite();
        bytes.writePosition(position);
        return bytes;
    }

    public void acquireBytesForWrite(final long position, @NotNull final VanillaBytes bytes)
            throws IOException, IllegalStateException, IllegalArgumentException {
        @Nullable final MappedBytesStore mbs = acquireByteStore(position);
        bytes.bytesStore(mbs, position, mbs.capacity() - position);
        bytes.writePosition(position);
    }

    @Override
    public void reserve(ReferenceOwner id) throws IllegalStateException {
        refCount.reserve(id);
    }

    @Override
    public void release(ReferenceOwner id) throws IllegalStateException {
        refCount.release(id);
    }

    @Override
    public void releaseLast(ReferenceOwner id) {
        refCount.releaseLast(id);
    }

    @Override
    public boolean tryReserve(ReferenceOwner id) {
        return refCount.tryReserve(id);
    }

    @Override
    public int refCount() {
        return refCount.refCount();
    }

    private synchronized void performRelease() {
        try {
            for (int i = 0; i < stores.size(); i++) {
                final WeakReference<MappedBytesStore> storeRef = stores.get(i);
                if (storeRef == null)
                    continue;
                @Nullable final MappedBytesStore mbs = storeRef.get();
                if (mbs != null) {
                    // this MappedFile is the only referrer to the MappedBytesStore at this point,
                    // so ensure that it is released
                    try {
                        if (mbs.refCount() > 0)
                            mbs.releaseLast(this);
                    } catch (IllegalStateException e) {
                        if (mbs.refCount() > 0)
                            throw e;
                    }
                }
                // Dereference released entities
                storeRef.clear();
                stores.set(i, null);
            }
        } finally {
            closeQuietly(raf);
            closed.set(true);
        }
    }

    @NotNull
    public String referenceCounts() {
        @NotNull final StringBuilder sb = new StringBuilder();
        sb.append("refCount: ").append(refCount());
        for (@Nullable final WeakReference<MappedBytesStore> store : stores) {
            long count = 0;
            if (store != null) {
                @Nullable final MappedBytesStore mbs = store.get();
                if (mbs != null)
                    count = mbs.refCount();
            }
            sb.append(", ").append(count);
        }
        return sb.toString();
    }

    public long capacity() {
        return capacity;
    }

    public long chunkSize() {
        return chunkSize;
    }

    public long overlapSize() {
        return overlapSize;
    }

    public NewChunkListener getNewChunkListener() {
        return newChunkListener;
    }

    public void setNewChunkListener(final NewChunkListener listener) {
        this.newChunkListener = listener;
    }

    public long actualSize() throws IORuntimeException {
        boolean interrupted = Thread.interrupted();
        try {
            return fileChannel.size();

        } catch (ArrayIndexOutOfBoundsException aiooe) {
            // try again.
            return actualSize();

        } catch (ClosedByInterruptException cbie) {
            closed.set(true);
            interrupted = true;
            throw new IllegalStateException(cbie);

        } catch (IOException e) {
            final boolean open = fileChannel.isOpen();
            if (open) {
                throw new IORuntimeException(e);
            } else {
                closed.set(true);
                throw new IllegalStateException(e);
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @NotNull
    public RandomAccessFile raf() {
        return raf;
    }
}