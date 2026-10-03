-- 补齐 V54 多币种财务表的全部字段说明，保证数据库本身可独立理解业务含义。

COMMENT ON COLUMN currency_exchange_rates.id IS '汇率记录唯一标识。';
COMMENT ON COLUMN currency_exchange_rates.created_at IS '汇率记录创建时间。';

COMMENT ON COLUMN supplier_billing_imports.id IS '供应商账单导入批次唯一标识。';
COMMENT ON COLUMN supplier_billing_imports.supplier_id IS '账单所属供应商标识。';
COMMENT ON COLUMN supplier_billing_imports.file_name IS '管理员上传的原始文件名，不包含文件内容。';
COMMENT ON COLUMN supplier_billing_imports.period_from IS '供应商账单周期开始日期。';
COMMENT ON COLUMN supplier_billing_imports.period_to IS '供应商账单周期结束日期。';
COMMENT ON COLUMN supplier_billing_imports.row_count IS '成功解析并写入的账单明细数量。';
COMMENT ON COLUMN supplier_billing_imports.imported_at IS '账单导入完成时间。';

COMMENT ON COLUMN supplier_billing_items.id IS '供应商账单明细唯一标识。';
COMMENT ON COLUMN supplier_billing_items.import_id IS '所属供应商账单导入批次。';
COMMENT ON COLUMN supplier_billing_items.billed_at IS '供应商记录该笔费用的时间。';
COMMENT ON COLUMN supplier_billing_items.model_name IS '供应商账单中的模型名称快照。';
COMMENT ON COLUMN supplier_billing_items.quantity IS '供应商账单记录的计费用量。';
COMMENT ON COLUMN supplier_billing_items.amount IS '供应商账单记录的未税或按供应商口径金额。';
COMMENT ON COLUMN supplier_billing_items.currency IS '该笔供应商费用的三位大写币种代码。';

COMMENT ON COLUMN financial_reconciliation_records.id IS '多币种财务对账记录唯一标识。';
COMMENT ON COLUMN financial_reconciliation_records.supplier_id IS '对账记录所属供应商标识。';
COMMENT ON COLUMN financial_reconciliation_records.billing_item_id IS '关联的供应商账单明细；缺少供应商明细时为空。';
COMMENT ON COLUMN financial_reconciliation_records.request_id IS '关联的平台请求 ID；缺少平台记录时为空。';
COMMENT ON COLUMN financial_reconciliation_records.local_amount IS '平台侧供应商成本金额快照。';
COMMENT ON COLUMN financial_reconciliation_records.local_currency IS '平台侧供应商成本币种快照。';
COMMENT ON COLUMN financial_reconciliation_records.provider_amount IS '供应商账单金额快照。';
COMMENT ON COLUMN financial_reconciliation_records.provider_currency IS '供应商账单币种快照。';
COMMENT ON COLUMN financial_reconciliation_records.created_at IS '对账结论生成时间。';
