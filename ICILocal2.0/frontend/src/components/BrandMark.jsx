import './status-pages.css';

/** ICI logo and product name, used on the signed-out screens (ICI2-105). */
export function BrandMark() {
  return <div className="auth-brand">
    <span className="auth-logo" aria-hidden="true">ICI</span>
    <span><strong>ICI Local 2.0</strong><small>Iligan Computer Institute</small></span>
  </div>;
}
