package com.lunar.rockets.apply.service;

import com.lunar.rockets.apply.claim.ClaimService;
import com.lunar.rockets.apply.config.ApplyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplyWorkerTest {

    @Mock
    ClaimService claimService;

    @Mock
    ChannelApplyService applyService;

    ApplyProperties properties;
    ApplyWorker worker;
    UUID channel;

    @BeforeEach
    void setUp() {
        properties = new ApplyProperties();
        properties.setVersionConflictMaxRetries(2);
        worker = new ApplyWorker(claimService, applyService, properties);
        channel = UUID.randomUUID();
    }

    @Test
    void runOnceReleasesWhenApplyCompletes() {
        when(claimService.claimDirtyBatch("w1")).thenReturn(List.of(channel));
        when(applyService.applyContiguous(channel)).thenReturn(false);

        worker.runOnce("w1");

        verify(claimService).releaseClaim(channel, false);
    }

    @Test
    void versionConflictRetriesThenReleasesDirty() {
        when(claimService.claimDirtyBatch("w1")).thenReturn(List.of(channel));
        when(applyService.applyContiguous(channel))
                .thenThrow(new VersionConflictException("v1"))
                .thenThrow(new VersionConflictException("v2"));

        worker.runOnce("w1");

        verify(applyService, times(2)).applyContiguous(channel);
        verify(claimService).releaseClaim(channel, true);
    }

    @Test
    void versionConflictThenSuccessReleasesWithRemainingFlag() {
        when(claimService.claimDirtyBatch("w1")).thenReturn(List.of(channel));
        when(applyService.applyContiguous(channel))
                .thenThrow(new VersionConflictException("v1"))
                .thenReturn(true);

        worker.runOnce("w1");

        verify(applyService, times(2)).applyContiguous(channel);
        verify(claimService).releaseClaim(channel, true);
        verify(claimService, never()).releaseClaim(eq(channel), eq(false));
    }

    @Test
    void runtimeFailureReleasesDirtyWithoutRetry() {
        when(claimService.claimDirtyBatch("w1")).thenReturn(List.of(channel));
        when(applyService.applyContiguous(channel)).thenThrow(new IllegalStateException("boom"));

        worker.runOnce("w1");

        verify(applyService, times(1)).applyContiguous(channel);
        verify(claimService).releaseClaim(channel, true);
        verify(claimService, never()).releaseClaim(any(), eq(false));
    }
}
