package com.webssh.bot;

/**
 * 聊天机器人提供者接口 — 所有机器人类型的统一抽象。
 * <p>
 * 每种机器人类型（Telegram、QQ 等）需实现此接口。
 * {@link ChatBotManager} 通过此接口统一管理所有机器人的生命周期。
 * </p>
 */
public interface ChatBotProvider {

    /**
     * 机器人类型标识（唯一），如 "telegram"、"qq"。
     * 与 {@link BotSettings#getType()} 对应。
     */
    String getType();

    /** 机器人类型的显示名称，用于 UI 展示 */
    String getDisplayName();

    /**
     * 启动机器人。
     * <p>
     * 实现类应在此方法中完成初始化和连接工作。
     * 若启动失败应抛出异常，由 {@link ChatBotManager} 处理。
     * </p>
     *
     * @param settings 机器人配置
     * @throws Exception 启动失败时抛出
     */
    void start(BotSettings settings) throws Exception;

    /**
     * 停止机器人并释放资源。
     * 实现类应确保此方法幂等——多次调用不应抛异常。
     */
    void stop();

    /** 当前是否处于运行状态 */
    boolean isRunning();

    /** 获取当前运行状态的描述文本，用于 UI 展示 */
    String getStatusMessage();
}
