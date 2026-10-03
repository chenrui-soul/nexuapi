import { apiDataRequest } from "./api.ts";
export type DeviceKey={id:string;name:string;applicationCode:string;key:string;secret:string;status:"active"|"disabled"|"expired";bound:boolean;activatedAt:string|null;expiresAt:string|null;lastVerifiedAt:string|null;createdAt:string;version:number};
export type DeviceKeyPage={items:DeviceKey[];total:number;page:number;pageSize:number};
type Backend=Omit<DeviceKey,"applicationCode"|"key"|"secret"|"activatedAt"|"expiresAt"|"lastVerifiedAt"|"createdAt">&{application_code:string;key:string;secret:string;activated_at:string|null;expires_at:string|null;last_verified_at:string|null;created_at:string};
type BackendPage={items:Backend[];total:number;page:number;page_size:number};
const map=(x:Backend):DeviceKey=>({id:x.id,name:x.name,applicationCode:x.application_code,key:x.key,secret:x.secret,status:x.status,bound:x.bound,activatedAt:x.activated_at,expiresAt:x.expires_at,lastVerifiedAt:x.last_verified_at,createdAt:x.created_at,version:x.version});
async function csrf(){const x=await apiDataRequest<{header:string;token:string}>("/api/v1/auth/csrf",{cache:"no-store"});return {[x.header]:x.token};}
export async function listDeviceKeys(input:{page?:number;pageSize?:number;query?:string}={}):Promise<DeviceKeyPage>{
  const params=new URLSearchParams({page:String(input.page??1),page_size:String(input.pageSize??20)});
  if(input.query?.trim()) params.set("query",input.query.trim());
  const page=await apiDataRequest<BackendPage>(`/api/v1/admin/device-activation-keys?${params.toString()}`,{cache:"no-store"});
  return {items:page.items.map(map),total:page.total,page:page.page,pageSize:page.page_size};
}
export async function createDeviceKey(input:{name:string;applicationCode:string;expiresAt:string|null}){return apiDataRequest<{id:string;key:DeviceKey;secret:string}>("/api/v1/admin/device-activation-keys",{method:"POST",headers:await csrf(),body:JSON.stringify({name:input.name,application_code:input.applicationCode,expires_at:input.expiresAt})});}
export async function setDeviceKeyStatus(key:DeviceKey,status:"active"|"disabled"){return map(await apiDataRequest<Backend>(`/api/v1/admin/device-activation-keys/${encodeURIComponent(key.id)}/status`,{method:"PUT",headers:await csrf(),body:JSON.stringify({status,version:key.version})}));}
export async function deleteDeviceKey(id:string){return apiDataRequest<{deleted:boolean}>(`/api/v1/admin/device-activation-keys/${encodeURIComponent(id)}`,{method:"DELETE",headers:await csrf()});}
