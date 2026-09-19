/** Small shared primitives, so every screen looks like the same product. */

import type { ReactNode } from "react";

export function cx(...parts: (string | false | null | undefined)[]) {
  return parts.filter(Boolean).join(" ");
}

export function Card({
  title,
  subtitle,
  right,
  children,
  className,
}: {
  title?: string;
  subtitle?: string;
  right?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={cx(
        "rounded-2xl border border-line bg-surface/70 p-4 backdrop-blur sm:p-5",
        className,
      )}
    >
      {(title || right) && (
        <header className="mb-3 flex items-start justify-between gap-3">
          <div>
            {title && <h2 className="text-sm font-semibold tracking-wide">{title}</h2>}
            {subtitle && <p className="mt-0.5 text-xs text-muted">{subtitle}</p>}
          </div>
          {right}
        </header>
      )}
      {children}
    </section>
  );
}

type ButtonProps = {
  children: ReactNode;
  onClick?: () => void;
  variant?: "primary" | "ghost" | "outline" | "danger";
  size?: "sm" | "md";
  disabled?: boolean;
  title?: string;
  type?: "button" | "submit";
};

export function Button({
  children,
  onClick,
  variant = "outline",
  size = "md",
  disabled,
  title,
  type = "button",
}: ButtonProps) {
  const base =
    "inline-flex items-center justify-center gap-2 rounded-xl font-medium transition disabled:cursor-not-allowed disabled:opacity-45";
  const sizes = { sm: "px-2.5 py-1.5 text-xs", md: "px-4 py-2 text-sm" };
  const variants = {
    primary: "bg-accent text-void hover:bg-accent/85",
    outline: "border border-line bg-raised text-ink hover:border-accent/60",
    ghost: "text-muted hover:text-ink",
    danger: "border border-danger/40 text-danger hover:bg-danger/10",
  };
  return (
    <button
      type={type}
      title={title}
      onClick={onClick}
      disabled={disabled}
      className={cx(base, sizes[size], variants[variant])}
    >
      {children}
    </button>
  );
}

export function Pill({
  children,
  tone = "neutral",
}: {
  children: ReactNode;
  tone?: "neutral" | "accent" | "warn" | "danger" | "good";
}) {
  const tones = {
    neutral: "border-line text-muted",
    accent: "border-accent/40 text-accent",
    warn: "border-warn/40 text-warn",
    danger: "border-danger/40 text-danger",
    good: "border-good/40 text-good",
  };
  return (
    <span
      className={cx(
        "inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-medium",
        tones[tone],
      )}
    >
      {children}
    </span>
  );
}

/** A labelled slider, used throughout My Style. */
export function Slider({
  label,
  value,
  onChange,
  left,
  right,
  format,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
  left: string;
  right: string;
  format?: (value: number) => string;
}) {
  return (
    <label className="block">
      <div className="mb-1 flex items-baseline justify-between">
        <span className="text-xs font-medium">{label}</span>
        <span className="text-xs text-accent">
          {format ? format(value) : `${Math.round(value * 100)}%`}
        </span>
      </div>
      <input
        type="range"
        min={0}
        max={1}
        step={0.05}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full accent-accent"
      />
      <div className="mt-0.5 flex justify-between text-[11px] text-muted">
        <span>{left}</span>
        <span>{right}</span>
      </div>
    </label>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-xl border border-dashed border-line px-4 py-6 text-center text-sm text-muted">
      {children}
    </p>
  );
}

export function Banner({
  tone = "warn",
  children,
}: {
  tone?: "warn" | "danger" | "accent";
  children: ReactNode;
}) {
  const tones = {
    warn: "border-warn/30 bg-warn/5 text-warn",
    danger: "border-danger/30 bg-danger/5 text-danger",
    accent: "border-accent/30 bg-accent/5 text-accent",
  };
  return (
    <div className={cx("rounded-xl border px-3 py-2 text-xs leading-relaxed", tones[tone])}>
      {children}
    </div>
  );
}
