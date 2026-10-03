import Link from "next/link";
import { ChannelsAdminPage } from "@/components/admin/ChannelsAdminPage";

export default async function SupplierEndpointsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <>
    <Link className="admin-button secondary" href={`/admin/suppliers/${id}`}>返回供应商详情</Link>
    <ChannelsAdminPage supplierId={id}/>
  </>;
}
