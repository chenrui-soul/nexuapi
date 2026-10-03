export function BrandMark({ className = "brand-mark-svg" }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 40 40" fill="none" aria-hidden="true">
      <rect x="4.75" y="4.75" width="30.5" height="30.5" rx="9.25" stroke="currentColor" strokeWidth="1.5" opacity=".42" />
      <path d="M11.5 28.5v-17l17 17v-17" stroke="currentColor" strokeWidth="2.35" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="11.5" cy="11.5" r="2" fill="currentColor" />
      <circle cx="28.5" cy="28.5" r="2" fill="currentColor" />
      <circle cx="28.5" cy="11.5" r="2" fill="currentColor" />
    </svg>
  );
}
