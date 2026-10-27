package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.service.AuditClient;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @Transactional rolls each test back so tests don't leak redemptions into each other.
@SpringBootTest
@Transactional
class VoucherServiceTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignRepository campaignRepository;

    // Stops tests from calling the real audit service.
    @MockBean
    private AuditClient auditClient;

    @Test
    void redeemActiveVoucherSucceeds() {
        RedeemResponse res = voucherService.redeem("RAYA-0001", "user-1");
        assertEquals("OK", res.getResult());
    }

    @Test
    void redeemAlreadyRedeemedVoucherFails() {
        RedeemResponse res = voucherService.redeem("RAYA-0004", "user-2");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    void redeemUnknownCodeFails() {
        RedeemResponse res = voucherService.redeem("NOPE-9999", "user-3");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    void userCanRedeemUpToDefaultLimitThenIsRejected() {
        assertEquals("OK", voucherService.redeem("RAYA-0001", "user-1").getResult());
        assertEquals("OK", voucherService.redeem("RAYA-0002", "user-1").getResult());

        RedeemResponse third = voucherService.redeem("RAYA-0003", "user-1");
        assertEquals("FAILED", third.getResult());
        assertTrue(third.getMessage().contains("Redemption limit reached"));
    }

    @Test
    void voidedRedemptionsCountTowardsLimit() {
        // user-99 has one REDEEMED and one VOID redemption on campaign 1 -> already at limit 2
        RedeemResponse res = voucherService.redeem("RAYA-0001", "user-99");
        assertEquals("FAILED", res.getResult());
        assertTrue(res.getMessage().contains("Redemption limit reached"));
    }

    @Test
    void limitIsPerCampaign() {
        // user-99 is at the limit on campaign 1 but has no history on campaign 2
        RedeemResponse res = voucherService.redeem("MRDK-0001", "user-99");
        assertEquals("OK", res.getResult());
    }

    @Test
    void customCampaignLimitIsRespected() {
        Campaign campaign = campaignRepository.findById(2L).get();
        campaign.setMaxRedemptionsPerUser(1);
        campaignRepository.save(campaign);

        assertEquals("OK", voucherService.redeem("MRDK-0001", "user-5").getResult());

        RedeemResponse second = voucherService.redeem("MRDK-0002", "user-5");
        assertEquals("FAILED", second.getResult());
        assertTrue(second.getMessage().contains("at most 1"));
    }

    @Test
    void rejectedRedemptionDoesNotConsumeStockOrVoucher() {
        int stockBefore = campaignRepository.findById(1L).get().getRemainingStock();

        assertEquals("FAILED", voucherService.redeem("RAYA-0001", "user-99").getResult());

        assertEquals(stockBefore, campaignRepository.findById(1L).get().getRemainingStock());
        // voucher is still ACTIVE, so another user can redeem it
        assertEquals("OK", voucherService.redeem("RAYA-0001", "user-7").getResult());
    }
}
