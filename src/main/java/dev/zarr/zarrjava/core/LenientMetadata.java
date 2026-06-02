package dev.zarr.zarrjava.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.utils.Utils;
import dev.zarr.zarrjava.v3.DataType;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lenient Zarr metadata reader. Parses {@code zarr.json} / {@code .zarray} /
 * {@code .zgroup} / {@code .zattrs} as a generic JSON tree, so it works even if
 * codecs (e.g. {@code imagecodecs_jpeg2k}) are unknown to zarr-java's
 * {@code CodecRegistry} or fail {@code CodecPipeline} validation.
 *
 * <p>Only fields that are useful without instantiating codecs are exposed:
 * <ul>
 *   <li>{@code shape}, {@code chunkShape}, {@code dataType} (mirrors
 *       {@link ArrayMetadata#shape} / {@link ArrayMetadata#dataType()} /
 *       {@link ArrayMetadata#chunkShape()})</li>
 *   <li>{@code fillValue}, {@code dimensionNames}, {@code attributes}</li>
 *   <li>raw codecs JSON (so you can inspect compression info if you want)</li>
 *   <li>the entire raw JSON tree as a fallback</li>
 * </ul>
 */
public final class LenientMetadata {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Whether the metadata at a location describes an array or a group. */
    public enum NodeType {
        /** A Zarr array node. */
        ARRAY,
        /** A Zarr group node. */
        GROUP
    }

    /**
     * Lenient view of a Zarr array's metadata, populated from raw JSON without
     * instantiating codecs.
     */
    public static final class ArrayInfo {
        /** Zarr format version: {@code 2} or {@code 3}. */
        public final int zarrFormat;
        /** Array shape, in elements per dimension. */
        public final long[] shape;
        /** Chunk shape, in elements per dimension. */
        public final int[] chunkShape;
        /** Raw data type string from JSON (e.g. {@code "uint16"}, {@code "<u2"}). */
        public final String dataTypeRaw;
        /** Best-effort mapping of {@link #dataTypeRaw} to {@link DataType}, or {@code null} if unknown. */
        public final DataType dataType;
        /** Raw fill value as decoded from JSON, or {@code null}. */
        public final Object fillValue;
        /** Dimension names (v3 only); may be {@code null}. */
        public final String[] dimensionNames;
        /** User attributes; never {@code null}, possibly empty. */
        public final Map<String, Object> attributes;
        /**
         * Raw codecs JSON. For v3 this is the {@code codecs} array; for v2 this is a
         * synthesized object containing {@code filters} and/or {@code compressor}.
         */
        public final JsonNode codecsRaw;
        /** The entire metadata document as a JSON tree. */
        public final JsonNode rawJson;

        ArrayInfo(int zarrFormat, long[] shape, int[] chunkShape,
                  String dataTypeRaw, DataType dataType, Object fillValue,
                  String[] dimensionNames, Map<String, Object> attributes,
                  JsonNode codecsRaw, JsonNode rawJson) {
            this.zarrFormat = zarrFormat;
            this.shape = shape;
            this.chunkShape = chunkShape;
            this.dataTypeRaw = dataTypeRaw;
            this.dataType = dataType;
            this.fillValue = fillValue;
            this.dimensionNames = dimensionNames;
            this.attributes = attributes;
            this.codecsRaw = codecsRaw;
            this.rawJson = rawJson;
        }

        /**
         * Returns the array shape.
         *
         * @return the shape, in elements per dimension
         */
        public long[] shape() { return shape; }

        /**
         * Returns the chunk shape.
         *
         * @return the chunk shape, in elements per dimension
         */
        public int[] chunkShape() { return chunkShape; }

        /**
         * Returns the best-effort {@link DataType}, or {@code null} if the dtype
         * string could not be mapped.
         *
         * @return the data type, or {@code null} if unknown
         */
        public DataType dataType() { return dataType; }
    }

    /**
     * Lenient view of a Zarr group's metadata.
     */
    public static final class GroupInfo {
        /** Zarr format version: {@code 2} or {@code 3}. */
        public final int zarrFormat;
        /** User attributes; never {@code null}, possibly empty. */
        public final Map<String, Object> attributes;
        /** The entire metadata document as a JSON tree. */
        public final JsonNode rawJson;

        GroupInfo(int zarrFormat, Map<String, Object> attributes, JsonNode rawJson) {
            this.zarrFormat = zarrFormat;
            this.attributes = attributes;
            this.rawJson = rawJson;
        }
    }

    /**
     * Tagged union of {@link ArrayInfo} and {@link GroupInfo}.
     */
    public static final class NodeInfo {
        /** The kind of node. */
        public final NodeType type;
        /** Non-{@code null} iff {@link #type} is {@link NodeType#ARRAY}. */
        public final ArrayInfo array;
        /** Non-{@code null} iff {@link #type} is {@link NodeType#GROUP}. */
        public final GroupInfo group;

        NodeInfo(ArrayInfo a) { this.type = NodeType.ARRAY; this.array = a; this.group = null; }
        NodeInfo(GroupInfo g) { this.type = NodeType.GROUP; this.array = null; this.group = g; }
    }

    private LenientMetadata() {}

    /**
     * Auto-detects v3 vs v2 and array vs group at the given handle and reads
     * the metadata leniently.
     *
     * @param handle the storage location of the Zarr node
     * @return the parsed node info
     * @throws IOException if no metadata document can be found or it cannot be read
     */
    public static NodeInfo open(StoreHandle handle) throws IOException {
        StoreHandle v3 = handle.resolve("zarr.json");
        if (v3.exists()) {
            JsonNode root = MAPPER.readTree(Utils.toArray(v3.readNonNull()));
            String nodeType = root.path("node_type").asText("array");
            if ("group".equals(nodeType)) {
                return new NodeInfo(parseV3Group(root));
            } else {
                return new NodeInfo(parseV3Array(root));
            }
        }
        StoreHandle v2arr = handle.resolve(".zarray");
        StoreHandle v2grp = handle.resolve(".zgroup");
        StoreHandle v2att = handle.resolve(".zattrs");
        if (v2arr.exists()) {
            JsonNode root = MAPPER.readTree(Utils.toArray(v2arr.readNonNull()));
            Map<String, Object> attrs = readV2Attrs(v2att);
            return new NodeInfo(parseV2Array(root, attrs));
        }
        if (v2grp.exists()) {
            JsonNode root = MAPPER.readTree(Utils.toArray(v2grp.readNonNull()));
            Map<String, Object> attrs = readV2Attrs(v2att);
            return new NodeInfo(new GroupInfo(2, attrs, root));
        }
        throw new NoSuchFileException(
                "No zarr.json, .zarray or .zgroup found at " + handle);
    }

    /**
     * Convenience wrapper around {@link #open(StoreHandle)} that asserts the
     * node is an array.
     *
     * @param handle the storage location of the Zarr array
     * @return the parsed array info
     * @throws IOException if the metadata cannot be read or the node is a group
     */
    public static ArrayInfo openArray(StoreHandle handle) throws IOException {
        NodeInfo n = open(handle);
        if (n.type != NodeType.ARRAY) {
            throw new IOException("Expected array at " + handle + ", got group");
        }
        return n.array;
    }

    // ---------- v3 ----------

    private static ArrayInfo parseV3Array(JsonNode root) {
        long[] shape = toLongArray(root.get("shape"));
        String dtRaw = root.path("data_type").asText(null);
        DataType dt = parseV3DataType(dtRaw);

        int[] chunkShape = toIntArray(
                root.path("chunk_grid").path("configuration").path("chunk_shape"));

        Object fill = jsonToJava(root.get("fill_value"));

        String[] dimNames = null;
        JsonNode dn = root.get("dimension_names");
        if (dn != null && dn.isArray()) {
            dimNames = new String[dn.size()];
            for (int i = 0; i < dn.size(); i++) {
                dimNames[i] = dn.get(i).isNull() ? null : dn.get(i).asText();
            }
        }

        Map<String, Object> attrs = jsonToMap(root.get("attributes"));
        JsonNode codecs = root.get("codecs");
        return new ArrayInfo(3, shape, chunkShape, dtRaw, dt, fill, dimNames, attrs, codecs, root);
    }

    private static GroupInfo parseV3Group(JsonNode root) {
        Map<String, Object> attrs = jsonToMap(root.get("attributes"));
        return new GroupInfo(3, attrs, root);
    }

    // ---------- v2 ----------

    private static ArrayInfo parseV2Array(JsonNode root, Map<String, Object> attrs) {
        long[] shape = toLongArray(root.get("shape"));
        int[] chunkShape = toIntArray(root.get("chunks"));
        String dtRaw = root.path("dtype").asText(null);
        DataType dt = parseV2DataType(dtRaw);
        Object fill = jsonToJava(root.get("fill_value"));

        // Synthesize a "codecs"-ish JSON object from filters + compressor for inspection.
        ObjectNode synth = MAPPER.createObjectNode();
        if (root.has("filters") && !root.get("filters").isNull()) {
            synth.set("filters", root.get("filters"));
        }
        if (root.has("compressor") && !root.get("compressor").isNull()) {
            synth.set("compressor", root.get("compressor"));
        }
        return new ArrayInfo(2, shape, chunkShape, dtRaw, dt, fill, null, attrs, synth, root);
    }

    private static Map<String, Object> readV2Attrs(StoreHandle attrsHandle) throws IOException {
        if (!attrsHandle.exists()) return Collections.emptyMap();
        JsonNode n = MAPPER.readTree(Utils.toArray(attrsHandle.readNonNull()));
        return jsonToMap(n);
    }

    // ---------- helpers ----------

    /**
     * Best-effort mapping from a v3 dtype string (e.g. {@code "uint16"}) to
     * {@link DataType}.
     *
     * @param s the v3 dtype string from the metadata, may be {@code null}
     * @return the matching {@link DataType}, or {@code null} if unknown
     */
    public static DataType parseV3DataType(String s) {
        if (s == null) return null;
        for (DataType d : DataType.values()) {
            if (d.getValue().equals(s)) return d;
        }
        return null;
    }

    /**
     * Best-effort mapping from a v2 dtype string to {@link DataType}.
     *
     * <p>Accepts both v3-style names (e.g. {@code "uint16"}) and NumPy-style
     * descriptors with an optional byte-order prefix
     * ({@code '<'}, {@code '>'}, {@code '|'}, {@code '='}) followed by a kind
     * and byte count, e.g. {@code "<u2"}, {@code "|u1"}, {@code ">f4"}.
     *
     * @param s the v2 dtype string from the metadata, may be {@code null}
     * @return the matching {@link DataType}, or {@code null} if unknown
     */
    public static DataType parseV2DataType(String s) {
        if (s == null) return null;
        // Try the v3-style name first.
        DataType direct = parseV3DataType(s);
        if (direct != null) return direct;
        // Strip endian/byte-order prefix.
        String core = s;
        if (core.length() > 0 && (core.charAt(0) == '<' || core.charAt(0) == '>'
                || core.charAt(0) == '|' || core.charAt(0) == '=')) {
            core = core.substring(1);
        }
        switch (core) {
            case "b1": return DataType.BOOL;
            case "i1": return DataType.INT8;
            case "i2": return DataType.INT16;
            case "i4": return DataType.INT32;
            case "i8": return DataType.INT64;
            case "u1": return DataType.UINT8;
            case "u2": return DataType.UINT16;
            case "u4": return DataType.UINT32;
            case "u8": return DataType.UINT64;
            case "f4": return DataType.FLOAT32;
            case "f8": return DataType.FLOAT64;
            default:   return null;
        }
    }

    private static long[] toLongArray(JsonNode node) {
        if (node == null || !node.isArray()) return new long[0];
        long[] out = new long[node.size()];
        for (int i = 0; i < node.size(); i++) out[i] = node.get(i).asLong();
        return out;
    }

    private static int[] toIntArray(JsonNode node) {
        if (node == null || !node.isArray()) return new int[0];
        int[] out = new int[node.size()];
        for (int i = 0; i < node.size(); i++) out[i] = node.get(i).asInt();
        return out;
    }

    private static Object jsonToJava(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isFloatingPointNumber()) return n.asDouble();
        if (n.isTextual()) return n.asText();
        if (n.isArray()) {
            Object[] out = new Object[n.size()];
            for (int i = 0; i < n.size(); i++) out[i] = jsonToJava(n.get(i));
            return out;
        }
        if (n.isObject()) return jsonToMap(n);
        return n.toString();
    }

    private static Map<String, Object> jsonToMap(JsonNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (n == null || !n.isObject()) return out;
        n.fields().forEachRemaining(e -> out.put(e.getKey(), jsonToJava(e.getValue())));
        return out;
    }
}
