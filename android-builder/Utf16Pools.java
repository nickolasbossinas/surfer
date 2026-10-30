import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites the UTF-8 string pools of an APK (resources.arsc and compiled XML)
 * as UTF-16. aapt2 always writes UTF-8 pools, which Android 2.2 introduced:
 * Android 2.1 and older cannot read them and reject the package ("parse error").
 * The output is unsigned (the old signature files are dropped); sign it again.
 *
 * Usage: java Utf16Pools.java in.apk out.apk
 */
public final class Utf16Pools {
    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_TABLE = 0x0002;
    private static final int RES_XML = 0x0003;
    private static final int RES_TABLE_PACKAGE = 0x0200;
    private static final int UTF8_FLAG = 0x100;

    public static void main(String[] args) throws Exception {
        int converted = 0;
        try (ZipFile in = new ZipFile(args[0]);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            for (Enumeration<? extends ZipEntry> e = in.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.equals("META-INF/MANIFEST.MF") || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                byte[] data;
                try (InputStream s = in.getInputStream(entry)) {
                    data = s.readAllBytes();
                }
                if (name.equals("resources.arsc") || name.endsWith(".xml")) {
                    int type = data.length >= 8 ? u16(data, 0) : -1;
                    if (type == RES_TABLE || type == RES_XML) {
                        data = chunk(data, 0);
                        converted++;
                    }
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setMethod(entry.getMethod());
                if (entry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    copy.setCrc(crc.getValue());
                }
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("UTF-16 string pools: " + converted + " files");
    }

    /** The chunk at {@code off}, with every string pool inside it rewritten. */
    private static byte[] chunk(byte[] b, int off) {
        int type = u16(b, off);
        int headerSize = u16(b, off + 2);
        int size = u32(b, off + 4);
        if (type == RES_STRING_POOL) return pool(b, off);
        if (type != RES_TABLE && type != RES_XML && type != RES_TABLE_PACKAGE) {
            return slice(b, off, size);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // A package header points at its type and key string pools by offset.
        int oldTypeStrings = type == RES_TABLE_PACKAGE ? u32(b, off + 268) : -1;
        int oldKeyStrings = type == RES_TABLE_PACKAGE ? u32(b, off + 276) : -1;
        int newTypeStrings = oldTypeStrings, newKeyStrings = oldKeyStrings;
        for (int o = off + headerSize; o < off + size; o += u32(b, o + 4)) {
            int rel = o - off;
            int newRel = headerSize + body.size();
            if (rel == oldTypeStrings) newTypeStrings = newRel;
            if (rel == oldKeyStrings) newKeyStrings = newRel;
            body.writeBytes(chunk(b, o));
        }
        byte[] out = new byte[headerSize + body.size()];
        System.arraycopy(b, off, out, 0, headerSize);
        System.arraycopy(body.toByteArray(), 0, out, headerSize, body.size());
        put32(out, 4, out.length);
        if (type == RES_TABLE_PACKAGE) {
            put32(out, 268, newTypeStrings);
            put32(out, 276, newKeyStrings);
        }
        return out;
    }

    private static byte[] pool(byte[] b, int off) {
        int headerSize = u16(b, off + 2);
        int size = u32(b, off + 4);
        int count = u32(b, off + 8);
        int styleCount = u32(b, off + 12);
        int flags = u32(b, off + 16);
        int stringsStart = u32(b, off + 20);
        int stylesStart = u32(b, off + 24);
        if ((flags & UTF8_FLAG) == 0) return slice(b, off, size);

        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            int o = off + stringsStart + u32(b, off + headerSize + 4 * i);
            // UTF-8 entry: length in UTF-16 units, then in bytes (1 or 2 bytes each), the bytes, a 0.
            o += (b[o] & 0x80) != 0 ? 2 : 1;
            int bytes = b[o] & 0xff;
            if ((bytes & 0x80) != 0) {
                bytes = ((bytes & 0x7f) << 8) | (b[o + 1] & 0xff);
                o += 2;
            } else {
                o += 1;
            }
            char[] chars = new String(b, o, bytes, StandardCharsets.UTF_8).toCharArray();
            offsets[i] = strings.size();
            // UTF-16 entry: length in units (1 or 2 units), the units, a 0 unit.
            if (chars.length > 0x7fff) {
                le16(strings, 0x8000 | (chars.length >>> 16));
                le16(strings, chars.length & 0xffff);
            } else {
                le16(strings, chars.length);
            }
            for (char c : chars) le16(strings, c);
            le16(strings, 0);
        }
        while (strings.size() % 4 != 0) strings.write(0);

        byte[] styles = styleCount > 0 ? slice(b, off + stylesStart, size - stylesStart) : new byte[0];
        int newStringsStart = headerSize + 4 * count + 4 * styleCount;
        int newStylesStart = styleCount > 0 ? newStringsStart + strings.size() : 0;
        byte[] out = new byte[newStringsStart + strings.size() + styles.length];
        System.arraycopy(b, off, out, 0, headerSize);
        put32(out, 4, out.length);
        put32(out, 16, flags & ~UTF8_FLAG);
        put32(out, 20, newStringsStart);
        put32(out, 24, newStylesStart);
        for (int i = 0; i < count; i++) put32(out, headerSize + 4 * i, offsets[i]);
        // Style offsets are relative to the style data, which is copied as it is.
        System.arraycopy(b, off + headerSize + 4 * count, out, headerSize + 4 * count, 4 * styleCount);
        System.arraycopy(strings.toByteArray(), 0, out, newStringsStart, strings.size());
        System.arraycopy(styles, 0, out, newStringsStart + strings.size(), styles.length);
        return out;
    }

    private static int u16(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    private static int u32(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void put32(byte[] b, int o, int v) {
        ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }
}
