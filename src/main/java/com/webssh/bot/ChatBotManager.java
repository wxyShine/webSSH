package com.webssh.bot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人统一管理器。
 * <p>
 * 负责注册所有 {@link ChatBotProvider}，并在应用启动时根据持久化配置自动启动已启用的机器人。
 * 提供统一的启动、停止、重启、状态查询接口，供 {@link BotSettingsController} 调用。
 * </p>
 */
@Service
public class ChatBotManager {

    private static final Logger log = LoggerFactory.getLogger(ChatBotManager.class);

    private final BotSettingsStore settingsStore;
    /** 已注册的提供者，key 为 type */
    private final Map<String, ChatBotProvider> providers = new ConcurrentHashMap<>();

    public ChatBotManager(BotSettingsStore settingsStore, List<ChatBotProvider> providerList) {
        this.settingsStore = settingsStore;
        for (ChatBotProvider provider : providerList) {
            providers.put(provider.getType(), provider);
        }
    }

    /** 应用启动后自动拉起已启用的机器人 */
    @PostConstruct
    public void init() {
        List<BotSettings> allSettings = settingsStore.listAll();
        for (BotSettings settings : allSettings) {
            if (settings.isEnabled()) {
                ChatBotProvider provider = providers.get(settings.getType());
                if (provider != null) {
                    try {
                        provider.start(settings);
                        log.info("机器人 [{}] 已自动启动", settings.getType());
                    } catch (Exception e) {
                        log.error("机器人 [{}] 自动启动失败: {}", settings.getType(), e.getMessage(), e);
                    }
                }
            }
        }
    }

    /** 应用关闭时停止所有机器人 */
    @PreDestroy
    public void shutdown() {
        providers.values().forEach(provider -> {
            try {
                if (provider.isRunning()) {
                    provider.stop();
                }
            } catch (Exception e) {
                log.debug("停止机器人 [{}] 异常: {}", provider.getType(), e.getMessage());
            }
        });
    }

    /** 获取所有已注册的机器人类型信息 */
    public List<Map<String, Object>> listProviders() {
        return providers.values().stream().map(p -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", p.getType());
            info.put("displayName", p.getDisplayName());
            info.put("running", p.isRunning());
            info.put("statusMessage", p.getStatusMessage());
            return info;
        }).toList();
    }

    /** 启动指定类型的机器人 */
    public void startBot(String type) throws Exception {
        ChatBotProvider provider = getProvider(type);
        BotSettings settings = settingsStore.get(type);
        if (settings == null) {
            throw new IllegalStateException("未找到类型为 [" + type + "] 的机器人配置");
        }
        if (provider.isRunning()) {
            provider.stop();
        }
        provider.start(settings);
    }

    /** 停止指定类型的机器人 */
    public void stopBot(String type) {
        ChatBotProvider provider = getProvider(type);
        provider.stop();
    }

    /** 重启指定类型的机器人 */
    public void restartBot(String type) throws Exception {
        stopBot(type);
        startBot(type);
    }

    /** 获取指定类型机器人的状态 */
    public Map<String, Object> getStatus(String type) {
        ChatBotProvider provider = getProvider(type);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", type);
        status.put("running", provider.isRunning());
        status.put("statusMessage", provider.getStatusMessage());
        return status;
    }

    /** 配置变更后的处理：如果机器人正在运行则重启，如果禁用则停止 */
    public void onSettingsChanged(BotSettings settings) {
        ChatBotProvider provider = providers.get(settings.getType());
        if (provider == null) {
            return;
        }
        try {
            if (settings.isEnabled()) {
                if (provider.isRunning()) {
                    provider.stop();
                }
                provider.start(settings);
                log.info("机器人 [{}] 配置变更后已重启", settings.getType());
            } else {
                if (provider.isRunning()) {
                    provider.stop();
                    log.info("机器人 [{}] 已停用", settings.getType());
                }
            }
        } catch (Exception e) {
            log.error("机器人 [{}] 配置变更处理失败: {}", settings.getType(), e.getMessage(), e);
        }
    }

    private ChatBotProvider getProvider(String type) {
        ChatBotProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的机器人类型: " + type);
        }
        return provider;
    }
}
