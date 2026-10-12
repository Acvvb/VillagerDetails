package com.villagerdetails.util;

import com.villagerdetails.VillagerDetails;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 极简「扫包」工具：把一个 Java 包下面的类全找出来。
 *
 * <p>只找<b>这个包本身</b>的类，子包里的不算；内部类 / 匿名类 / {@code package-info} /
 * {@code module-info} 一律跳过（它们不是注册单元）。
 *
 * <p>三路兜底，开发和打包后都能扫到，结果按类名去重：
 * <ol>
 *     <li>{@link FabricLoader} 给模组登记的根目录 —— 开发环境指向 {@code build/classes} 输出目录，
 *         打包后指向 mod jar（Fabric 已经把它挂成文件系统，直接走就行）</li>
 *     <li>锚点类（{@code anchor}）所在的位置 —— 和它同一个输出目录 / 同一个 jar</li>
 *     <li>类加载器的 {@code getResources} —— 处理目录形态的 classpath</li>
 * </ol>
 *
 * <p>扫不到的类只记日志跳过、不抛异常；但注意<b>一个类都扫不到就等于什么都没注册</b>，
 * 所以调用方要自己盯着返回结果，别默默空转。
 */
public final class PackageScanner {

    private static final Logger LOGGER = LogManager.getLogger(PackageScanner.class);

    private static final String CLASS_SUFFIX = ".class";

    private PackageScanner() {
    }

    /**
     * 同上，但把「扫到了类却加载不起来」这种情况交给调用方。
     *
     * <p>扫包本身永远不抛异常（一个类坏掉不代表别的类也要放弃），但静默跳过会让
     * 「某个包没注册上」变成一个查不出来的问题 —— 所以调用方可以在这里收口，
     * 把这些类汇总成一条告警。
     *
     * @param onLoadFailure 类加载失败时的回调（类名, 原始异常），可为 {@code null}
     */
    public static List<Class<?>> scan(String packageName, Class<?> anchor,
                                      BiConsumer<String, Throwable> onLoadFailure) {
        String path = packageName.replace('.', '/');
        Set<String> names = new LinkedHashSet<>();

        collectFromModRoots(path, names);
        collectFromCodeSource(anchor, path, names);
        collectFromClassLoader(path, names);

        ClassLoader loader = anchor == null ? PackageScanner.class.getClassLoader() : anchor.getClassLoader();
        List<Class<?>> classes = new ArrayList<>(names.size());
        for (String name : names) {
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (Throwable t) {
                LOGGER.warn("扫包 {}：类 {} 加载失败，已跳过", packageName, name, t);
                if (onLoadFailure != null) {
                    onLoadFailure.accept(name, t);
                }
            }
        }
        return classes;
    }

    // ==============================================================
    // 三路来源
    // ==============================================================

    /**
     * 第一路：Fabric 登记的模组根目录
     */
    private static void collectFromModRoots(String path, Set<String> out) {
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(VillagerDetails.MOD_ID);
            if (container.isEmpty()) {
                return;
            }
            for (Path root : container.get().getRootPaths()) {
                collectFromRoot(root, path, out);
            }
        } catch (Throwable t) {
            LOGGER.debug("从模组根目录扫 {} 失败：{}", path, t.toString());
        }
    }

    /**
     * 第二路：锚点类所在的位置
     */
    private static void collectFromCodeSource(Class<?> anchor, String path, Set<String> out) {
        if (anchor == null) {
            return;
        }
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return;
            }
            URL location = source.getLocation();
            if (!"file".equals(location.getProtocol())) {
                return;
            }
            collectFromRoot(Path.of(location.toURI()), path, out);
        } catch (Throwable t) {
            LOGGER.debug("从 {} 的位置扫 {} 失败：{}", anchor.getName(), path, t.toString());
        }
    }

    /**
     * 第三路：类加载器的资源查找（兜底）
     */
    private static void collectFromClassLoader(String path, Set<String> out) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = PackageScanner.class.getClassLoader();
            }
            Enumeration<URL> urls = loader.getResources(path);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                switch (url.getProtocol()) {
                    case "file" -> walkDirectory(Path.of(url.toURI()), path, out);
                    case "jar" -> collectFromJarUrl(url, path, out);
                    default -> {
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("从类加载器扫 {} 失败：{}", path, t.toString());
        }
    }

    // ==============================================================
    // 具体来源 → 类名
    // ==============================================================

    /**
     * 一个根：可能是目录（开发环境），也可能是 jar 文件（打包后）
     */
    private static void collectFromRoot(Path root, String path, Set<String> out) {
        if (root == null) {
            return;
        }
        if (Files.isDirectory(root)) {
            walkDirectory(root.resolve(path), path, out);
        } else if (Files.isRegularFile(root)) {
            collectFromJar(root, path, out);
        }
    }

    /**
     * 走目录。
     *
     * @param dir    包目录本身
     * @param prefix 包对应的路径前缀，用来拼类名
     */
    private static void walkDirectory(Path dir, String prefix, Set<String> out) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String relative = dir.relativize(file).toString().replace(File.separatorChar, '/');
                addClassName(prefix + "/" + relative, out);
            });
        } catch (Throwable t) {
            LOGGER.debug("扫描目录 {} 失败：{}", dir, t.toString());
        }
    }

    /**
     * 走 jar 文件里以包路径开头的条目（不依赖 jar 里有没有目录条目）
     */
    private static void collectFromJar(Path jarPath, String path, Set<String> out) {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            collectFromJarEntries(jar, path, out);
        } catch (Throwable t) {
            LOGGER.debug("扫描 jar {} 失败：{}", jarPath, t.toString());
        }
    }

    /**
     * 同上，来源是 {@code jar:} 协议的 URL
     */
    private static void collectFromJarUrl(URL url, String path, Set<String> out) {
        try {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            try (JarFile jar = connection.getJarFile()) {
                collectFromJarEntries(jar, path, out);
            }
        } catch (Throwable t) {
            LOGGER.debug("扫描 jar URL {} 失败：{}", url, t.toString());
        }
    }

    private static void collectFromJarEntries(JarFile jar, String path, Set<String> out) {
        String prefix = path + "/";
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix)) {
                addClassName(name, out);
            }
        }
    }

    /**
     * 条目名 → 类名，顺手把不是注册单元的都剔掉
     */
    private static void addClassName(String entryName, Set<String> out) {
        if (entryName == null || !entryName.endsWith(CLASS_SUFFIX)) {
            return;
        }
        String name = entryName.substring(0, entryName.length() - CLASS_SUFFIX.length());
        int lastSlash = name.lastIndexOf('/');
        String simpleName = lastSlash < 0 ? name : name.substring(lastSlash + 1);
        // 内部类 / 匿名类：注册单元一律是顶层类
        if (simpleName.indexOf('$') >= 0) {
            return;
        }
        if (simpleName.equals("package-info") || simpleName.equals("module-info")) {
            return;
        }
        out.add(name.replace('/', '.'));
    }
}
