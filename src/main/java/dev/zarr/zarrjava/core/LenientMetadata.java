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
 * Lenient Zarr metadata reader. Parses zarr.json / .zarray / .zgroup / .zattrs as
 * a generic JSON tree, so it works even if codecs (e.g. imagecodecs_jpeg2k) are
 * unknown to zarr-java's CodecRegistry or fail CodecPipeline validation.
 *
 * Only fields that are useful without instantiating codecs are exposed:
 *   - shape, chunkShape, dataType  (mirrors ArrayMetadata.shape / dataType() / chunkShape())
 *   - fillValue, dimensionNames, attributes
 *   - raw codecs JSON (so you can inspect compression info if you want)
 *   - the entire raw JSON tree as a fallback
 */
public final class LenientMetadata {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum NodeType { ARRAY, GROUP }

    public static final class ArrayInfo {
        public final int zarrFormat;             // 2 or 3
        public final long[] shape;
        public final int[] chunkShape;
        public final String dataTypeRaw;         // raw string from JSON ("uint16", "<u2", ...)
        public final DataType dataType;          // best-effort mapping to v3 DataType, or null
        public final Object fillValue;           // raw JSON value
        public final String[] dimensionNames;    // v3 only, may be null
        public final Map<String, Object> attributes; // never null, possibly empty
        public final JsonNode codecsRaw;         // v3: codecs array; v2: synthesized [filters..., compressor]
        public final JsonNode rawJson;           // entire metadata document

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

        // Mirrors what your existing code uses on ArrayMetadata:
        public long[] shape()       { return shape; }
        public int[]  chunkShape()  { return chunkShape; }
        public DataType dataType()  { return dataType; }
    }

    public static final class GroupInfo {
        public final int zarrFormat;
        public final Map<String, Object> attributes;
        public final JsonNode rawJson;

        GroupInfo(int zarrFormat, Map<String, Object> attributes, JsonNode rawJson) {
            this.zarrFormat = zarrFormat;
            this.attributes = attributes;
            this.rawJson = rawJson;
        }
    }

    public static final class NodeInfo {
        public final NodeType type;
        public final ArrayInfo array;   // non-null iff type == ARRAY
        public final GroupInfo group;   // non-null iff type == GROUP

        NodeInfo(ArrayInfo a) { this.type = NodeType.ARRAY; this.array = a; this.group = null; }
        NodeInfo(GroupInfo g) { this.type = NodeType.GROUP; this.array = null; this.group = g; }
    }

    private LenientMetadata() {}

    /** Auto-detects v3 vs v2 and array vs group at the given handle. */
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

    /** Convenience: assert ARRAY and return ArrayInfo. */
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

        // Synthesize a "codecs"-ish JSON array from filters + compressor for inspection.
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

    /** Best-effort mapping from a v3 dtype string to {@link DataType}; null if unknown. */
    public static DataType parseV3DataType(String s) {
        if (s == null) return null;
        for (DataType d : DataType.values()) {
            if (d.getValue().equals(s)) return d;
        }
        return null;
    }

    /** Best-effort mapping from a v2 dtype string ("<u2", "|u1", ">f4", "uint16", ...). */
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
