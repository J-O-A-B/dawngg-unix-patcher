package dev.dawnunix.agent;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class DawnUnixAgent {
    private static final String FLOW_CLASS = "gg/dawn/launcher/desktop/hosting/MinecraftLaunchFlow";
    private static final String AGENT_CLASS = "dev/dawnunix/agent/DawnUnixAgent";
    private static final String INSTALLER_CLASS = "gg/dawn/launcher/desktop/hosting/DawnClientInstaller";
    private static final String HASH_MATCHES = "hashMatches";
    private static final String HASH_MATCHES_DESC = "(Lgg/dawn/launcher/desktop/hosting/DawnClientHashedFile;Ljava/nio/file/Path;)Z";
    private static final String CEF_BUILDER_CLASS = "me/friwi/jcefmaven/CefAppBuilder";
    private static final List<String> CEF_GPU_ARGS = List.of(
        "--no-zygote", "--disable-gpu", "--disable-gpu-compositing", "--enable-begin-frame-scheduling",
        "--disable-smooth-scrolling", "--ignore-gpu-blocklist", "--disable-breakpad",
        "--disable-field-trial-config", "--disable-site-isolation-trials", "--disable-gpu-process-crash-limit",
        "--disable-spell-checking", "--disable-extensions", "--disable-pinch",
        "--autoplay-policy=no-user-gesture-required",
        "--disable-features=Translate,MediaRouter,WebBluetooth,WebUSB"
    );
    private static final String FCEF_LIB = "libfcef.so";
    private static final String NATIVE_BASE = "cache/minecraft/cache/dawn-client/libraries/native";
    private static final List<String[]> FEATHER_NATIVES = List.of(
        new String[]{"net/digitalingot/fcef/0.1.1/fcef-0.1.1-natives-linux.jar", "libfcef.so", "feather_web_helper"},
        new String[]{"net/digitalingot/fjni/0.0.2/fjni-0.0.2-natives-linux.jar", "libfjni.so"},
        new String[]{"net/digitalingot/favif/0.0.1/favif-0.0.1-natives-linux.jar", "libfavif.so"},
        new String[]{"net/digitalingot/fwebp/0.0.2/fwebp-0.0.2-natives-linux.jar", "fwebp.so"},
        new String[]{"net/digitalingot/fdiscord/0.0.1/fdiscord-0.0.1-natives-linux.jar", "libfdiscord.so"}
    );
    private static final String CEF_BINARY_JAR = "net/digitalingot/cef_binary/145.0.24+gad514df/cef_binary-145.0.24+gad514df-linux64.jar";
    private static final List<String> CEF_FILES = List.of(
        "libcef.so", "jcef_helper", "libEGL.so", "libGLESv2.so", "libvk_swiftshader.so", "libvulkan.so.1",
        "icudtl.dat", "chrome_100_percent.pak", "chrome_200_percent.pak", "resources.pak",
        "v8_context_snapshot.bin", "vk_swiftshader_icd.json"
    );
    private static final String DISCORD_JAR = "com/discord/discord-game-sdk/3.2.1/discord-game-sdk-3.2.1-natives-linux.jar";
    private static final String DISCORD_NATIVE = "discord_game_sdk.so";
    private static final String DISCORD_NATIVE_LIB = "libdiscord_game_sdk.so";

    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile Path projectRoot;
    private static volatile long lastPatchMillis;
    private static volatile boolean payloadWarningPrinted;

    private DawnUnixAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        projectRoot = projectRoot();
        log("loaded from " + projectRoot);
        instrumentation.addTransformer(new LauncherTransformer());
        patchNow();
    }

    private static final class LauncherTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            try {
                if (FLOW_CLASS.equals(className)) {
                    log("patched " + className);
                    return patchedFlow(classfileBuffer);
                }
                if (INSTALLER_CLASS.equals(className)) {
                    log("patched " + className);
                    return patchedInstaller(classfileBuffer);
                }
                if (CEF_BUILDER_CLASS.equals(className)) {
                    log("patched " + className);
                    return patchedCefBuilder(classfileBuffer);
                }
            } catch (Throwable t) {
                log("transform failed for " + className + ": " + t);
            }
            return null;
        }
    }

    public static void patchNow() {
        long now = System.currentTimeMillis();
        if (now - lastPatchMillis < 500 || !running.compareAndSet(false, true)) {
            return;
        }
        lastPatchMillis = now;
        try {
            Path root = projectRoot != null ? projectRoot : projectRoot();
            Path payloadDir = root.resolve("payload");
            Path payload = payloadDir.resolve(FCEF_LIB);
            if (!Files.isRegularFile(payload)) {
                if (!payloadWarningPrinted) {
                    payloadWarningPrinted = true;
                    log("missing payload/" + FCEF_LIB);
                }
                return;
            }

            Path dawn = dawnHome();
            int changed = 0;

            for (String[] artifact : FEATHER_NATIVES) {
                Path jar = inside(dawn, NATIVE_BASE + "/" + artifact[0]);
                if (Files.isRegularFile(jar) && jarMatchesPayload(jar, payloadDir, artifact)) {
                    continue;
                }
                if (buildJar(jar, payloadDir, artifact, 1)) {
                    changed++;
                    log("seeded " + jar);
                }
            }

            Path cefJar = inside(dawn, NATIVE_BASE + "/" + CEF_BINARY_JAR);
            if (!Files.isRegularFile(cefJar)) {
                Path jcefDir = newestJcefDir(dawn);
                if (jcefDir != null && Files.isRegularFile(jcefDir.resolve("libcef.so"))) {
                    buildCefBinaryJar(cefJar, jcefDir);
                    changed++;
                    log("built " + cefJar + " from " + jcefDir);
                }
            }

            Path discordJar = inside(dawn, NATIVE_BASE + "/" + DISCORD_JAR);
            if (Files.isRegularFile(discordJar)
                    && zipEntry(discordJar, DISCORD_NATIVE_LIB) == null
                    && zipEntry(discordJar, DISCORD_NATIVE) != null) {
                renameZipEntry(discordJar, DISCORD_NATIVE, DISCORD_NATIVE_LIB);
                changed++;
                log("renamed discord native in " + discordJar);
            }

            if (changed > 0) {
                log("provisioning complete changed=" + changed + " clearedNativeDirs=" + clearNativeCache(dawn));
            }
        } catch (Throwable t) {
            log("patch failed: " + t);
        } finally {
            running.set(false);
        }
    }

    private static String versionKey(String name) {
        StringBuilder out = new StringBuilder();
        for (String token : name.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")) {
            out.append(token.chars().allMatch(Character::isDigit) ? String.format("%020d", new java.math.BigInteger(token)) : token);
        }
        return out.toString();
    }

    private static byte[] patchedFlow(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"prepare".equals(name) || !descriptor.startsWith("(Lgg/dawn/launcher/game/GameLaunchRequest;")) {
                    return next;
                }
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_CLASS, "patchNow", "()V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchedInstaller(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (HASH_MATCHES.equals(name) && HASH_MATCHES_DESC.equals(descriptor)) {
                    next.visitCode();
                    next.visitInsn(Opcodes.ICONST_1);
                    next.visitInsn(Opcodes.IRETURN);
                    next.visitMaxs(0, 0);
                    next.visitEnd();
                    return null;
                }
                return next;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchedCefBuilder(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name)) {
                    return next;
                }
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            pushInt(this, CEF_GPU_ARGS.size());
                            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
                            for (int i = 0; i < CEF_GPU_ARGS.size(); i++) {
                                visitInsn(Opcodes.DUP);
                                pushInt(this, i);
                                visitLdcInsn(CEF_GPU_ARGS.get(i));
                                visitInsn(Opcodes.AASTORE);
                            }
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, CEF_BUILDER_CLASS, "addJcefArgs", "([Ljava/lang/String;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        }
    }

    private static boolean jarMatchesPayload(Path jar, Path payloadDir, String[] names) {
        try {
            for (int i = 1; i < names.length; i++) {
                byte[] inJar = zipEntry(jar, names[i]);
                if (inJar == null || !java.util.Arrays.equals(inJar, Files.readAllBytes(payloadDir.resolve(names[i])))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean buildJar(Path target, Path payloadDir, String[] names, int from) throws Exception {
        for (int i = from; i < names.length; i++) {
            if (!Files.isRegularFile(payloadDir.resolve(names[i]))) {
                log("missing payload/" + names[i] + " for " + target.getFileName());
                return false;
            }
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                writeManifest(zip);
                for (int i = from; i < names.length; i++) {
                    zip.putNextEntry(new ZipEntry(names[i]));
                    Files.copy(payloadDir.resolve(names[i]), zip);
                    zip.closeEntry();
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }

    private static void buildCefBinaryJar(Path target, Path jcefDir) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                writeManifest(zip);
                for (String name : CEF_FILES) {
                    Path file = jcefDir.resolve(name);
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(name));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
                Path locales = jcefDir.resolve("locales");
                if (Files.isDirectory(locales)) {
                    zip.putNextEntry(new ZipEntry("locales/"));
                    zip.closeEntry();
                    try (var paths = Files.list(locales)) {
                        for (Path pak : paths.filter(Files::isRegularFile).sorted().toList()) {
                            zip.putNextEntry(new ZipEntry("locales/" + pak.getFileName()));
                            Files.copy(pak, zip);
                            zip.closeEntry();
                        }
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Path newestJcefDir(Path dawn) throws Exception {
        Path base = inside(dawn, "browser/aditude/native-webview/jcef");
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (var paths = Files.list(base)) {
            return paths
                .filter(Files::isDirectory)
                .max(Comparator.comparing(path -> versionKey(path.getFileName().toString())))
                .orElse(null);
        }
    }

    private static void renameZipEntry(Path zipPath, String from, String to) throws Exception {
        Path tmp = Files.createTempFile(zipPath.getParent(), zipPath.getFileName().toString() + ".", ".tmp");
        try {
            try (ZipFile src = new ZipFile(zipPath.toFile()); ZipOutputStream dst = new ZipOutputStream(Files.newOutputStream(tmp))) {
                Enumeration<? extends ZipEntry> entries = src.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry old = entries.nextElement();
                    ZipEntry next = new ZipEntry(old.getName().equals(from) ? to : old.getName());
                    next.setTime(old.getTime());
                    dst.putNextEntry(next);
                    if (!old.isDirectory()) {
                        try (InputStream in = src.getInputStream(old)) {
                            in.transferTo(dst);
                        }
                    }
                    dst.closeEntry();
                }
            }
            Files.move(tmp, zipPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeManifest(ZipOutputStream zip) throws Exception {
        zip.putNextEntry(new ZipEntry("META-INF/"));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        zip.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        zip.closeEntry();
    }

    private static byte[] zipEntry(Path zipPath, String name) throws Exception {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static int clearNativeCache(Path dawn) throws Exception {
        int removed = 0;
        for (String relative : List.of("cache/minecraft/cache/natives", "cache/minecraft/natives")) {
            Path root = inside(dawn, relative);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path lib : paths.filter(path -> path.getFileName().toString().equals(FCEF_LIB)).toList()) {
                    deleteTree(lib.getParent());
                    removed++;
                }
            }
        }
        return removed;
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path dawnHome() {
        return Path.of(System.getenv().getOrDefault("DAWN_HOME", Path.of(System.getProperty("user.home"), ".dawn").toString()));
    }

    private static Path inside(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve(relative).normalize();
        if (!path.startsWith(base)) {
            throw new SecurityException("path escapes Dawn home: " + relative);
        }
        return path;
    }

    private static Path projectRoot() {
        try {
            URI uri = DawnUnixAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path jar = Path.of(uri).toAbsolutePath().normalize();
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            if (dir != null && dir.getFileName() != null && "agent".equals(dir.getFileName().toString())) {
                return dir.getParent();
            }
            return dir != null ? dir : Path.of(".").toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private static void log(String message) {
        System.err.println("[dawn-unix-agent] " + message);
    }
}
