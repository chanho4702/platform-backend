package com.platform.searchservice.event;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Redis Streams 소비 계약. 기본값은 wiki-backend 발행자와 맞춘다. */
@Component
@ConfigurationProperties(prefix = "platform.events")
public class EventConsumerProperties {

    private String stream = "platform:events:v1";
    private String consumerGroup = "search-service";
    private boolean enabled = true;
    private int maxRetries = 5;
    private String dlq = "platform:events:v1:dlq";

    public String getStream() { return stream; }
    public void setStream(String stream) { this.stream = stream; }

    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public String getDlq() { return dlq; }
    public void setDlq(String dlq) { this.dlq = dlq; }
}
