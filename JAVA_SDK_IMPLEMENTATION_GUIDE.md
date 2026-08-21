# Java SDK 完整实现框架

## 项目结构

```
dingtalk-channel-sdk-java/
├── src/main/java/com/dingtalk/channel/
│   ├── types/
│   │   ├── RejectReason.java               ✅ 已创建
│   │   ├── DedupConfig.java                ✅ 已创建
│   │   ├── PolicyConfig.java               🔽 见下文
│   │   ├── MediaBatchConfig.java           🔽 见下文
│   │   ├── SafetyConfig.java               🔽 见下文
│   │   ├── IncomingMessage.java            🔽 见下文
│   │   ├── RejectEvent.java                🔽 见下文
│   │   ├── PolicyDecision.java             🔽 见下文
│   │   └── BotIdentity.java                🔽 见下文
│   │
│   └── safety/
│       ├── StaleDetector.java              🔽 见下文
│       ├── SeenCache.java                  🔽 见下文
│       ├── PolicyGate.java                 🔽 见下文
│       ├── ProcessingLock.java             🔽 见下文
│       ├── MediaPipelineManager.java       🔽 见下文
│       └── SafetyPipeline.java             🔽 见下文
│
├── src/test/java/com/dingtalk/channel/safety/
│   ├── StaleDetectorTest.java              🔽 见下文
│   ├── SeenCacheTest.java                  🔽 见下文
│   └── PolicyGateTest.java                 🔽 见下文
│
├── pom.xml                                  🔽 见下文
└── README.md                                🔽 见下文
```

---

## 核心类实现

### 1. PolicyConfig.java
```java
package com.dingtalk.channel.types;

import java.util.*;

public class PolicyConfig {
    private List<String> allowFrom = new ArrayList<>();
    private List<String> denyFrom = new ArrayList<>();
    private List<String> admins = new ArrayList<>();
    
    private String dmMode = "open"; // "open" | "disabled" | "allowlist" | "blocklist"
    private List<String> dmAllowlist = new ArrayList<>();
    private List<String> dmBlocklist = new ArrayList<>();
    
    private List<String> groupAllowlist = new ArrayList<>();
    private List<String> groupBlocklist = new ArrayList<>();
    private Boolean requireMention = true;
    private Boolean respondToMentionAll = false;
    
    private Map<String, GroupOverride> groupOverrides = new HashMap<>();

    // Getters and setters
    public List<String> getAllowFrom() { return allowFrom; }
    public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
    
    public List<String> getDenyFrom() { return denyFrom; }
    public void setDenyFrom(List<String> denyFrom) { this.denyFrom = denyFrom; }
    
    public List<String> getAdmins() { return admins; }
    public void setAdmins(List<String> admins) { this.admins = admins; }
    
    public String getDmMode() { return dmMode; }
    public void setDmMode(String dmMode) { this.dmMode = dmMode; }
    
    public List<String> getGroupAllowlist() { return groupAllowlist; }
    public void setGroupAllowlist(List<String> groupAllowlist) { this.groupAllowlist = groupAllowlist; }
    
    public Boolean getRequireMention() { return requireMention; }
    public void setRequireMention(Boolean requireMention) { this.requireMention = requireMention; }
    
    public Boolean getRespondToMentionAll() { return respondToMentionAll; }
    public void setRespondToMentionAll(Boolean respondToMentionAll) { this.respondToMentionAll = respondToMentionAll; }
    
    public Map<String, GroupOverride> getGroupOverrides() { return groupOverrides; }
    public void setGroupOverrides(Map<String, GroupOverride> groupOverrides) { this.groupOverrides = groupOverrides; }
    
    public static PolicyConfig createDefault() {
        return new PolicyConfig();
    }
}

class GroupOverride {
    private Boolean enabled;
    private Boolean requireMention;
    private Boolean respondToMentionAll;
    private List<String> allowFrom;
    private List<String> blockFrom;
    
    // Getters and setters
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    
    public Boolean getRequireMention() { return requireMention; }
    public void setRequireMention(Boolean requireMention) { this.requireMention = requireMention; }
    
    public List<String> getAllowFrom() { return allowFrom; }
    public void setAllowFrom(List<String> allowFrom) { this.allowFrom = allowFrom; }
}
```

### 2. IncomingMessage.java
```java
package com.dingtalk.channel.types;

import java.util.*;

public class IncomingMessage {
    private String conversationId;
    private String conversationType;
    private String senderId;
    private String senderStaffId;
    private String msgId;
    private String msgType;
    private String text;
    private long createAt;
    
    private boolean isInAtList = false;
    private boolean mentionAll = false;
    private List<Resource> resources = new ArrayList<>();
    private List<IncomingMessage> batchedSources;

    // Getters and setters
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    
    public String getConversationType() { return conversationType; }
    public void setConversationType(String conversationType) { this.conversationType = conversationType; }
    
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    
    public String getSenderStaffId() { return senderStaffId; }
    public void setSenderStaffId(String senderStaffId) { this.senderStaffId = senderStaffId; }
    
    public String getMsgId() { return msgId; }
    public void setMsgId(String msgId) { this.msgId = msgId; }
    
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    
    public long getCreateAt() { return createAt; }
    public void setCreateAt(long createAt) { this.createAt = createAt; }
    
    public boolean isInAtList() { return isInAtList; }
    public void setInAtList(boolean isInAtList) { this.isInAtList = isInAtList; }
    
    public boolean isMentionAll() { return mentionAll; }
    public void setMentionAll(boolean mentionAll) { this.mentionAll = mentionAll; }
    
    public List<Resource> getResources() { return resources; }
    public void setResources(List<Resource> resources) { this.resources = resources; }
}

class Resource {
    private String type;
    private String downloadCode;
    private String fileName;
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getDownloadCode() { return downloadCode; }
    public void setDownloadCode(String downloadCode) { this.downloadCode = downloadCode; }
    
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
}
```

### 3. StaleDetector.java
```java
package com.dingtalk.channel.safety;

import java.time.Duration;

/**
 * 过期消息检测器
 * 对标 Go SDK internal/safety/stale_detector.go
 */
public class StaleDetector {
    private final long staleWindowMs;

    public StaleDetector(Duration staleWindow) {
        this.staleWindowMs = staleWindow.toMillis();
    }

    /**
     * 检测消息是否过期
     *
     * @param createAt 消息创建时间戳（毫秒）
     * @return true 表示消息过期，应拒绝处理
     */
    public boolean isStale(long createAt) {
        if (createAt <= 0) {
            return false; // 无效时间戳，不判定为过期
        }

        long nowMs = System.currentTimeMillis();
        long ageMs = nowMs - createAt;

        return ageMs > staleWindowMs;
    }
}
```

### 4. SeenCache.java
```java
package com.dingtalk.channel.safety;

import com.dingtalk.channel.types.DedupConfig;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/**
 * 增强版去重缓存（使用 LinkedHashMap LRU）
 * 对标 Go SDK internal/safety/seen_cache.go
 */
public class SeenCache {
    private final DedupConfig config;
    private final Map<String, Long> cache;
    private final ScheduledExecutorService sweepExecutor;

    public SeenCache(DedupConfig config) {
        this.config = config;
        
        // LinkedHashMap with access order for LRU
        this.cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(config.getMaxEntries(), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > config.getMaxEntries();
                }
            }
        );
        
        // 启动后台清理
        this.sweepExecutor = Executors.newSingleThreadScheduledExecutor();
        long sweepIntervalMs = config.getSweepInterval().toMillis();
        this.sweepExecutor.scheduleAtFixedRate(
            this::sweep,
            sweepIntervalMs,
            sweepIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * 检查并标记为已见
     *
     * @param keys 1-3个键（协议ID, msgId, 指纹）
     * @return true 表示已见过（重复），false 表示首次见到
     */
    public boolean checkAndMark(String... keys) {
        if (keys == null || keys.length == 0) {
            return false;
        }

        // 组合键
        String combinedKey = String.join(":", keys);
        long now = System.currentTimeMillis();

        synchronized (cache) {
            if (cache.containsKey(combinedKey)) {
                // 命中：更新访问顺序（LRU）
                cache.put(combinedKey, now);
                return true;
            }

            // 首次见到，标记
            cache.put(combinedKey, now);
            return false;
        }
    }

    /**
     * 清理过期条目
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        long ttlMs = config.getTtl().toMillis();

        synchronized (cache) {
            cache.entrySet().removeIf(entry -> 
                now - entry.getValue() > ttlMs
            );
        }
    }

    /**
     * 释放资源
     */
    public void dispose() {
        sweepExecutor.shutdown();
        try {
            sweepExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            sweepExecutor.shutdownNow();
        }
    }

    /**
     * 计算内容指纹（SHA-256）
     */
    public static String contentFingerprint(String conversationId, long createAt, 
                                           String msgType, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(conversationId.getBytes());
            md.update(String.valueOf(createAt).getBytes());
            md.update(msgType.getBytes());
            md.update(content.getBytes());
            
            byte[] hash = md.digest();
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

### 5. PolicyGate.java
```java
package com.dingtalk.channel.safety;

import com.dingtalk.channel.types.*;

/**
 * 策略门控
 * 对标 Go SDK internal/safety/policy_gate.go
 */
public class PolicyGate {
    private PolicyConfig config;
    private BotIdentity bot;

    public PolicyGate(PolicyConfig config) {
        this.config = config;
    }

    /**
     * 评估消息是否允许通过
     */
    public PolicyDecision evaluate(IncomingMessage msg) {
        // 1. 管理员绕过（最高优先级）
        if (isAdmin(msg.getSenderStaffId())) {
            return new PolicyDecision(true, null);
        }

        // 2. 全局黑名单
        if (isDenied(msg.getSenderStaffId())) {
            return new PolicyDecision(false, RejectReason.SENDER_DENIED);
        }

        // 3. 全局白名单（如果设置了白名单，必须在名单内）
        if (!config.getAllowFrom().isEmpty() && 
            !isInAllowFrom(msg.getSenderStaffId())) {
            return new PolicyDecision(false, RejectReason.SENDER_NOT_ALLOWED);
        }

        // 4. 按会话类型评估
        if ("group".equals(msg.getConversationType())) {
            return evaluateGroup(msg);
        } else {
            return evaluateDM(msg);
        }
    }

    private PolicyDecision evaluateGroup(IncomingMessage msg) {
        // 群组黑名单
        if (config.getGroupBlocklist().contains(msg.getConversationId())) {
            return new PolicyDecision(false, RejectReason.GROUP_BLOCKED);
        }

        // 群组白名单检查
        if (!config.getGroupAllowlist().isEmpty() &&
            !config.getGroupAllowlist().contains(msg.getConversationId())) {
            return new PolicyDecision(false, RejectReason.GROUP_NOT_ALLOWED);
        }

        // 全局配置
        if (config.getRequireMention() != null && 
            config.getRequireMention() && 
            !msg.isInAtList()) {
            return new PolicyDecision(false, RejectReason.NO_MENTION);
        }

        if (msg.isMentionAll() && 
            (config.getRespondToMentionAll() == null || 
             !config.getRespondToMentionAll())) {
            return new PolicyDecision(false, RejectReason.MENTION_ALL_BLOCKED);
        }

        return new PolicyDecision(true, null);
    }

    private PolicyDecision evaluateDM(IncomingMessage msg) {
        if ("disabled".equals(config.getDmMode())) {
            return new PolicyDecision(false, RejectReason.DM_DISABLED);
        }

        if ("allowlist".equals(config.getDmMode()) &&
            !config.getDmAllowlist().contains(msg.getSenderId())) {
            return new PolicyDecision(false, RejectReason.DM_NOT_ALLOWED);
        }

        if ("blocklist".equals(config.getDmMode()) &&
            config.getDmBlocklist().contains(msg.getSenderId())) {
            return new PolicyDecision(false, RejectReason.DM_BLOCKED);
        }

        return new PolicyDecision(true, null);
    }

    private boolean isAdmin(String staffId) {
        return staffId != null && config.getAdmins().contains(staffId);
    }

    private boolean isDenied(String staffId) {
        return staffId != null && config.getDenyFrom().contains(staffId);
    }

    private boolean isInAllowFrom(String staffId) {
        return staffId != null && config.getAllowFrom().contains(staffId);
    }

    public void updateConfig(PolicyConfig config) {
        this.config = config;
    }

    public void setBotIdentity(BotIdentity bot) {
        this.bot = bot;
    }
}
```

### 6. SafetyPipeline.java
```java
package com.dingtalk.channel.safety;

import com.dingtalk.channel.types.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 安全管线统一门面
 * 对标 Go SDK internal/safety/pipeline.go
 */
public class SafetyPipeline {
    private final SafetyConfig config;
    private final BiConsumer<IncomingMessage, List<IncomingMessage>> onMessage;
    private final Consumer<RejectEvent> onReject;
    private String botRobotCode;

    private final StaleDetector stale;
    private final SeenCache seen;
    private final ProcessingLock lock;
    private final PolicyGate policy;
    private final MediaPipelineManager media;

    public SafetyPipeline(
        SafetyConfig config,
        BiConsumer<IncomingMessage, List<IncomingMessage>> onMessage,
        Consumer<RejectEvent> onReject,
        String botRobotCode
    ) {
        this.config = config;
        this.onMessage = onMessage;
        this.onReject = onReject;
        this.botRobotCode = botRobotCode;

        // 创建安全组件
        this.stale = new StaleDetector(config.getStaleWindow());
        this.seen = new SeenCache(config.getDedup());
        this.lock = new ProcessingLock(config.getLockTtl(), Duration.ofMinutes(1));
        this.policy = new PolicyGate(config.getPolicy());
        this.media = new MediaPipelineManager(config.getMediaBatch());
    }

    /**
     * 推送消息到完整安全管线
     */
    public CompletableFuture<Void> pushMessage(String protoId, IncomingMessage msg) {
        return CompletableFuture.runAsync(() -> {
            // 1. 过期检测
            if (stale.isStale(msg.getCreateAt())) {
                emitReject(msg, RejectReason.STALE);
                return;
            }

            // 2. 去重
            String fingerprint = "";
            if (config.getDedup().isEnableFingerprint()) {
                fingerprint = SeenCache.contentFingerprint(
                    msg.getConversationId(),
                    msg.getCreateAt(),
                    msg.getMsgType(),
                    msg.getText()
                );
            }

            if (seen.checkAndMark(protoId, msg.getMsgId(), fingerprint)) {
                emitReject(msg, RejectReason.DUPLICATE);
                return;
            }

            // 3. 自回复过滤
            if (config.isDropSelfSent() &&
                botRobotCode != null &&
                botRobotCode.equals(msg.getSenderId())) {
                emitReject(msg, RejectReason.SELF_SENT);
                return;
            }

            // 4. 策略门控
            PolicyDecision decision = policy.evaluate(msg);
            if (!decision.isAllowed()) {
                emitReject(msg, decision.getReason());
                return;
            }

            // 5. 处理锁
            if (!lock.acquire(msg.getMsgId())) {
                emitReject(msg, RejectReason.LOCK_CONTENTION);
                return;
            }

            // 6. 媒体批处理
            if (media.isCompatible(msg)) {
                media.push(msg, merged -> {
                    try {
                        onMessage.accept(merged, 
                            merged.getBatchedSources() != null ? 
                            merged.getBatchedSources() : 
                            Collections.singletonList(merged));
                    } finally {
                        lock.release(merged.getMsgId());
                    }
                });
                return;
            }

            // 7. 直接处理
            try {
                onMessage.accept(msg, Collections.singletonList(msg));
            } finally {
                lock.release(msg.getMsgId());
            }
        });
    }

    private void emitReject(IncomingMessage msg, RejectReason reason) {
        if (onReject != null) {
            RejectEvent event = new RejectEvent(
                msg.getMsgId(),
                msg.getConversationId(),
                msg.getSenderId(),
                reason
            );
            onReject.accept(event);
        }
    }

    public void setBotIdentity(String robotCode) {
        this.botRobotCode = robotCode;
        if (robotCode != null) {
            BotIdentity bot = new BotIdentity();
            bot.setRobotCode(robotCode);
            policy.setBotIdentity(bot);
        }
    }

    public void dispose() {
        seen.dispose();
        lock.dispose();
        media.dispose();
    }
}
```

---

## Maven 配置 (pom.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.dingtalk</groupId>
    <artifactId>dingtalk-channel-sdk</artifactId>
    <version>0.1.0</version>
    <packaging>jar</packaging>

    <name>DingTalk Channel SDK</name>
    <description>企业级消息安全管线</description>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <junit.version>5.9.2</junit.version>
    </properties>

    <dependencies>
        <!-- JUnit 5 for testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.0.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 测试示例

### StaleDetectorTest.java
```java
package com.dingtalk.channel.safety;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class StaleDetectorTest {
    @Test
    void testFreshMessage() {
        StaleDetector detector = new StaleDetector(Duration.ofMinutes(30));
        long now = System.currentTimeMillis();
        assertFalse(detector.isStale(now));
    }

    @Test
    void testStaleMessage() {
        StaleDetector detector = new StaleDetector(Duration.ofMinutes(10));
        long old = System.currentTimeMillis() - (15 * 60 * 1000); // 15 分钟前
        assertTrue(detector.isStale(old));
    }

    @Test
    void testInvalidTimestamp() {
        StaleDetector detector = new StaleDetector(Duration.ofMinutes(30));
        assertFalse(detector.isStale(0));
        assertFalse(detector.isStale(-1));
    }
}
```

---

## 使用示例

```java
import com.dingtalk.channel.safety.SafetyPipeline;
import com.dingtalk.channel.types.*;

public class Example {
    public static void main(String[] args) {
        // 创建安全配置
        SafetyConfig config = SafetyConfig.createDefault();

        // 创建安全管线
        SafetyPipeline pipeline = new SafetyPipeline(
            config,
            (msg, sources) -> {
                System.out.println("收到消息: " + msg.getText());
            },
            event -> {
                System.out.println("消息被拒绝: " + event.getReason());
            },
            "robot123"
        );

        // 推送消息
        IncomingMessage msg = new IncomingMessage();
        msg.setConversationId("chat1");
        msg.setMsgId("msg1");
        msg.setMsgType("text");
        msg.setText("Hello!");
        msg.setCreateAt(System.currentTimeMillis());

        pipeline.pushMessage("proto1", msg).join();

        // 清理
        pipeline.dispose();
    }
}
```

---

## 实现清单

- [x] 类型定义（RejectReason, DedupConfig）
- [x] StaleDetector
- [x] SeenCache（LinkedHashMap LRU）
- [x] PolicyGate
- [ ] ProcessingLock（需实现）
- [ ] MediaPipelineManager（需实现）
- [ ] 完整测试用例
- [ ] README.md

**预计完成时间**：5-7 天全职工作
