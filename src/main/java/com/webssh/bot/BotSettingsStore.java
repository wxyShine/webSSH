package com.webssh.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 机器人配置持久化服务。
 * <p>
 * 将所有机器人配置以 JSON 数组形式存储在 {@code data/bot-settings/settings.json} 文件中。
 * 支持多个机器人并存（如同时配置 Telegram 和 QQ），各机器人通过 {@code type} 字段区分。
 * </p>
 */
@Service
public class BotSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(BotSettingsStore.class);
    private static final TypeReference<List<BotSettings>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path settingsFile;
    private final Object lock = new Object();

    public BotSettingsStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.settingsFile = Paths.get("./data/bot-settings/settings.json").toAbsolutePath().normalize();
    }

    /** 列出所有机器人配置 */
    public List<BotSettings> listAll() {
        synchronized (lock) {
            return new ArrayList<>(readAll());
        }
    }

    /** 获取指定类型的机器人配置，不存在则返回 null */
    public BotSettings get(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        synchronized (lock) {
            return findByType(readAll(), type);
        }
    }

    /** 保存或更新指定类型的机器人配置 */
    public BotSettings save(BotSettings settings) {
        if (settings == null || settings.getType() == null || settings.getType().isBlank()) {
            throw new IllegalArgumentException("机器人类型不能为空");
        }
        synchronized (lock) {
            List<BotSettings> all = readAll();
            int index = findIndexByType(all, settings.getType());
            if (index >= 0) {
                all.set(index, settings);
            } else {
                all.add(settings);
            }
            writeAll(all);
            return settings;
        }
    }

    /** 删除指定类型的机器人配置 */
    public boolean delete(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        synchronized (lock) {
            List<BotSettings> all = readAll();
            boolean removed = all.removeIf(s -> type.equals(s.getType()));
            if (removed) {
                writeAll(all);
            }
            return removed;
        }
    }

    private List<BotSettings> readAll() {
        if (!Files.exists(settingsFile)) {
            return new ArrayList<>();
        }
        try {
            List<BotSettings> list = objectMapper.readValue(settingsFile.toFile(), LIST_TYPE);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (IOException e) {
            log.error("读取机器人配置失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    private void writeAll(List<BotSettings> list) {
        try {
            Files.createDirectories(settingsFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), list);
        } catch (IOException e) {
            throw new IllegalStateException("保存机器人配置失败: " + e.getMessage(), e);
        }
    }

    private BotSettings findByType(List<BotSettings> list, String type) {
        for (BotSettings s : list) {
            if (type.equals(s.getType())) {
                return s;
            }
        }
        return null;
    }

    private int findIndexByType(List<BotSettings> list, String type) {
        for (int i = 0; i < list.size(); i++) {
            if (type.equals(list.get(i).getType())) {
                return i;
            }
        }
        return -1;
    }
}
