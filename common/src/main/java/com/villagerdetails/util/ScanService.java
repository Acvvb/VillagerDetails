package com.villagerdetails.util;

import com.villagerdetails.VillagerDetails;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 扫包<b>唯一入口</b>：整个模组的「包名 → 类名」只在这里建一次，之后所有注册中心共享同一份结果。
 *
 * <p>为什么要有它：{@code PayloadTypes}、{@code ServerChannels}（5 行目录）、{@code ServerEventManager}、
 * {@code ServerSessionStates}、{@code ClientChannels}、{@code ClientSessionStates}、
 * {@code ClientFeatureScanner} 过去各自递归走一遍目录树 —— 专用服启动要扫 8 次、集成服/开发客户端要扫 11 次，
 * 而扫的是同一批根、同一批类。现在统一改成「走一次、按包分组、按需取用」。
 *
 * <p><b>扫描范围是白名单</b>（{@link #SCAN_PACKAGES}）：只索引「注册单元」实际所在的几个包
 * （会话状态 {@code cache.impl}、服务端事件 {@code event.impl}、网络 {@code network.sync.*}、
 * 客户端 {@code client.network.sync} / {@code client.cache.impl} / {@code client.feature}）。
 *
 * <p>以前是直接走 {@code com.villagerdetails} 整棵树，把 {@code pojo} / {@code handler} / {@code mixin} /
 * {@code command} / {@code rule} 甚至客户端的界面类全部索引进内存 —— 这些目录里没有任何「被扫包注册」的东西，
 * 白白付出遍历 + 建立索引的启动开销，也让「扫包到底扫了什么」完全没法控制。
 * 现在加包只需改 {@link #SCAN_PACKAGES} 一处；注册中心请求白名单以外的包会直接报 ERROR（而不是悄悄扫不到）。
 *
 * <p><b>会连子包一起扫</b>：{@code classNames("a.b")} 拿到 {@code a.b} 与所有 {@code a.b.*} 的类，
 * 客户端那几个注册中心靠这一点省配置（{@code sync.event}、{@code feature.*}、{@code cache.impl}）。
 *
 * <p>内部类 / 匿名类 / {@code package-info} / {@code module-info} 一律跳过（它们不是注册单元）。
 *
 * <p>三路来源合并去重（开发和打包后都能扫到）：
 * <ol>
 *     <li>{@link FabricLoader} 给模组登记的根目录 —— 开发环境指向 {@code build/classes} 输出目录，
 *         打包后指向 mod jar</li>
 *     <li>本类所在的位置 —— 和它同一个输出目录 / 同一个 jar</li>
 *     <li>类加载器的 {@code getResources} —— 兜底处理目录形态的 classpath</li>
 * </ol>
 *
 * <p><b>去重是硬要求</b>：三路来源经常指到同一个目录（同一个输出目录的两种写法），
 * 一个类被扫到两次就会被注册两次（收包器挂两遍、事件回调触发两次）。所以根目录按规范路径去重，
 * 同一个类名在结果里也只会出现一次。
 *
 * <p><b>物理服务端不扫 {@code com.villagerdetails.client}</b>：专用服的类路径上本来就没有客户端类，
 * 这一条只是保险 —— 万一产物里混进了客户端类，也不该在服务端被反射加载
 * （{@code Class.forName(..., false, ...)} 仍会解析父类，碰到 {@code net.minecraft.client.*} 直接就是
 * {@code NoClassDefFoundError}）。
 *
 * <p>扫到的类<b>只交出类名、不在这里 forName</b>：加载动作由各注册中心按自己的包自己做
 * （见 {@link #loadClasses}），这样每家的「类加载失败」仍然记到自己的 {@code Failure} 里，
 * 而且服务端永远不会因为一次全量扫描而把客户端类加载进来。
 */
public final class ScanService {

    private static final Logger log = LogManager.getLogger(ScanService.class);

    private static final String CLASS_SUFFIX = ".class";

    /**
     * 允许被索引的包（含子包）—— <b>白名单，只收「注册单元」所在的包</b>。
     *
     * <p>每一项都对应一个真实存在的注册中心；不在表里的包一律不索引、也不允许查询：
     * <ul>
     *     <li>{@code cache.impl} —— {@code ServerSessionStates}（{@code SessionState}）</li>
     *     <li>{@code event.impl} —— {@code ServerEventManager}（{@code ServerEventRegisterServer}）</li>
     *     <li>{@code network.sync} —— {@code ServerChannels} 的 5 行子包 + {@code PayloadTypes}</li>
     *     <li>{@code client.network.sync} —— {@code ClientChannels}</li>
     *     <li>{@code client.cache.impl} —— {@code ClientSessionStates}</li>
     *     <li>{@code client.feature} —— {@code ClientFeatureScanner}</li>
     * </ul>
     *
     * <p>加一个新的扫包注册中心时，把它的包名加进这里即可；忘了加会立刻在启动日志里看到 ERROR
     * （见 {@link #classNames(String)}），不会是「扫到 0 个、功能静默失效」。
     */
    private static final List<String> SCAN_PACKAGES = List.of(
            // 服务端
            "com.villagerdetails.cache.impl",
            "com.villagerdetails.event.impl",
            "com.villagerdetails.network.sync",
            // 客户端（物理服务端不索引，见 skipClient）
            "com.villagerdetails.client.network.sync",
            "com.villagerdetails.client.cache.impl",
            "com.villagerdetails.client.feature"
    );

    /** 物理客户端的专属子树；专用服不扫 */
    private static final String CLIENT_PACKAGE = "com.villagerdetails.client";

    private static volatile Index index;

    private ScanService() {
    }

    /**
     * 一次扫描的结果：按<b>完整包名</b>分组的类名。
     *
     * <p>只保留取数必需的 {@code byPackage}：扫描耗时 / 根数量这些统计只在
     * {@link #build()} 里打一行日志，没有任何外部读取方（原来的 {@code stats()} 入口已删除）。
     *
     * @param byPackage 完整包名 → 该包下的类名（不含子包；子包各自成 key）
     */
    public record Index(Map<String, List<String>> byPackage) {

        /**
         * 取某个包（含其所有子包）的类名，<b>按类名排序</b>。
         *
         * <p>排序是刻意保证的：{@code ChannelScanner} 一直要求「按类名排序后注册」，
         * 而 {@code PayloadTypes} 过去拿到的是文件系统 / jar 条目顺序（不同机器可能不同）。
         * 现在统一由这里给出确定顺序，两边的注册顺序都不再依赖磁盘。
         */
        List<String> under(String packageName) {
            List<String> merged = new ArrayList<>();
            List<String> direct = byPackage.get(packageName);
            if (direct != null) {
                merged.addAll(direct);
            }
            String prefix = packageName + ".";
            for (Map.Entry<String, List<String>> entry : byPackage.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    merged.addAll(entry.getValue());
                }
            }
            merged.sort(null);
            return merged;
        }
    }

    // ==============================================================
    // 对外入口
    // ==============================================================

    /**
     * 取某个包（含子包）的类名，不加载。
     *
     * <p><b>只接受白名单内的包</b>：不在 {@link #SCAN_PACKAGES} 覆盖范围内的包名会直接报 ERROR
     * 并返回空列表 —— 这样「新加了注册中心却忘了登记包」会在启动日志里一眼看到，
     * 而不是安静地扫到 0 个、功能悄悄失效。
     *
     * @param packageName 包名，例如 {@code com.villagerdetails.event.impl}
     */
    public static List<String> classNames(String packageName) {
        if (!isCovered(packageName)) {
            log.error("扫包包名不在白名单里：{}；允许的前缀 = {}。"
                            + "请把它的包加进 ScanService.SCAN_PACKAGES，否则这段注册会扫到 0 个类",
                    packageName, SCAN_PACKAGES);
            return List.of();
        }
        return index().under(packageName);
    }

    /**
     * 这个包 / 类名是否被白名单覆盖（自身等于某个前缀，或位于某个前缀之下）。
     */
    private static boolean isCovered(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        for (String pkg : SCAN_PACKAGES) {
            if (packageName.equals(pkg) || packageName.startsWith(pkg + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取某个包（含子包）的类，{@code Class.forName(name, false, loader)} <b>只加载不初始化</b>。
     *
     * <p>和改造前 {@code PackageScanner.scan} 的行为逐条一致：单个类加载失败只记 WARN 并跳过，
     * 不中断其它类；失败同时交给 {@code onLoadFailure} 让调用方汇总成 {@code Failure}。
     *
     * @param anchor        用来取类加载器的锚点类（Fabric 环境下都是同一个 Knot 类加载器）
     * @param onLoadFailure 类加载失败时的回调（类名, 原始异常），可为 {@code null}
     */
    public static List<Class<?>> loadClasses(String packageName, Class<?> anchor,
                                             BiConsumer<String, Throwable> onLoadFailure) {
        List<String> names = classNames(packageName);
        ClassLoader loader = anchor == null ? ScanService.class.getClassLoader() : anchor.getClassLoader();
        List<Class<?>> classes = new ArrayList<>(names.size());
        for (String name : names) {
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (Throwable t) {
                log.warn("扫包 {}：类 {} 加载失败，已跳过", packageName, name, t);
                if (onLoadFailure != null) {
                    onLoadFailure.accept(name, t);
                }
            }
        }
        return classes;
    }

    // ==============================================================
    // 只扫一次
    // ==============================================================

    private static Index index() {
        Index local = index;
        if (local != null) {
            return local;
        }
        synchronized (ScanService.class) {
            if (index == null) {
                index = build();
            }
            return index;
        }
    }

    private static Index build() {
        long start = System.nanoTime();
        // TreeMap：包维度天然有序，日志和调试时好看；Set：同一个类名只允许出现一次
        Map<String, Set<String>> byPackage = new TreeMap<>();
        boolean skipClient = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;

        Set<Path> roots = new LinkedHashSet<>();
        for (Path root : modRoots()) {
            canonical(root).ifPresent(roots::add);
        }
        codeSourceOf(ScanService.class).flatMap(ScanService::canonical).ifPresent(roots::add);

        for (Path root : roots) {
            collectFromRoot(root, byPackage, skipClient);
        }
        int rootCount = roots.size();
        if (collectFromClassLoader(byPackage, skipClient)) {
            rootCount++;
        }

        long millis = (System.nanoTime() - start) / 1_000_000;
        Map<String, List<String>> frozen = new TreeMap<>();
        int classes = 0;
        for (Map.Entry<String, Set<String>> entry : byPackage.entrySet()) {
            frozen.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            classes += entry.getValue().size();
        }
        Index built = new Index(frozen);
        log.info("扫包完成：一次扫描 {} 个类 / {} 个包 / {} 个根（白名单 {} 项，子包已含），耗时 {} ms{}",
                classes, frozen.size(), rootCount, SCAN_PACKAGES.size(), millis,
                skipClient ? "，物理服务端已跳过 client 专属包" : "");
        if (classes == 0) {
            log.error("扫包结果为空：白名单 {} 一个类都没扫到 —— 检查产物里有没有把源码包打进去", SCAN_PACKAGES);
        }
        log.debug("扫包根：{}", roots);
        return built;
    }

    /**
     * 把路径归一成可用于去重的规范形式。
     *
     * <p>必须做这一步：Fabric 给的根目录和 {@code CodeSource} 给的可能是同一个目录的两种写法
     * （相对 / 绝对、分隔符不同、含 {@code ..}），{@link Path#equals} 认不出来，
     * 于是同一棵树被走两遍、每个类被登记两次。
     */
    private static Optional<Path> canonical(Path root) {
        if (root == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(root.toRealPath());
        } catch (Throwable ignored) {
            // 路径可能还不存在（例如被裁剪掉的资源目录），退回到绝对化 + 规范化
            try {
                return Optional.of(root.toAbsolutePath().normalize());
            } catch (Throwable t) {
                return Optional.empty();
            }
        }
    }

    // ==============================================================
    // 三路来源
    // ==============================================================

    /**
     * 第一路：Fabric 登记的模组根目录
     */
    private static Set<Path> modRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        try {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(VillagerDetails.MOD_ID);
            if (container.isEmpty()) {
                // 正常不会发生；专用服 / 开发环境都注册了这个 id
                log.debug("模组容器 {} 不存在，退回到位置探测", VillagerDetails.MOD_ID);
                return roots;
            }
            for (Path root : container.get().getRootPaths()) {
                if (root != null) {
                    roots.add(root);
                }
            }
        } catch (Throwable t) {
            log.debug("枚举模组根目录失败：{}", t.toString());
        }
        return roots;
    }

    /**
     * 第二路：某个类所在的位置（开发环境是输出目录，打包后是 jar）
     */
    private static Optional<Path> codeSourceOf(Class<?> anchor) {
        try {
            var source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return Optional.empty();
            }
            URL location = source.getLocation();
            if (!"file".equals(location.getProtocol())) {
                return Optional.empty();
            }
            return Optional.of(Path.of(location.toURI()));
        } catch (Throwable t) {
            log.debug("探测 {} 的位置失败：{}", anchor.getName(), t.toString());
            return Optional.empty();
        }
    }

    /**
     * 第三路：类加载器的资源查找（兜底）。
     *
     * <p>按白名单逐个包 {@code getResources(packagePath)}：只走注册单元所在的目录，
     * 不再从 {@code com/villagerdetails} 这一层整棵树开走。
     *
     * @return 是否真的从这里找到了根
     */
    private static boolean collectFromClassLoader(Map<String, Set<String>> out, boolean skipClient) {
        boolean found = false;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ScanService.class.getClassLoader();
        }
        for (String packageName : SCAN_PACKAGES) {
            if (skipClient && packageName.startsWith(CLIENT_PACKAGE)) {
                continue;
            }
            try {
                Enumeration<URL> urls = loader.getResources(packageName.replace('.', '/'));
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    found = true;
                    switch (url.getProtocol()) {
                        case "file" -> {
                            Path dir = Path.of(url.toURI());
                            if (Files.isDirectory(dir)) {
                                walkDirectory(dir, packageName, out, skipClient);
                            }
                        }
                        case "jar" -> collectFromJarUrl(url, out, skipClient);
                        default -> {
                        }
                    }
                }
            } catch (Throwable t) {
                log.debug("从类加载器扫 {} 失败：{}", packageName, t.toString());
            }
        }
        return found;
    }

    // ==============================================================
    // 具体来源 → 类名
    // ==============================================================

    /**
     * 一个根：可能是目录（开发环境），也可能是 jar 文件（打包后）
     *
     * <p>目录形态逐个白名单包定位子目录再走（<b>不</b>从 {@code com/villagerdetails} 整棵走）；
     * jar 形态只能遍历条目，但只用前缀匹配白名单，其它条目连类名都不解析。
     */
    private static void collectFromRoot(Path root, Map<String, Set<String>> out, boolean skipClient) {
        if (root == null) {
            return;
        }
        try {
            if (Files.isDirectory(root)) {
                for (String packageName : SCAN_PACKAGES) {
                    if (skipClient && packageName.startsWith(CLIENT_PACKAGE)) {
                        continue;
                    }
                    Path dir = root.resolve(packageName.replace('.', '/'));
                    if (Files.isDirectory(dir)) {
                        walkDirectory(dir, packageName, out, skipClient);
                    }
                }
            } else if (Files.isRegularFile(root)) {
                try (JarFile jar = new JarFile(root.toFile())) {
                    collectFromJarEntries(jar, out, skipClient);
                }
            }
        } catch (Throwable t) {
            log.debug("扫描根 {} 失败：{}", root, t.toString());
        }
    }

    /**
     * 走目录。{@code dir} 就是某个白名单包对应的目录（例如 {@code .../cache/impl}），
     * 类名由「包路径 + 相对路径」拼出来。
     */
    private static void walkDirectory(Path dir, String packageName, Map<String, Set<String>> out,
                                      boolean skipClient) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        String packagePath = packageName.replace('.', '/');
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String relative = dir.relativize(file).toString().replace(File.separatorChar, '/');
                addClassName(packagePath + "/" + relative, out, skipClient);
            });
        } catch (Throwable t) {
            log.debug("扫描目录 {} 失败：{}", dir, t.toString());
        }
    }

    /**
     * 走 jar 里以根包路径开头的条目（不依赖 jar 里有没有目录条目）
     */
    private static void collectFromJarUrl(URL url, Map<String, Set<String>> out, boolean skipClient) {
        try {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            try (JarFile jar = connection.getJarFile()) {
                collectFromJarEntries(jar, out, skipClient);
            }
        } catch (Throwable t) {
            log.debug("扫描 jar URL {} 失败：{}", url, t.toString());
        }
    }

    private static void collectFromJarEntries(JarFile jar, Map<String, Set<String>> out, boolean skipClient) {
        List<String> prefixes = new ArrayList<>(SCAN_PACKAGES.size());
        for (String packageName : SCAN_PACKAGES) {
            if (skipClient && packageName.startsWith(CLIENT_PACKAGE)) {
                continue;
            }
            prefixes.add(packageName.replace('.', '/') + "/");
        }
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(CLASS_SUFFIX)) {
                continue;
            }
            // 只认白名单前缀：其它条目不解析类名，省掉对整包 pojo/handler/mixin 的处理
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    addClassName(name, out, skipClient);
                    break;
                }
            }
        }
    }

    /**
     * 条目名 → 类名并按包归档，顺手把不是注册单元的都剔掉。
     */
    private static void addClassName(String entryName, Map<String, Set<String>> out, boolean skipClient) {
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
        String className = name.replace('/', '.');
        // 只认白名单里的包（含子包）
        if (!isCovered(className)) {
            return;
        }
        // 物理服务端上的客户端专属子树直接丢掉
        if (skipClient && className.startsWith(CLIENT_PACKAGE + ".")) {
            return;
        }
        int lastDot = className.lastIndexOf('.');
        String packageName = lastDot < 0 ? "" : className.substring(0, lastDot);
        Set<String> bucket = out.computeIfAbsent(packageName, k -> new LinkedHashSet<>());
        bucket.add(className);
    }
}
