import assert from "node:assert/strict";
import test from "node:test";
import { getModelMarketDetail, listModelMarket } from "../lib/model-market.ts";

test("listModelMarket maps group prices without exposing internal routing fields", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request, init) => {
    assert.equal(
      String(request),
      "/api/v1/model-market?page=1&page_size=6&sort=price_asc&service_group_id=group-1&capability_type=text",
    );
    assert.equal(init?.method, "GET");
    return Response.json({
      success: true,
      data: {
        items: [{
          id: "model-1",
          public_name: "gpt-5.6-sol",
          display_name: "GPT 5.6 Sol",
          provider: "OpenAI",
          capability_type: "text",
          context_window: 400000,
          max_output_tokens: 128000,
          supports_streaming: true,
          supports_tools: true,
          supports_structured_output: true,
          service_group_id: "group-1",
          service_group_name: "特价组",
          price_multiplier: 0.2,
          base_input_price: 1.25,
          base_output_price: 10,
          effective_input_price: 0.25,
          effective_output_price: 2,
          effective_cached_input_price: 0.05,
          price_unit: "million_tokens",
          billing_type: 4,
          billing_unit: "million_tokens",
          base_unit_price: 2,
          effective_unit_price: 0.4,
          display_original_price: 3,
          input_token_ratio: 10000,
          output_token_ratio: 40000,
          audio_input_token_ratio: 30000,
          audio_output_token_ratio: 70000,
          cached_input_token_ratio: 2500,
          cache_write_5m_token_ratio: 0,
          cache_write_1h_token_ratio: 0,
          charge_desc: "输入、输出和缓存命中按不同倍率计费",
          pricing_version_id: "pricing-v2",
          availability: "in_group",
        }],
        total: 1,
        page: 1,
        page_size: 6,
        service_group_id: "group-1",
        service_group_name: "特价组",
        price_multiplier: 0.2,
      },
      request_id: "req-market",
    });
  };
  try {
    const page = await listModelMarket({
      serviceGroupId: "group-1",
      pageSize: 6,
      capabilityType: "text",
      sort: "price_asc",
    });
    assert.equal(page.items[0].effectiveInputPrice, "0.25");
    assert.equal(page.items[0].effectiveOutputPrice, "2");
    assert.equal(page.items[0].serviceGroupId, "group-1");
    assert.equal(page.items[0].availability, "in_group");
    assert.equal(page.items[0].billingType, 4);
    assert.equal(page.items[0].effectiveUnitPrice, "0.4");
    assert.equal(page.items[0].outputTokenRatio, 40000);
    assert.equal(page.items[0].pricingVersionId, "pricing-v2");
    assert.equal("encryptedCredential" in page.items[0], false);
    assert.equal("baseUrl" in page.items[0], false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listModelMarket requests the full public catalog without a group", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request, init) => {
    assert.equal(String(request), "/api/v1/model-market?page=1&page_size=12&sort=name");
    assert.equal(init?.method, "GET");
    return Response.json({
      success: true,
      data: {
        items: [{
          id: "model-catalog",
          public_name: "catalog-model",
          display_name: "Catalog Model",
          provider: "Nexus",
          capability_type: "image",
          context_window: null,
          max_output_tokens: null,
          supports_streaming: true,
          supports_tools: false,
          supports_structured_output: false,
          service_group_id: null,
          service_group_name: null,
          price_multiplier: null,
          base_input_price: 1,
          base_output_price: 2,
          effective_input_price: 1,
          effective_output_price: 2,
          effective_cached_input_price: 0,
          price_unit: "image",
          billing_type: 2,
          billing_unit: "quantity",
          base_unit_price: 18,
          effective_unit_price: 18,
          display_original_price: 24,
          input_token_ratio: 10000,
          output_token_ratio: 10000,
          audio_input_token_ratio: 10000,
          audio_output_token_ratio: 10000,
          cached_input_token_ratio: 10000,
          cache_write_5m_token_ratio: 0,
          cache_write_1h_token_ratio: 0,
          charge_desc: "按生成图片张数计费",
          pricing_version_id: "pricing-image-v1",
          availability: "catalog",
        }],
        total: 1,
        page: 1,
        page_size: 12,
        service_group_id: null,
        service_group_name: null,
        price_multiplier: null,
      },
      request_id: "req-catalog",
    });
  };
  try {
    const page = await listModelMarket();
    assert.equal(page.items[0].serviceGroupId, null);
    assert.equal(page.items[0].priceMultiplier, null);
    assert.equal(page.items[0].availability, "catalog");
    assert.equal(page.items[0].billingType, 2);
    assert.equal(page.items[0].billingUnit, "quantity");
    assert.equal(page.items[0].displayOriginalPrice, "24");
    assert.equal(page.serviceGroupId, null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listModelMarket treats omitted optional group fields as null", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => Response.json({
    success: true,
    data: {
      items: [{
        id: "model-omitted",
        public_name: "catalog-model",
        display_name: "Catalog Model",
        provider: "unknown",
        capability_type: "text",
        supports_streaming: true,
        supports_tools: true,
        supports_structured_output: true,
        base_input_price: 1,
        base_output_price: 2,
        effective_input_price: 1,
        effective_output_price: 2,
        effective_cached_input_price: 0,
        price_unit: "million_tokens",
        billing_type: 4,
        billing_unit: "million_tokens",
        base_unit_price: 1,
        effective_unit_price: 1,
        display_original_price: 0,
        input_token_ratio: 10000,
        output_token_ratio: 10000,
        audio_input_token_ratio: 0,
        audio_output_token_ratio: 0,
        cached_input_token_ratio: 0,
        cache_write_5m_token_ratio: 0,
        cache_write_1h_token_ratio: 0,
        charge_desc: null,
        pricing_version_id: null,
        availability: "catalog",
      }],
      total: 1,
      page: 1,
      page_size: 12,
    },
  });
  try {
    const page = await listModelMarket();
    assert.equal(page.items[0].serviceGroupId, null);
    assert.equal(page.items[0].serviceGroupName, null);
    assert.equal(page.items[0].priceMultiplier, null);
    assert.equal(page.serviceGroupId, null);
    assert.equal(page.priceMultiplier, null);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("getModelMarketDetail maps interface fields and visible service group prices", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (request, init) => {
    assert.equal(String(request), "/api/v1/model-market/model%2Fdetail");
    assert.equal(init?.method, "GET");
    return Response.json({
      success: true,
      data: {
        model: {
          id: "model/detail", public_name: "gpt-detail", display_name: "GPT Detail", provider: "OpenAI",
          capability_type: "text", context_window: 128000, max_output_tokens: 32000,
          supports_streaming: true, supports_tools: true, supports_structured_output: true,
          base_input_price: 1, base_output_price: 4, effective_input_price: 1, effective_output_price: 4,
          effective_cached_input_price: 0.1, price_unit: "million_tokens", billing_type: 4,
          billing_unit: "million_tokens", base_unit_price: 1, effective_unit_price: 1,
          display_original_price: 5, input_token_ratio: 10000, output_token_ratio: 40000,
          audio_input_token_ratio: 0, audio_output_token_ratio: 0, cached_input_token_ratio: 1000,
          cache_write_5m_token_ratio: 0, cache_write_1h_token_ratio: 0,
          charge_desc: "按 Token 计费", pricing_version_id: "pricing-1", availability: "catalog",
        },
        interfaces: [{
          id: "interface-1", interface_code: "openai_chat", interface_name: "Chat Completions",
          interface_version: "v1", capability_type: "text", transport_mode: "stream",
          http_method: "POST", public_path: "/v1/chat/completions", request_content_type: "application/json",
          description: "文本对话接口",
          request_fields: [{ name: "model", path: "model", type: "string", required: true,
            description: "模型名称", example: "gpt-detail", enum_values: [], deprecated: false, children: [] }],
          response_fields: [],
        }],
        service_groups: [{
          id: "group-1", code: "value", name: "性价比", description: "日常调用",
          price_multiplier: 0.7, effective_input_price: 0.7, effective_output_price: 2.8,
          effective_cached_input_price: 0.07, effective_unit_price: 0.7,
        }],
      },
    });
  };
  try {
    const detail = await getModelMarketDetail("model/detail");
    assert.equal(detail.model.publicName, "gpt-detail");
    assert.equal(detail.interfaces[0].requestFields[0].example, "gpt-detail");
    assert.equal(detail.serviceGroups[0].priceMultiplier, "0.7");
    assert.equal(detail.serviceGroups[0].effectiveOutputPrice, "2.8");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
