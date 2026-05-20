package pt.properia.api.modules.team.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class AdvertiserUserId implements Serializable {
    private UUID advertiserId;
    private UUID userId;

    public AdvertiserUserId() {}
    public AdvertiserUserId(UUID advertiserId, UUID userId) {
        this.advertiserId = advertiserId;
        this.userId = userId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdvertiserUserId that)) return false;
        return Objects.equals(advertiserId, that.advertiserId) && Objects.equals(userId, that.userId);
    }
    @Override public int hashCode() { return Objects.hash(advertiserId, userId); }
}
