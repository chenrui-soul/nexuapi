"use client";

import { paginationItems } from "@/lib/pagination";

type PaginationProps = {
  page: number;
  pageSize: number;
  total: number;
  onChange: (page: number) => void;
  itemLabel?: string;
  ariaLabel?: string;
  className?: string;
};

/** 全站列表统一分页，负责范围文案、紧凑页码和前后翻页。 */
export function Pagination({
  page,
  pageSize,
  total,
  onChange,
  itemLabel = "条记录",
  ariaLabel = "列表分页",
  className = "",
}: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const currentPage = Math.min(Math.max(1, page), totalPages);
  const start = total === 0 ? 0 : (currentPage - 1) * pageSize + 1;
  const end = Math.min(currentPage * pageSize, total);
  const goTo = (nextPage: number) => onChange(Math.min(Math.max(1, nextPage), totalPages));

  return (
    <nav className={`unified-pagination ${className}`.trim()} aria-label={ariaLabel}>
      <p>显示 <b>{start}–{end}</b>，共 {total.toLocaleString("zh-CN")} {itemLabel}</p>
      <div className="unified-pagination-actions">
        <button className="pagination-step pagination-previous" disabled={currentPage === 1} onClick={() => goTo(currentPage - 1)} aria-label="上一页">
          <span aria-hidden="true">←</span> 上一页
        </button>
        {paginationItems(currentPage, totalPages).map((item, index) => item === "ellipsis"
          ? <span className="pagination-ellipsis" aria-hidden="true" key={`ellipsis-${index}`}>…</span>
          : <button className={currentPage === item ? "active" : ""} aria-current={currentPage === item ? "page" : undefined} aria-label={`第 ${item} 页`} onClick={() => goTo(item)} key={item}>{item}</button>)}
        <button className="pagination-step pagination-next" disabled={currentPage === totalPages} onClick={() => goTo(currentPage + 1)} aria-label="下一页">
          下一页 <span aria-hidden="true">→</span>
        </button>
      </div>
    </nav>
  );
}
