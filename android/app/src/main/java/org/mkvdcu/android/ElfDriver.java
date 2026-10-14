package org.mkvdcu.android;

import java.io.*;
import java.util.*;

/** Checks ABI and DT_NEEDED without executing the imported library. */
final class ElfDriver {
    private static final Set<String> SYSTEM = new HashSet<>(Arrays.asList(
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
        "libnativewindow.so", "libsync.so", "libvulkan.so", "libhardware.so"));
    static void validate(File file, Set<String> bundled) throws IOException {
        List<String> missing = missingDependencies(file, bundled);
        if (!missing.isEmpty()) throw new IOException("Dependências Bionic ausentes no pacote: " + String.join(", ", missing));
    }
    static List<String> missingDependencies(File file, Set<String> bundled) throws IOException {
        Info info = inspect(file, bundled);
        List<String> missing = new ArrayList<>();
        for (String dependency : info.dependencies)
            if (!SYSTEM.contains(dependency) && !bundled.contains(dependency)) missing.add(dependency);
        return missing;
    }
    // Termux also uses Bionic with versioned SONAMEs (e.g. libzstd.so.1).
    static List<String> dependenciesForImport(File file, Set<String> bundled) throws IOException {
        // Read only. prepare() still removes build paths before any execution.
        return new ArrayList<>(inspect(file, bundled, true).dependencies);
    }
    // A suffix alone is not evidence of glibc. Every bundled helper is inspected.
    private static boolean libraryName(String name) {
        return name.matches("[A-Za-z0-9_][A-Za-z0-9_+.-]*\\.so(?:\\.[0-9]+)*");
    }
    private static final class Info {
        final List<String> dependencies = new ArrayList<>();
        String soname;
        long dynamic, dynamicSize;
        boolean externalSearch;
    }
    private static Info inspect(File file, Set<String> bundled) throws IOException {
        return inspect(file, bundled, false);
    }
    private static Info inspect(File file, Set<String> bundled, boolean preparing) throws IOException {
        Info info = new Info();
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 64 || in.readInt() != 0x7f454c46 || in.readByte() != 2 ||
                in.readByte() != 1) throw new IOException("ELF não é ARM64 little-endian: " + file.getName());
            in.seek(16);
            if (u16(in) != 3 || u16(in) != 183) throw new IOException("ELF não é uma biblioteca ARM64");
            in.seek(32); long phoff = u64(in);
            in.seek(54); int phsize = u16(in), phcount = u16(in);
            if (phsize < 56 || phcount == 0 || phcount > 256 || phoff < 64 ||
                    phoff > in.length() || (long)phsize * phcount > in.length() - phoff)
                throw new IOException("Tabela ELF inválida");
            List<long[]> loads = new ArrayList<>(); long dynamic = -1, dynamicSize = 0;
            for (int i = 0; i < phcount; i++) {
                in.seek(phoff + (long)i * phsize); long type = u32(in); u32(in);
                long offset = u64(in), address = u64(in); u64(in); long size = u64(in);
                if (offset < 0 || size < 0 || offset > in.length() || size > in.length() - offset)
                    throw new IOException("Segmento ELF fora do arquivo");
                if (type == 1) loads.add(new long[]{offset, address, size});
                if (type == 3) throw new IOException("ELF contém interpretador de executável; é necessária uma biblioteca Bionic");
                if (type == 2) { dynamic = offset; dynamicSize = size; }
            }
            if (dynamic < 0 || dynamicSize > 1048576) throw new IOException("ELF sem tabela dinâmica válida");
            info.dynamic = dynamic; info.dynamicSize = dynamicSize;
            List<Long> needed = new ArrayList<>(), searchPaths = new ArrayList<>();
            long strings = -1, stringSize = 0, sonameIndex = -1, versionNeeds = -1, versionCount = 0;
            boolean dynamicTerminated = false;
            for (long pos = dynamic; pos + 16 <= dynamic + dynamicSize; pos += 16) {
                in.seek(pos); long tag = u64(in), value = u64(in);
                if (tag == 0) { dynamicTerminated = true; break; }
                if (tag == 1) needed.add(value);
                if (tag == 5) strings = value;
                if (tag == 10) stringSize = value;
                if (tag == 14) sonameIndex = value;
                if (tag == 15 || tag == 29) searchPaths.add(value);
                if (tag == 0x6ffffffeL) versionNeeds = value;
                if (tag == 0x6fffffffL) versionCount = value;
            }
            if (!dynamicTerminated) throw new IOException("Tabela dinâmica ELF sem terminação");
            long stringOffset = -1;
            for (long[] load : loads) if (strings >= 0 && load[1] >= 0 && strings >= load[1] &&
                    strings - load[1] < load[2] && stringSize <= load[2] - (strings - load[1]))
                stringOffset = load[0] + strings - load[1];
            if (stringOffset < 0 || stringSize <= 0 || stringSize > in.length() - stringOffset)
                throw new IOException("Tabela de strings inválida");
            if (versionCount < 0 || versionCount > 256 || (versionCount > 0 && versionNeeds < 0))
                throw new IOException("Tabela de versões ELF inválida");
            long requirement = versionNeeds;
            for (int v = 0; v < versionCount; ++v) {
                in.seek(mappedOffset(loads, requirement, 16));
                int version = u16(in), count = u16(in); u32(in);
                long aux = u32(in), next = u32(in);
                if (version != 1 || count > 256 || (count > 0 && aux < 16))
                    throw new IOException("Requisito de versão ELF inválido");
                long entry = requirement + aux;
                for (int a = 0; a < count; ++a) {
                    in.seek(mappedOffset(loads, entry, 16)); u32(in); u16(in); u16(in);
                    long index = u32(in), step = u32(in);
                    String symbolVersion = string(in, stringOffset, stringSize, index);
                    if (symbolVersion.startsWith("GLIBC_") || symbolVersion.startsWith("GLIBCXX_"))
                        throw new IOException("Versão de símbolos glibc não suportada: " + symbolVersion);
                    if (a + 1 < count && step < 16) throw new IOException("Cadeia de versões ELF inválida");
                    entry += step;
                }
                if (v + 1 < versionCount && next < 16) throw new IOException("Cadeia de requisitos ELF inválida");
                requirement += next;
            }
            if (sonameIndex >= 0) {
                info.soname = string(in, stringOffset, stringSize, sonameIndex);
                if (info.soname.contains("/") || info.soname.contains("\\")) throw new IOException("SONAME inválido");
            }
            // Only the verified flat package directory may be searched. $ORIGIN
            // is supported by Bionic, and is common in multi-library Mesa ZIPs.
            for (long index : searchPaths) {
                String path = string(in, stringOffset, stringSize, index);
                if (!path.isEmpty() && !path.equals("$ORIGIN") && !path.equals("${ORIGIN}")) {
                    info.externalSearch = true;
                    if (!preparing) throw new IOException("RPATH/RUNPATH externo não suportado");
                }
            }
            for (long index : needed) {
                if (index < 0 || index >= stringSize) throw new IOException("Dependência ELF inválida");
                in.seek(stringOffset + index); StringBuilder name = new StringBuilder();
                boolean terminated = false;
                for (int n = 0; n < 256 && index + n < stringSize; n++) {
                    int ch = in.read(); if (ch == 0) { terminated = true; break; }
                    if (ch < 33 || ch > 126) throw new IOException("Nome de dependência inválido");
                    name.append((char)ch);
                }
                String dependency = name.toString();
                if (dependency.contains("/") || dependency.contains("\\") ||
                        dependency.startsWith("ld-linux") ||
                        dependency.matches("lib(c|m|dl|pthread|rt|resolv|util|anl)\\.so\\.[0-9]+") ||
                        !libraryName(dependency))
                    throw new IOException("Dependência não é Android/Bionic: " + dependency);
                if (!terminated) throw new IOException("Dependência ELF sem terminação");
                info.dependencies.add(dependency);
            }
        }
        return info;
    }
    /** Discard build-machine search paths in the private staged copy only.
     * Compact dynamic entries, keeping offsets, instructions, strings, SONAME,
     * dependencies and relocations unchanged. No DT_NULL is inserted early.
     */
    static boolean prepare(File file, Set<String> bundled) throws IOException {
        Info info = inspect(file, bundled, true);
        if (!info.externalSearch) return false;
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            long write = info.dynamic;
            for (long pos = info.dynamic; pos + 16 <= info.dynamic + info.dynamicSize; pos += 16) {
                out.seek(pos); long tag = u64(out), value = u64(out);
                if (tag == 0) break;
                if (tag == 15 || tag == 29) continue;
                out.seek(write); out.writeLong(Long.reverseBytes(tag)); out.writeLong(Long.reverseBytes(value)); write += 16;
            }
            // Clear retired entries and terminate the table at its new end.
            for (; write + 16 <= info.dynamic + info.dynamicSize; write += 16) {
                out.seek(write); out.writeLong(0); out.writeLong(0);
            }
        }
        inspect(file, bundled);
        return true;
    }
    /** Preload only reachable packaged helpers, in dependency order; never OEM libraries. */
    static String[] loadOrder(File directory, String library) throws IOException {
        File[] files = directory.listFiles();
        Set<String> names = new HashSet<>();
        if (files != null) for (File file : files) if (file.isFile()) names.add(file.getName());
        List<String> ordered = new ArrayList<>();
        visit(directory, library, library, names, new HashSet<>(), new HashSet<>(), ordered);
        return ordered.toArray(new String[0]);
    }
    private static void visit(File directory, String name, String library, Set<String> names,
                              Set<String> visiting, Set<String> done, List<String> ordered) throws IOException {
        if (done.contains(name)) return;
        if (!libraryName(name) || !names.contains(name))
            throw new IOException("Biblioteca de dependência inválida: " + name);
        if (!visiting.add(name)) throw new IOException("Dependências cíclicas não suportadas: " + name);
        Info info = inspect(new File(directory, name), names);
        validate(new File(directory, name), names);
        if (!name.equals(library) && (info.soname == null || !info.soname.equals(name)))
            throw new IOException("SONAME da dependência deve corresponder ao arquivo: " + name);
        for (String dependency : info.dependencies) {
            if (SYSTEM.contains(dependency)) {
                if (names.contains(dependency)) throw new IOException("Pacote não pode substituir biblioteca Android: " + dependency);
            } else visit(directory, dependency, library, names, visiting, done, ordered);
        }
        visiting.remove(name); done.add(name);
        if (!name.equals(library)) ordered.add(name);
    }
    private static String string(RandomAccessFile in, long offset, long size, long index) throws IOException {
        if (index < 0 || index >= size) throw new IOException("Índice de string ELF inválido");
        in.seek(offset + index);
        StringBuilder value = new StringBuilder();
        for (int n = 0; n < 256 && index + n < size; n++) {
            int ch = in.read();
            if (ch == 0) return value.toString();
            if (ch < 32 || ch > 126) throw new IOException("String ELF inválida");
            value.append((char) ch);
        }
        throw new IOException("String ELF sem terminação");
    }
    private static long mappedOffset(List<long[]> loads, long address, long bytes) throws IOException {
        for (long[] load : loads) if (address >= 0 && load[1] >= 0 && address >= load[1] &&
                address - load[1] <= load[2] && bytes <= load[2] - (address - load[1]))
            return load[0] + address - load[1];
        throw new IOException("Endereço de versão ELF fora de segmento carregável");
    }
    private static int u16(RandomAccessFile in) throws IOException { return in.readUnsignedByte() | in.readUnsignedByte() << 8; }
    private static long u32(RandomAccessFile in) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt())); }
    private static long u64(RandomAccessFile in) throws IOException { return Long.reverseBytes(in.readLong()); }
}
