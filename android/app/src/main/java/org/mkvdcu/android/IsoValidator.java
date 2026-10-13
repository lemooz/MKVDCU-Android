package org.mkvdcu.android;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates that a user-supplied disc image is an Xbox 360 XGD image and that
 * it contains the expected Mortal Kombat vs. DC Universe executable.
 *
 * This mirrors the GDFX parsing done by the ReXGlue runtime
 * (src/filesystem/devices/disc_image_device.cpp): magic "MICROSOFT*XBOX*MEDIA"
 * at sector 32 of the game partition, found at one of several known disc
 * offsets. Directory entries are little-endian.
 */
final class IsoValidator {

    /** The retail executable this Android build was precompiled against. */
    static final String EXPECTED_XEX_SHA256 =
            "2955F2E2BE61EC1948CD2FD3538AD45BEB04772F5EBD5E0FB1BFDE484748E5A7";

    private static final long SECTOR = 2048L;
    private static final long[] LIKELY_GAME_OFFSETS = {
            0x00000000L, 0x0000FB20L, 0x00020600L, 0x02080000L, 0x0FD90000L
    };
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);

    static final class Result {
        final boolean ok;
        final String message;
        final String xexSha256;
        final long gameOffset;
        final long rootSector;
        final long rootSize;
        final int fileCount;
        final long totalBytes;

        private Result(boolean ok, String message, String xexSha256, long gameOffset,
                       long rootSector, long rootSize, int fileCount, long totalBytes) {
            this.ok = ok;
            this.message = message;
            this.xexSha256 = xexSha256;
            this.gameOffset = gameOffset;
            this.rootSector = rootSector;
            this.rootSize = rootSize;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }

        static Result fail(String message) {
            return new Result(false, message, null, -1, -1, -1, 0, 0);
        }

        String summary() {
            long mib = totalBytes / (1024L * 1024L);
            return "XEX: " + (xexSha256 == null ? "?" : xexSha256.substring(0, 16) + "…")
                    + "\nOffset de jogo: 0x" + Long.toHexString(gameOffset)
                    + "\nArquivos: " + fileCount + " (" + mib + " MiB)"
                    + "\nCompatibilidade: OK";
        }
    }

    private static final class Entry {
        final String path;
        final long offset;
        final long size;

        Entry(String path, long offset, long size) {
            this.path = path;
            this.offset = offset;
            this.size = size;
        }
    }

    private IsoValidator() {
    }

    static Result validate(Context ctx, Uri uri) {
        ParcelFileDescriptor pfd = null;
        FileInputStream stream = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) {
                return Result.fail("Não foi possível abrir o arquivo selecionado.");
            }
            stream = new FileInputStream(pfd.getFileDescriptor());
            FileChannel channel = stream.getChannel();
            long imageSize = channel.size();
            if (imageSize < 64L * 1024L) {
                return Result.fail("O arquivo é pequeno demais para ser uma ISO de Xbox 360.");
            }

            long gameOffset = -1;
            for (long candidate : LIKELY_GAME_OFFSETS) {
                long probe = candidate + 32L * SECTOR;
                if (probe + MAGIC.length <= imageSize && hasMagic(channel, probe)) {
                    gameOffset = candidate;
                    break;
                }
            }
            if (gameOffset < 0) {
                return Result.fail("Esta imagem não parece ser uma cópia válida de "
                        + "Mortal Kombat vs. DC Universe para Xbox 360.\n"
                        + "Assinatura Xbox 360 (MICROSOFT*XBOX*MEDIA) não encontrada.");
            }

            long fsPtr = gameOffset + 32L * SECTOR;
            long rootSector = readU32(channel, fsPtr + 20);
            long rootSize = readU32(channel, fsPtr + 24);
            if (rootSize < 13 || rootSize > 32L * 1024L * 1024L) {
                return Result.fail("Sistema de arquivos Xbox 360 inválido nesta imagem.");
            }

            byte[] rootBuffer = read(channel, gameOffset + rootSector * SECTOR, (int) rootSize);
            List<Entry> files = new ArrayList<>();
            walk(channel, gameOffset, rootBuffer, 0, "", files);

            Entry xex = null;
            long total = 0;
            for (Entry e : files) {
                total += e.size;
                String base = e.path.toLowerCase(Locale.US);
                if (base.equals("default.xex") || base.endsWith("\\default.xex")) {
                    xex = e;
                }
            }

            if (xex == null) {
                return Result.fail("A imagem é uma ISO de Xbox 360, mas não contém "
                        + "default.xex na raiz. Esta não parece ser a versão suportada de "
                        + "Mortal Kombat vs. DC Universe.");
            }

            String sha = sha256(channel, xex.offset, xex.size);
            if (!EXPECTED_XEX_SHA256.equalsIgnoreCase(sha)) {
                return Result.fail("Versão incompatível de Mortal Kombat vs. DC Universe.\n"
                        + "XEX esperado: " + EXPECTED_XEX_SHA256.substring(0, 16) + "…\n"
                        + "XEX da ISO:  " + sha.substring(0, 16) + "…\n"
                        + "Utilize a versão retail suportada do Xbox 360.");
            }

            return new Result(true, "OK", sha, gameOffset, rootSector, rootSize,
                    files.size(), total);
        } catch (Exception e) {
            return Result.fail("Falha ao ler a imagem: " + e.getMessage());
        } finally {
            try {
                if (stream != null) stream.close();
            } catch (Exception ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void walk(FileChannel channel, long gameOffset, byte[] buffer, int ordinal,
                             String prefix, List<Entry> out) {
        int p = ordinal * 4;
        if (p + 14 > buffer.length) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        int nodeL = bb.getShort(p) & 0xFFFF;
        int nodeR = bb.getShort(p + 2) & 0xFFFF;
        long sector = bb.getInt(p + 4) & 0xFFFFFFFFL;
        long length = bb.getInt(p + 8) & 0xFFFFFFFFL;
        int attributes = buffer[p + 12] & 0xFF;
        int nameLen = buffer[p + 13] & 0xFF;
        int nameEnd = Math.min(p + 14 + nameLen, buffer.length);
        String name = nameEnd > p + 14
                ? new String(buffer, p + 14, nameEnd - (p + 14), StandardCharsets.US_ASCII)
                : "";

        if (nodeL != 0) {
            walk(channel, gameOffset, buffer, nodeL, prefix, out);
        }

        String path = prefix.isEmpty() ? name : (name.isEmpty() ? prefix : prefix + "\\" + name);
        boolean directory = (attributes & 0x10) != 0;
        if (directory) {
            if (length > 0) {
                try {
                    byte[] child = read(channel, gameOffset + sector * SECTOR, (int) length);
                    walk(channel, gameOffset, child, 0, path, out);
                } catch (Exception ignored) {
                }
            }
        } else if (length > 0 && !name.isEmpty()) {
            out.add(new Entry(path, gameOffset + sector * SECTOR, length));
        }

        if (nodeR != 0) {
            walk(channel, gameOffset, buffer, nodeR, prefix, out);
        }
    }

    private static boolean hasMagic(FileChannel channel, long offset) throws Exception {
        byte[] probe = read(channel, offset, MAGIC.length);
        if (probe.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (probe[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static long readU32(FileChannel channel, long offset) throws Exception {
        byte[] b = read(channel, offset, 4);
        return (b[0] & 0xFFL)
                | ((b[1] & 0xFFL) << 8)
                | ((b[2] & 0xFFL) << 16)
                | ((b[3] & 0xFFL) << 24);
    }

    private static byte[] read(FileChannel channel, long offset, int length) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(length);
        long pos = offset;
        while (bb.hasRemaining()) {
            int n = channel.read(bb, pos);
            if (n < 0) {
                break;
            }
            pos += n;
        }
        byte[] full = new byte[length];
        bb.flip();
        bb.get(full, 0, bb.limit());
        if (bb.limit() < length) {
            byte[] trimmed = new byte[bb.limit()];
            System.arraycopy(full, 0, trimmed, 0, bb.limit());
            return trimmed;
        }
        return full;
    }

    private static String sha256(FileChannel channel, long offset, long length) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteBuffer bb = ByteBuffer.allocate(1024 * 1024);
        long remaining = length;
        long pos = offset;
        while (remaining > 0) {
            bb.clear();
            int want = (int) Math.min(bb.capacity(), remaining);
            bb.limit(want);
            int n = channel.read(bb, pos);
            if (n <= 0) {
                break;
            }
            pos += n;
            remaining -= n;
            bb.flip();
            md.update(bb);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toUpperCase(Locale.US);
    }
}
