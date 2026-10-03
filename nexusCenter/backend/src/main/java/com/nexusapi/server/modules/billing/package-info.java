/**
 * 钱包、额度分桶、预冻结、幂等结算、退款和不可变账本的唯一写入边界。
 *
 * <p>其他模块只能调用 Billing Service，不得直接更新 wallet_accounts 或 billing_ledger。</p>
 */
package com.nexusapi.server.modules.billing;
