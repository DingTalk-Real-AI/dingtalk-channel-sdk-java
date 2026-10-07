package com.dingtalk.channel;

import com.google.gson.JsonObject;

/** 完整回执与服务端 bizId；缺少 bizId 时保留回执，不自动重发。 */
public final class A2UICardResult {
    public final String bizId;
    public final JsonObject receipt;
    public final String updateWarning;

    A2UICardResult(String bizId, JsonObject receipt) {
        this.bizId = bizId;
        this.receipt = receipt;
        this.updateWarning = bizId == null
                ? "回执未包含可用的 bizId；请保留回执并核实服务端标识，不要自动重发创建请求。" : null;
    }
}
