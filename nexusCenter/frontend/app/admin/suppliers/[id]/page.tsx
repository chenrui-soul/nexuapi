import { SupplierDetailAdminPage } from "@/components/admin/SupplierDetailAdminPage";

export default async function SupplierDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <SupplierDetailAdminPage supplierId={id}/>;
}
