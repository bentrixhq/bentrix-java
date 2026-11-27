// The C ABI (crates/bentrix-ffi/include/bentrix.h) through Java's Foreign Function & Memory API (final
// in Java 22): no JNI, no generated glue, no dependency. The library is found on java.library.path
// (-Djava.library.path=<folder holding libbentrix.so / libbentrix.dll>), or at the file
// BENTRIX_FFI names, or in the folder BENTRIX_FFI_DIR names, or — on Windows — in Bentrix's own folder
// (%LOCALAPPDATA%\Programs\BENTRIX, where the installer puts it); the JVM is run with
// --enable-native-access=ALL-UNNAMED.
package bentrix;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

final class Native {
    private Native() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = lookup();

    private static SymbolLookup lookup() {
        String file = System.getenv("BENTRIX_FFI");
        if (file != null && !file.isEmpty()) {
            return SymbolLookup.libraryLookup(Path.of(file), Arena.global());
        }
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String name = windows ? "libbentrix.dll" : System.getProperty("os.name").startsWith("Mac") ? "libbentrix.dylib" : "libbentrix.so";
        String dir = System.getenv("BENTRIX_FFI_DIR");
        if (dir != null && !dir.isEmpty() && Path.of(dir, name).toFile().isFile()) {
            return SymbolLookup.libraryLookup(Path.of(dir, name), Arena.global());
        }
        String local = System.getenv("LOCALAPPDATA");
        if (windows && local != null && !local.isEmpty() && Path.of(local, "Programs", "BENTRIX", name).toFile().isFile()) {
            return SymbolLookup.libraryLookup(Path.of(local, "Programs", "BENTRIX", name), Arena.global());
        }
        // `libbentrix` on every platform: Java adds `lib` on Linux and macOS and nothing on Windows.
        System.loadLibrary(System.getProperty("os.name").startsWith("Windows") ? "libbentrix" : "bentrix");
        return SymbolLookup.loaderLookup();
    }

    /** **The engine's interface this package speaks** ({@code bx_abi}): a
     * library of another one is refused before {@code bx_open}, in a sentence naming both. */
    static final int ABI = 2;

    /** The interface the library speaks: its {@code bx_abi()}, or 1 for a library from before it (0.1.382). */
    static int abiOfTheLibrary() {
        var at = LIB.find("bx_abi");
        if (at.isEmpty()) {
            return 1;
        }
        try {
            return (int) LINKER.downcallHandle(at.get(), FunctionDescriptor.of(JAVA_INT)).invoke();
        } catch (Throwable t) {
            throw new BentrixException(t.toString());
        }
    }

    /** Refused, in words, unless the library speaks {@link #ABI}. */
    static void refuseAnotherInterface() {
        int have = abiOfTheLibrary();
        if (have != ABI) {
            String built;
            try {
                built = string((MemorySegment) VERSION.invoke());
            } catch (Throwable t) {
                built = "?";
            }
            String update = have < ABI ? "update Bentrix" : "update this package";
            throw new BentrixException("Bentrix's engine library speaks interface " + have + " (Bentrix " + built + ") and this package speaks " + ABI + ": " + update + ".");
        }
    }

    private static MethodHandle fn(String name, FunctionDescriptor d) {
        return LINKER.downcallHandle(LIB.find(name).orElseThrow(() -> new BentrixException("the library has no " + name)), d);
    }

    // size_t is 64 bits wide on every platform Bentrix builds for.
    static final MethodHandle VERSION = fn("bx_version", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle LAST_ERROR = fn("bx_last_error", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle FREE = fn("bx_free", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle OPEN = fn("bx_open", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle CLOSE = fn("bx_close", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle CALL = fn("bx_call", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle READ = fn("bx_read", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle ARM = fn("bx_arm", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle ADVANCE = fn("bx_advance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    static final MethodHandle SETTLE = fn("bx_settle", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    static final MethodHandle INTERVAL_MS = fn("bx_interval_ms", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle MAKE_TAPE = fn("bx_make_tape", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    /** A C string this library answered, read as UTF-8 (never freed here). */
    static String string(MemorySegment p) {
        return p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Why the last failure on this thread failed. */
    static BentrixException failure() {
        try {
            return new BentrixException(string((MemorySegment) LAST_ERROR.invoke()));
        } catch (Throwable t) {
            return new BentrixException(t.toString());
        }
    }

    /** A C string this library answered, read and freed; a NULL is the last failure. */
    static String take(MemorySegment p) {
        if (p.equals(MemorySegment.NULL)) {
            throw failure();
        }
        try {
            return string(p);
        } finally {
            try {
                FREE.invoke(p);
            } catch (Throwable t) {
                throw new BentrixException(t.toString());
            }
        }
    }

    static MemorySegment cstr(Arena arena, String s) {
        return s == null ? MemorySegment.NULL : arena.allocateFrom(s);
    }
}
