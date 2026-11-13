package com.lunar.rockets.apply.service;

import com.lunar.rockets.apply.claim.ClaimService;
import com.lunar.rockets.apply.config.ApplyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ApplyWorker {

    private static final Logger log = LoggerFactory.getLogger(ApplyWorker.class);

    private final ClaimService claimService;
    private final ChannelApplyService applyService;
    private final ApplyProperties properties;

    public ApplyWorker(
            ClaimService claimService,
            ChannelApplyService applyService,
            ApplyProperties properties) {
        this.claimService = claimService;
        this.applyService = applyService;
        this.properties = properties;
    }

    public void runOnce(String workerId) {
        List<UUID> channels = claimService.claimDirtyBatch(workerId);
        for (UUID channel : channels) {
            processChannel(channel);
        }
    }

    private void processChannel(UUID channel) {
        int attempts = 0;
        while (true) {
            try {
                boolean remaining = applyService.applyContiguous(channel);
                claimService.releaseClaim(channel, remaining);
                return;
            } catch (VersionConflictException ex) {
                attempts++;
                log.warn("version conflict applying channel {} attempt {}: {}", channel, attempts, ex.getMessage());
                if (attempts >= properties.getVersionConflictMaxRetries()) {
                    claimService.releaseClaim(channel, true);
                    return;
                }
            } catch (RuntimeException ex) {
                log.error("apply failed for channel {}", channel, ex);
                claimService.releaseClaim(channel, true);
                return;
            }
        }
    }
}
