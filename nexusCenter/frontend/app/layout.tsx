import type { Metadata } from "next";
import "./globals.css";
// 管理端样式由根布局统一加载，避免 Vinext 开发模式解析嵌套布局相对 CSS 时丢失文件。
import "./admin/admin.css";
import "./platform.css";
import { AuthProvider } from "@/components/auth/AuthProvider";

export const metadata: Metadata = {
  title: "NEXUS API · AI 能力聚合平台",
  description: "一个接口，连接全球顶尖 AI 模型。",
  other: {
    "codex-preview": "development",
  },
  icons: {
    icon: "/favicon.svg?v=2",
    shortcut: "/favicon.svg?v=2",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{__html:`(()=>{try{const saved=localStorage.getItem("nexus-theme")||"system";const dark=saved==="dark"||(saved==="system"&&matchMedia("(prefers-color-scheme: dark)").matches);const theme=dark?"dark":"light";document.documentElement.dataset.theme=theme;document.documentElement.style.colorScheme=theme}catch{document.documentElement.dataset.theme="dark"}})()`}} />
      </head>
      <body className="antialiased"><AuthProvider>{children}</AuthProvider></body>
    </html>
  );
}
