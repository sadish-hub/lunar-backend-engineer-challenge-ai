package com.lunar.rockets.apply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lunar.apply")
public class ApplyProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 250L;
    private int claimBatchSize = 8;
    private int maxMessagesPerClaim = 100;
    private int claimLeaseSeconds = 60;
    private int versionConflictMaxRetries = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public int getMaxMessagesPerClaim() {
        return maxMessagesPerClaim;
    }

    public void setMaxMessagesPerClaim(int maxMessagesPerClaim) {
        this.maxMessagesPerClaim = maxMessagesPerClaim;
    }

    public int getClaimLeaseSeconds() {
        return claimLeaseSeconds;
    }

    public void setClaimLeaseSeconds(int claimLeaseSeconds) {
        this.claimLeaseSeconds = claimLeaseSeconds;
    }

    public int getVersionConflictMaxRetries() {
        return versionConflictMaxRetries;
    }

    public void setVersionConflictMaxRetries(int versionConflictMaxRetries) {
        this.versionConflictMaxRetries = versionConflictMaxRetries;
    }
}
