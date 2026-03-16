package com.webssh.bot;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.webssh.config.SshCompatibilityProperties;
import com.webssh.session.SessionProfileStore;
import com.webssh.session.SshSessionProfile;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Bot 侧 SSH 会话管理器，所有机器人类型共享。
 * <p>
 * 每个聊天用户（通过 {@code botType:userId} 唯一标识）维护一个 SSH 连接。
 * 复用 {@link SessionProfileStore} 读取已保存的会话配置（含解密后的凭据），
 * 通过 JSch 建立 SSH 连接并开启 Shell 通道。
 * </p>
 */
@Service
public class BotSshSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BotSshSessionManager.class);

    /** Telegram 单条消息最大字符数 */
    private static final int MAX_MESSAGE_LENGTH = 4000;

    /** Shell 输出读取缓冲区大小 */
    private static final int OUTPUT_BUFFER_SIZE = 8192;

    /** 命令执行后等待输出的初始延迟（毫秒） */
    private static final long OUTPUT_INITIAL_DELAY_MS = 500;

    /** 无新输出后的最大等待时间（毫秒） */
    private static final long OUTPUT_IDLE_TIMEOUT_MS = 3000;

    private final SessionProfileStore profileStore;
    private final SshCompatibilityProperties sshProperties;
    private final ConcurrentMap<String, SshConnection> connections = new ConcurrentHashMap<>();
    private final ExecutorService outputExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bot-ssh-output");
        t.setDaemon(true);
        return t;
    });

    /** SSH 连接状态封装 */
    public static class SshConnection {
        private final Session session;
        private final ChannelShell channel;
        private final OutputStream inputWriter;
        private final InputStream outputReader;
        private final String profileName;
        private volatile boolean closed = false;

        SshConnection(Session session, ChannelShell channel, OutputStream inputWriter,
                      InputStream outputReader, String profileName) {
            this.session = session;
            this.channel = channel;
            this.inputWriter = inputWriter;
            this.outputReader = outputReader;
            this.profileName = profileName;
        }

        public String getProfileName() {
            return profileName;
        }

        public boolean isConnected() {
            return !closed && session.isConnected() && channel.isConnected();
        }

        /** 发送命令到 Shell（自动追加换行符） */
        public void sendCommand(String command) throws IOException {
            if (!isConnected()) {
                throw new IOException("SSH 连接已断开");
            }
            inputWriter.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            inputWriter.flush();
        }

        /** 读取当前可用的 Shell 输出 */
        public String readAvailableOutput(long initialDelayMs, long idleTimeoutMs) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[OUTPUT_BUFFER_SIZE];
            try {
                // 等待命令开始输出
                Thread.sleep(initialDelayMs);

                long lastReadTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - lastReadTime < idleTimeoutMs) {
                    int available = outputReader.available();
                    if (available > 0) {
                        int read = outputReader.read(buf, 0, Math.min(available, buf.length));
                        if (read > 0) {
                            sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
                            lastReadTime = System.currentTimeMillis();
                            // 防止输出过长
                            if (sb.length() > MAX_MESSAGE_LENGTH * 3) {
                                break;
                            }
                        }
                    } else {
                        Thread.sleep(100);
                    }
                }
            } catch (IOException | InterruptedException e) {
                log.debug("读取 SSH 输出异常: {}", e.getMessage());
            }
            return sb.toString();
        }

        public void close() {
            closed = true;
            try { inputWriter.close(); } catch (Exception ignored) {}
            try { channel.disconnect(); } catch (Exception ignored) {}
            try { session.disconnect(); } catch (Exception ignored) {}
        }
    }

    public BotSshSessionManager(SessionProfileStore profileStore,
                                SshCompatibilityProperties sshProperties) {
        this.profileStore = profileStore;
        this.sshProperties = sshProperties;
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(SshConnection::close);
        outputExecutor.shutdownNow();
    }

    /** 生成连接的唯一标识 */
    private String connectionKey(String botType, String userId) {
        return botType + ":" + userId;
    }

    /** 列出指定用户的 SSH 会话配置 */
    public List<SshSessionProfile> listProfiles(String sshUsername) {
        return profileStore.list(sshUsername);
    }

    /** 获取指定用户的当前连接 */
    public SshConnection getConnection(String botType, String userId) {
        String key = connectionKey(botType, userId);
        SshConnection conn = connections.get(key);
        if (conn != null && !conn.isConnected()) {
            connections.remove(key);
            conn.close();
            return null;
        }
        return conn;
    }

    /** 断开指定用户的 SSH 连接 */
    public void disconnect(String botType, String userId) {
        String key = connectionKey(botType, userId);
        SshConnection conn = connections.remove(key);
        if (conn != null) {
            conn.close();
        }
    }

    /** 断开指定机器人类型的所有连接 */
    public void disconnectAll(String botType) {
        List<String> toRemove = new ArrayList<>();
        connections.forEach((key, conn) -> {
            if (key.startsWith(botType + ":")) {
                toRemove.add(key);
                conn.close();
            }
        });
        toRemove.forEach(connections::remove);
    }

    /**
     * 连接到指定 SSH 会话（通过名称或序号匹配）。
     *
     * @param botType     机器人类型
     * @param userId      聊天用户 ID
     * @param sshUsername  WebSSH 用户名
     * @param target      会话名称或序号（1-based）
     * @return 连接成功后的描述信息
     */
    public String connect(String botType, String userId, String sshUsername, String target) throws Exception {
        // 先断开已有连接
        disconnect(botType, userId);

        List<SshSessionProfile> profiles = profileStore.list(sshUsername);
        if (profiles.isEmpty()) {
            throw new IllegalStateException("没有已保存的 SSH 会话配置");
        }

        // 匹配目标会话：优先名称精确匹配，其次序号
        SshSessionProfile matched = null;
        try {
            int index = Integer.parseInt(target.trim()) - 1;
            if (index >= 0 && index < profiles.size()) {
                matched = profiles.get(index);
            }
        } catch (NumberFormatException ignored) {
        }
        if (matched == null) {
            for (SshSessionProfile p : profiles) {
                if (target.trim().equalsIgnoreCase(p.getName())) {
                    matched = p;
                    break;
                }
            }
        }
        if (matched == null) {
            throw new IllegalArgumentException("未找到匹配的会话: " + target);
        }

        // 获取完整凭据
        SshSessionProfile detail = profileStore.get(sshUsername, matched.getId());
        if (detail == null) {
            throw new IllegalStateException("无法获取会话详情: " + matched.getName());
        }

        // 建立 SSH 连接
        SshConnection conn = openSshConnection(detail);
        connections.put(connectionKey(botType, userId), conn);

        return String.format("✅ 已连接到 %s (%s@%s:%d)",
                detail.getName(), detail.getUsername(), detail.getHost(), detail.getPort());
    }

    /**
     * 执行命令并返回输出。
     *
     * @param botType  机器人类型
     * @param userId   聊天用户 ID
     * @param command  Shell 命令
     * @param callback 输出回调，可能被多次调用（分批发送）
     */
    public void executeCommand(String botType, String userId, String command, Consumer<String> callback) {
        SshConnection conn = getConnection(botType, userId);
        if (conn == null) {
            callback.accept("❌ 未连接 SSH。请先使用 /connect 连接。");
            return;
        }

        try {
            conn.sendCommand(command);
        } catch (IOException e) {
            disconnect(botType, userId);
            callback.accept("❌ 发送命令失败: " + e.getMessage());
            return;
        }

        // 异步读取输出
        outputExecutor.submit(() -> {
            try {
                String output = conn.readAvailableOutput(OUTPUT_INITIAL_DELAY_MS, OUTPUT_IDLE_TIMEOUT_MS);
                if (output.isEmpty()) {
                    callback.accept("(无输出)");
                    return;
                }
                // 清理 ANSI 转义序列
                output = stripAnsiCodes(output);
                // 分批发送（Telegram 消息长度限制）
                sendInChunks(output, callback);
            } catch (Exception e) {
                log.error("执行命令异常: {}", e.getMessage(), e);
                callback.accept("❌ 读取输出失败: " + e.getMessage());
            }
        });
    }

    /** 建立 SSH 连接并打开 Shell 通道 */
    private SshConnection openSshConnection(SshSessionProfile profile) throws Exception {
        JSch jsch = new JSch();

        if ("PRIVATE_KEY".equalsIgnoreCase(profile.getAuthType())) {
            byte[] passBytes = profile.getPassphrase() != null && !profile.getPassphrase().isBlank()
                    ? profile.getPassphrase().getBytes(StandardCharsets.UTF_8)
                    : null;
            jsch.addIdentity(
                    "bot-ssh-key-" + UUID.randomUUID(),
                    profile.getPrivateKey().getBytes(StandardCharsets.UTF_8),
                    null,
                    passBytes);
        }

        Session sshSession = null;
        try {
            sshSession = jsch.getSession(profile.getUsername(), profile.getHost(), profile.getPort());
            if ("PASSWORD".equalsIgnoreCase(profile.getAuthType())) {
                sshSession.setPassword(profile.getPassword());
            }

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            config.put("PreferredAuthentications",
                    "PASSWORD".equalsIgnoreCase(profile.getAuthType()) ? "password" : "publickey");
            if (sshProperties.isAllowLegacySshRsa()) {
                String hostKeyAlgos = sshSession.getConfig("server_host_key");
                if (hostKeyAlgos != null && !hostKeyAlgos.contains("ssh-rsa")) {
                    config.put("server_host_key", hostKeyAlgos + ",ssh-rsa");
                }
                String pubKeyAlgos = sshSession.getConfig("PubkeyAcceptedAlgorithms");
                if (pubKeyAlgos != null && !pubKeyAlgos.contains("ssh-rsa")) {
                    config.put("PubkeyAcceptedAlgorithms", pubKeyAlgos + ",ssh-rsa");
                }
            }
            sshSession.setConfig(config);

            int keepAliveMs = sshProperties.getServerAliveIntervalMs();
            if (keepAliveMs > 0) {
                sshSession.setServerAliveInterval(keepAliveMs);
            }
            int keepAliveMax = sshProperties.getServerAliveCountMax();
            if (keepAliveMax > 0) {
                sshSession.setServerAliveCountMax(keepAliveMax);
            }

            sshSession.connect(10_000);

            ChannelShell channel = (ChannelShell) sshSession.openChannel("shell");
            channel.setPtyType("dumb");
            channel.setPtySize(200, 50, 0, 0);
            InputStream outputReader = channel.getInputStream();
            OutputStream inputWriter = channel.getOutputStream();
            channel.connect(5_000);

            // 消费初始登录输出（MOTD 等）
            SshConnection conn = new SshConnection(sshSession, channel, inputWriter, outputReader, profile.getName());
            conn.readAvailableOutput(1000, 2000);

            return conn;
        } catch (Exception e) {
            if (sshSession != null) {
                try { sshSession.disconnect(); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    /** 去除 ANSI 转义序列 */
    private String stripAnsiCodes(String text) {
        return text.replaceAll("\\x1b\\[[0-9;]*[a-zA-Z]", "")
                   .replaceAll("\\x1b\\][^\\x07]*\\x07", "")
                   .replaceAll("\\x1b\\[\\?[0-9;]*[a-zA-Z]", "")
                   .replaceAll("[\\x00-\\x08\\x0e-\\x1f]", "");
    }

    /** 将长文本分批发送 */
    private void sendInChunks(String text, Consumer<String> callback) {
        if (text.length() <= MAX_MESSAGE_LENGTH) {
            callback.accept(text);
            return;
        }
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + MAX_MESSAGE_LENGTH, text.length());
            // 尝试在换行处截断
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > offset) {
                    end = lastNewline + 1;
                }
            }
            callback.accept(text.substring(offset, end));
            offset = end;
        }
    }
}
