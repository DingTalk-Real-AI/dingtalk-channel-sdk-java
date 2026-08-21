package io.github.typefield.dingtalk.channel.safety;

import io.github.typefield.dingtalk.channel.IncomingMessage;

import java.util.Collections;
import java.util.List;

/**
 * 批处理后的消息。
 */
public class BatchedMessage {
    /** 合并后的消息（最后一条为基础，内容合并） */
    public final IncomingMessage message;
    /** 源消息 ID 列表 */
    public final List<String> sourceIds;

    public BatchedMessage(IncomingMessage message, List<String> sourceIds) {
        this.message = message;
        this.sourceIds = sourceIds != null ? sourceIds : Collections.emptyList();
    }
}
