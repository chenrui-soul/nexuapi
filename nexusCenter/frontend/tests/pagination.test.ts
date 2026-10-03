import assert from "node:assert/strict";
import test from "node:test";
import { paginate, paginationItems } from "../lib/pagination.ts";

test("paginate slices items and clamps invalid pages", () => {
  const items = Array.from({ length: 12 }, (_, index) => index + 1);
  assert.deepEqual(paginate(items, 2, 6), {
    items: [7, 8, 9, 10, 11, 12], page: 2, totalPages: 2, start: 6, end: 12,
  });
  assert.equal(paginate(items, 99, 6).page, 2);
  assert.equal(paginate([], 1, 6).totalPages, 1);
});

test("paginationItems keeps long pagination compact", () => {
  assert.deepEqual(paginationItems(1, 35), [1, 2, 3, 4, 5, "ellipsis", 35]);
  assert.deepEqual(paginationItems(18, 35), [1, "ellipsis", 17, 18, 19, "ellipsis", 35]);
  assert.deepEqual(paginationItems(35, 35), [1, "ellipsis", 31, 32, 33, 34, 35]);
  assert.deepEqual(paginationItems(2, 5), [1, 2, 3, 4, 5]);
});
