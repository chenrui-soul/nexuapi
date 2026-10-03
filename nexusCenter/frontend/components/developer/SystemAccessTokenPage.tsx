"use client";

import { useRef } from "react";
import gsap from "gsap";
import { useGSAP } from "@gsap/react";
import { SystemAccessTokenPanel } from "./SystemAccessTokenPanel";

if (typeof window !== "undefined") gsap.registerPlugin(useGSAP);

/** 系统访问令牌拥有独立入口，不与用户 API 令牌或上游 APIKey 混合展示。 */
export function SystemAccessTokenPage() {
  const pageRef = useRef<HTMLDivElement>(null);

  useGSAP(() => {
    const motion = gsap.matchMedia();
    motion.add("(prefers-reduced-motion: no-preference)", () => {
      gsap.fromTo(
        ".system-token-page > section",
        { autoAlpha: 0, y: 12 },
        {
          autoAlpha: 1,
          y: 0,
          duration: 0.32,
          stagger: 0.055,
          ease: "power3.out",
          clearProps: "transform,opacity,visibility",
        },
      );
    }, pageRef);
    return () => motion.revert();
  }, { scope: pageRef });

  return <div className="system-token-page" ref={pageRef}><SystemAccessTokenPanel /></div>;
}
