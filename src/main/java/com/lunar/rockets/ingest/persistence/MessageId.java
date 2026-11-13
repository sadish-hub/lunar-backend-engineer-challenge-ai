package com.lunar.rockets.ingest.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class MessageId implements Serializable {

    @Column(name = "channel", nullable = false)
    private UUID channel;

    @Column(name = "message_number", nullable = false)
    private Long messageNumber;

    protected MessageId() {
    }

    public MessageId(UUID channel, Long messageNumber) {
        this.channel = channel;
        this.messageNumber = messageNumber;
    }

    public UUID getChannel() {
        return channel;
    }

    public Long getMessageNumber() {
        return messageNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MessageId that)) {
            return false;
        }
        return Objects.equals(channel, that.channel)
                && Objects.equals(messageNumber, that.messageNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channel, messageNumber);
    }
}
