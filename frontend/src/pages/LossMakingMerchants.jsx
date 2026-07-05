import React from 'react';
import CeoVolumeRevenue from './CeoVolumeRevenue';

/* Loss-Making Merchants — the Volume & Revenue report scoped to merchants
   running at a net loss (net revenue < 0), via the shared CeoVolumeRevenue
   component's lossOnly prop. Same columns, same MTD/YTD/This-Month/month
   filters; server applies HAVING SUM(net_revenue) < 0. */
const LossMakingMerchants = () => (
    <CeoVolumeRevenue
        lossOnly
        title="Loss-Making Merchants"
        subtitleSuffix="merchants running at a net loss"
    />
);

export default LossMakingMerchants;
