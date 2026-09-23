import React from 'react';
import CeoVolumeRevenue from './CeoVolumeRevenue';

/* Loss-Making Merchants — the Volume & Revenue report scoped to merchants
   running at a net loss (NET SPREAD < 0: margin + DCC + rental + FX when
   enabled), via the shared CeoVolumeRevenue component's lossOnly prop. Same
   columns, same MTD/YTD/This-Month/month filters; server applies HAVING on
   the spread expression. */
const LossMakingMerchants = () => (
    <CeoVolumeRevenue
        lossOnly
        title="Loss-Making Merchants"
        subtitleSuffix="merchants running at a net loss"
    />
);

export default LossMakingMerchants;
