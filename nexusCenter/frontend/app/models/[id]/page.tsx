import { ModelDetailPage } from "@/components/market/ModelDetailPage";

export default async function PublicModelDetailRoute({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ service_group_id?: string }> }) {
  const { id } = await params;
  const { service_group_id: serviceGroupId } = await searchParams;
  return <ModelDetailPage modelId={id} initialServiceGroupId={serviceGroupId}/>;
}
