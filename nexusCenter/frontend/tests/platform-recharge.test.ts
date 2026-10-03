import assert from "node:assert/strict";
import test from "node:test";
import { acknowledgeAnnouncement } from "../lib/announcement-state.ts";
import { rechargeUser, type AnnouncementFeed, type Announcement } from "../lib/platform.ts";

test("read receipt updates count once and preserves other unread announcements", () => {
  const a = {id:"a", title:"first"} as Announcement, b = {id:"b",title:"second"} as Announcement;
  const feed: AnnouncementFeed = {announcements:{items:[a,b],total:2,page:1,page_size:10},unread_count:2,next_unread:a};
  const read = acknowledgeAnnouncement(feed,"a","now")!;
  assert.equal(read.unread_count,1); assert.equal(read.next_unread,null);
  assert.equal(read.announcements.items[0].read_at,"now");
  assert.equal(read.announcements.items[1].read_at,undefined);
  assert.equal(acknowledgeAnnouncement(read,"a","later")!.unread_count,1);
  assert.equal(acknowledgeAnnouncement(null,"a"),null);
});

test("recharge retries preserve request identity and acquire CSRF for every attempt", async () => {
  const original=globalThis.fetch; const payloads:unknown[]=[]; let csrf=0;
  const input={request_id:"same-uuid",credits:"25.50",reason:"Offline payment"};
  try {
    globalThis.fetch=async (url,init) => {
      if(String(url)==="/api/v1/auth/csrf") {csrf++;return Response.json({success:true,data:{header:"X-CSRF-TOKEN",token:"token"}});}
      assert.equal(String(url),"/api/v1/admin/users/user-id/recharge");
      assert.equal(new Headers(init?.headers).get("X-CSRF-TOKEN"),"token");
      payloads.push(JSON.parse(init!.body as string));
      if(payloads.length===1) throw new TypeError("Network interrupted after commit");
      return Response.json({success:true,data:{request_id:"same-uuid",replayed:true}});
    };
    await assert.rejects(rechargeUser("user-id",input));
    assert.equal((await rechargeUser("user-id",input)).replayed,true);
    assert.deepEqual(payloads,[input,input]);assert.equal(csrf,2);
  } finally {globalThis.fetch=original;}
});
