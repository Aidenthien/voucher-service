# NOTES

## What I changed and why

### Per-user redemption limit
- **New column `campaign.max_redemptions_per_user`** (`INT NOT NULL DEFAULT 2`, `CHECK > 0`) in `schema.sql`, mapped on `Campaign`.
  - The brief says each campaign defines its own limit, so it lives on the campaign row.
  - `DEFAULT 2` gives every existing campaign the required default without touching seed data.
  - The `CHECK` stops a misconfigured limit of 0 or below from silently blocking every user.
- **`RedemptionRepository.countByCampaignIdAndUserId`**: counts how many times the user has redeemed in that campaign.
  - Uses a Spring Data derived query, the same style as the existing repository methods.
  - **Voided redemptions count towards the limit.** A voided voucher was still a redemption by that user, so it uses up a slot. This also stops redeem → void → redeem again from getting around the limit.
  - As a result `user-99` (1 redeemed + 1 voided on campaign 1) is already at the limit.
- **Check in `VoucherService.redeem()`**, after the active and stock checks and before any writes.
  - Rejects with `FAILED` and the message `Redemption limit reached: a user may redeem at most N voucher(s) in this campaign`.
  - Follows the existing `RedeemResponse.fail(...)` contract, so the storefront needs no changes.
  - A rejected request changes nothing: no stock is used and the voucher stays `ACTIVE`.
- **Change the SQL files**, so that I can test locally on my MSSQL.
  - A temporary change of SQL so that I can test in my local MSSQL database and once that is done I will have to revert back to the original H2 syntax.

### Concurrency safety for redeem
- **`@Transactional` on `redeem()`.** Previously the voucher, campaign and redemption writes were three separate commits, so a failure halfway left the data inconsistent.
- **Pessimistic row locks**: `VoucherRepository.findByCodeForUpdate` then `CampaignRepository.findByIdForUpdate`.
  - Without them, two parallel requests from the same user could both read "1 used" and both succeed, bypassing the limit. The same race already allowed double-redeeming one voucher and overselling stock.
  - Locks are always taken in the same order (voucher, then campaign), so two requests can't deadlock by each waiting on the other's lock.
  - Redemptions within one campaign now run one at a time. That's acceptable at the current scale.

### Audit API key moved out of source
- `AuditClient` reads the key from `audit.api-key`, which `application.yml` resolves from `AUDIT_API_KEY`.
- Locally it is loaded from `.env` (git-ignored) via `spring.config.import`. Other environments set it as a real environment variable.
- `.env.example` is committed so a fresh clone knows what to set. The README has the setup step.
- The old key is still in git history, so it should be **rotated**.

### Tests (`VoucherServiceTest`)
- Added tests:
  - Redeeming up to the default limit, then the next one is rejected.
  - Voided redemptions count towards the limit.
  - The limit is per campaign.
  - A custom per-campaign limit is respected.
  - A rejected request doesn't use stock or the voucher.
- The test class is now `@Transactional`, so each test rolls back. Previously tests shared and changed the same H2 data, so the result depended on run order.
- `AuditClient` is mocked with `@MockBean`, so tests no longer call the real audit service over the network.

## Noticed but deliberately did not change
- **`voidVoucher` doesn't reconcile stock or redemption history** (`VoucherService.voidVoucher`). *(Requires a business decision: what does "void" mean (refund, fraud cancel, admin correction), and should it return stock?)*
- **N+1 query in campaign stats** (`CampaignStatsService.getStats`: one redemption query per voucher; voided vouchers also count as "redeemed"). *(Needs discussion with clients on what the stats should show and how they use them before changing the numbers they see.)*
- **No unique constraint on `voucher.code`** (`schema.sql`; `findByCode` assumes only one match). *(Need to check with the business how the voucher code works, unique or no.)*
- **HTTP 200 returned for failures** (`RedeemResponse.fail` and the controllers). *(Requires discussion: this is an API design and contract change that affects every storefront client.)*
- **Mixed dependency injection styles** (field `@Autowired` in `VoucherController`/`VoucherService` vs constructor injection elsewhere). *(If alignment is wanted it needs a coding-style discussion with the team. It isn't part of this requirement, so I followed the existing style of each class.)*

## If I had another day
- Email the team to ask for more business details on the open questions above (void semantics, code uniqueness, what the stats should show).
- Add more audit logging: log at the start of `redeem()` (request received) and on success, as well as on failure.
- Move the audit HTTP call so it runs after the transaction commits, and give `RestTemplate` timeouts. Right now it runs inside the transaction with no timeout, so a slow audit service keeps the voucher and campaign row locks held and blocks every other redemption in that campaign.

## Reflections
- **What did you get wrong first, and how did you notice?**
  - At first I thought of using count to check on the redeem limit will work by checking on the table using count, but after reading on AI suggestions, I realize that it can be happening the same time on different device, meaning to say when they click on the redeem same time on 2 different devices, and both are process together, count does not work as when count at that time, it will still count as 1 used instead of 2. Therefore, applying a lock on the process (wait for the first process done, then only goes for the second) will ensure the mentioned issue won't happen.
- **Which AI suggestion did you reject, and why?**
  - AI suggested me to not count voided redempions towards the limit, but from my understanding on the usage of voucher/coupon, once it used and if the user voided it, it still counsidered as used but in terms of the limit not the voucher is used. Meaning to said if the user used 2 times, no matter is it voided or no, it still consider as part of the redeem.
- **What took you longest?**
  - Decision making on the AI because AI raised a few issues that seems to be a problem but most of it are business or decision related, so I will have to think which one is changeable, which one is strongly rely on those decision. Also, I spent quite some time on thinking my count solution and the process lock solution suggested by AI which one is most preferred.
