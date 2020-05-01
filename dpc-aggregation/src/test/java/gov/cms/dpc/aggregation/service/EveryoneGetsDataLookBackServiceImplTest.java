package gov.cms.dpc.aggregation.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class EveryoneGetsDataLookBackServiceImplTest {

    private LookBackService lookBackService = new EveryoneGetsDataLookBackServiceImpl();

    @Test
    public void alwaysReturnUUIDFromGetProviderIDFromRosterTest() {
        UUID uuid = lookBackService.getProviderIDFromRoster(UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        Assertions.assertNotNull(uuid);
    }

    @Test
    public void alwaysReturnTrueFromHasClaimWithin() {
        boolean result = lookBackService.hasClaimWithin(null, UUID.randomUUID(), UUID.randomUUID(), 0L);
        Assertions.assertTrue(result);
    }
}