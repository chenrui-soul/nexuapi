import type { HTMLAttributes, ReactNode } from "react";

type PageFrameProps = HTMLAttributes<HTMLDivElement> & {
  children: ReactNode;
};

/** Shared content frame for first-party pages. Keeps page rhythm in one place. */
export function PageFrame({ children, className = "", ...props }: PageFrameProps) {
  return <div {...props} className={`page-frame ${className}`.trim()}>{children}</div>;
}

type PagePanelProps = HTMLAttributes<HTMLElement> & {
  children: ReactNode;
};

/** Shared surface wrapper for panels that participate in a page frame. */
export function PagePanel({ children, className = "", ...props }: PagePanelProps) {
  return <section {...props} className={`page-panel panel ${className}`.trim()}>{children}</section>;
}
