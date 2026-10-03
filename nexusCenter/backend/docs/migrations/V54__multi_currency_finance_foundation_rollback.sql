-- 仅在确认无汇率、账单导入和对账数据需要保留时执行；生产回滚前必须完成备份。
BEGIN;
DROP TABLE IF EXISTS financial_reconciliation_records;
DROP TABLE IF EXISTS supplier_billing_items;
DROP TABLE IF EXISTS supplier_billing_imports;
DROP TABLE IF EXISTS currency_exchange_rates;
COMMIT;
