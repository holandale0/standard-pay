package org.leonardo.standardpay.balances.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.leonardo.standardpay.balances.entity.Recipient;
import org.leonardo.standardpay.platform.ApiException;
import org.leonardo.standardpay.platform.Ids;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class Recipients {

    @Transactional
    public Recipient create(String merchantId, String name, Map<String, String> metadata) {
        var r = new Recipient();
        r.id = Ids.generate("rcp");
        r.merchantId = merchantId;
        r.name = name.trim();
        r.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        r.createdAt = Instant.now();
        r.persist();
        return r;
    }

    public Recipient findOwned(String merchantId, String id) {
        return Recipient.findOwned(merchantId, id).orElseThrow(() -> ApiException.notFound("recipient", id));
    }

    public List<Recipient> list(String merchantId, int limit) {
        return Recipient.recentOf(merchantId, limit);
    }
}
