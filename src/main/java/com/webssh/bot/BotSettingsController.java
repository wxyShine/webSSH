package com.webssh.bot;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 机器人配置管理 REST API 控制器。
 * <p>
 * 提供机器人配置的 CRUD 操作和运行状态管理。前端机器人设置面板通过此 API 进行交互。
 * </p>
 */
@RestController
@RequestMapping("/api/bot-settings")
public class BotSettingsController {

    private final BotSettingsStore store;
    private final ChatBotManager manager;

    public BotSettingsController(BotSettingsStore store, ChatBotManager manager) {
        this.store = store;
        this.manager = manager;
    }

    /** 列出所有已注册的机器人类型及其配置 */
    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "providers", manager.listProviders(),
                "settings", store.listAll()
        );
    }

    /** 获取指定类型的机器人配置 */
    @GetMapping("/{type}")
    public ResponseEntity<BotSettings> get(@PathVariable String type) {
        BotSettings settings = store.get(type);
        if (settings == null) {
            // 返回空的默认配置以便前端展示
            BotSettings empty = new BotSettings();
            empty.setType(type);
            return ResponseEntity.ok(empty);
        }
        return ResponseEntity.ok(settings);
    }

    /** 保存或更新机器人配置，自动触发启动/停止 */
    @PostMapping("/{type}")
    public BotSettings save(@PathVariable String type, @RequestBody BotSettings settings) {
        settings.setType(type);
        BotSettings saved = store.save(settings);
        manager.onSettingsChanged(saved);
        return saved;
    }

    /** 删除机器人配置 */
    @DeleteMapping("/{type}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String type) {
        // 先停止运行
        try {
            manager.stopBot(type);
        } catch (Exception ignored) {
        }
        boolean deleted = store.delete(type);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("deleted", false, "message", "配置不存在"));
        }
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /** 获取机器人运行状态 */
    @GetMapping("/{type}/status")
    public Map<String, Object> status(@PathVariable String type) {
        return manager.getStatus(type);
    }

    /** 手动重启机器人 */
    @PostMapping("/{type}/restart")
    public Map<String, Object> restart(@PathVariable String type) {
        try {
            manager.restartBot(type);
            return Map.of("success", true, "message", "重启成功");
        } catch (Exception e) {
            return Map.of("success", false, "message", "重启失败: " + e.getMessage());
        }
    }
}
