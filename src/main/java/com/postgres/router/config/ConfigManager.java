package com.postgres.router.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * YAML 配置管理器。
 * 职责：读取 / 写入 config.yml，支持文件变更热加载。
 */
public class ConfigManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ConfigManager.class.getName());

    private final Path configPath;
    private final AtomicReference<RouterConfig> configRef = new AtomicReference<>();
    private final Yaml yamlReader;
    private final Yaml yamlWriter;
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running = true;

    public ConfigManager(Path configPath) {
        this.configPath = configPath.toAbsolutePath().normalize();

        // YAML reader (with custom Constructor for RouterConfig)
        LoaderOptions loaderOptions = new LoaderOptions();
        Constructor constructor = new Constructor(RouterConfig.class, loaderOptions);
        this.yamlReader = new Yaml(constructor);

        // YAML writer (pretty print)
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        dumperOptions.setIndent(2);
        Representer representer = new Representer(dumperOptions);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        this.yamlWriter = new Yaml(representer, dumperOptions);
    }

    // ======================== 读取 ========================

    /** 从 YAML 文件加载配置 */
    public RouterConfig load() throws IOException {
        if (!Files.exists(configPath)) {
            LOG.warning("配置文件不存在: " + configPath + "，返回空配置");
            RouterConfig empty = new RouterConfig(java.util.Collections.emptyList(), null);
            configRef.set(empty);
            return empty;
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            RouterConfig config = yamlReader.loadAs(in, RouterConfig.class);
            if (config == null) {
                config = new RouterConfig(java.util.Collections.emptyList(), null);
            }
            if (config.getDatabases() == null) {
                config.setDatabases(java.util.Collections.emptyList());
            }
            configRef.set(config);
            LOG.info("已加载配置，共 " + config.getDatabases().size() + " 个数据库配置");
            return config;
        }
    }

    /** 获取当前内存中的配置快照 */
    public RouterConfig current() {
        return configRef.get();
    }

    /** 获取当前活跃的 DatabaseConfig */
    public DatabaseConfig activeDatabase() {
        RouterConfig cfg = configRef.get();
        return cfg != null ? cfg.activeConfig() : null;
    }

    // ======================== 写入 ========================

    /** 将配置写入 YAML 文件 */
    public synchronized void save(RouterConfig config) throws IOException {
        String yaml = yamlWriter.dumpAsMap(config);
        Files.writeString(configPath, yaml, StandardCharsets.UTF_8);
        configRef.set(config);
        LOG.info("配置已保存");
    }

    // ======================== 热加载 ========================

    /** 启动文件变更监听（WatchService） */
    public void startWatching(Consumer<RouterConfig> onChange) throws IOException {
        Path dir = configPath.getParent();
        if (dir == null) dir = Paths.get(".");

        watchService = FileSystems.getDefault().newWatchService();
        dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

        watchThread = new Thread(() -> {
            while (running) {
                try {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed.equals(configPath.getFileName())) {
                            LOG.info("检测到配置文件变更，重新加载...");
                            RouterConfig newConfig = load();
                            if (onChange != null) {
                                onChange.accept(newConfig);
                            }
                        }
                    }
                    key.reset();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "配置文件重载失败", e);
                }
            }
        }, "config-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    @Override
    public void close() {
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
