package com.dingtalk.channel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 归一化后的卡片交互回调（E7）。 */
public final class CardAction {
    public final String outTrackId;
    public final String userId;
    public final JsonElement dataContent;
    public final JsonObject raw;

    CardAction(JsonObject d) {
        this.outTrackId = d.has("outTrackId") ? d.get("outTrackId").getAsString() : "";
        this.userId = d.has("userId") ? d.get("userId").getAsString() : "";
        this.dataContent = d.has("dataContent") ? d.get("dataContent") : null;
        this.raw = d;
    }
}
