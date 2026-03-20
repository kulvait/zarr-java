package dev.zarr.zarrjava.store;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.io.input.BoundedInputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;
//Java logging for debugging
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * A Store implementation that provides read-only access to a zip archive stored in an underlying Store.
 * Compared to BufferedZipStore, this implementation reads directly from the zip archive without parsing
 * its contents into a buffer store first making it more efficient for read-only access to large zip archives.
 */
public class ReadOnlyZipStore extends ZipStore {
private static final Logger logger = Logger.getLogger(ReadOnlyZipStore.class.getName());

    private Map<String, Long> fileIndex;
    private Set<String> directoryIndex;
    private boolean isCached = false;


// Main constructor: receives a StoreHandle directly
    public ReadOnlyZipStore(@Nonnull StoreHandle handle) {
        super(handle);
    }

    // Convenience constructor for filesystem paths
    public ReadOnlyZipStore(@Nonnull Path zipPath) {
        this(new FilesystemStore(zipPath.getParent()).resolve(
                                                              zipPath.getFileName().toString()));
    }

    // Convenience constructor for string paths
    public ReadOnlyZipStore(@Nonnull String zipPath) {
        this(Paths.get(zipPath));
    }


    private synchronized void ensureCacheOriginal() {
        fileIndex = new LinkedHashMap<>();
        directoryIndex = new LinkedHashSet<>();

        InputStream inputStream = underlyingStore.getInputStream();
        if (inputStream == null) {
            isCached = true;
            return;
        }

        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(inputStream)) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = normalizeEntryName(entry.getName());
                if (entry.isDirectory()) {
                    directoryIndex.add(name);
                } else {
                    fileIndex.put(name, entry.getSize());
                }
            }
        } catch (IOException e) {
            throw StoreException.readFailed(
                                            underlyingStore.toString(), new String[]{}, new IOException("Failed to read ZIP directory from underlying store", e));
        }
        isCached = true;
    }

// Helper method to add parent directories of a file entry to the directory index
private void addParentDirs(String entryName, Set<String> dirIndex) {
    int lastSlash = entryName.lastIndexOf('/'); // Find the last '/' in the file name
    while (lastSlash > 0) {                      // Keep going until no more slashes are found
        String parentDir = entryName.substring(0, lastSlash + 1); // Extract the parent directory path
        if (!dirIndex.add(parentDir)) {          // Add the parent to the directory index if it’s not already added
            break; // Exit if this parent directory has already been added
        }
        lastSlash = entryName.lastIndexOf('/', lastSlash - 1); // Move the search for slashes up
    }
}

    private synchronized void ensureCacheNew() {
        fileIndex = new LinkedHashMap<>();
        directoryIndex = new LinkedHashSet<>();

        // Try fast path: use Path + ZipFile (random access, central directory)
        final Path zipStorePath;
        try {
            zipStorePath = underlyingStore.toPath(); // throws if not FilesystemStore
			System.out.println("Resolved underlying store to filesystem path: " + zipStorePath);
        } catch (Exception e) {
            // Fallback to original sequential scan
            System.out.println("Fast path failed, falling back to original ensureCache");
            ensureCacheOriginal();
            return;
        }


        // Fast path using ZipFile
        try (ZipFile zf = new ZipFile(zipStorePath.toFile())) {
            // Optionally pre-size: count entries once (cheap and avoids rehashing on huge zips)
            int entryCount = zf.size();
            fileIndex = new LinkedHashMap<>(Math.max(16, entryCount), 0.75f);
            directoryIndex = new LinkedHashSet<>(Math.max(16, entryCount / 2), 0.75f);

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = normalizeEntryName(e.getName());
//				System.out.println("Processing entry: " + name + " (isDirectory=" + e.isDirectory() + ", size=" + e.getSize() + ")");
                if (name.isEmpty()) continue; // guard against odd entries

                if (e.isDirectory() || name.endsWith("/")) {
                    // Ensure directory names end with '/'
                    if (!name.endsWith("/")) name = name + "/";
                    directoryIndex.add(name);
                } else {
                    // Put file size (may be -1 for STORED anomalies, but ZipFile usually knows it)
                    fileIndex.put(name, e.getSize());
                    // Derive and add parent directories even if the zip lacks explicit dir entries
                    addParentDirs(name, directoryIndex);
                }
            }
            isCached = true;
        } catch (IOException io) {
            // If central directory read fails for any reason, fall back to the original safe path.
            try {
                ensureCacheOriginal();
                return;
            } catch (RuntimeException re) {
                // Surface the better error (central dir attempt) with context if both fail
                throw StoreException.readFailed(
                                                underlyingStore.toString(), new String[]{}, new IOException("Failed to read ZIP central directory from filesystem path", io)
                );
            }
        }
    }

    private synchronized void ensureCache() {
        if (isCached) return;
        long startTime, endTime;
        //System.out.println("START ensureCacheOriginal()");
        //startTime = System.currentTimeMillis();
        // swap to test the new implementation:
        //ensureCacheOriginal(); // or ensureCacheNew()
        //endTime = System.currentTimeMillis();
        //System.out.println("END ensureCacheOriginal() in " + (endTime - startTime) + " ms");

logger.log(Level.INFO, "Starting ensureCacheNew() for underlying store: {0}", underlyingStore.toString());
        startTime = System.currentTimeMillis();
        // swap to test the new implementation:
        ensureCacheNew(); // or ensureCacheOriginal()
        endTime = System.currentTimeMillis();
		logger.log(Level.INFO, "ensureCacheNew() completed in {0} ms", (endTime - startTime));
System.out.println("Cache contents after ensureCache, fileIndex:");
System.out.println(fileIndex);
System.out.println("Cache contents after ensureCache, directoryIndex:");
System.out.println(directoryIndex);
    }

    String resolveKeys(String[] keys) {
        return String.join("/", keys);
    }

    String[] resolveEntryKeys(String entryKey) {
        return entryKey.split("/");
    }

    @Override
    public boolean exists(String[] keys) {
        ensureCache();
        return fileIndex.containsKey(resolveKeys(keys));
    }

    @Nullable
    @Override
    public ByteBuffer get(String[] keys) {
        return get(keys, 0);
    }

    @Nullable
    @Override
    public ByteBuffer get(String[] keys, long start) {
        return get(keys, start, -1);
    }

    @Nullable
    @Override
    public ByteBuffer get(String[] keys, long start, long end) {
        ensureCache();
        String key = resolveKeys(keys);
        if (!fileIndex.containsKey(key)) {
            return null;
        }

        InputStream inputStream = underlyingStore.getInputStream();
        if (inputStream == null) {
            return null;
        }
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(inputStream)) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (entryName.startsWith("/")) {
                    entryName = entryName.substring(1);
                }
                if (entry.isDirectory() || !entryName.equals(resolveKeys(keys))) {
                    continue;
                }

                long skipResult = zis.skip(start);
                if (skipResult != start) {
                    throw new IOException("Failed to skip to start position " + start + " in zip entry " + entryName);
                }

                long bytesToRead;
                if (end != -1) bytesToRead = end - start;
                else bytesToRead = Long.MAX_VALUE;

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] bufferArray = new byte[8192];
                int len;
                while (bytesToRead > 0 && (len = zis.read(bufferArray, 0, (int) Math.min(bufferArray.length, bytesToRead))) != -1) {
                    baos.write(bufferArray, 0, len);
                    bytesToRead -= len;
                }
                byte[] bytes = baos.toByteArray();
                return ByteBuffer.wrap(bytes);
            }
        } catch (IOException e) {
            throw StoreException.readFailed(underlyingStore.toString(), keys, e);
        }
        return null;
    }

    @Override
    public void set(String[] keys, ByteBuffer bytes) {
        throw new UnsupportedOperationException("ReadOnlyZipStore does not support set operation.");
    }

    @Override
    public void delete(String[] keys) {
        throw new UnsupportedOperationException("ReadOnlyZipStore does not support delete operation.");
    }

    @Nonnull
    @Override
    public StoreHandle resolve(String... keys) {
        return new StoreHandle(this, keys);
    }

    @Override
    public String toString() {
        return "ReadOnlyZipStore(" + underlyingStore.toString() + ")";
    }

    @Override
    public Stream<String[]> list(String[] prefixKeys) {
        ensureCache();
        Stream.Builder<String[]> builder = Stream.builder();

        String prefix = resolveKeys(prefixKeys);
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }

        for (String name : fileIndex.keySet()) {
            if (name.startsWith(prefix)) {
                builder.add(resolveEntryKeys(name.substring(prefix.length())));
            }
        }
        return builder.build();
    }

    @Override
    public Stream<String> listChildren(String[] prefixKeys) {
        ensureCache();
        Set<String> children = new LinkedHashSet<>();

        String prefix = resolveKeys(prefixKeys);
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }

        String finalPrefix = prefix;
        Stream.concat(fileIndex.keySet().stream(), directoryIndex.stream()).forEach(name -> {
            if (name.startsWith(finalPrefix) && !name.equals(finalPrefix)) {
                String relative = name.substring(finalPrefix.length());
                String[] parts = relative.split("/");
                children.add(parts[0]);
            }
        });

        return children.stream();
    }

    private String normalizeEntryName(String name) {
        if (name.startsWith("/")) name = name.substring(1);
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        return name;
    }


    @Override
    public InputStream getInputStream(String[] keys, long start, long end) {
        ensureCache();
        String key = resolveKeys(keys);
        if (!fileIndex.containsKey(key)) {
            return null;
        }

        InputStream baseStream = underlyingStore.getInputStream();

        try {
            ZipArchiveInputStream zis = new ZipArchiveInputStream(baseStream);
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (entryName.startsWith("/")) {
                    entryName = entryName.substring(1);
                }
                if (entry.isDirectory() || !entryName.equals(resolveKeys(keys))) {
                    continue;
                }

                long skipResult = zis.skip(start);
                if (skipResult != start) {
                    throw new IOException("Failed to skip to start position " + start + " in zip entry " + entryName);
                }

                long bytesToRead;
                if (end != -1) bytesToRead = end - start;
                else bytesToRead = Long.MAX_VALUE;

                return new BoundedInputStream(zis, bytesToRead);
            }
            return null;
        } catch (IOException e) {
            throw StoreException.readFailed(underlyingStore.toString(), keys, e);
        }
    }

    @Override
    public long getSize(String[] keys) {
        ensureCache();
        String key = resolveKeys(keys);
        Long cachedSize = fileIndex.get(key);
        if (cachedSize == null) {
            return -1;
        }
        if (cachedSize >= 0) {
            return cachedSize;
        }

        // if size is not in header/cache, we fallback to reading
        InputStream inputStream = underlyingStore.getInputStream();
        if (inputStream == null) {
            throw StoreException.readFailed(
                                            underlyingStore.toString(), keys, new IOException("Cannot get size - underlying store input stream is null"));
        }
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(inputStream)) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (entryName.startsWith("/")) {
                    entryName = entryName.substring(1);
                }
                if (entry.isDirectory() || !entryName.equals(resolveKeys(keys))) {
                    continue;
                }
                long size = entry.getSize();
                if (size < 0) {
                    // read the entire entry to determine size
                    size = 0;
                    byte[] bufferArray = new byte[8192];
                    int len;
                    while ((len = zis.read(bufferArray)) != -1) {
                        size += len;
                    }
                }
                return size;
            }
            return -1; // file not found
        } catch (IOException e) {
            throw StoreException.readFailed(
                                            underlyingStore.toString(), keys, new IOException("Failed to read ZIP entry size for key: " + String.join("/", keys), e));
        }
    }
}
