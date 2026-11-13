package com.lunar.rockets.apply.claim;

import com.lunar.rockets.apply.config.ApplyProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ClaimService {

    private final ChannelClaimJdbc claimJdbc;
    private final ChannelWorkClaimRepository claims;
    private final ApplyProperties properties;

    public ClaimService(
            ChannelClaimJdbc claimJdbc,
            ChannelWorkClaimRepository claims,
            ApplyProperties properties) {
        this.claimJdbc = claimJdbc;
        this.claims = claims;
        this.properties = properties;
    }

    @Transactional
    public List<UUID> claimDirtyBatch(String workerId) {
        return claimJdbc.claimBatch(
                workerId,
                properties.getClaimBatchSize(),
                properties.getClaimLeaseSeconds());
    }

    @Transactional
    public void releaseClaim(UUID channel, boolean unappliedRemain) {
        if (unappliedRemain) {
            claims.clearClaim(channel);
        } else {
            claims.deleteWork(channel);
        }
    }
}
