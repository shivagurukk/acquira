# End-to-End Test Output - Spring Boot 4.1.1 / JDK 25 - Three Institutions, Two Years of Data

**Run date:** 2026-11-11 to 2026-11-12, local environment  
**Branch:** `upgrade/spring-boot-3.5` (Spring Boot 4.1.1, Spring Batch 6, Java 25.0.4.1), built from the working tree  
**Database:** PostgreSQL 18.1, local dev instance  
**Data:** 1 November 2024 to 31 October 2026 (24 full months), synthetic, 12 merchants per institution

## 1. Result in one page

| Area | Result |
|---|---|
| Data loaded | 486,923 transactions across 3 institutions; 72 monthly files + 3 merchant masters, 75 of 75 batch jobs completed |
| Feed vs database | Every one of the 72 institution-months matches the feed on row count, volume, MSF, refunds and international count |
| Fee arithmetic | Stored interchange and scheme fee equal the applied rule on every priced purchase row (0 off, all 3 institutions) |
| Report APIs vs database | 1,777 of 1,778 comparisons match over 24 months |
| API sweep | 193 endpoints per institution; 183 return 200 for each; median 0.03 s, slowest 2.8 s |
| Merchant PDFs | 15 of 15 totals and month-on-month figures match the database; bulk job 11 of 11 |
| Bank-scoped users | 72 access checks, 69 passed; the 3 others are a malformed upload request returning 500 instead of 400 (access itself is denied correctly) |
| Tenant isolation (database) | 0 cross-tenant rows in facts, stores, terminals, summaries; 0 wrong-currency rows |
| Reprice and rebuild | 24 months for one institution in 508 s; all totals identical afterwards |
| UI | Login, organisation switch, Executive Dashboard (month and year-to-date) correct for all 3; no console errors |
| Frontend | Production build passes; unit tests: 110 passed (110) |
| Backend unit tests | 306 run, 304 pass, 2 fail (both pre-existing RBAC coverage failures) |

**Verdict:** the core platform works end to end on Spring Boot 4.1.1 / JDK 25 at this data volume. No defect was traced to the upgrade itself. The run surfaced the defects listed in section 8, the most relevant open one being the scheme summary that drops refund-heavy days. The Executive Dashboard USD conversion defect was fixed and re-verified.

## 2. The three institutions

| | UAE test institution | Bahrain test institution | Egypt test institution |
|---|---|---|---|
| Short code / tenant id | E2EAE / 12 | E2EBH / 10 | E2EEG / 11 |
| Currency (decimals) | AED (2) | BHD (3) | EGP (2) |
| Feed profile | CMM, minor units / 100 | AMS, final decimals, BIN-typed | CMM, divisor override / 1000 |
| Transactions loaded | 162,903 | 162,586 | 161,434 |
| Volume | 30,814,353.84 | 3,119,026.223 | 163,717,399.81 |
| MSF | 569,439.73 | 57,663.054 | 3,030,415.57 |
| Interchange | 384,700.25 | 37,965.466 | 2,580,603.99 |
| Scheme fee | 87,191.03 | 8,206.393 | 487,824.23 |
| Feed rows / match | 162,903 / exact | 162,586 / exact | 161,434 / exact |
| Load time (24 files) | 356 s, 458 rows/s | 442 s, 354 rows/s | 473 s, 342 rows/s |
| Fee resolution | RESOLVED 160,614; NO_RATE_FOUND 2,289 | RESOLVED 162,586 | RESOLVED 161,434 |

Each institution has 12 merchants with deliberate life-cycle cases: one that goes dormant from July 2026, one onboarded in March 2026, one whose volume drops 60% from September 2026, and one that grows 45% a year.

## 3. Output - UAE test institution (E2EAE, AED)

Profile: CMM feed, amounts in minor units (divide by 100).

### Year on year

| Period | Transactions | Volume | MSF | Interchange | Scheme fee |
|---|---:|---:|---:|---:|---:|
| Year 1 (Nov-24..Oct-25) | 74,757 | 14,138,065.75 | 260,759.50 | 175,293.48 | 39,645.19 |
| Year 2 (Nov-25..Oct-26) | 88,146 | 16,676,288.09 | 308,680.23 | 209,406.77 | 47,545.84 |

Year-two volume is 18.0% higher than year one.

### Month by month (database, matches the feed exactly)

| Month | Txns | Volume | MSF | Interchange | Scheme fee | Net (MSF - IC - SF - PG) | Refunds | Intl txns | Active merchants |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2024-11 | 5,698 | 1,102,885.90 | 20,497.62 | 13,951.79 | 3,216.10 | 3,192.20 | 111 | 789 | 11 |
| 2024-12 | 5,899 | 1,100,695.35 | 20,410.45 | 13,628.75 | 3,045.12 | 3,588.79 | 129 | 803 | 11 |
| 2025-01 | 5,908 | 1,132,644.68 | 20,415.01 | 13,692.88 | 3,102.80 | 3,481.09 | 127 | 795 | 11 |
| 2025-02 | 5,568 | 1,011,875.88 | 18,662.54 | 12,669.71 | 2,835.78 | 3,029.43 | 108 | 752 | 11 |
| 2025-03 | 6,266 | 1,186,674.46 | 21,962.76 | 14,779.44 | 3,363.63 | 3,668.13 | 143 | 884 | 11 |
| 2025-04 | 5,986 | 1,142,874.52 | 20,965.71 | 13,795.55 | 3,170.66 | 3,859.28 | 109 | 831 | 11 |
| 2025-05 | 6,317 | 1,222,923.77 | 22,866.64 | 15,363.51 | 3,404.67 | 3,940.43 | 165 | 910 | 11 |
| 2025-06 | 6,419 | 1,236,755.48 | 22,957.60 | 15,363.72 | 3,488.21 | 3,956.44 | 130 | 831 | 11 |
| 2025-07 | 6,471 | 1,207,650.09 | 22,225.04 | 14,810.34 | 3,347.90 | 3,918.12 | 149 | 870 | 11 |
| 2025-08 | 6,846 | 1,257,304.49 | 23,235.18 | 15,525.82 | 3,430.10 | 4,120.50 | 150 | 932 | 11 |
| 2025-09 | 6,491 | 1,250,197.46 | 22,952.42 | 15,572.63 | 3,522.71 | 3,696.71 | 109 | 922 | 11 |
| 2025-10 | 6,888 | 1,285,583.67 | 23,608.53 | 16,139.33 | 3,717.50 | 3,602.12 | 140 | 975 | 11 |
| 2025-11 | 6,659 | 1,236,661.34 | 22,293.81 | 15,052.37 | 3,325.54 | 3,765.96 | 146 | 880 | 11 |
| 2025-12 | 6,981 | 1,295,180.38 | 23,697.67 | 16,170.04 | 3,659.03 | 3,703.18 | 148 | 939 | 11 |
| 2026-01 | 7,082 | 1,379,593.00 | 25,220.16 | 17,156.67 | 3,859.05 | 4,038.85 | 162 | 976 | 11 |
| 2026-02 | 6,609 | 1,235,540.71 | 22,810.39 | 15,573.71 | 3,537.08 | 3,545.88 | 123 | 932 | 11 |
| 2026-03 | 7,698 | 1,409,078.51 | 26,226.17 | 17,787.29 | 3,999.44 | 4,181.68 | 150 | 1,092 | 12 |
| 2026-04 | 7,642 | 1,425,412.92 | 26,339.39 | 17,734.06 | 3,982.68 | 4,366.34 | 172 | 1,105 | 12 |
| 2026-05 | 7,938 | 1,447,326.55 | 27,119.10 | 18,224.19 | 4,063.72 | 4,570.01 | 165 | 1,193 | 12 |
| 2026-06 | 7,843 | 1,389,763.92 | 25,738.53 | 17,640.36 | 4,098.37 | 3,737.90 | 172 | 1,092 | 12 |
| 2026-07 | 7,489 | 1,541,118.48 | 28,892.15 | 19,511.99 | 4,470.44 | 4,625.86 | 148 | 1,130 | 11 |
| 2026-08 | 7,739 | 1,508,691.54 | 28,339.97 | 19,021.99 | 4,280.51 | 4,747.85 | 155 | 1,127 | 11 |
| 2026-09 | 7,103 | 1,418,103.97 | 26,315.08 | 17,764.34 | 4,141.20 | 4,143.50 | 129 | 1,047 | 11 |
| 2026-10 | 7,363 | 1,389,816.77 | 25,687.80 | 17,769.75 | 4,128.79 | 3,512.06 | 156 | 1,108 | 11 |

### Observed rates by scheme, card type, destination and channel (purchases)

| Scheme | Card type | Destination | Channel | Txns | Volume | Interchange % | Scheme fee % |
|---|---|---|---|---:|---:|---:|---:|
| AMEX | CREDIT | DOMESTIC | ECOM | 374 | 79,538.28 | - | 0.1400 |
| AMEX | CREDIT | DOMESTIC | POS | 2,390 | 369,734.71 | 0.0687 | 0.1100 |
| JAYWAN | DEBIT | DOMESTIC | ECOM | 2,241 | 525,178.40 | 0.5000 | 0.1400 |
| JAYWAN | DEBIT | DOMESTIC | POS | 14,127 | 2,297,591.76 | 0.5000 | 0.1100 |
| MASTERCARD | CREDIT | DOMESTIC | ECOM | 4,028 | 937,875.84 | 1.4269 | 0.1422 |
| MASTERCARD | CREDIT | DOMESTIC | POS | 25,919 | 4,323,240.76 | 1.2862 | 0.1113 |
| MASTERCARD | DEBIT | DOMESTIC | ECOM | 2,554 | 615,144.08 | 0.9958 | 0.1400 |
| MASTERCARD | DEBIT | DOMESTIC | POS | 16,884 | 2,726,108.03 | 0.7155 | 0.1100 |
| MASTERCARD | PREPAID | DOMESTIC | ECOM | 715 | 154,212.00 | 1.0000 | 0.1400 |
| MASTERCARD | PREPAID | DOMESTIC | POS | 4,664 | 774,017.52 | 0.9151 | 0.1100 |
| UnionPay International | CREDIT | DOMESTIC | ECOM | 174 | 36,872.29 | 1.7500 | 0.0500 |
| UnionPay International | CREDIT | DOMESTIC | POS | 1,289 | 208,437.52 | 1.5336 | 0.0500 |
| VISA | CREDIT | DOMESTIC | ECOM | 4,546 | 1,123,135.68 | 1.8000 | 0.1400 |
| VISA | CREDIT | DOMESTIC | POS | 29,826 | 5,029,807.32 | 1.2941 | 0.1100 |
| VISA | DEBIT | DOMESTIC | ECOM | 2,877 | 658,341.40 | 1.0000 | 0.1400 |
| VISA | DEBIT | DOMESTIC | POS | 18,982 | 3,075,662.28 | 0.7177 | 0.1100 |
| VISA | PREPAID | DOMESTIC | ECOM | 724 | 149,222.69 | 1.0000 | 0.1400 |
| VISA | PREPAID | DOMESTIC | POS | 4,756 | 801,827.29 | 0.9080 | 0.1100 |
| AMEX | CREDIT | INTERNATIONAL | ECOM | 187 | 74,219.91 | 1.8500 | 0.9000 |
| AMEX | CREDIT | INTERNATIONAL | POS | 511 | 141,680.14 | 1.8500 | 0.7500 |
| MASTERCARD | CREDIT | INTERNATIONAL | ECOM | 2,382 | 1,005,832.04 | 1.8500 | 0.9016 |
| MASTERCARD | CREDIT | INTERNATIONAL | POS | 5,672 | 1,713,015.30 | 1.8500 | 0.7513 |
| MASTERCARD | DEBIT | INTERNATIONAL | ECOM | 596 | 247,932.93 | 1.8500 | 0.9010 |
| MASTERCARD | DEBIT | INTERNATIONAL | POS | 1,421 | 429,929.49 | 1.8500 | 0.7514 |
| UnionPay International | CREDIT | INTERNATIONAL | ECOM | 139 | 75,139.55 | 1.8500 | 0.0500 |
| UnionPay International | CREDIT | INTERNATIONAL | POS | 339 | 112,903.52 | 1.8500 | 0.0500 |
| VISA | CREDIT | INTERNATIONAL | ECOM | 2,508 | 1,049,573.40 | 1.8500 | 0.9000 |
| VISA | CREDIT | INTERNATIONAL | POS | 6,394 | 1,973,100.68 | 1.8500 | 0.7500 |
| VISA | DEBIT | INTERNATIONAL | ECOM | 712 | 302,645.49 | 1.8500 | 0.9000 |
| VISA | DEBIT | INTERNATIONAL | POS | 1,576 | 469,025.59 | 1.8500 | 0.7500 |

### Mastercard UAE changes effective 1 October 2026 (the reason for the `card_type` fix)

| Case | September 2026 | October 2026 |
|---|---:|---:|
| Domestic credit, POS - scheme fee % | 0.1100 | 0.1400 |
| Domestic credit, e-commerce - scheme fee % | 0.1400 | 0.1700 |
| Domestic debit, POS - scheme fee % | 0.1100 | 0.1100 |
| Domestic prepaid, POS - scheme fee % | 0.1100 | 0.1100 |
| International credit, POS - scheme fee % | 0.7500 | 0.7800 |
| MCC 5811 domestic credit - interchange % (recategorised MCC) | 1.0000 | 1.8000 |

Jaywan (after the corrective migration `V2026_11_11_01`): 16,368 purchases, interchange 0.5000%, 16,368 resolved on the Jaywan rule.

### Summary tables vs facts

| Summary table | Rows | Result |
|---|---:|---|
| `sum_daily_bank` | 730 | matches |
| `sum_daily_merchant` | 7,971 | matches |
| `sum_daily_full` | 55,516 | matches |
| `sum_daily_terminal` | 7,971 | matches |
| `sum_daily_scheme` | 3,562 | differs: txns 162822 vs fact 162903; volume 30826000.4000 vs fact 30814353.8400; msf 569681.7488 vs fact 569439.7338; interchange 384648.9447 vs fact 384700.2488; scheme_fee 87181.9168 vs fact 87191.0299 |
| `sum_daily_channel` | 2,158 | matches |
| `sum_daily_merchant_destination` | 14,515 | matches |
| `sum_daily_finance_rollup` | 730 | matches |
| `sum_daily_insight` | 55,516 | matches |
| `sum_monthly_insight` | 3,763 | matches |

### Merchants

| MID | Merchant | MCC | First txn | Last txn | Txns | Volume |
|---|---|---|---|---|---:|---:|
| E2EAEM001 | E2E AE Grocery LLC | 5411 | 2024-11-01 | 2026-10-31 | 26,605 | 2,295,544.36 |
| E2EAEM002 | E2E AE Online Store LLC | 5811 | 2024-11-01 | 2026-10-31 | 17,664 | 2,521,234.09 |
| E2EAEM003 | E2E AE Fuel Station LLC | 5541 | 2024-11-01 | 2026-10-31 | 21,967 | 1,580,461.59 |
| E2EAEM004 | E2E AE Pharmacy LLC | 5912 | 2024-11-01 | 2026-10-31 | 13,314 | 1,340,915.07 |
| E2EAEM005 | E2E AE Electronics LLC | 5732 | 2024-11-01 | 2026-10-31 | 7,127 | 4,136,617.19 |
| E2EAEM006 | E2E AE Fashion LLC | 5651 | 2024-11-01 | 2026-10-31 | 9,933 | 2,557,421.77 |
| E2EAEM007 | E2E AE Hotel LLC | 7011 | 2024-11-01 | 2026-10-31 | 4,495 | 3,863,214.23 |
| E2EAEM008 | E2E AE Travel Agency LLC | 4722 | 2024-11-01 | 2026-10-31 | 3,159 | 3,576,737.87 |
| E2EAEM009 | E2E AE School LLC | 8211 | 2024-11-01 | 2026-10-31 | 1,884 | 2,605,643.26 |
| E2EAEM010 | E2E AE Hypermarket LLC | 5411 | 2024-11-01 | 2026-10-31 | 39,264 | 5,089,391.32 |
| E2EAEM011 | E2E AE Cafe LLC | 5814 | 2024-11-01 | 2026-06-30 | 13,002 | 466,029.46 |
| E2EAEM012 | E2E AE E-Shop LLC | 5999 | 2026-03-01 | 2026-10-31 | 4,489 | 781,143.63 |

### Report APIs vs database

| Check | Compared | Matched | Note |
|---|---:|---:|---|
| Finance Summary: 24 months x volume/MSF/interchange/scheme fee | 96 | 96 |  |
| Volume & Revenue: 24 months x volume/MSF/count | 72 | 72 |  |
| Net Spread: 24 monthly totals x count/volume/MSF/IC/SF | 120 | 120 |  |
| Daily Merchant Performance: 8 sample days x count/volume/MSF | 24 | 24 |  |
| Transactions paging: one full day walked page by page | 292 | 292 | 3 pages, 292 rows returned |
| Executive Dashboard: Oct-2026 month-to-date volume and count | 2 | 2 | api 1389816.77 / 7363 |

### Merchant PDFs

| Merchant | Month | PDF total | Database net sales | Match | PDF month-on-month | Database | Pages | Seconds |
|---|---|---:|---:|---|---|---|---|---:|
| M001 | 2026-10 | 101,929.96 | 101,929.96 | yes | -4% | -4% | 8 | 4.1 |
| M007 | 2026-10 | 164,295.65 | 164,295.65 | yes | -4% | -4% | 8 | 2.5 |
| M010 | 2025-12 | 220,499.10 | 220,499.10 | yes | +8% | +8% | 8 | 2.5 |
| M011 | 2026-06 | 24,854.92 | 24,854.92 | yes | -1% | -1% | 8 | 2.6 |
| M012 | 2026-10 | 103,158.24 | 103,158.24 | yes | -0% | -0% | 8 | 2.6 |

### API sweep

193 endpoints called: 183 returned 200, 8 returned 4xx (a required parameter or body field was not supplied), 1 returned 500, 1 is a live stream that never completes. Response time for the 200s: median 0.028 s, 95th percentile 0.181 s, slowest 0.87 s.

## 4. Output - Bahrain test institution (E2EBH, BHD)

Profile: AMS feed, final decimals, card type from the BIN table.

### Year on year

| Period | Transactions | Volume | MSF | Interchange | Scheme fee |
|---|---:|---:|---:|---:|---:|
| Year 1 (Nov-24..Oct-25) | 74,282 | 1,422,502.950 | 26,163.130 | 17,222.460 | 3,703.470 |
| Year 2 (Nov-25..Oct-26) | 88,304 | 1,696,523.270 | 31,499.930 | 20,743.000 | 4,502.920 |

Year-two volume is 19.3% higher than year one.

### Month by month (database, matches the feed exactly)

| Month | Txns | Volume | MSF | Interchange | Scheme fee | Net (MSF - IC - SF - PG) | Refunds | Intl txns | Active merchants |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2024-11 | 5,694 | 109,338.712 | 2,014.298 | 1,295.580 | 284.120 | 291.498 | 118 | 760 | 11 |
| 2024-12 | 5,837 | 110,262.513 | 2,023.141 | 1,342.492 | 288.791 | 250.378 | 133 | 799 | 11 |
| 2025-01 | 6,118 | 114,289.410 | 2,120.556 | 1,384.323 | 306.069 | 284.544 | 131 | 842 | 11 |
| 2025-02 | 5,548 | 103,056.853 | 1,920.030 | 1,297.717 | 286.633 | 193.660 | 125 | 780 | 11 |
| 2025-03 | 6,115 | 112,743.467 | 2,074.188 | 1,359.843 | 276.745 | 290.180 | 123 | 802 | 11 |
| 2025-04 | 6,091 | 121,044.075 | 2,190.237 | 1,441.950 | 312.469 | 295.058 | 107 | 815 | 11 |
| 2025-05 | 6,296 | 121,690.302 | 2,240.205 | 1,452.200 | 312.409 | 319.356 | 123 | 853 | 11 |
| 2025-06 | 6,173 | 119,913.091 | 2,181.662 | 1,443.342 | 303.681 | 286.319 | 125 | 827 | 11 |
| 2025-07 | 6,530 | 126,284.643 | 2,325.056 | 1,538.931 | 335.653 | 300.531 | 129 | 900 | 11 |
| 2025-08 | 6,750 | 130,607.816 | 2,412.368 | 1,601.688 | 337.989 | 313.212 | 114 | 935 | 11 |
| 2025-09 | 6,404 | 126,545.126 | 2,351.373 | 1,528.482 | 322.293 | 347.057 | 120 | 877 | 11 |
| 2025-10 | 6,726 | 126,726.946 | 2,310.013 | 1,535.914 | 336.620 | 287.899 | 127 | 944 | 11 |
| 2025-11 | 6,641 | 125,765.192 | 2,319.702 | 1,528.445 | 320.337 | 309.640 | 131 | 885 | 11 |
| 2025-12 | 6,937 | 129,976.522 | 2,420.330 | 1,584.812 | 354.722 | 312.495 | 132 | 973 | 11 |
| 2026-01 | 7,071 | 127,526.377 | 2,342.227 | 1,558.570 | 329.899 | 288.338 | 139 | 914 | 11 |
| 2026-02 | 6,376 | 122,350.908 | 2,233.397 | 1,457.700 | 299.259 | 337.298 | 117 | 834 | 11 |
| 2026-03 | 7,911 | 148,796.954 | 2,776.539 | 1,813.385 | 391.027 | 308.426 | 147 | 1,138 | 12 |
| 2026-04 | 7,691 | 149,715.040 | 2,816.154 | 1,837.800 | 409.625 | 311.508 | 150 | 1,103 | 12 |
| 2026-05 | 8,107 | 149,846.253 | 2,811.301 | 1,845.400 | 401.750 | 290.011 | 168 | 1,184 | 12 |
| 2026-06 | 7,948 | 151,615.877 | 2,825.275 | 1,852.389 | 402.547 | 314.918 | 151 | 1,172 | 12 |
| 2026-07 | 7,390 | 145,894.065 | 2,699.787 | 1,819.582 | 393.594 | 214.271 | 163 | 1,116 | 11 |
| 2026-08 | 7,450 | 150,933.959 | 2,804.440 | 1,849.527 | 411.583 | 276.750 | 160 | 1,131 | 11 |
| 2026-09 | 7,129 | 142,915.633 | 2,655.998 | 1,757.531 | 390.346 | 242.802 | 142 | 1,047 | 11 |
| 2026-10 | 7,653 | 151,186.489 | 2,794.779 | 1,837.862 | 398.230 | 268.707 | 161 | 1,087 | 11 |

### Observed rates by scheme, card type, destination and channel (purchases)

| Scheme | Card type | Destination | Channel | Txns | Volume | Interchange % | Scheme fee % |
|---|---|---|---|---:|---:|---:|---:|
| Benefit | DEBIT | DOMESTIC | ECOM | 9,500 | 226,076.350 | 0.6000 | 0.0600 |
| Benefit | DEBIT | DOMESTIC | POS | 61,846 | 1,027,977.230 | 0.5975 | 0.0600 |
| Benefit QR | DEBIT | DOMESTIC | ECOM | 934 | 22,249.070 | 0.6000 | 0.0600 |
| Benefit QR | DEBIT | DOMESTIC | POS | 5,955 | 97,757.180 | 0.5974 | 0.0600 |
| MASTERCARD | CREDIT | DOMESTIC | ECOM | 1,842 | 46,594.550 | 1.9080 | 0.1400 |
| MASTERCARD | CREDIT | DOMESTIC | POS | 12,206 | 204,741.380 | 1.5783 | 0.1100 |
| MASTERCARD | DEBIT | DOMESTIC | ECOM | 1,899 | 43,174.880 | 1.9000 | 0.1400 |
| MASTERCARD | DEBIT | DOMESTIC | POS | 12,142 | 205,671.600 | 1.4632 | 0.1100 |
| MASTERCARD | PREPAID | DOMESTIC | ECOM | 243 | 5,241.300 | 1.3500 | 0.1400 |
| MASTERCARD | PREPAID | DOMESTIC | POS | 1,562 | 25,021.430 | 1.0376 | 0.1100 |
| VISA | CREDIT | DOMESTIC | ECOM | 1,677 | 41,868.330 | 2.0500 | 0.1400 |
| VISA | CREDIT | DOMESTIC | POS | 10,791 | 176,790.300 | 1.6327 | 0.1100 |
| VISA | DEBIT | DOMESTIC | ECOM | 2,177 | 53,107.600 | 1.4500 | 0.1400 |
| VISA | DEBIT | DOMESTIC | POS | 14,292 | 239,973.060 | 1.1535 | 0.1100 |
| MASTERCARD | CREDIT | INTERNATIONAL | ECOM | 2,859 | 128,791.920 | 1.8500 | 0.9000 |
| MASTERCARD | CREDIT | INTERNATIONAL | POS | 7,193 | 220,399.210 | 1.8500 | 0.7500 |
| VISA | CREDIT | INTERNATIONAL | ECOM | 3,659 | 156,179.430 | 1.8500 | 0.9000 |
| VISA | CREDIT | INTERNATIONAL | POS | 8,573 | 261,440.590 | 1.8500 | 0.7500 |

### Card typing from the BIN table (the feed sends the card type blank)

| Destination | Stored card type | Issuer country from BIN | Txns |
|---|---|---|---:|
| DOMESTIC | DEBIT | - | 79,831 |
| DOMESTIC | DEBIT | BH | 31,140 |
| DOMESTIC | CREDIT | BH | 25,285 |
| DOMESTIC | PREPAID | BH | 1,837 |
| DOMESTIC | CREDIT | - | 1,775 |
| INTERNATIONAL | CREDIT | GB | 12,265 |
| INTERNATIONAL | CREDIT | US | 9,343 |
| INTERNATIONAL | CREDIT | SA | 1,110 |

Rows with no issuer country are the domestic debit network (no BIN lookup, typed DEBIT by rule) and one BIN whose reference rows conflict (finding 8.6).

### Summary tables vs facts

| Summary table | Rows | Result |
|---|---:|---|
| `sum_daily_bank` | 730 | matches |
| `sum_daily_merchant` | 7,959 | matches |
| `sum_daily_full` | 45,138 | matches |
| `sum_daily_terminal` | 7,959 | matches |
| `sum_daily_scheme` | 2,918 | differs: txns 162571 vs fact 162586; volume 3119085.5640 vs fact 3119026.2230; msf 57665.7608 vs fact 57663.0545; interchange 37964.5047 vs fact 37965.4656; scheme_fee 8206.2969 vs fact 8206.3929 |
| `sum_daily_channel` | 2,162 | matches |
| `sum_daily_merchant_destination` | 14,526 | matches |
| `sum_daily_finance_rollup` | 730 | differs: volume 3119026.65 vs fact 3119026.2230 |
| `sum_daily_insight` | 45,138 | matches |
| `sum_monthly_insight` | 2,363 | matches |

### Merchants

| MID | Merchant | MCC | First txn | Last txn | Txns | Volume |
|---|---|---|---|---|---:|---:|
| E2EBHM001 | E2E BH Grocery WLL | 5411 | 2024-11-01 | 2026-10-31 | 26,229 | 226,259.550 |
| E2EBHM002 | E2E BH Online Store WLL | 5812 | 2024-11-01 | 2026-10-31 | 17,692 | 254,118.380 |
| E2EBHM003 | E2E BH Fuel Station WLL | 5541 | 2024-11-01 | 2026-10-31 | 21,914 | 157,512.510 |
| E2EBHM004 | E2E BH Pharmacy WLL | 5912 | 2024-11-01 | 2026-10-31 | 13,189 | 132,721.950 |
| E2EBHM005 | E2E BH Electronics WLL | 5732 | 2024-11-01 | 2026-10-31 | 7,123 | 413,779.190 |
| E2EBHM006 | E2E BH Fashion WLL | 5651 | 2024-11-01 | 2026-10-31 | 10,004 | 261,876.060 |
| E2EBHM007 | E2E BH Hotel WLL | 7011 | 2024-11-01 | 2026-10-31 | 4,402 | 383,958.480 |
| E2EBHM008 | E2E BH Travel Agency WLL | 4722 | 2024-11-01 | 2026-10-31 | 3,265 | 377,407.260 |
| E2EBHM009 | E2E BH School WLL | 8211 | 2024-11-02 | 2026-10-31 | 1,821 | 273,056.770 |
| E2EBHM010 | E2E BH Hypermarket WLL | 5411 | 2024-11-01 | 2026-10-31 | 39,786 | 515,882.450 |
| E2EBHM011 | E2E BH Cafe WLL | 5814 | 2024-11-01 | 2026-06-30 | 12,805 | 46,280.000 |
| E2EBHM012 | E2E BH E-Shop WLL | 5999 | 2026-03-01 | 2026-10-31 | 4,356 | 76,173.650 |

### Report APIs vs database

| Check | Compared | Matched | Note |
|---|---:|---:|---|
| Finance Summary: 24 months x volume/MSF/interchange/scheme fee | 96 | 95 | 2025-03 total_vol api=112743.52 db=112743.4670 |
| Volume & Revenue: 24 months x volume/MSF/count | 72 | 72 |  |
| Net Spread: 24 monthly totals x count/volume/MSF/IC/SF | 120 | 120 |  |
| Daily Merchant Performance: 8 sample days x count/volume/MSF | 24 | 24 |  |
| Transactions paging: one full day walked page by page | 271 | 271 | 3 pages, 271 rows returned |
| Executive Dashboard: Oct-2026 month-to-date volume and count | 2 | 2 | api 151186.489 / 7653 |

### Merchant PDFs

| Merchant | Month | PDF total | Database net sales | Match | PDF month-on-month | Database | Pages | Seconds |
|---|---|---:|---:|---|---|---|---|---:|
| M001 | 2026-10 | 11,055.337 | 11,055.337 | yes | +5% | +5% | 8 | 2.5 |
| M007 | 2026-10 | 19,490.064 | 19,490.064 | yes | +4% | +4% | 8 | 2.3 |
| M010 | 2025-12 | 22,105.061 | 22,105.061 | yes | +5% | +5% | 8 | 2.5 |
| M011 | 2026-06 | 2,492.733 | 2,492.733 | yes | -5% | -5% | 8 | 2.5 |
| M012 | 2026-10 | 9,954.651 | 9,954.651 | yes | +10% | +10% | 8 | 2.5 |

### API sweep

193 endpoints called: 183 returned 200, 8 returned 4xx (a required parameter or body field was not supplied), 1 returned 500, 1 is a live stream that never completes. Response time for the 200s: median 0.036 s, 95th percentile 0.289 s, slowest 2.80 s.

## 5. Output - Egypt test institution (E2EEG, EGP)

Profile: CMM feed with the tenant divisor override (divide by 1000).

### Year on year

| Period | Transactions | Volume | MSF | Interchange | Scheme fee |
|---|---:|---:|---:|---:|---:|
| Year 1 (Nov-24..Oct-25) | 73,966 | 74,968,083.17 | 1,386,306.41 | 1,209,672.42 | 221,124.19 |
| Year 2 (Nov-25..Oct-26) | 87,468 | 88,749,316.64 | 1,644,109.16 | 1,370,931.57 | 266,700.04 |

Year-two volume is 18.4% higher than year one.

### Month by month (database, matches the feed exactly)

| Month | Txns | Volume | MSF | Interchange | Scheme fee | Net (MSF - IC - SF - PG) | Refunds | Intl txns | Active merchants |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2024-11 | 5,751 | 5,994,367.56 | 110,775.38 | 95,301.47 | 17,387.50 | -1,913.60 | 128 | 782 | 11 |
| 2024-12 | 5,775 | 5,895,075.34 | 109,983.23 | 95,806.94 | 17,866.87 | -3,690.58 | 107 | 838 | 11 |
| 2025-01 | 6,073 | 6,433,847.86 | 119,844.11 | 103,846.44 | 18,065.20 | -2,067.53 | 95 | 795 | 11 |
| 2025-02 | 5,563 | 5,469,781.44 | 101,155.86 | 88,823.70 | 16,299.11 | -3,966.94 | 119 | 729 | 11 |
| 2025-03 | 6,246 | 6,425,409.59 | 119,046.27 | 103,062.61 | 19,026.92 | -3,043.26 | 115 | 856 | 11 |
| 2025-04 | 5,900 | 5,799,921.53 | 106,413.77 | 92,340.41 | 16,655.10 | -2,581.74 | 121 | 806 | 11 |
| 2025-05 | 6,388 | 6,374,596.60 | 117,432.82 | 103,488.96 | 18,692.65 | -4,748.79 | 119 | 844 | 11 |
| 2025-06 | 6,284 | 6,473,301.15 | 120,429.84 | 104,194.76 | 19,919.39 | -3,684.31 | 145 | 859 | 11 |
| 2025-07 | 6,262 | 6,444,348.70 | 119,117.83 | 104,029.96 | 19,040.76 | -3,952.90 | 138 | 879 | 11 |
| 2025-08 | 6,630 | 6,656,213.48 | 121,806.37 | 107,653.61 | 19,837.86 | -5,685.09 | 134 | 903 | 11 |
| 2025-09 | 6,317 | 6,259,966.90 | 116,523.09 | 102,441.13 | 18,396.48 | -4,314.52 | 151 | 860 | 11 |
| 2025-10 | 6,777 | 6,741,253.02 | 123,777.84 | 108,682.41 | 19,936.35 | -4,840.93 | 139 | 916 | 11 |
| 2025-11 | 6,681 | 6,729,287.55 | 122,912.53 | 107,823.20 | 19,513.11 | -4,423.78 | 113 | 878 | 11 |
| 2025-12 | 6,819 | 6,863,180.71 | 126,411.58 | 111,021.58 | 21,829.19 | -6,439.18 | 144 | 981 | 11 |
| 2026-01 | 7,174 | 7,045,030.73 | 129,755.72 | 115,075.65 | 20,490.61 | -5,810.55 | 153 | 934 | 11 |
| 2026-02 | 6,518 | 6,399,504.79 | 117,306.21 | 102,964.92 | 18,842.49 | -4,501.20 | 135 | 855 | 11 |
| 2026-03 | 7,782 | 7,818,465.69 | 143,833.94 | 126,002.16 | 23,537.44 | -5,705.65 | 132 | 1,140 | 12 |
| 2026-04 | 7,557 | 7,676,541.51 | 141,666.50 | 123,758.47 | 23,190.14 | -5,282.11 | 147 | 1,144 | 12 |
| 2026-05 | 8,053 | 8,199,474.96 | 152,541.35 | 133,020.50 | 23,812.32 | -4,291.47 | 161 | 1,149 | 12 |
| 2026-06 | 7,499 | 7,730,844.97 | 144,038.79 | 124,887.37 | 23,097.83 | -3,946.41 | 148 | 1,064 | 12 |
| 2026-07 | 7,441 | 7,676,226.84 | 143,687.20 | 127,787.23 | 23,160.81 | -7,260.85 | 161 | 1,124 | 11 |
| 2026-08 | 7,498 | 7,813,748.23 | 147,593.15 | 105,535.02 | 24,329.85 | 17,728.29 | 163 | 1,148 | 11 |
| 2026-09 | 6,941 | 6,960,502.80 | 128,769.57 | 91,700.57 | 21,328.70 | 15,740.30 | 139 | 999 | 11 |
| 2026-10 | 7,505 | 7,836,507.86 | 145,592.62 | 101,354.91 | 23,567.55 | 20,670.17 | 129 | 1,067 | 11 |

### Observed rates by scheme, card type, destination and channel (purchases)

| Scheme | Card type | Destination | Channel | Txns | Volume | Interchange % | Scheme fee % |
|---|---|---|---|---:|---:|---:|---:|
| MASTERCARD | CREDIT | DOMESTIC | ECOM | 2,176 | 2,649,202.82 | 1.9000 | 0.2000 |
| MASTERCARD | CREDIT | DOMESTIC | POS | 14,025 | 12,258,200.87 | 1.4331 | 0.1700 |
| MASTERCARD | DEBIT | DOMESTIC | ECOM | 2,557 | 3,104,165.75 | 1.9000 | 0.2000 |
| MASTERCARD | DEBIT | DOMESTIC | POS | 16,431 | 14,273,857.00 | 1.4418 | 0.1700 |
| MASTERCARD | PREPAID | DOMESTIC | ECOM | 540 | 672,583.23 | 1.9000 | 0.2000 |
| MASTERCARD | PREPAID | DOMESTIC | POS | 3,586 | 3,121,949.40 | 1.4540 | 0.1700 |
| MEEZA | DEBIT | DOMESTIC | ECOM | 6,975 | 8,867,259.98 | 1.6623 | 0.0600 |
| MEEZA | DEBIT | DOMESTIC | POS | 44,639 | 39,156,013.37 | 1.3126 | 0.0600 |
| MEEZA | PREPAID | DOMESTIC | ECOM | 665 | 884,499.62 | 1.7213 | 0.0600 |
| MEEZA | PREPAID | DOMESTIC | POS | 4,573 | 4,050,798.36 | 1.3467 | 0.0600 |
| VISA | CREDIT | DOMESTIC | ECOM | 2,213 | 2,814,086.45 | 1.9000 | 0.2000 |
| VISA | CREDIT | DOMESTIC | POS | 14,245 | 12,769,240.97 | 1.3618 | 0.1700 |
| VISA | DEBIT | DOMESTIC | ECOM | 2,523 | 3,378,162.13 | 1.9000 | 0.2000 |
| VISA | DEBIT | DOMESTIC | POS | 16,845 | 14,844,576.32 | 1.3611 | 0.1700 |
| VISA | PREPAID | DOMESTIC | ECOM | 547 | 704,602.01 | 1.9000 | 0.2000 |
| VISA | PREPAID | DOMESTIC | POS | 3,587 | 3,042,313.01 | 1.4123 | 0.1700 |
| MASTERCARD | CREDIT | INTERNATIONAL | ECOM | 2,543 | 6,040,145.71 | 1.8500 | 0.9000 |
| MASTERCARD | CREDIT | INTERNATIONAL | POS | 6,213 | 10,037,467.45 | 1.8500 | 0.7500 |
| MASTERCARD | DEBIT | INTERNATIONAL | ECOM | 435 | 958,800.65 | 1.8500 | 0.9000 |
| MASTERCARD | DEBIT | INTERNATIONAL | POS | 1,073 | 1,826,994.66 | 1.8500 | 0.7500 |
| VISA | CREDIT | INTERNATIONAL | ECOM | 2,968 | 6,834,845.76 | 1.8500 | 0.9000 |
| VISA | CREDIT | INTERNATIONAL | POS | 7,029 | 11,477,821.11 | 1.8500 | 0.7500 |
| VISA | DEBIT | INTERNATIONAL | ECOM | 522 | 1,231,843.46 | 1.8500 | 0.9000 |
| VISA | DEBIT | INTERNATIONAL | POS | 1,288 | 2,162,411.46 | 1.8500 | 0.7500 |

### Amount scaling

| Store currency | Transaction currency | Txns | Max decimals stored |
|---|---|---:|---:|
| EGP | EGP | 150,093 | 4 |
| EGP | USD | 11,341 | 4 |

The feed carries EGP with three implied decimals; the 24-month volume in the database equals the feed total to the last unit, and foreign-currency (USD, ISO 840) amounts kept their own two-decimal scale.

### Summary tables vs facts

| Summary table | Rows | Result |
|---|---:|---|
| `sum_daily_bank` | 730 | matches |
| `sum_daily_merchant` | 7,972 | matches |
| `sum_daily_full` | 52,165 | matches |
| `sum_daily_terminal` | 7,972 | matches |
| `sum_daily_scheme` | 2,190 | matches |
| `sum_daily_channel` | 2,168 | matches |
| `sum_daily_merchant_destination` | 14,516 | matches |
| `sum_daily_finance_rollup` | 730 | matches |
| `sum_daily_insight` | 52,165 | matches |
| `sum_monthly_insight` | 3,135 | matches |

### Merchants

| MID | Merchant | MCC | First txn | Last txn | Txns | Volume |
|---|---|---|---|---|---:|---:|
| E2EEGM001 | E2E EG Grocery SAE | 5411 | 2024-11-01 | 2026-10-31 | 26,172 | 12,045,542.51 |
| E2EEGM002 | E2E EG Online Store SAE | 5812 | 2024-11-01 | 2026-10-31 | 17,401 | 13,455,645.79 |
| E2EEGM003 | E2E EG Fuel Station SAE | 5541 | 2024-11-01 | 2026-10-31 | 21,832 | 8,438,762.01 |
| E2EEGM004 | E2E EG Pharmacy SAE | 5912 | 2024-11-01 | 2026-10-31 | 13,321 | 7,113,540.35 |
| E2EEGM005 | E2E EG Electronics SAE | 5732 | 2024-11-01 | 2026-10-31 | 7,069 | 21,593,166.82 |
| E2EEGM006 | E2E EG Fashion SAE | 5651 | 2024-11-01 | 2026-10-31 | 9,814 | 13,681,734.61 |
| E2EEGM007 | E2E EG Hotel SAE | 7011 | 2024-11-01 | 2026-10-31 | 4,379 | 20,182,999.16 |
| E2EEGM008 | E2E EG Travel Agency SAE | 4722 | 2024-11-01 | 2026-10-31 | 3,286 | 19,835,791.40 |
| E2EEGM009 | E2E EG School SAE | 8211 | 2024-11-03 | 2026-10-30 | 1,813 | 13,769,939.75 |
| E2EEGM010 | E2E EG Hypermarket SAE | 5411 | 2024-11-01 | 2026-10-31 | 39,136 | 27,062,539.16 |
| E2EEGM011 | E2E EG Cafe SAE | 5814 | 2024-11-01 | 2026-06-30 | 12,757 | 2,468,784.21 |
| E2EEGM012 | E2E EG E-Shop SAE | 5999 | 2026-03-01 | 2026-10-31 | 4,454 | 4,068,954.04 |

### Report APIs vs database

| Check | Compared | Matched | Note |
|---|---:|---:|---|
| Finance Summary: 24 months x volume/MSF/interchange/scheme fee | 96 | 96 |  |
| Volume & Revenue: 24 months x volume/MSF/count | 72 | 72 |  |
| Net Spread: 24 monthly totals x count/volume/MSF/IC/SF | 120 | 120 |  |
| Daily Merchant Performance: 8 sample days x count/volume/MSF | 24 | 24 |  |
| Transactions paging: one full day walked page by page | 273 | 273 | 3 pages, 273 rows returned |
| Executive Dashboard: Oct-2026 month-to-date volume and count | 2 | 2 | api 7836507.86 / 7505 |

### Merchant PDFs

| Merchant | Month | PDF total | Database net sales | Match | PDF month-on-month | Database | Pages | Seconds |
|---|---|---:|---:|---|---|---|---|---:|
| M001 | 2026-10 | 563,346.93 | 563,346.93 | yes | +9% | +9% | 8 | 2.6 |
| M007 | 2026-10 | 815,544.45 | 815,544.45 | yes | -9% | -9% | 8 | 2.4 |
| M010 | 2025-12 | 1,190,143.15 | 1,190,143.15 | yes | +5% | +5% | 8 | 2.5 |
| M011 | 2026-06 | 129,580.87 | 129,580.87 | yes | +6% | +6% | 8 | 2.7 |
| M012 | 2026-10 | 535,044.72 | 535,044.72 | yes | +7% | +7% | 8 | 2.5 |

### API sweep

193 endpoints called: 183 returned 200, 8 returned 4xx (a required parameter or body field was not supplied), 1 returned 500, 1 is a live stream that never completes. Response time for the 200s: median 0.031 s, 95th percentile 0.186 s, slowest 1.46 s.

## 6. Access control and isolation

Two users were created per institution through the API: a bank admin and a read-only business user.

| User type | Check | UAE | Bahrain | Egypt |
|---|---|---|---|---|
| Bank admin | forced first-login password change | pass (200) | pass (200) | pass (200) |
| Bank admin | login: allowed tenants = own only | pass (ok) | pass (ok) | pass (ok) |
| Bank admin | login: no SUPER_ADMIN authority | pass (ok) | pass (ok) | pass (ok) |
| Bank admin | executive dashboard (own tenant) | pass (200) | pass (200) | pass (200) |
| Bank admin | forged X-Tenant-Id to another institution | pass (403) | pass (403) | pass (403) |
| Bank admin | merchant list: own merchants only | pass (200) | pass (200) | pass (200) |
| Bank admin | transactions (keyset) | pass (200) | pass (200) | pass (200) |
| Bank admin | user list scoped to own institution | pass (ok) | pass (ok) | pass (ok) |
| Bank admin | bank list: own institution only | pass (ok) | pass (ok) | pass (ok) |
| Bank admin | create a new institution | pass (403) | pass (403) | pass (403) |
| Bank admin | merchant type-ahead: own only | pass (200) | pass (200) | pass (200) |
| Bank admin | rebuild another institution's summaries | pass (403) | pass (403) | pass (403) |
| Business user | forced first-login password change | pass (200) | pass (200) | pass (200) |
| Business user | login: allowed tenants = own only | pass (ok) | pass (ok) | pass (ok) |
| Business user | sidebar has no admin menus | pass (ok) | pass (ok) | pass (ok) |
| Business user | executive dashboard | pass (200) | pass (200) | pass (200) |
| Business user | volume & revenue report | pass (200) | pass (200) | pass (200) |
| Business user | user administration | pass (403) | pass (403) | pass (403) |
| Business user | file upload request with no file attached | FAIL (500) | FAIL (500) | FAIL (500) |
| Business user | file upload with a real file | pass (403) | pass (403) | pass (403) |
| Business user | create institution | pass (403) | pass (403) | pass (403) |
| Business user | batch rebuild | pass (403) | pass (403) | pass (403) |
| Business user | finance summary (not granted to Business User) | pass (403) | pass (403) | pass (403) |
| Business user | forged X-Tenant-Id to another institution | pass (403) | pass (403) | pass (403) |

The 3 failures are all the business user's upload attempt made **without a file**, which returned 500. Repeated with a real file it returned 403 for all three institutions, so access is correctly denied; the 500 is the malformed-request handling in finding 8.4.

Bank admins were also checked on audit logs, ingest history, ingest overview, batch jobs and report listings: 24 calls, 0 containing another institution's data.

Database sweep (all three institutions): fact rows pointing at another tenant's merchant, store or terminal: 0 / 0 / 0. Rows in the wrong currency: 0. Staging rows left behind: 0. The pre-existing tenants were not touched.

## 7. Build, tests and performance

| Item | Result |
|---|---|
| Backend build (6 modules, JDK 25) | Success |
| Backend unit tests | 306 run, 304 pass, 2 fail (`RbacGuardCoverageTest`, reproduced on the clean branch head) |
| Frontend production build | Success |
| Frontend unit tests | Test files: 15 passed (15); tests: 110 passed (110) |
| Application start | About 2 minutes on first start |
| Ingest throughput | 458 / 354 / 342 rows/s end to end (file to summaries), about 14 s per monthly file of 6-7k rows |
| Slowest ingest step | Summary population, 10.5 s average per file |
| Reprice + rebuild | 24 months, 161k rows: 508 s |
| Single PDF | 2.3 to 4.1 s; bulk job 11 PDFs in 16.7 s |
| Database size after load | 2.1 GB |

The laptop entered standby twice during the run (battery saver). One ingest job was suspended mid-flight for three hours and completed correctly on wake; nothing had to be reloaded.

## 8. Findings

None of these was traced to the Spring Boot 4 / JDK 25 upgrade. They are defects or data problems the run exposed.

| # | Severity | Finding | Evidence |
|---|---|---|---|
| 8.1 | Fixed in this session | Executive Dashboard USD view converted a floating currency at the wrong rate. Two causes: the page requests FX rates from 1 January two years back and the API refused ranges over 800 days with a 400; and the page memoised its converted totals without depending on the loaded rate series, so the fallback rate from the first render stuck even when the request succeeded. | Egypt, October 2026: was USD 144.6K at 54.19 EGP/USD; now USD 145.9K at 53.72, matching the database (145,881.82). Year to date USD 1.479M and both growth figures (+7.9%, -4.7%) also match. Fix: range cap raised to 1,100 days, totals recompute when rates arrive, a refused range now logs a console warning (uncommitted). |
| 8.2 | Medium | `sum_daily_scheme` drops any scheme-day whose net volume is negative (`HAVING SUM(amount) > 0`), so scheme-level totals do not reconcile to the facts. | UAE: 81 transactions and 11,647 AED of difference over two years; Bahrain: 15 transactions. Every other summary table reconciles. |
| 8.3 | Medium | Two RBAC unit tests fail on the branch head: the Profitability Report endpoints name a screen missing from the RBAC catalog (unreachable for everyone but super admin), and the FX rate controller has no authorisation annotation. | `RbacGuardCoverageTest`, 2 of 3 failing, reproduced on a clean checkout. |
| 8.4 | Low | A request with a missing required parameter or a missing upload file returns 500 instead of 400. | `GET /api/fx/series` without dates; `POST /api/upload` without a file. |
| 8.5 | Low | The finance rollup stores volume at 2 decimals, so a 3-decimal currency loses its third decimal there. | Bahrain: Finance Summary volume differs from facts by 0.43 BHD over 24 months (0.053 in March 2025). |
| 8.6 | Low | The BIN reference table has duplicate and conflicting ranges; the lookup returns whichever it finds first. | Bahrain BINs 538721 (blank issuer vs BH prepaid), 543856 (debit vs prepaid), 512894 (a foreign Visa range overlapping a local Mastercard range). |
| 8.7 | Low | Domestic Amex in the UAE has no interchange rate row. The rows are flagged `NO_RATE_FOUND` rather than priced. | 2,289 rows, 387k AED over two years. Needs a business decision on whether a rate should exist. |
| 8.8 | Low | Institutions created through the API are saved with a blank status instead of ACTIVE. | Tenants 10, 11 and 12. |
| 8.9 | Low | Super admin gets 403 on `/api/users/me/menus` for an institution created after their account. | Tenants 10 to 12 return 403; tenant 1 returns 200. |
| 8.10 | Fixed in this session | Jaywan was never registered as a scheme because its migration reused an id already taken, so it priced on the wildcard. | Corrective migration `V2026_11_11_01`; Jaywan now prices at 0.50% (uncommitted). |
| 8.11 | Local only | The local database had a tenant id sequence that was behind and no `ensure_tenant_partitions()` function. | First tenant create returned 500; partition provisioning step failed without blocking ingest. |

## 9. Not covered

- Multi-pod deployment: the application ran as one process with role `all`. The pod-role split, cross-pod cache and scheduled-job locking were not exercised.
- The Integration Hub database pull and the backfill ingest path (including the Egypt divisor on those paths).
- Email/SMTP, S3 upload, SSO, login MFA and the AI assistant.
- Endpoints that need a path id or required parameters beyond the report filters, and all write endpoints other than those used here.
- UI screens other than login, organisation switch and the Executive Dashboard; no bank-scoped login through the UI.
- Load beyond 487k rows, concurrent users, and Docker/Kubernetes deployment.

## 10. What was left on the machine

- Test institutions 10, 11 and 12 with two years of data, plus six test users (`e2e_<country>_admin`, `e2e_<country>_viewer`).
- Local migrations applied: `V2026_10_05_01`, `V2026_10_05_02`, `V2026_10_26_01` (Jaywan) and `V2026_11_11_01`.
- Generated PDFs under `reports/E2EBH/2026-10`.
- Uncommitted changes on the branch: the `card_type` scheme-fee change, three migrations, the FX range and dashboard conversion fix (3 files), and a `backend-jdk25` launch entry.
