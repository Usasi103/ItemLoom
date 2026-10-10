import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Checks the attributed fork's executable class structure, not legal authorship. */
public final class VerifyForkClasses {
    private static final String ORIGINAL = "org/snakeyaml/engine/";
    private static final String PACKAGED =
            "dev/itemloom/internal/keystone/libs/sparrow/yaml/libs/snakeyaml/engine/";

    private static byte[] normalized(byte[] bytes, String prefix) {
        var writer = new ClassWriter(0);
        var visitor =
                new ClassRemapper(
                        writer,
                        new Remapper() {
                            @Override
                            public String map(String name) {
                                return name.startsWith(prefix)
                                        ? ORIGINAL + name.substring(prefix.length())
                                        : name;
                            }
                        });
        // SKIP_DEBUG also discards reflection-visible MethodParameters. Keep those
        // and all other class structure; omit only source/line/local-variable tables.
        var withoutDebug =
                new ClassVisitor(Opcodes.ASM7, visitor) {
                    @Override
                    public void visitSource(String source, String debug) {}

                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        return new MethodVisitor(
                                Opcodes.ASM7,
                                super.visitMethod(
                                        access, name, descriptor, signature, exceptions)) {
                            @Override
                            public void visitLineNumber(int line, Label start) {}

                            @Override
                            public void visitLocalVariable(
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Label start,
                                    Label end,
                                    int index) {}
                        };
                    }
                };
        new ClassReader(bytes).accept(withoutDebug, 0);
        return writer.toByteArray();
    }

    private static Set<String> classes(ZipFile archive, String prefix) {
        var names = new HashSet<String>();
        archive.stream()
                .filter(
                        entry ->
                                entry.getName().startsWith(prefix)
                                        && entry.getName().endsWith(".class"))
                .forEach(entry -> names.add(ORIGINAL + entry.getName().substring(prefix.length())));
        return names;
    }

    private static void checkArchive(ZipFile archive, boolean plugin) {
        Set<String> names = new HashSet<>();
        archive.stream()
                .forEach(
                        entry -> {
                            String name = entry.getName();
                            if (!names.add(name)) {
                                throw new IllegalStateException("Duplicate ZIP entry: " + name);
                            }
                            if (name.startsWith("META-INF/versions/")
                                    && name.endsWith(".class")
                                    && (name.contains(ORIGINAL) || name.contains(PACKAGED))) {
                                throw new IllegalStateException(
                                        "Unreviewed multi-release fork class: " + name);
                            }
                            if (plugin
                                    && (name.equals("module-info.class")
                                            || name.endsWith("/module-info.class"))) {
                                throw new IllegalStateException(
                                        "Unexpected packaged module descriptor: " + name);
                            }
                        });
    }

    public static void main(String[] args) throws Exception {
        Path rebuilt = Path.of(args[1]);
        int identical = 0;
        try (var fork = new ZipFile(args[0]);
                var plugin = new ZipFile(args[2])) {
            checkArchive(fork, false);
            checkArchive(plugin, true);
            Set<String> expected = classes(fork, ORIGINAL);
            Set<String> actual = classes(plugin, PACKAGED);
            Set<String> compiled = new HashSet<>();
            try (var paths = Files.walk(rebuilt)) {
                paths.filter(path -> path.toString().endsWith(".class"))
                        .forEach(
                                path -> {
                                    String name =
                                            rebuilt.relativize(path).toString().replace('\\', '/');
                                    // Maven adds module metadata; ItemLoom explicitly excludes this
                                    // descriptor.
                                    if (!name.equals("module-info.class")) compiled.add(name);
                                });
            }
            if (expected.size() != Integer.parseInt(args[3])
                    || !expected.equals(actual)
                    || !expected.equals(compiled)) {
                throw new IllegalStateException("Fork/rebuilt/packaged class sets differ");
            }
            for (String name : expected) {
                byte[] original = fork.getInputStream(fork.getEntry(name)).readAllBytes();
                byte[] fresh = Files.readAllBytes(rebuilt.resolve(name));
                String relocated = PACKAGED + name.substring(ORIGINAL.length());
                byte[] packaged = plugin.getInputStream(plugin.getEntry(relocated)).readAllBytes();
                if (Arrays.equals(original, fresh)) identical++;
                byte[] canonical = normalized(original, ORIGINAL);
                if (!Arrays.equals(canonical, normalized(fresh, ORIGINAL))
                        || !Arrays.equals(canonical, normalized(packaged, PACKAGED))) {
                    throw new IllegalStateException(
                            "Fork source or packaged class differs: " + name);
                }
            }
            System.out.println(
                    "{\"runtime_classes\":"
                            + expected.size()
                            + ",\"byte_identical_rebuilt_classes\":"
                            + identical
                            + ",\"normalized_rebuilt_and_packaged_classes\":"
                            + expected.size()
                            + "}");
        }
    }
}
