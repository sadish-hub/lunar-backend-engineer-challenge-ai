package com.lunar.rockets.apply.schedule;

import com.lunar.rockets.apply.config.ApplyProperties;
import com.lunar.rockets.apply.service.ApplyWorker;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ApplyClaimScheduler {

    private final ApplyWorker worker;
    private final ApplyProperties properties;
    private final String workerId;

    public ApplyClaimScheduler(ApplyWorker worker, ApplyProperties properties) {
        this.worker = worker;
        this.properties = properties;
        this.workerId = "pod-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${lunar.apply.poll-interval-ms:250}")
    public void pollAndApply() {
        if (!properties.isEnabled()) {
            return;
        }
        worker.runOnce(workerId);
    }

    String workerId() {
        return workerId;
    }
}
